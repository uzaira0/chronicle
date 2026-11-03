package com.openlattice.chronicle.services.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.device.EXPANSION_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.device.ExpansionUploadWorker
import com.openlattice.chronicle.collection.device.INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmediateSyncFanoutTest {
    @Test
    fun `upload now includes every queue outside the combined worker`() {
        val shipsAuxiliaryCollectors = DistributionChannel.current() == DistributionChannel.RESEARCH
        val expectedWorkerNames = buildList {
            if (shipsAuxiliaryCollectors) {
                add("com.openlattice.chronicle.collection.interaction.InteractionUploadWorker")
                add("com.openlattice.chronicle.collection.audio.AudioUploadWorker")
            }
            add(ExpansionUploadWorker::class.java.name)
        }
        val expectedQueueOwners = buildSet {
            if (shipsAuxiliaryCollectors) {
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
        assertEquals(ExistingWorkPolicy.KEEP, IMMEDIATE_UPLOAD_EXISTING_WORK_POLICY)
        assertEquals(NetworkType.CONNECTED, UPLOAD_NETWORK_CONSTRAINT.requiredNetworkType)

        val expansion = AUXILIARY_UPLOADS.single { it.workerClass == ExpansionUploadWorker::class.java }
        assertTrue(expansion.collectBeforeManualUpload)
        assertFalse(
            AUXILIARY_UPLOADS.filterNot { it == expansion }.any { it.collectBeforeManualUpload },
        )


    }

    /**
     * Exercises the request construction itself rather than the text of its call site: one request
     * per auxiliary queue, each carrying its descriptor's collect-before-upload flag. A descriptor
     * dropped from [AUXILIARY_UPLOADS] — as happened when the restricted fan-out was linked into a
     * distribution that never collects those queues — changes this set.
     */
    @Test
    fun `manual upload builds exactly one request per auxiliary queue`() {
        val requests = buildImmediateAuxiliaryUploadRequests(collectExpansionBeforeUpload = true)

        assertEquals(AUXILIARY_UPLOADS.size, requests.size)
        assertEquals(
            AUXILIARY_UPLOADS.map { it.workerClass.name },
            requests.map { it.workSpec.workerClassName },
        )
        assertEquals(
            AUXILIARY_UPLOADS.map { it.collectBeforeManualUpload },
            requests.map { it.workSpec.input.getBoolean(INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD, false) },
        )
        assertTrue(
            requests.all { it.workSpec.constraints.requiredNetworkType == NetworkType.CONNECTED },
        )
    }

    @Test
    fun `upload retry backoff varies between requests within the bounded window`() {
        val samples = (1..100).map {
            listOf(
                buildPeriodicChronicleSyncRequest().workSpec.backoffDelayDuration,
                buildImmediateChronicleSyncRequest().workSpec.backoffDelayDuration,
            ) + buildImmediateAuxiliaryUploadRequests(collectExpansionBeforeUpload = true)
                .map { it.workSpec.backoffDelayDuration }
        }
        for (queue in samples.first().indices) {
            val delays = samples.map { it[queue] }
            assertTrue(delays.all { it in 30_000L..90_000L })
            assertTrue("Retry base for queue $queue must vary", delays.toSet().size > 1)
        }
    }

    @Test
    fun `a periodic auxiliary drain never pre-collects`() {
        val requests = buildImmediateAuxiliaryUploadRequests(collectExpansionBeforeUpload = false)

        assertTrue(
            requests.none {
                it.workSpec.input.getBoolean(INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD, false)
            },
        )
    }
}
