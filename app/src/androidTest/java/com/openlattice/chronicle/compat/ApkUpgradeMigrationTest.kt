package com.openlattice.chronicle.compat

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.study.StudyEncryptionSetting
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.UploadExecutor
import org.apache.olingo.commons.api.edm.FullQualifiedName
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Two-stage in-place upgrade proof; pass predecessorVersion and currentVersion instrumentation arguments. */
@RunWith(AndroidJUnit4::class)
class ApkUpgradeMigrationTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seedPredecessorState() {
        assumeTrue(
            "seed stage requires the supplied predecessor APK",
            installedVersionCode() == requiredVersion(PREDECESSOR_VERSION_ARGUMENT),
        )

        EnrollmentSettings(context).apply {
            setStudyId(STUDY_ID)
            setParticipantId(PARTICIPANT_ID)
            setParticipationStatus(ParticipationStatus.ENROLLED)
            setAwarenessNotificationsEnabled(true)
            setPropertyTypeIds(mapOf(PROPERTY_TYPE to PROPERTY_ID))
        }
        assertTrue(
            EncryptedPrefsHelper.getEncryptedPrefs(context)
                .edit()
                .putString(PREF_MARKER_KEY, PREF_MARKER_VALUE)
                .commit()
        )
        EncryptionSettingStore.of(context).put(
            STUDY_ID,
            StudyEncryptionSetting(
                enabled = false,
                keyId = ENCRYPTION_KEY_ID,
                publicKeyPem = "migration-public-key-marker",
            ),
        )
        assertTrue(
            "Failed to flush predecessor encrypted preferences",
            EncryptedPrefsHelper.getEncryptedPrefs(context)
                .edit()
                .putBoolean(PREF_FLUSH_KEY, true)
                .commit(),
        )
        assertNotNull("Predecessor encryption JSON was not durably persisted", awaitLegacyEncryptionJson())

        val db = ChronicleDb.getInstance(context)
        db.uploadServerDao().insert(
            UploadServerEntity(
                name = SERVER_NAME,
                url = SERVER_URL,
                studyId = STUDY_ID.toString(),
                participantId = PARTICIPANT_ID,
                sourceDeviceId = DEVICE_ID,
                authMode = AUTH_MODE_API_KEY,
                apiKey = API_KEY,
                enabled = true,
                createdAt = "2026-07-10T12:34:56Z",
            )
        )
        db.queueEntryData().insertEntry(QueueEntry(QUEUE_TIMESTAMP, QUEUE_ID, QUEUE_PAYLOAD))
        context.openFileOutput(FILE_NAME, 0).use { it.write(FILE_CONTENT) }

