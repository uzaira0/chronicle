package com.openlattice.chronicle.services.upload

import android.content.Context
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.checkLocalStoreWrite
import com.openlattice.chronicle.storage.UploadServerEntity
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** Credential failures wait for enrollment correction; other failures use bounded backoff. */
internal object UploadRetryGate {
    private fun key(server: UploadServerEntity, family: LocalUploadModuleFamily) =
        "upload_retry:${server.id}:${server.createdAt}:${family.name}"
    fun batchLimit(context: Context, server: UploadServerEntity, family: LocalUploadModuleFamily, maximum: Int): Int =
        EncryptedPrefsHelper.getEncryptedPrefs(context).getInt("${key(server, family)}.batchLimit", maximum).coerceIn(1, maximum)

    fun reduceBatchLimit(context: Context, server: UploadServerEntity, family: LocalUploadModuleFamily, count: Int) {
        checkLocalStoreWrite(EncryptedPrefsHelper.getEncryptedPrefs(context).edit()
            .putInt("${key(server, family)}.batchLimit", (count / 2).coerceAtLeast(1)).commit())
    }
    fun resetBatchLimit(context: Context, server: UploadServerEntity, family: LocalUploadModuleFamily) {
        checkLocalStoreWrite(EncryptedPrefsHelper.getEncryptedPrefs(context).edit()
            .remove("${key(server, family)}.batchLimit").commit())
    }
    // nosemgrep: chronicle-uuid-from-string -- Local retry cache compares configuration fingerprints; this value grants no access or identity.
    private fun fingerprint(context: Context, server: UploadServerEntity, db: ChronicleDb) = UUID.nameUUIDFromBytes(

        listOf(server.url, server.studyId, server.participantId, server.sourceDeviceId, server.authMode,
            server.apiKey, server.mobileSigningSecretOverride,
            db.uploadServerDao().getById(server.id)?.enrollmentIssuedAtEpochMillis,
            ResearchErasureFence(context).settingsGeneration(),
            TERMINAL_CREDENTIAL_STATUSES.joinToString(",")).joinToString("|").toByteArray()).toString()
    fun shouldAttempt(context: Context, server: UploadServerEntity, family: LocalUploadModuleFamily,
                      db: ChronicleDb, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
        val key = key(server, family)
        return prefs.getString("$key.fingerprint", null) != fingerprint(context, server, db) ||
            prefs.getLong("$key.after", 0) <= nowMillis ||
            (prefs.getLong("$key.after", 0) == Long.MAX_VALUE && !prefs.getBoolean("$key.credentials", false))
    }
    fun isBlocked(context: Context, server: UploadServerEntity, db: ChronicleDb): Boolean =
        LocalUploadModuleFamily.entries.any { family ->
            val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
            val key = key(server, family)
            prefs.getString("$key.fingerprint", null) == fingerprint(context, server, db) &&
                prefs.getLong("$key.after", 0) == Long.MAX_VALUE && prefs.getBoolean("$key.credentials", false)
        }
    fun recordFailure(context: Context, server: UploadServerEntity, family: LocalUploadModuleFamily,
                      error: Exception, db: ChronicleDb = ChronicleDb.getInstance(context)) {
        if (error is com.openlattice.chronicle.services.crypto.EncryptionRequiredButUnavailableException) return
        val status = uploadHttpStatus(error)
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
        val key = key(server, family)
        val fingerprint = fingerprint(context, server, db)
        val previous = if (prefs.getString("$key.fingerprint", null) == fingerprint) prefs.getInt("$key.attempt", 0) else 0
        val attempt = (previous + 1).coerceAtMost(6)
        val credentials = status in TERMINAL_CREDENTIAL_STATUSES
        val after = if (credentials) Long.MAX_VALUE else System.currentTimeMillis() + retryDelayMillis(attempt)
        checkLocalStoreWrite(prefs.edit().putString("$key.fingerprint", fingerprint).putLong("$key.after", after)
            .putInt("$key.attempt", attempt).putBoolean("$key.credentials", credentials).commit())
    }
    // A 403 can reflect a collection cutoff or changing consent, rather than invalid credentials.
    // Including this policy in the fingerprint also releases blocks persisted by older clients.
    private val TERMINAL_CREDENTIAL_STATUSES = setOf(401, 410)

    fun retryDelayMillis(attempt: Int): Long {
        val ceiling = minOf(900_000L, 30_000L shl (attempt - 1).coerceIn(0, 5))
        return ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1)
    }
    fun familyFor(payload: EncryptedPayloadType): LocalUploadModuleFamily = when (payload) {
        EncryptedPayloadType.USAGE -> LocalUploadModuleFamily.USAGE_LIFECYCLE
        EncryptedPayloadType.SENSOR -> LocalUploadModuleFamily.SENSOR
        EncryptedPayloadType.INTERACTION -> LocalUploadModuleFamily.INTERACTION
        EncryptedPayloadType.BATTERY -> LocalUploadModuleFamily.BATTERY
        EncryptedPayloadType.CONNECTIVITY_STATE -> LocalUploadModuleFamily.CONNECTIVITY
        EncryptedPayloadType.DEVICE_SETTINGS -> LocalUploadModuleFamily.DEVICE_SETTINGS
        EncryptedPayloadType.APP_NETWORK_USAGE -> LocalUploadModuleFamily.APP_NETWORK
        EncryptedPayloadType.SLEEP -> LocalUploadModuleFamily.SLEEP
        EncryptedPayloadType.ACTIVITY_RECOGNITION -> LocalUploadModuleFamily.ACTIVITY_RECOGNITION
        EncryptedPayloadType.AUDIO_ACTIVITY -> LocalUploadModuleFamily.AUDIO_ACTIVITY
        EncryptedPayloadType.AUDIO_CONTENT -> LocalUploadModuleFamily.AUDIO_CONTENT
        EncryptedPayloadType.NOTIFICATION_ACTIVITY -> LocalUploadModuleFamily.NOTIFICATION
        else -> LocalUploadModuleFamily.HEALTH
    }
}
