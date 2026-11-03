package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.storage.LocalStoreRecoveryManager
import java.security.MessageDigest
import java.util.WeakHashMap
import java.time.Instant

import com.openlattice.chronicle.storage.checkLocalStoreWrite
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.HardStop
import com.openlattice.chronicle.time.ServerClock
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.serialization.JsonSerializer

/** Durable epochs and replay intent. Mutations belong to ResearchPersistenceGate.stop. */
class ResearchErasureFence(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE)

    fun generation(module: CollectionModuleId): Long = prefs.getLong("generation:${module.id}", 0)
    fun settingsGeneration(): Long = prefs.getLong("settings_generation", 0)
    fun floor(module: CollectionModuleId): Long = prefs.getLong("floor:${module.id}", 0)
    fun pending(): Set<CollectionModuleId> = prefs.getStringSet("pending", emptySet()).orEmpty()
        .mapNotNull(CollectionModuleId::fromIdOrNull).toSet()

    fun hasPendingErasures(): Boolean = pending().isNotEmpty() || prefs.contains("pending_recovery_scope")

    fun completeAfterVerifiedReset() {
        val editor = prefs.edit().remove("pending")
        pending().forEach {
            editor.remove("pending_state:${it.id}").remove("pending_owner:${it.id}")
        }
        checkLocalStoreWrite(editor.commit())
    }

    /** Survives reset/process death until every indivisible recovery artifact is removed. */
    fun markRecoveryErasure(ownerScope: String?) {
        checkLocalStoreWrite(prefs.edit().putString("pending_recovery_scope", ownerScope ?: "unknown").commit())
    }

    fun replayRecoveryErasure(context: Context) {
        if (!prefs.contains("pending_recovery_scope")) return
        LocalStoreRecoveryManager.erasePendingRecoveryArtifacts(context,
            prefs.getString("pending_recovery_scope", null)?.takeUnless { it == "unknown" })
        checkLocalStoreWrite(prefs.edit().remove("pending_recovery_scope").commit())
    }

    fun settingsChanged() {
        checkLocalStoreWrite(prefs.edit().putLong("settings_generation", settingsGeneration() + 1).commit())
    }

    /** Commit before changing decisions: interruption can close admission, never lose erasure. */
    fun erase(modules: Set<CollectionModuleId>, durableIntent: Boolean = true, now: Long = System.currentTimeMillis(),
              states: Collection<CollectionModuleState> = emptyList(), ownerKey: String? = null, installedEnrollment: String? = null,
              hardStopGeneration: Long = UNSEEN_HARD_STOP) {
        val editor = prefs.edit().putLong("settings_generation", settingsGeneration() + 1)
        if (installedEnrollment != null) {
            editor.putString("installed_enrollment", installedEnrollment)
                .remove(SUSPENDED_AT).remove(SUSPENDED_ELAPSED).remove(SUSPENSIONS).remove(COLLECTING).remove(CAPTURE_WINDOWS)
                .remove(HARD_STOP_AT).putLong(HARD_STOP_SEEN, hardStopGeneration)
                .remove(ENROLLMENT_STOP_OWNER).remove(ENROLLMENT_STOP_GENERATION)
            prefs.all.keys.filter { key -> listOf(STOPPED_AT, STOPPED_ELAPSED, STOPS).any(key::startsWith) }
                .forEach(editor::remove)
        }
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

    /**
     * The required-decline halt, a decision made on this phone and timed on its own clock. Pause, completion,
     * archive and the processing block are the server's windows instead ([recordCaptureWindows]).
     */
    fun reconcileHaltSuspension(halted: Boolean, now: Long = System.currentTimeMillis(),
                                elapsed: Long = SystemClock.elapsedRealtime()) {
        if (halted == prefs.contains(SUSPENDED_AT)) return
        val editor = prefs.edit()
        if (halted) editor.openStop(SUSPENDED_AT, SUSPENDED_ELAPSED, now, elapsed)
        else editor.closeStop(SUSPENDED_AT, SUSPENDED_ELAPSED, SUSPENSIONS, now, elapsed)
        checkLocalStoreWrite(editor.commit())
    }

    /**
     * Closes the stop [openStop] began and appends it to [intervalsKey]. The elapsed clock bounds the stop in
     * both wall-clock frames, so a clock set back (or forward) during it still excludes what was captured inside it.
     */
    private fun SharedPreferences.Editor.closeStop(atKey: String, elapsedKey: String, intervalsKey: String,
                                                   now: Long, elapsed: Long) {
        val startedAt = prefs.getLong(atKey, Long.MIN_VALUE)
        remove(atKey).remove(elapsedKey)
        if (startedAt == Long.MIN_VALUE) return
        // A backward correction D during a stop of length P excludes up to 2·min(D, P) of legitimate
        // data, split across both sides of the stop; wall-clock stamps cannot say which frame they came from.
        // A reboot during the stop resets elapsed time, leaving only [startedAt, now); if the clock was also
        // set back so that now < startedAt at resume, nothing is recorded.
        val stoppedFor = (elapsed - prefs.getLong(elapsedKey, Long.MAX_VALUE)).coerceAtLeast(0)
        val closed = listOf(startedAt to maxOf(now, startedAt + stoppedFor), now - stoppedFor to now)
            .filter { (start, end) -> start < end }.map { (start, end) -> "$start:$end" }
        putStringSet(intervalsKey, prefs.getStringSet(intervalsKey, emptySet()).orEmpty() + closed)
    }

    fun suspendedIntervals(): List<LongRange> = closedIntervals(SUSPENSIONS) + captureWindows() +
        if (prefs.contains(SUSPENDED_AT)) listOf(prefs.getLong(SUSPENDED_AT, 0)..Long.MAX_VALUE) else emptyList()

    /**
     * The server's closed capture windows as its status poll published them, kept on the server's clock and
     * converted on every read, so a later clock measurement or manual clock change moves them with the phone.
     *
     * With them, the latest hard stop (Mark not enrolled or withdrawal): everything captured before it is one more
     * window, so a phone that missed the stop (enrolled again before it polled) never uploads, nor reads again, its
     * pre-stop backlog. The stop never moves earlier (a server restored from an older backup), so a dropped row never
     * comes back. A stop this enrollment has not seen also runs [clearUnseen] for what has no capture time, and is
     * recorded after it, so a process death in between only clears again. A stop is unseen when its generation is
     * above the one seen, or when it began after the stop already kept: polls apply in the order they were sent
     * (pollParticipationStatus), so a lower generation means the server was restored without its continuity
     * checkpoint, and the phone takes the server's count. Both are server instants, so no phone clock is compared.
     * The enrollment response seeds the baseline before collection starts; an unknown baseline clears conservatively
     * on the first stop. Caller holds the research stop.
     */
    fun recordCaptureWindows(windows: List<CaptureWindow>, hardStop: HardStop?, clearUnseen: () -> Unit) {
        val published = hardStop?.generation ?: 0
        val seen = prefs.getLong(HARD_STOP_SEEN, UNSEEN_HARD_STOP)
        val kept = prefs.getLong(HARD_STOP_AT, Long.MIN_VALUE)
        val at = hardStop?.at?.toInstant()?.toEpochMilli()
        val later = at != null && at > kept
        val beganAfterTheKeptStop = later && kept != Long.MIN_VALUE
        checkLocalStoreWrite(prefs.edit().putStringSet(CAPTURE_WINDOWS, windows.mapTo(linkedSetOf()) {
            "${it.start.toInstant().toEpochMilli()}:${it.end?.toInstant()?.toEpochMilli() ?: ""}"
        }).commit()) { "Unable to commit capture windows" }
        if (published > 0 && (published > seen || beganAfterTheKeptStop)) clearUnseen()
        val consumed = prefs.edit().putLong(HARD_STOP_SEEN, published)
        if (later) consumed.putLong(HARD_STOP_AT, checkNotNull(at))
        checkLocalStoreWrite(consumed.commit()) { "Unable to commit the hard stop" }
    }

    /**
     * [recordCaptureWindows] on this phone's wall clock: `[start, end)`, open-ended while a window lasts, and the time
     * before the latest hard stop, the one window that starts at [Long.MIN_VALUE].
     */
    fun captureWindows(): List<LongRange> {
        val stored = prefs.getStringSet(CAPTURE_WINDOWS, emptySet()).orEmpty()
        val hardStop = prefs.getLong(HARD_STOP_AT, Long.MIN_VALUE)
        if (stored.isEmpty() && hardStop == Long.MIN_VALUE) return emptyList()
        val clock = ServerClock.of(context)
        return stored.map { window ->
            val (start, end) = window.split(':')
            val phoneStart = clock.toPhone(Instant.ofEpochMilli(start.toLong()))
            if (end.isEmpty()) phoneStart..Long.MAX_VALUE
            else phoneStart until clock.toPhone(Instant.ofEpochMilli(end.toLong()))
        } + if (hardStop == Long.MIN_VALUE) emptyList() else listOf(Long.MIN_VALUE until clock.toPhone(Instant.ofEpochMilli(hardStop)))
    }

    private fun closedIntervals(key: String): List<LongRange> = prefs.getStringSet(key, emptySet()).orEmpty().map {
        val (start, end) = it.split(':').map(String::toLong)
        start until end
    }

    /**
     * Records when each consent-gated module stops and resumes collecting (study disable, participant
     * hold), the per-module counterpart of [reconcileHaltSuspension]. Sources that read history
     * later (UsageStats) then apply the decision that held when each observation was captured, instead
     * of the decision at read time. Before a module first collects nothing is recorded: its floor
     * already governs the initial reviewed history.
     */
    fun reconcileModuleCollection(collecting: Set<CollectionModuleId>, now: Long = System.currentTimeMillis(),
                                  elapsed: Long = SystemClock.elapsedRealtime()) {
        val ids = collecting.filter { it in CollectionStateMachine.ACK_GATED_MODULES }.map { it.id }.toSet()
        val was = prefs.getStringSet(COLLECTING, emptySet()).orEmpty()
        if (ids == was) return
        val editor = prefs.edit().putStringSet(COLLECTING, ids)
        (was - ids).forEach { editor.openStop(STOPPED_AT + it, STOPPED_ELAPSED + it, now, elapsed) }
        (ids - was).forEach { editor.closeStop(STOPPED_AT + it, STOPPED_ELAPSED + it, STOPS + it, now, elapsed) }
        checkLocalStoreWrite(editor.commit()) { "Unable to commit module collection intervals" }
    }

    /** [suspendedIntervals] plus [module]'s own stopped intervals; a consent-gated module not collecting now is open-ended. */
    fun suspendedIntervals(module: CollectionModuleId): List<LongRange> {
        if (module !in CollectionStateMachine.ACK_GATED_MODULES) return suspendedIntervals()
        val stops = closedIntervals(STOPS + module.id)
        val open = if (module.id in prefs.getStringSet(COLLECTING, emptySet()).orEmpty()) emptyList()
            else listOf(prefs.getLong(STOPPED_AT + module.id, Long.MIN_VALUE)..Long.MAX_VALUE)
        return suspendedIntervals() + stops + open
    }

    /**
     * Opens each module's capture epoch at [now]. Only the enrollment's [initialReview] of a never-erased module keeps
     * history captured before it: a field accepted mid-study never attaches to earlier events.
     */
    fun accepted(modules: Set<CollectionModuleId>, initialReview: Boolean, now: Long = System.currentTimeMillis()) {
        val editor = prefs.edit()
        modules.filter { prefs.getLong("accepted_generation:${it.id}", -1) != generation(it) }.forEach {
            if (floor(it) > 0 || !initialReview) editor.putLong("floor:${it.id}", now)
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
        val baseline = if (owner.reservationNonce != null && prefs.getString(ENROLLMENT_STOP_OWNER, null) == owner.reservationNonce)
            prefs.getLong(ENROLLMENT_STOP_GENERATION, UNSEEN_HARD_STOP) else UNSEEN_HARD_STOP
        erase(modules, durableIntent = false, now = if (firstEnrollment) 0 else System.currentTimeMillis(),
            installedEnrollment = key, hardStopGeneration = baseline)
        return true
    }

    /** Under the stop, before persisting the issued credential: process recovery must retain its response baseline. */
    fun recordEnrollmentHardStop(ownerNonce: String, generation: Long?) {
        checkLocalStoreWrite(prefs.edit().putString(ENROLLMENT_STOP_OWNER, ownerNonce)
            .putLong(ENROLLMENT_STOP_GENERATION, generation ?: UNSEEN_HARD_STOP).commit())
    }

    fun pendingOwner(module: CollectionModuleId): String? = prefs.getString("pending_owner:${module.id}", null)

    fun pendingState(module: CollectionModuleId): CollectionModuleState? =
        prefs.getString("pending_state:${module.id}", null)?.let { JsonSerializer.fromJson<CollectionModuleState>(it) }

    fun completed(module: CollectionModuleId) {
        val editor = prefs.edit().remove("pending_state:${module.id}").remove("pending_owner:${module.id}")
            .putStringSet("pending", (pending() - module).map { it.id }.toSet())
        checkLocalStoreWrite(editor.commit())
    }

    companion object {
        private const val SUSPENDED_AT = "suspended_at"
        private const val SUSPENDED_ELAPSED = "suspended_elapsed"
        private const val SUSPENSIONS = "suspensions"
        private const val CAPTURE_WINDOWS = "capture_windows"
        private const val HARD_STOP_AT = "hard_stop_at"
        private const val HARD_STOP_SEEN = "hard_stop_seen"
        private const val UNSEEN_HARD_STOP = -1L
        private const val ENROLLMENT_STOP_OWNER = "enrollment_stop_owner"
        private const val ENROLLMENT_STOP_GENERATION = "enrollment_stop_generation"
        private const val COLLECTING = "collecting"
        private const val STOPPED_AT = "stopped_at:"
        private const val STOPPED_ELAPSED = "stopped_elapsed:"
        private const val STOPS = "stops:"
        interface Observer { fun eraseResearchObservations() }
        private val observers = WeakHashMap<Observer, Set<CollectionModuleId>>()
        fun registerObserver(observer: Observer, modules: Set<CollectionModuleId>) {
            synchronized(observers) { observers[observer] = modules }
        }
        fun unregisterObserver(observer: Observer) { synchronized(observers) { observers.remove(observer) } }
        fun enrollmentKey(owner: UploadServerEntity): String = MessageDigest.getInstance("SHA-256")
            .digest(
                "${owner.id}:${owner.createdAt}:${owner.enrollmentIssuedAtEpochMillis}:${owner.studyId}:${owner.participantId}:${owner.sourceRandomDeviceId}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

private fun SharedPreferences.Editor.openStop(atKey: String, elapsedKey: String, now: Long, elapsed: Long) =
    putLong(atKey, now).putLong(elapsedKey, elapsed)