        assertSeededState()
    }

    @Test
    fun verifyCurrentState() {
        assumeTrue(
            "verification stage requires the supplied current APK",
            installedVersionCode() == requiredVersion(CURRENT_VERSION_ARGUMENT),
        )
        assertSeededState()

        assertNotNull("Predecessor encryption JSON disappeared during upgrade", legacyEncryptionJson())
        val encryption = EncryptionSettingStore.of(context).get(STUDY_ID)
        assertEquals(ENCRYPTION_KEY_ID, encryption?.keyId)
        assertFalse(encryption?.enabled ?: true)

        val databaseHeader = ByteArray(16)
        context.getDatabasePath("chronicle_encrypted").inputStream().use { input ->
            assertEquals(databaseHeader.size, input.read(databaseHeader))
        }
        var posts = 0
        val api = java.lang.reflect.Proxy.newProxyInstance(
            ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java),
        ) { _, method, args ->
            check(method.name == "uploadAndroidUsageEventData")
            posts++
            (args[4] as ChronicleData).size
        } as ChronicleStudyApi
        val uploader = UploadExecutor(context,
            ChronicleDb.getInstance(context), emptyMap(), { api })
        repeat(2) { uploader.uploadForServer(ChronicleDb.getInstance(context).uploadServerDao().getByUrl(SERVER_URL)!!) }
        assertEquals(1, posts)
        assertEquals(0, ChronicleDb.getInstance(context).queueEntryData().getSize())

        assertFalse(
            "SQLCipher database must not expose the plaintext SQLite header",
            databaseHeader.contentEquals("SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)),
        )
    }

    private fun assertSeededState() {
        val enrollment = EnrollmentSettings(context)
        assertTrue(enrollment.isEnrolled())
        assertEquals(STUDY_ID, enrollment.getStudyId())
        assertEquals(PARTICIPANT_ID, enrollment.getParticipantId())
        assertEquals(ParticipationStatus.ENROLLED, enrollment.getParticipationStatus())
        assertTrue(enrollment.getAwarenessNotificationsEnabled())
        assertEquals(PROPERTY_ID, enrollment.getPropertyTypeIds()[PROPERTY_TYPE])
        assertEquals(
            PREF_MARKER_VALUE,
            EncryptedPrefsHelper.getEncryptedPrefs(context).getString(PREF_MARKER_KEY, null),
        )
        assertTrue(EncryptedPrefsHelper.getEncryptedPrefs(context).getBoolean(PREF_FLUSH_KEY, false))

        val db = ChronicleDb.getInstance(context)
        val server = db.uploadServerDao().getByUrl(SERVER_URL)
        assertEquals(SERVER_NAME, server?.name)
        assertEquals(STUDY_ID.toString(), server?.studyId)
        assertEquals(PARTICIPANT_ID, server?.participantId)
        assertEquals(DEVICE_ID, server?.sourceDeviceId)
        assertEquals(AUTH_MODE_API_KEY, server?.authMode)
        assertEquals(API_KEY, server?.apiKey)
        assertTrue(server?.enabled == true)

        val queueEntry = db.queueEntryData().getNextEntries(1).single()
        assertEquals(QUEUE_TIMESTAMP, queueEntry.writeTimestamp)
        assertEquals(QUEUE_ID, queueEntry.id)
        assertArrayEquals(QUEUE_PAYLOAD, queueEntry.data)
        assertArrayEquals(FILE_CONTENT, context.openFileInput(FILE_NAME).use { it.readBytes() })
    }

    private fun awaitLegacyEncryptionJson(): String? {
        repeat(50) {
            legacyEncryptionJson()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun legacyEncryptionJson(): String? =
        EncryptedPrefsHelper.getEncryptedPrefs(context).getString(
            "com.openlattice.chronicle.encryption.setting.$STUDY_ID",
            null,
        )

    private fun requiredVersion(argument: String): Long =
        requireNotNull(InstrumentationRegistry.getArguments().getString(argument)?.toLongOrNull()) {
            "Missing positive instrumentation argument $argument"
        }.also { require(it > 0) }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    private companion object {
        const val PREDECESSOR_VERSION_ARGUMENT = "predecessorVersion"
        const val CURRENT_VERSION_ARGUMENT = "currentVersion"
        val STUDY_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val PROPERTY_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val PROPERTY_TYPE: FullQualifiedName = FullQualifiedName("migration.property")
        const val PARTICIPANT_ID = "migration-participant"
        const val DEVICE_ID = "migration-device"
        const val SERVER_NAME = "Migration server"
        const val SERVER_URL = "https://chronicle-screentime-app.research.bcm.edu/"
        const val API_KEY = "migration-api-key"
        const val ENCRYPTION_KEY_ID = "migration-key-id"
        const val PREF_MARKER_KEY = "migration-proof-marker"
        const val PREF_MARKER_VALUE = "upgrade-seed-encrypted-preference"
        const val PREF_FLUSH_KEY = "migration-proof-flush"
        const val FILE_NAME = "migration-proof.bin"
        val FILE_CONTENT: ByteArray = "upgrade-seed-private-file".toByteArray(StandardCharsets.UTF_8)
        const val QUEUE_TIMESTAMP = 1_720_613_696_000L
        const val QUEUE_ID = 49_048L
        val QUEUE_PAYLOAD: ByteArray = JsonSerializer.serializeQueueEntry(listOf(
            ExtractedUsageEvent(
                appPackageName = "upgrade.fixture", interactionType = "Activity Resumed",
                timestamp = java.time.OffsetDateTime.parse("2026-07-10T12:34:56Z"),
                timezone = "UTC", user = "", applicationLabel = "Upgrade fixture",
            ),
        ))
    }
}
