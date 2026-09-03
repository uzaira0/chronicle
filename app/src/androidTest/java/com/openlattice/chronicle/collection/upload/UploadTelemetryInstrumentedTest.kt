package com.openlattice.chronicle.collection.upload

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.openlattice.chronicle.IsolatedChronicleTestDb
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.services.upload.COMBINED_UPLOAD_WORK_NAME
import com.openlattice.chronicle.services.upload.scheduleCombinedUploadWork
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for the Phase 8 upload-telemetry module (refactor plan §11.1
 * step 20 / §11.2 step 20 — "Android instrumented test for the upload-stats DB and
 * combined-upload WorkManager scheduling").
 *
 * Exercises the [RoomUploadStateProbe] against the real SQLCipher-backed [ChronicleDb]
 * (queue depth, server projection, `upload_stats` row count) and the
 * [WorkManagerUploadProbe] against a `WorkManagerTestInitHelper`-driven WorkManager
 * (the `combined_upload` unique work is scheduled, then read back). Both are paths the
 * JVM fakes cannot cover.
 *
 * Requires a connected device or emulator. When none is available the run is recorded as
 * a BLOCKER; the JVM `UploadTelemetryCollectionModuleTest` / `CombinedUploadOrchestratorTest`
 * / `UploadTelemetryRedactionTest` classes plus `CollectionSinkInstrumentedTest` provide
 * the strongest local proof.
 *
 */
@RunWith(AndroidJUnit4::class)
class UploadTelemetryInstrumentedTest {

    private lateinit var db: ChronicleDb
    private lateinit var isolatedDb: IsolatedChronicleTestDb

    @Before
    fun setUp() {
        isolatedDb = IsolatedChronicleTestDb.create("upload_telemetry")
        db = isolatedDb.db
    }

    @After
    fun tearDown() {
        isolatedDb.close()
    }

    @Test
    fun roomUploadStateProbeReadsQueueDepthAndServerStateFromRealDb() {
        val now = System.currentTimeMillis()
        db.queueEntryData().insertEntries(
            listOf(
                QueueEntry(writeTimestamp = now, id = 1L, data = byteArrayOf(1)),
                QueueEntry(writeTimestamp = now + 1, id = 2L, data = byteArrayOf(2)),
                QueueEntry(writeTimestamp = now + 2, id = 3L, data = byteArrayOf(3)),
            ),
        )
        db.uploadServerDao().insert(
            UploadServerEntity(
                name = "enabled-server",
                url = "https://study.example",
                studyId = "study-1",
                participantId = "participant-1",
                sourceDeviceId = "device-1",
                apiKey = "should-never-surface",
                enabled = true,
            ),
        )
        val probe = RoomUploadStateProbe(db)
        assertEquals(3, probe.usageQueueDepth())
        assertEquals(0, probe.sensorQueueDepth())

        val servers = probe.servers()
        assertEquals(1, servers.size)
        assertEquals(1, servers.count { it.enabled })
        assertEquals(0, servers.count { !it.enabled })
        // Projection drops the apiKey — it never reaches telemetry.
        assertFalse(servers.toString().contains("should-never-surface"))
    }

    @Test
    fun uploadStatsRowCountReadsThroughRealDb() {
        val serverId = db.uploadServerDao().insert(
            UploadServerEntity(
                name = "s", url = "https://study.example", studyId = "study",
                participantId = "p", sourceDeviceId = "d",
            ),
        )
        val statsDao = db.uploadStatsDao()
        statsDao.insertDay(
            com.openlattice.chronicle.storage.UploadStatsEntity(serverId = serverId, date = "2026-05-20"),
        )
        statsDao.insertDay(
            com.openlattice.chronicle.storage.UploadStatsEntity(serverId = serverId, date = "2026-05-21"),
        )
        assertEquals(2, RoomUploadStateProbe(db).uploadStatsRowCount())
    }

    @Test
    fun workManagerProbeReadsScheduledCombinedUploadWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        initializeTestWorkManager(context)
        scheduleCombinedUploadWork(context)

        val probe = WorkManagerUploadProbe(WorkManager.getInstance(context))
        val status = probe.periodicUploadStatus()
        assertNotNull("combined_upload work must be readable after scheduling", status)
        assertEquals(COMBINED_UPLOAD_WORK_NAME, status!!.workName)
        // A freshly scheduled periodic work is ENQUEUED — never SUCCEEDED.
        assertFalse(status.isFailedOrCancelled)
        assertTrue(status.state == "ENQUEUED" || status.state == "RUNNING")
    }

    @Test
    fun moduleDiagnosticsRenderEndToEndAgainstRealDbWithoutSecrets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        initializeTestWorkManager(context)
        db.uploadServerDao().insert(
            UploadServerEntity(
                name = "s", url = "https://SECRET-host.test", studyId = "study",
                participantId = "SECRET-participant", sourceDeviceId = "d",
                apiKey = "SECRET-apikey", enabled = true,
            ),
        )
        val module = UploadTelemetryCollectionModule(
            RoomUploadStateProbe(db),
            WorkManagerUploadProbe(WorkManager.getInstance(context)),
            NoOpCollectionLog,
        )
        val rendered = module.diagnostics().toString() + module.snapshot().toString()
        listOf("SECRET-host", "SECRET-participant", "SECRET-apikey").forEach {
            assertFalse("diagnostics must not contain $it", rendered.contains(it))
        }
    }

    private fun initializeTestWorkManager(context: android.content.Context) {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().build(),
        )
    }
}
