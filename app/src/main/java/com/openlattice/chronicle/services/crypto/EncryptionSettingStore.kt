package com.openlattice.chronicle.services.crypto

import android.content.Context
import android.content.SharedPreferences
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
 * **Fail closed.** [isEncryptionRequired] is true whenever the study's encryption policy is not
 * KNOWN to permit plaintext. A policy becomes known only through a successful [put] — the server
 * always answers the Encryption settings read with an authoritative value (an un-provisioned study
 * returns a disabled default), so a fetch that threw is "unknown", never "disabled". An enabled
 * setting keeps the requirement sticky even if its key material is missing or corrupt; only an
 * authoritative *disabled* fetch clears it. Combined with an in-memory authoritative copy of the
 * setting (so a transient `SharedPreferences` write failure cannot silently drop the key), this
 * lets the upload paths retain and retry a batch rather than send PHI in plaintext whenever the
 * policy is unknown or the key is momentarily unavailable. See [PayloadSealer.routing].
 *
 * The persistent copy lives in [EncryptedPrefsHelper]'s SharedPreferences (encrypted at rest),
 * serialized with the app's Kotlin-aware JSON boundary, and carries the study
 * PUBLIC key only — never any private material.
 */
class EncryptionSettingStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext))

    /**
     * Persists [setting] for [studyId]. Idempotent; overwrites any prior cached value.
     *
     * Writes the in-memory authoritative copy FIRST (cannot fail) so a `SharedPreferences` write
     * failure can't drop e2ee to plaintext for the life of the process. This is the ONLY call that
     * makes the study's policy authoritatively known: an enabled fetch marks the study
     * e2ee-required (sticky, regardless of whether its key material parses — a missing or corrupt
     * key must fail closed, not fall back to plaintext); a disabled fetch is an *authoritative*
     * disable and clears the sticky flag (back to plaintext).
     */
    fun put(studyId: UUID, setting: StudyEncryptionSetting) {
        memoryCache[studyId] = setting
        val required = setting.enabled
        if (required) requiredStudies.add(studyId) else requiredStudies.remove(studyId)
        knownStudies.add(studyId)
        try {
            val edit = prefs.edit().putString(keyFor(studyId), JsonSerializer.toJson(setting))
                .putBoolean(knownKeyFor(studyId), true)
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
     * True when [studyId] may NOT be uploaded in plaintext: either it is known to require e2ee
     * (an enabled setting was observed; cleared only by an authoritative *disabled* fetch, never
     * by a failed sync), or its policy is not known at all — no successful fetch has landed yet,
     * so the device cannot rule out that the study requires encryption. When this is true but
     * [get] yields no usable key, the upload paths fail closed (retain + retry) instead of sending
     * PHI in plaintext.
     */
    fun isEncryptionRequired(studyId: UUID): Boolean =
        requiredStudies.contains(studyId) ||
            prefs.getBoolean(requiredKeyFor(studyId), false) ||
            !isPolicyKnown(studyId)

    /** Convenience for upload delegates that hold the studyId as a String. Fails closed on a bad id. */
    fun isEncryptionRequired(studyId: String): Boolean =
        runCatching { UUID.fromString(studyId) }.getOrNull()?.let { isEncryptionRequired(it) } ?: true

    /**
     * Whether an authoritative encryption policy for [studyId] has ever been fetched. The server
     * answers this read for every study (an un-provisioned one returns a disabled default), so a
     * missing answer means the fetch failed — transport, authentication, or a malformed response —
     * and is never evidence that plaintext is permitted.
     */
    fun isPolicyKnown(studyId: UUID): Boolean =
        knownStudies.contains(studyId) || prefs.getBoolean(knownKeyFor(studyId), false)

    /**
     * Forgets ALL cached encryption state for [studyId]: the persisted public-key setting, the
     * in-memory authoritative copy, and the sticky `encryptionRequired` flag (both the in-memory
     * set and its pref). Call on un-enrollment (server/study removal) so a removed study's key is
     * not retained and a later re-enrollment starts from a clean slate. Safe when nothing is cached.
     */
    fun evict(studyId: UUID) {
        memoryCache.remove(studyId)
        requiredStudies.remove(studyId)
        knownStudies.remove(studyId)
        try {
            if (!prefs.edit().remove(keyFor(studyId)).remove(requiredKeyFor(studyId))
                    .remove(knownKeyFor(studyId)).commit()
            ) {
                Log.w(TAG, "Failed to clear persisted encryption setting")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear persisted encryption setting", e)
        }
    }

    private fun keyFor(studyId: UUID): String = "$KEY_PREFIX$studyId"
    private fun requiredKeyFor(studyId: UUID): String = "$REQUIRED_PREFIX$studyId"
    private fun knownKeyFor(studyId: UUID): String = "$KNOWN_PREFIX$studyId"

    companion object {
        private const val TAG = "EncryptionSettingStore"
        private const val KEY_PREFIX = "com.openlattice.chronicle.encryption.setting."
        private const val REQUIRED_PREFIX = "com.openlattice.chronicle.encryption.required."
        private const val KNOWN_PREFIX = "com.openlattice.chronicle.encryption.known."

        // Process-lifetime caches shared across the per-call instances (`of` is not a singleton),
        // so a SharedPreferences write failure or a settings-sync miss can't drop a known key.
        private val memoryCache = ConcurrentHashMap<UUID, StudyEncryptionSetting>()
        private val requiredStudies: MutableSet<UUID> =
            java.util.Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
        private val knownStudies: MutableSet<UUID> =
            java.util.Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())

        fun of(context: Context): EncryptionSettingStore = EncryptionSettingStore(context)
    }
}
