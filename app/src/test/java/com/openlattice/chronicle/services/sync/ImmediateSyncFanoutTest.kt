package com.openlattice.chronicle.services.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.device.EXPANSION_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.device.ExpansionUploadWorker
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImmediateSyncFanoutTest {
    @Test
    fun `upload now includes every queue outside the combined worker`() {
        val expectedWorkerNames = buildList {
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
                add("com.openlattice.chronicle.collection.interaction.InteractionUploadWorker")
                add("com.openlattice.chronicle.collection.audio.AudioUploadWorker")
            }
            add(ExpansionUploadWorker::class.java.name)
        }
        val expectedQueueOwners = buildSet {
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
                add("interaction_events_upload")
                add("app_audio_upload")
            }
            add(EXPANSION_UPLOAD_WORK_NAME)
        }
        assertEquals(
            expectedWorkerNames.toSet(),
            AUXILIARY_UPLOADS.map { it.workerClass.name }.toSet(),
        )
        assertEquals(
            expectedQueueOwners,
            AUXILIARY_UPLOADS.map { it.queueOwner }.toSet(),
        )
        assertEquals(expectedWorkerNames, AUXILIARY_UPLOADS.map { it.workerClass.name })
    }

    @Test
    fun `manual upload always collects before upload and coalesces repeat taps`() {
        assertEquals(ChronicleSyncStrategy.COORDINATED_COLLECT_THEN_UPLOAD, MANUAL_SYNC_STRATEGY)
        assertEquals(ExistingWorkPolicy.KEEP, IMMEDIATE_UPLOAD_EXISTING_WORK_POLICY)
        assertEquals(NetworkType.CONNECTED, UPLOAD_NETWORK_CONSTRAINT.requiredNetworkType)

        val expansion = AUXILIARY_UPLOADS.single { it.workerClass == ExpansionUploadWorker::class.java }
        assertTrue(expansion.collectBeforeManualUpload)
        assertFalse(
            AUXILIARY_UPLOADS.filterNot { it == expansion }.any { it.collectBeforeManualUpload },
        )

        val syncSource = File(
            "src/main/java/com/openlattice/chronicle/services/sync/ChronicleSyncWorker.kt",
        ).readText()
        assertTrue(syncSource.contains("uniqueWorkName, IMMEDIATE_UPLOAD_EXISTING_WORK_POLICY, requests"))
        assertTrue(syncSource.contains("requests = listOf(request) + buildImmediateAuxiliaryUploadRequests"))
    }

    @Test
    fun `coordinated periodic sync preserves offline collection and battery gate`() {
        val unconstrainedBattery = coordinatedSyncConstraints(requiresBatteryNotLow = false)
        assertEquals(NetworkType.NOT_REQUIRED, unconstrainedBattery.requiredNetworkType)
        assertFalse(unconstrainedBattery.requiresBatteryNotLow())

        val batteryAware = coordinatedSyncConstraints(requiresBatteryNotLow = true)
        assertEquals(NetworkType.NOT_REQUIRED, batteryAware.requiredNetworkType)
        assertTrue(batteryAware.requiresBatteryNotLow())
    }

    @Test
    fun `periodic and manual auxiliary workers share queue leases`() {
        val workers = mapOf(
            "collection/interaction/InteractionUploadWorker.kt" to "INTERACTION_UPLOAD_WORK_NAME",
            "collection/audio/AudioUploadWorker.kt" to "AUDIO_UPLOAD_WORK_NAME",
            "collection/device/ExpansionUploadWorker.kt" to "EXPANSION_UPLOAD_WORK_NAME",
        )
        workers.forEach { (path, owner) ->
            val sourceRoot = if (
                path.startsWith("collection/audio/") ||
                path.startsWith("collection/interaction/")
            ) {
                "src/googleServices/java"
            } else {
                "src/main/java"
            }
            val source = File("$sourceRoot/com/openlattice/chronicle/$path").readText()
            assertTrue(source.contains("UploadQueueSingleFlight.tryAcquire($owner)"))
            assertTrue(source.contains("UploadQueueSingleFlight.release($owner)"))
        }

        val expansionSource = File(
            "src/main/java/com/openlattice/chronicle/collection/device/ExpansionUploadWorker.kt",
        ).readText()
        assertTrue(expansionSource.contains("inputData.getBoolean(INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD, false)"))
        assertTrue(expansionSource.contains("collectExpansionSamples(applicationContext)"))
    }

    @Test
    fun `every independently schedulable uploader joins the withdrawal mutation barrier`() {
        val workers = mapOf(
            "collection/battery/BatteryUploadWorker.kt" to "BATTERY_UPLOAD_WORK_NAME",
            "services/sensors/SensorUploadWorker.kt" to "LEGACY_SENSOR_UPLOAD_WORK_NAME",
        )
        workers.forEach { (path, owner) ->
            val sourceRoot = if (path.startsWith("services/sensors/")) {
                "src/googleServices/java"
            } else {
                "src/main/java"
            }
            val source = File("$sourceRoot/com/openlattice/chronicle/$path").readText()
            assertTrue(source.contains("UploadQueueSingleFlight.tryAcquire($owner)"))
            assertTrue(source.contains("UploadQueueSingleFlight.release($owner)"))
            assertTrue(source.contains("ResearchPersistenceGate.runIfActive(applicationContext)"))
        }
    }
}
