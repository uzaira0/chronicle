package com.openlattice.chronicle.services.upload

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.storage.MAX_PERSISTED_QUEUE_PAYLOAD_BYTES
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class LocalUploadDiagnosticsImportTest {
    @Test fun currentOversizedAdmissionArchivesReachDiagnosticsOnceWithPrivateWarnings() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = EnrollmentTestStores.install(context, enrolled = true)
        try {
            val secret = "private-payload".repeat(MAX_PERSISTED_QUEUE_PAYLOAD_BYTES / 7)
            val sample = SensorSampleEntry("private-row-id", "accelerometer", "2026-09-01T00:00:00Z", "UTC",
                1f, 2f, 3f, null, 3, secret)
            db.sensorSampleDao().insertAll(listOf(sample))
            val server = db.uploadServerDao().getConfiguredServer()!!
            val now = OffsetDateTime.now().toString()
            db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity("private-diagnostic-id", server.studyId, server.participantId,
                server.sourceRandomDeviceId, "${server.id}:${server.createdAt}", LocalDate.now().toString(), LocalUploadModuleFamily.SENSOR.name,
                UploadFailureIssue.UPLOAD_FAILURE.name, 1, now, now, null, secret))
            val store = LocalUploadDiagnosticsStore.of(context)
            repeat(2) {
                val quarantined = store.pending().filter { it.issue == LocalOperationalIssue.SAMPLE_QUARANTINED.name }
                assertEquals(1, quarantined.filter { it.moduleFamily == LocalUploadModuleFamily.SENSOR.name }.sumOf { it.count })
                assertEquals(1, quarantined.filter { it.moduleFamily == LocalUploadModuleFamily.LOCAL_STORE.name }.sumOf { it.count })
            }
            store.settle(UploadReceipt(store.pending().mapTo(hashSetOf()) { UUID.fromString(it.id) }, emptySet(), 0))
            assertTrue(LocalUploadDiagnosticsStore.of(context).pending().isEmpty())
            val warnings = ShadowLog.getLogs().filter { it.type == Log.WARN && it.msg.startsWith("Quarantined oversized row:") }
            assertEquals(2, warnings.size)
            val quarantineMessage = Regex("Quarantined oversized row: table=(sensor_samples|upload_diagnostics) bytes=[0-9]+")
            assertTrue(warnings.all { it.throwable == null && it.msg.matches(quarantineMessage) })
        } finally { db.close(); EnrollmentTestStores.db = null; EnrollmentTestStores.prefs = null }
    }



}
