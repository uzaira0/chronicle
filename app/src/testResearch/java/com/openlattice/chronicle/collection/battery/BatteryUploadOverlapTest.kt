package com.openlattice.chronicle.collection.battery

import androidx.work.ListenableWorker
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.state.ResearchErasureTestFixture
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.services.upload.runCombinedUpload
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.storage.BatterySampleEntry
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The combined upload (periodic or "Upload now") and the dedicated battery worker drain one queue. While the combined
 * run holds the battery drain, the dedicated worker sends nothing; each sample is sent and counted once; a transport
 * failure keeps the rows and releases ownership, so the next worker drains them.
 */
class BatteryUploadOverlapTest : ResearchErasureTestFixture() {
    private val posted = Collections.synchronizedList(mutableListOf<List<String>>())
    private val pending = ThreadLocal<List<String>>()

    private fun queueBattery(count: Int) = List(count) { UUID.randomUUID().toString() }.also { ids ->
        db.batterySampleDao().insertAll(ids.map { BatterySampleEntry(it, utc(System.currentTimeMillis()).toString(), "UTC",
            64, "DISCHARGING", "UNPLUGGED", 298, 4012, "GOOD") })
    }

    /** Serves status polls and uploads; the first battery POST waits at [selected] until [release]. */
    private fun server(selected: CountDownLatch, release: CountDownLatch, failBattery: Boolean,
                       polls: CountDownLatch = CountDownLatch(0)) = withStudyApi(onCall = { args ->
        if (args.size > 3 && args[3] != null) runCatching { decodedUploadBody<List<BatterySample>>(args[3]!!).map { it.id } }
            .getOrNull()?.let(pending::set)
    }) { method ->
        when (method) {
            "getDeviceParticipationStatus" -> { polls.countDown(); polled(ParticipationStatus.ENROLLED) }
            "uploadAndroidBatteryData" -> {
                val ids = checkNotNull(pending.get())
                posted += ids
                selected.countDown()
                check(release.await(30, TimeUnit.SECONDS))
                if (failBattery) throw IOException("connection reset")
                UploadReceipt(ids.mapTo(linkedSetOf(), UUID::fromString), emptySet(), 1)
            }
            else -> error("unexpected $method")
        }
    }

    private fun overlap(failBattery: Boolean): ListenableWorker.Result {
        val selected = CountDownLatch(1)
        val release = CountDownLatch(1)
        val polls = CountDownLatch(2)
        val threads = Executors.newFixedThreadPool(2)
        try {
            server(selected, release, failBattery, polls).use {
                val combined = threads.submit<ListenableWorker.Result> {
                    onPersistenceWorker { runCombinedUpload(context, 0) { false } }
                }
                assertTrue("the combined run selected the battery rows", selected.await(30, TimeUnit.SECONDS))
                val stolen = UploadQueueSingleFlight.tryAcquire(BATTERY_UPLOAD_WORK_NAME)
                if (stolen) UploadQueueSingleFlight.release(BATTERY_UPLOAD_WORK_NAME)
                assertFalse("the combined worker must own the battery drain", stolen)
                val dedicated = threads.submit<ListenableWorker.Result> {
                    onPersistenceWorker { BatteryUploadWorker(context, auditWorkerParameters()).doWork() }
                }
                assertTrue("both workers reached their status poll", polls.await(30, TimeUnit.SECONDS))
                assertEquals("no second request while the first is in flight", 1, posted.size)
                release.countDown()
                val result = combined.get(30, TimeUnit.SECONDS)
                dedicated.get(30, TimeUnit.SECONDS)
                return result
            }
        } finally { release.countDown(); threads.shutdownNow() }
    }

    private fun successCount() = db.uploadServerDao().getById(owner.id)!!.batteryUploadSuccessCount

    @Test fun theDedicatedWorkerDefersWhileTheBatteryDrainIsOwned() {
        val ids = queueBattery(2)
        check(UploadQueueSingleFlight.tryAcquire(BATTERY_UPLOAD_WORK_NAME))
        try {
            server(CountDownLatch(0), CountDownLatch(0), failBattery = false).use {
                assertEquals(ListenableWorker.Result.retry(),
                    onPersistenceWorker { BatteryUploadWorker(context, auditWorkerParameters()).doWork() })
            }
            assertTrue("no rows selected for upload", posted.isEmpty())
            assertEquals(ids.toSet(), db.batterySampleDao().getOldest(100).map { it.id }.toSet())
            assertEquals(0, successCount())
        } finally { UploadQueueSingleFlight.release(BATTERY_UPLOAD_WORK_NAME) }
    }

    @Test fun combinedAndDedicatedWorkersShareOneBatteryDrain() {
        val ids = queueBattery(3)
        overlap(failBattery = false)
        assertEquals("each sample is sent once", ids.sorted(), posted.flatten().filter { it in ids }.sorted())
        assertTrue("every sent row left the queue", db.batterySampleDao().getOldest(100).none { it.id in ids })
        assertEquals("each sample is counted once", ids.size, successCount())
    }

    @Test fun aTransportFailureKeepsTheRowsAndReleasesTheDrainForTheNextWorker() {
        val ids = queueBattery(2)
        overlap(failBattery = true)
        assertEquals(ids.toSet(), db.batterySampleDao().getOldest(100).map { it.id }.filter { it in ids }.toSet())
        assertEquals(0, successCount())

        posted.clear()
        val release = CountDownLatch(0)
        server(CountDownLatch(1), release, failBattery = false).use {
            assertEquals(ListenableWorker.Result.success(),
                onPersistenceWorker { BatteryUploadWorker(context, auditWorkerParameters()).doWork() })
        }
        assertEquals("the retry sends the same rows once", listOf(ids.toSet()), posted.map { it.toSet() })
        assertTrue(db.batterySampleDao().getOldest(100).none { it.id in ids })
        assertEquals(ids.size, successCount())
    }
}
