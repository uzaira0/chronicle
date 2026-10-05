package com.openlattice.chronicle.services.upload

import android.content.Context
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** Retain rejected data and retry only after a corrective version/credential change. */
internal object UploadRetryGate {
    private fun key(server: UploadServerEntity, family: LocalUploadModuleFamily) =
        "upload_retry:${server.id}:${server.createdAt}:${family.name}"
    private fun fingerprint(server: UploadServerEntity, db: ChronicleDb) = UUID.nameUUIDFromBytes(
        listOf(server.url, server.studyId, server.participantId, server.sourceDeviceId, server.authMode,
            server.apiKey, server.mobileSigningSecretOverride, BuildConfig.VERSION_CODE,
            db.collectionModuleStateDao().getAll().maxOfOrNull { it.appliedVersion }).joinToString("|").toByteArray()).toString()
    fun shouldAttempt(context: Context, server: UploadServerEntity, family: LocalUploadModuleFamily,
                      db: ChronicleDb, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
        val key = key(server, family)
        return prefs.getString("$key.fingerprint", null) != fingerprint(server, db) ||
            prefs.getLong("$key.after", 0) <= nowMillis
    }
    fun isBlocked(context: Context, server: UploadServerEntity, db: ChronicleDb): Boolean =
        LocalUploadModuleFamily.entries.any { family ->
            val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
            val key = key(server, family)
            prefs.getString("$key.fingerprint", null) == fingerprint(server, db) &&
                prefs.getLong("$key.after", 0) == Long.MAX_VALUE
        }
    fun recordFailure(context: Context, server: UploadServerEntity, family: LocalUploadModuleFamily,
                      error: Exception, db: ChronicleDb = ChronicleDb.getInstance(context)) {
        if (error is com.openlattice.chronicle.services.crypto.EncryptionRequiredButUnavailableException) return
        val status = uploadHttpStatus(error)
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
        val key = key(server, family)
        val fingerprint = fingerprint(server, db)
        val previous = if (prefs.getString("$key.fingerprint", null) == fingerprint) prefs.getInt("$key.attempt", 0) else 0
        val attempt = (previous + 1).coerceAtMost(6)
        val after = if (status in setOf(400, 413, 422)) Long.MAX_VALUE else System.currentTimeMillis() + retryDelayMillis(attempt)
        check(prefs.edit().putString("$key.fingerprint", fingerprint).putLong("$key.after", after)
            .putInt("$key.attempt", attempt).commit())
    }
    fun retryDelayMillis(attempt: Int): Long {
        val ceiling = minOf(900_000L, 30_000L shl (attempt - 1).coerceIn(0, 5))
        return ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1)
    }
    fun familyFor(payload: EncryptedPayloadType): LocalUploadModuleFamily = when (payload) {
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
