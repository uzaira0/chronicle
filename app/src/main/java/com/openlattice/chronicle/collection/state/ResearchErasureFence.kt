package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.storage.checkLocalStoreWrite
import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.serialization.JsonSerializer

/** Durable epochs and replay intent. Mutations belong to ResearchPersistenceGate.stop. */
class ResearchErasureFence(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE)

    fun generation(module: CollectionModuleId): Long = prefs.getLong("generation:${module.id}", 0)
    fun settingsGeneration(): Long = prefs.getLong("settings_generation", 0)
    fun floor(module: CollectionModuleId): Long = prefs.getLong("floor:${module.id}", 0)
    fun pending(): Set<CollectionModuleId> = prefs.getStringSet("pending", emptySet()).orEmpty()
        .mapNotNull(CollectionModuleId::fromIdOrNull).toSet() +
        legacyRetiredIntents().mapNotNull { CollectionModuleId.fromIdOrNull(it.substringAfter(':')) }

    fun hasPendingErasures(): Boolean = pending().isNotEmpty() || prefs.contains("pending_recovery_scope")

    // Upgrade only: these entries describe module obligations, never individual payload IDs.
    private fun legacyRetiredIntents(): Set<String> = prefs.getStringSet("retired_intents", emptySet()).orEmpty()

    private fun removeLegacyInventory(editor: android.content.SharedPreferences.Editor, module: CollectionModuleId) {
        editor.remove("pending_table:${module.id}").remove("pending_rows:${module.id}").remove("pending_shared_rows:${module.id}")
        legacyRetiredIntents().filter { it.substringAfter(':') == module.id }.forEach {
            editor.remove("retired_table:$it").remove("retired_rows:$it").remove("retired_shared_rows:$it")
        }
    }

    fun legacyCheckpointOwner(): String? = prefs.getString("legacy_checkpoint_owner", null)

    /** Only an enrollment proven before fence installation can adopt unscoped upgrade history. */
    fun rememberLegacyCheckpointOwner(owner: UploadServerEntity) {
        if (prefs.contains("installed_enrollment") || prefs.contains("legacy_checkpoint_checked")) return
        val legacy = com.openlattice.chronicle.storage.ChronicleDb.getInstance(context).usagePollCheckpointDao()
            .getLastPollTimestamp(com.openlattice.chronicle.sensors.USAGE_EVENTS_SENSOR_CHECKPOINT)
        checkLocalStoreWrite(prefs.edit().putBoolean("legacy_checkpoint_checked", true)
            .putString("legacy_checkpoint_owner", enrollmentKey(owner).takeIf { legacy != null && CollectionModuleId.values().all { generation(it) == 0L } }).commit())
    }

    fun completeAfterVerifiedReset() {
        val editor = prefs.edit().remove("pending").remove("retired_intents")
        pending().forEach {
            editor.remove("pending_state:${it.id}").remove("pending_owner:${it.id}")
            removeLegacyInventory(editor, it)
        }
        checkLocalStoreWrite(editor.commit())
    }

    /** Survives reset/process death until every indivisible recovery artifact is removed. */
    fun markRecoveryErasure(owner: Pair<String, String>?) {
        val scope = owner?.let {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("${it.first}\u0000${it.second}".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        } ?: "unknown"
        val ownerKey = ResearchPersistenceGate.currentOwnerKey()
            ?: prefs.getString("installed_enrollment", null)
            ?: pending().mapNotNull(::pendingOwner).distinct().singleOrNull()
        checkLocalStoreWrite(prefs.edit().putString("pending_recovery_scope", scope).putString("pending_recovery_owner", ownerKey).commit())
    }

    fun replayRecoveryErasure(context: Context) {
        if (!prefs.contains("pending_recovery_scope")) return
        com.openlattice.chronicle.storage.LocalStoreRecoveryManager.erasePendingRecoveryArtifacts(context,
            prefs.getString("pending_recovery_scope", null)?.takeUnless { it == "unknown" })
        checkLocalStoreWrite(prefs.edit().remove("pending_recovery_scope").remove("pending_recovery_owner").commit())
    }

    fun settingsChanged() {
        checkLocalStoreWrite(prefs.edit().putLong("settings_generation", settingsGeneration() + 1).commit())
    }

    /** Commit before changing decisions: interruption can close admission, never lose erasure. */
    fun erase(modules: Set<CollectionModuleId>, durableIntent: Boolean = true, now: Long = System.currentTimeMillis(),
              states: Collection<CollectionModuleState> = emptyList(), ownerKey: String? = null, installedEnrollment: String? = null) {
        val editor = prefs.edit().putLong("settings_generation", settingsGeneration() + 1)
        if (installedEnrollment != null) editor.putString("installed_enrollment", installedEnrollment)
        modules.forEach { module ->
            if (durableIntent && module !in pending()) editor.putString("pending_owner:${module.id}", ownerKey)
            editor.putLong("generation:${module.id}", generation(module) + 1)
                .putLong("floor:${module.id}", now)
        }
        states.filter { it.moduleId in modules }.forEach {
            editor.putString("pending_state:${it.moduleId.id}", JsonSerializer.toJson(it))
        }
        if (durableIntent) editor.putStringSet("pending", (pending() + modules).map { it.id }.toSet())
        checkLocalStoreWrite(editor.commit()) { "Unable to commit research erasure intent" }
        val affected = synchronized(observers) { observers.entries.filter { it.value.any(modules::contains) }.map { it.key } }
        affected.forEach { observer -> observer.eraseResearchObservations() }
    }

    fun accepted(modules: Set<CollectionModuleId>, now: Long = System.currentTimeMillis()) {
        val editor = prefs.edit()
        modules.filter { prefs.getLong("accepted_generation:${it.id}", -1) != generation(it) }.forEach {
            if (floor(it) > 0) editor.putLong("floor:${it.id}", now)
            editor.putLong("accepted_generation:${it.id}", generation(it))
        }
        checkLocalStoreWrite(editor.commit()) { "Unable to commit consent epoch" }
    }

    /** Initial reviewed history remains available; subsequent enrollment changes retire it. */
    fun installEnrollment(owner: UploadServerEntity): Boolean {
        val key = enrollmentKey(owner)
        if (prefs.getString("installed_enrollment", null) == key) return false
        // Finish the previous owner's obligations before rotating epochs or installing identity.
        CollectionLoopCoordinator(context).replayPendingErasures()
        check(!hasPendingErasures()) { "Previous enrollment erasure must be retried before installation" }
        val modules = CollectionModuleId.values().toSet()
        val firstEnrollment = modules.all { generation(it) == 0L }
        erase(modules, durableIntent = false, now = if (firstEnrollment) 0 else System.currentTimeMillis(), installedEnrollment = key)
        return true
    }

    fun pendingOwner(module: CollectionModuleId): String? = prefs.getString("pending_owner:${module.id}", null)

    fun pendingState(module: CollectionModuleId): CollectionModuleState? =
        prefs.getString("pending_state:${module.id}", null)?.let { JsonSerializer.fromJson<CollectionModuleState>(it) }

    fun completed(module: CollectionModuleId) {
        val editor = prefs.edit().remove("pending_state:${module.id}").remove("pending_owner:${module.id}")
            .putStringSet("pending", (pending() - module).map { it.id }.toSet())
            .putStringSet("retired_intents", legacyRetiredIntents().filterNot { it.substringAfter(':') == module.id }.toSet())
        removeLegacyInventory(editor, module)
        checkLocalStoreWrite(editor.commit())
    }

    companion object {
        interface Observer { fun eraseResearchObservations() }
        private val observers = java.util.WeakHashMap<Observer, Set<CollectionModuleId>>()
        fun registerObserver(observer: Observer, modules: Set<CollectionModuleId>) {
            synchronized(observers) { observers[observer] = modules }
        }
        fun unregisterObserver(observer: Observer) { synchronized(observers) { observers.remove(observer) } }
        fun enrollmentKey(owner: UploadServerEntity): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${owner.id}:${owner.createdAt}:${owner.enrollmentIssuedAtEpochMillis}:${owner.studyId}:${owner.participantId}:${owner.sourceDeviceId}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
