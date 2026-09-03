package com.openlattice.chronicle.services.crypto

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.study.StudyEncryptionSetting
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-study cache for the [StudyEncryptionSetting] (HIPAA-2028 W2 device side).
 *
 * The settings-sync path ([com.openlattice.chronicle.collection.state.CollectionLoopCoordinator.sync])
 * fetches the study's PUBLIC encryption key over the public settings endpoint and writes it
 * here; the upload delegates (usage / sensor / battery) — which run from a
 * [com.openlattice.chronicle.storage.UploadServerEntity] keyed by `studyId` and have no access
 * to the coordinator — read it here to decide encrypted-vs-plaintext per study and to seal.
 *
 * **Fail closed.** A study that is once observed to require e2ee is recorded as
 * [isEncryptionRequired] = true (a sticky flag), cleared only by a later authoritative *disabled*
 * fetch. Combined with an in-memory authoritative copy of the setting (so a transient
 * `SharedPreferences` write failure cannot silently drop the key), this lets the upload paths
 * refuse to send PHI in plaintext when e2ee is required but the public key is momentarily
 * unavailable (sync failed / persistence failed), rather than leaking plaintext. See
 * [PayloadSealer.routing].
 *
 * The persistent copy lives in [EncryptedPrefsHelper]'s SharedPreferences (encrypted at rest),
 * serialized with the app's Kotlin-aware JSON boundary, and carries the study
 * PUBLIC key only — never any private material.
 */
class EncryptionSettingStore(context: Context) {

    private val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext)

    /**
     * Persists [setting] for [studyId]. Idempotent; overwrites any prior cached value.
     *
     * Writes the in-memory authoritative copy FIRST (cannot fail) so a `SharedPreferences` write
     * failure can't drop e2ee to plaintext for the life of the process. A successful fetch that is
     * enabled+keyed marks the study e2ee-required (sticky); a successful fetch that is disabled is
     * an *authoritative* disable and clears the sticky flag (back to plaintext).
     */
    fun put(studyId: UUID, setting: StudyEncryptionSetting) {
        memoryCache[studyId] = setting
        val required = setting.enabled && setting.publicKeyPem.isNotBlank()
        if (required) requiredStudies.add(studyId) else requiredStudies.remove(studyId)
        try {
            val edit = prefs.edit().putString(keyFor(studyId), JsonSerializer.toJson(setting))
            if (required) edit.putBoolean(requiredKeyFor(studyId), true) else edit.remove(requiredKeyFor(studyId))
            if (!edit.commit()) {
                Log.w(TAG, "Failed to persist encryption setting; in-memory copy retained")
            }
        } catch (e: Exception) {
            // The in-memory copy above still holds for this process, so the upload path keeps the
            // key (or stays fail-closed if required) rather than silently dropping to plaintext.
            Log.w(TAG, "Failed to persist encryption setting; in-memory copy retained", e)
        }
    }

    /**
     * The cached [StudyEncryptionSetting] for [studyId], or null when none is cached or the cached
     * value cannot be read. Consults the in-memory copy first (authoritative within the process),
     * then the persistent store.
     */
    fun get(studyId: UUID): StudyEncryptionSetting? {
        // The persistent store is the cross-process source of truth: sync (which writes it) runs in
        // a different process from the upload workers (android:process=":remote"), and the in-memory
        // map is per-process. Read the pref FIRST so a fresh update (e.g. a key rotation or disable)
        // isn't shadowed by a stale in-memory entry; fall back to the in-memory copy only when the
        // pref is absent (e.g. a SharedPreferences write failed in this same process) so a transient
        // persistence failure still can't silently drop a known key.
        val json = prefs.getString(keyFor(studyId), null)
        if (json != null) {
            return try {
                (JsonSerializer.fromJson<StudyEncryptionSetting>(json)
                    ?: throw IllegalStateException("Cached encryption setting decoded as null"))
                    .also { memoryCache[studyId] = it }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read cached encryption setting", e)
                memoryCache[studyId]
            }
        }
        return memoryCache[studyId]
    }

    /** Convenience for upload delegates that hold the studyId as a String. */
    fun get(studyId: String): StudyEncryptionSetting? =
        runCatching { UUID.fromString(studyId) }.getOrNull()?.let { get(it) }

    /**
     * True when [studyId] is KNOWN to require e2ee — set once an enabled+keyed setting was observed
     * and cleared only by an authoritative *disabled* fetch (never by a failed sync). When this is
     * true but [get] yields no usable key, the upload paths fail closed (skip + retry) instead of
     * sending PHI in plaintext.
     */
    fun isEncryptionRequired(studyId: UUID): Boolean =
        requiredStudies.contains(studyId) || prefs.getBoolean(requiredKeyFor(studyId), false)

    /** Convenience for upload delegates that hold the studyId as a String. */
    fun isEncryptionRequired(studyId: String): Boolean =
        runCatching { UUID.fromString(studyId) }.getOrNull()?.let { isEncryptionRequired(it) } ?: false

    /**
     * Forgets ALL cached encryption state for [studyId]: the persisted public-key setting, the
     * in-memory authoritative copy, and the sticky `encryptionRequired` flag (both the in-memory
     * set and its pref). Call on un-enrollment (server/study removal) so a removed study's key is
     * not retained and a later re-enrollment starts from a clean slate. Safe when nothing is cached.
     */
    fun evict(studyId: UUID) {
        memoryCache.remove(studyId)
        requiredStudies.remove(studyId)
        try {
            if (!prefs.edit().remove(keyFor(studyId)).remove(requiredKeyFor(studyId)).commit()) {
                Log.w(TAG, "Failed to clear persisted encryption setting")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear persisted encryption setting", e)
        }
    }

    private fun keyFor(studyId: UUID): String = "$KEY_PREFIX$studyId"
    private fun requiredKeyFor(studyId: UUID): String = "$REQUIRED_PREFIX$studyId"

    companion object {
        private const val TAG = "EncryptionSettingStore"
        private const val KEY_PREFIX = "com.openlattice.chronicle.encryption.setting."
        private const val REQUIRED_PREFIX = "com.openlattice.chronicle.encryption.required."

        // Process-lifetime caches shared across the per-call instances (`of` is not a singleton),
        // so a SharedPreferences write failure or a settings-sync miss can't drop a known key.
        private val memoryCache = ConcurrentHashMap<UUID, StudyEncryptionSetting>()
        private val requiredStudies: MutableSet<UUID> =
            java.util.Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())

        fun of(context: Context): EncryptionSettingStore = EncryptionSettingStore(context)
    }
}
