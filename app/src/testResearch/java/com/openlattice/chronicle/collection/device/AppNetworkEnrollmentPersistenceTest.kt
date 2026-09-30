package com.openlattice.chronicle.collection.device

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.WorkerFactory
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.ForegroundUpdater
import androidx.work.Data
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.utils.Utils
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [AppNetworkEnrollmentPersistenceTest.StatsManager::class, AppNetworkEnrollmentPersistenceTest.EmptyStats::class])
class AppNetworkEnrollmentPersistenceTest {
    @get:org.junit.Rule val backgroundPersistence = com.openlattice.chronicle.collection.state.BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var source: AndroidAppNetworkUsageSource
    private val checkpoint get() = context.getSharedPreferences("chronicle_app_network_usage", Context.MODE_PRIVATE)
    private val study = "11111111-1111-1111-1111-111111111111"
    private lateinit var server: UploadServerEntity
    private lateinit var apiCache: MutableMap<String, ChronicleStudyApi>
    private lateinit var apiKey: String
    private var previousApi: ChronicleStudyApi? = null

    @Before fun setUp() {
        val prefs = context.getSharedPreferences("app-network-enrollment-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, study).putString(PARTICIPANT_ID, "participant")
            .putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name).commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, prefs)
        checkpoint.edit().clear().commit()
        StatsManager.windows.clear()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "study", url = "https://localhost", studyId = study, participantId = "participant",
            sourceDeviceId = "device", apiKey = "test-key", createdAt = Instant.ofEpochMilli(System.currentTimeMillis() - 60_000).toString(),
        ))
        server = db.uploadServerDao().getById(id)!!
        source = AndroidAppNetworkUsageSource(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.NETWORK_STATS_SERVICE) ReflectionHelpers.newInstance(NetworkStatsManager::class.java)
                else super.getSystemService(name)
        })
        if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().setExecutor(Executor { }).build())
        @Suppress("UNCHECKED_CAST")
        val cache = UploadWorker::class.java.getDeclaredField("studyApiCache").apply { isAccessible = true }
            .get(null) as MutableMap<String, ChronicleStudyApi>
        apiCache = cache
        apiKey = server.url + "|" + Utils.mobileSigningSecretFingerprint(null)
        previousApi = apiCache[apiKey]
        apiCache[apiKey] = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
            check(method.name == "withdrawCurrentEnrollment")
            null
        } as ChronicleStudyApi
    }

    @After fun tearDown() {
        previousApi?.let { apiCache[apiKey] = it } ?: apiCache.remove(apiKey)
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    @Test fun firstReadAfterDiscardEstablishesBaselineWithoutQueryingDeclinedInterval() {
        source.read()
        source.acknowledgeRead()
        AndroidAppNetworkUsageSource.clearCheckpoint(context)
        checkpoint.edit().putLong("reset_at_millis", System.currentTimeMillis() - 30_000).commit()
        StatsManager.windows.clear()
        val before = System.currentTimeMillis()
        assertTrue(source.read().isEmpty())
        assertTrue("declined interval must never be queried", StatsManager.windows.isEmpty())
        val baseline = checkpoint.getLong("last_end_millis", -1)
        assertTrue(baseline >= before)
        assertFalse(checkpoint.contains("pending_end_millis"))
        Thread.sleep(2)
        source.read()
        assertEquals(2, StatsManager.windows.size)
        assertTrue(StatsManager.windows.all { it.first == baseline })
    }

    @Test fun withdrawalCompletesWithBoundaryWriterWaitingForQueue() {
        assertTrue(ParticipantWithdrawalManager.begin(context))
        val inHttp = java.util.concurrent.CountDownLatch(1)
        val acknowledge = java.util.concurrent.CountDownLatch(1)
        apiCache[apiKey] = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
            check(method.name == "withdrawCurrentEnrollment")
            inHttp.countDown()
            check(acknowledge.await(10, java.util.concurrent.TimeUnit.SECONDS))
            null
        } as ChronicleStudyApi
        val barrier = ResearchPersistenceGate::class.java.getDeclaredField("barrier")
            .apply { isAccessible = true }.get(ResearchPersistenceGate)
        val gate = barrier.javaClass.getDeclaredField("lock").apply { isAccessible = true }
            .get(barrier) as java.util.concurrent.locks.ReentrantReadWriteLock
        val queue = com.openlattice.chronicle.services.upload.UploadQueueSingleFlight::class.java
            .getDeclaredField("mutationLock").apply { isAccessible = true }
            .get(com.openlattice.chronicle.services.upload.UploadQueueSingleFlight) as java.util.concurrent.locks.ReentrantReadWriteLock
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val boundaryThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val withdrawal = pool.submit<ListenableWorker.Result> { withdrawalWorker().doWork() }
        var boundary: java.util.concurrent.Future<*>? = null
        try {
            assertTrue(inHttp.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("withdrawal HTTP must release the persistence gate", gate.readLock().tryLock())
            gate.readLock().unlock()
            boundary = pool.submit {
                gate.writeLock().lockInterruptibly()
                try {
                    boundaryThread.set(Thread.currentThread())
                    queue.writeLock().lockInterruptibly()
                    queue.writeLock().unlock()
                } finally { gate.writeLock().unlock() }
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (boundaryThread.get()?.let(queue::hasQueuedThread) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue("boundary writer must be waiting for withdrawal's queue", boundaryThread.get()?.let(queue::hasQueuedThread) == true)
            acknowledge.countDown()
            assertEquals(ListenableWorker.Result.success(), withdrawal.get(3, java.util.concurrent.TimeUnit.SECONDS))
            boundary.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(WithdrawalState.COMPLETE, WithdrawalStateStore(context).state())
        } finally {
            acknowledge.countDown()
            boundary?.cancel(true)
            pool.shutdown()
            if (!pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) pool.shutdownNow()
        }
    }

    private fun withdrawalWorker(): ParticipantWithdrawalWorker {
        val executor = Executor { }
        val progress = Proxy.newProxyInstance(ProgressUpdater::class.java.classLoader, arrayOf(ProgressUpdater::class.java)) { _, _, _ ->
            error("no progress update expected")
        } as ProgressUpdater
        val foreground = Proxy.newProxyInstance(ForegroundUpdater::class.java.classLoader, arrayOf(ForegroundUpdater::class.java)) { _, _, _ ->
            error("no foreground update expected")
        } as ForegroundUpdater
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        return ParticipantWithdrawalWorker(context, WorkerParameters(UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(),
            0, 0, executor, EmptyCoroutineContext, WorkManagerTaskExecutor(executor), factory, progress, foreground))
    }

    @Test fun firstReadNeverBackfillsBeforeEnrollmentStart() {
        source.read()
        assertEquals(2, StatsManager.windows.size)
        val start = Instant.parse(server.createdAt).toEpochMilli()
        assertTrue(StatsManager.windows.all { it.first >= start })
    }

    @Test fun acknowledgedWithdrawalClearsPendingWindowAndLiveSource() {
        source.read()
        source.rejectRead()
        val pending = checkpoint.getLong("pending_end_millis", -1)
        assertTrue(pending > 0)
        assertTrue(ParticipantWithdrawalManager.begin(context))
        assertEquals("pending data remains until server acknowledgment", pending, checkpoint.getLong("pending_end_millis", -1))
        val executor = Executor { }
        val progress = Proxy.newProxyInstance(ProgressUpdater::class.java.classLoader, arrayOf(ProgressUpdater::class.java)) { _, _, _ ->
            error("no progress update expected")
        } as ProgressUpdater
        val foreground = Proxy.newProxyInstance(ForegroundUpdater::class.java.classLoader, arrayOf(ForegroundUpdater::class.java)) { _, _, _ ->
            error("no foreground update expected")
        } as ForegroundUpdater
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        val parameters = WorkerParameters(UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(),
            0, 0, executor, EmptyCoroutineContext, WorkManagerTaskExecutor(executor), factory,
            progress, foreground)
        ParticipantWithdrawalWorker(context, parameters).doWork()
        assertEquals(WithdrawalState.COMPLETE, WithdrawalStateStore(context).state())
        assertFalse(checkpoint.contains("pending_end_millis"))
        assertFalse(checkpoint.contains("last_end_millis"))
        assertNull(AndroidAppNetworkUsageSource::class.java.getDeclaredField("pendingEndMillis").apply { isAccessible = true }.get(source))
        source.acknowledgeRead()
        assertFalse("a stale acknowledgment cannot restore an erased checkpoint", checkpoint.contains("last_end_millis"))
    }

    @Test fun discardClearsPendingAndCommittedWindowsFromLiveSource() {
        source.read()
        source.acknowledgeRead()
        source.read()
        ResearchPersistenceGate.stop {
            CollectionLoopCoordinator::class.java.getDeclaredMethod("applyDisposition",
                CollectionModuleId::class.java, CollectionDataDisposition::class.java).apply { isAccessible = true }
                .invoke(CollectionLoopCoordinator(context), CollectionModuleId.APP_NETWORK_USAGE, CollectionDataDisposition.DISCARD_AND_STOP)
        }
        assertFalse(checkpoint.contains("pending_end_millis"))
        assertFalse(checkpoint.contains("last_end_millis"))
        assertNull(AndroidAppNetworkUsageSource::class.java.getDeclaredField("pendingEndMillis").apply { isAccessible = true }.get(source))
    }

    @Test fun replacementEnrollmentCannotReadPredecessorsPendingWindow() {
        source.read()
        source.rejectRead()
        val end = checkpoint.getLong("pending_end_millis", -1)
        db.uploadServerDao().update(server.copy(createdAt = Instant.ofEpochMilli(end + 1).toString()))
        StatsManager.windows.clear()
        source.read()
        assertTrue("no query may use the old enrollment window", StatsManager.windows.all { it.first >= end + 1 })
        assertTrue(checkpoint.getLong("pending_end_millis", -1) >= end + 1)
    }

    @Test fun upgradeKeepsUntaggedCheckpointSoSentWindowsAreNotReadAgain() {
        // Build 63 wrote last_end_millis without an enrollment tag.
        val sentUpTo = System.currentTimeMillis() - 10_000
        checkpoint.edit().putLong("last_end_millis", sentUpTo).commit()
        source.read()
        assertEquals(2, StatsManager.windows.size)
        assertTrue(StatsManager.windows.all { it.first == sentUpTo })
    }

    @Test fun retryKeepsTheExactWindowWithinOneEnrollment() {
        source.read()
        val first = StatsManager.windows.toList()
        source.rejectRead()
        StatsManager.windows.clear()
        source.read()
        assertEquals(first, StatsManager.windows)
    }

    @Test fun upgradeAdoptsLegacyTaggedScopeAndPreservesBothWindowEndpoints() {
        val sent = System.currentTimeMillis() - 20_000
        val pending = sent + 10_000
        val legacy = "${server.id}:${server.createdAt}:${server.studyId}:${server.participantId}:${server.sourceDeviceId}"
        checkpoint.edit().putString("enrollment_epoch", legacy).putLong("last_end_millis", sent)
            .putLong("pending_end_millis", pending).commit()
        val current = com.openlattice.chronicle.collection.state.ResearchErasureFence.enrollmentKey(server) + ":1"
        val scopedSource = AndroidAppNetworkUsageSource(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.NETWORK_STATS_SERVICE) ReflectionHelpers.newInstance(NetworkStatsManager::class.java)
                else super.getSystemService(name)
        }, consentEpoch = { current to 0L })
        scopedSource.read()
        assertEquals(listOf(sent to pending, sent to pending), StatsManager.windows)
        assertEquals(current, checkpoint.getString("enrollment_epoch", null))
        assertEquals(sent, checkpoint.getLong("last_end_millis", -1))
        assertEquals(pending, checkpoint.getLong("pending_end_millis", -1))
    }

    @Implements(NetworkStatsManager::class)
    class StatsManager {
        companion object { val windows = mutableListOf<Pair<Long, Long>>() }
        @Implementation fun querySummary(@Suppress("UNUSED_PARAMETER") type: Int,
            @Suppress("UNUSED_PARAMETER") subscriber: String?, start: Long, end: Long): NetworkStats {
            windows += start to end
            return ReflectionHelpers.newInstance(NetworkStats::class.java)
        }
    }

    @Implements(NetworkStats::class)
    class EmptyStats {
        @Implementation fun hasNextBucket(): Boolean = false
        @Implementation fun close() = Unit
    }
}
