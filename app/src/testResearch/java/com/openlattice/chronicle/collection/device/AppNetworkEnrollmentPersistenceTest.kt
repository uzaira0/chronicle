package com.openlattice.chronicle.collection.device

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.AppNetworkUsageSampleSink
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchPersistenceBarrier
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [AppNetworkEnrollmentPersistenceTest.StatsManager::class, AppNetworkEnrollmentPersistenceTest.EmptyStats::class])
class AppNetworkEnrollmentPersistenceTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    @get:Rule val backgroundPersistence = BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gate = ReentrantReadWriteLock(true)
    private lateinit var db: ChronicleDb
    private lateinit var source: AndroidAppNetworkUsageSource
    private val checkpoint get() = context.getSharedPreferences("chronicle_app_network_usage", Context.MODE_PRIVATE)
    private val study = "11111111-1111-1111-1111-111111111111"
    private lateinit var server: UploadServerEntity

    @Before fun setUp() {
        val prefs = context.getSharedPreferences("app-network-enrollment-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, study).putString(PARTICIPANT_ID, "participant")
            .putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name).commit()
        EnrollmentTestStores.prefs = prefs
        checkpoint.edit().clear().commit()
        StatsManager.windows.clear()
        StatsManager.afterQuery = null
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        EnrollmentTestStores.db = db
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "study", url = "https://localhost", studyId = study, participantId = "participant",
            sourceRandomDeviceId = "device", apiKey = "test-key", createdAt = Instant.ofEpochMilli(System.currentTimeMillis() - 60_000).toString(),
        ))
        server = db.uploadServerDao().getById(id)!!
        source = AndroidAppNetworkUsageSource(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.NETWORK_STATS_SERVICE) ReflectionHelpers.newInstance(NetworkStatsManager::class.java)
                else super.getSystemService(name)
        })
        EnrollmentTestStores.api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
            check(method.name == "withdrawCurrentEnrollment")
            null
        } as ChronicleStudyApi
        ResearchPersistenceGate.initialize(context, ResearchPersistenceBarrier(gate))
    }

    @After fun tearDown() {
        EnrollmentTestStores.api = null
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
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

    @Test @Config(sdk = [26, 36]) fun readWithoutUsageAccessKeepsCheckpointUntilAccessReturns() {
        source.read()
        source.acknowledgeRead()
        val last = checkpoint.getLong("last_end_millis", -1)
        Thread.sleep(2)
        source.read()
        val pending = checkpoint.getLong("pending_end_millis", -1)
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val shadow = Shadows.shadowOf(appOps)
        shadow.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName,
            AppOpsManager.MODE_IGNORED)
        StatsManager.windows.clear()
        Thread.sleep(2)
        val module = AppNetworkUsageCollectionModule(
            AppNetworkUsageSampleSink(db.appNetworkUsageSampleDao()),
            source, { true })
        assertEquals(ModuleResult.Ok(0), module.sample())
        source.acknowledgeRead()
        assertTrue("no summary without Usage Access", StatsManager.windows.isEmpty())
        assertEquals(last, checkpoint.getLong("last_end_millis", -1))
        assertEquals(pending, checkpoint.getLong("pending_end_millis", -1))
        shadow.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName,
            AppOpsManager.MODE_ALLOWED)
        source.read()
        assertTrue(StatsManager.windows.isNotEmpty() && StatsManager.windows.all { it.first == last && it.second == pending })
    }

    @Test @Config(sdk = [26, 36]) fun accessRevokedDuringEitherSummaryRetainsTheExactWindowForRetry() {
        val appOps = Shadows.shadowOf(context.getSystemService(AppOpsManager::class.java))
        for (revokeAfter in 1..2) {
            appOps.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName,
                AppOpsManager.MODE_ALLOWED)
            StatsManager.afterQuery = null
            source.read()
            source.acknowledgeRead()
            val last = checkpoint.getLong("last_end_millis", -1)
            Thread.sleep(2)
            StatsManager.windows.clear()
            StatsManager.afterQuery = {
                if (StatsManager.windows.size == revokeAfter) appOps.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_IGNORED)
            }
            val module = AppNetworkUsageCollectionModule(
                AppNetworkUsageSampleSink(db.appNetworkUsageSampleDao()),
                source, { true })
            assertEquals(ModuleResult.Ok(0), module.sample())
            val pending = checkpoint.getLong("pending_end_millis", -1)
            assertEquals(last, checkpoint.getLong("last_end_millis", -1))
            assertTrue(pending > last)
            assertEquals(0, db.appNetworkUsageSampleDao().count())
            StatsManager.afterQuery = null
            appOps.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName,
                AppOpsManager.MODE_ALLOWED)
            StatsManager.windows.clear()
            source.read()
            assertEquals(listOf(last to pending, last to pending), StatsManager.windows)
            source.acknowledgeRead()
        }
    }

    @Test fun withdrawalCompletesWithBoundaryWriterWaitingForQueue() {
        assertTrue(ParticipantWithdrawalManager.begin(context))
        val inHttp = CountDownLatch(1)
        val acknowledge = CountDownLatch(1)
        EnrollmentTestStores.api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
            check(method.name == "withdrawCurrentEnrollment")
            inHttp.countDown()
            check(acknowledge.await(10, TimeUnit.SECONDS))
            null
        } as ChronicleStudyApi
        val pool = Executors.newFixedThreadPool(2)
        val boundaryEntered = CountDownLatch(1)
        val withdrawal = pool.submit<ListenableWorker.Result> { withdrawalWorker().doWork() }
        var boundary: Future<*>? = null
        try {
            assertTrue(inHttp.await(5, TimeUnit.SECONDS))
            assertTrue("withdrawal HTTP must release the persistence gate", gate.readLock().tryLock())
            gate.readLock().unlock()
            boundary = pool.submit {
                gate.writeLock().lockInterruptibly()
                try {
                    boundaryEntered.countDown()
                    UploadQueueSingleFlight.withExclusiveMutation {}
                } finally { gate.writeLock().unlock() }
            }
            assertTrue("boundary writer must own the gate before the HTTP acknowledgment", boundaryEntered.await(5, TimeUnit.SECONDS))
            acknowledge.countDown()
            assertEquals(ListenableWorker.Result.success(), withdrawal.get(3, TimeUnit.SECONDS))
            boundary.get(3, TimeUnit.SECONDS)
            assertEquals(WithdrawalState.COMPLETE, WithdrawalStateStore(context).state())
        } finally {
            acknowledge.countDown()
            boundary?.cancel(true)
            pool.shutdown()
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) pool.shutdownNow()
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
        source.acknowledgeRead()
        assertFalse("a stale acknowledgment cannot restore an erased pending window", checkpoint.contains("pending_end_millis"))
        assertFalse("a stale acknowledgment cannot restore an erased checkpoint", checkpoint.contains("last_end_millis"))
    }

    @Test fun discardClearsPendingAndCommittedWindowsFromLiveSource() {
        source.read()
        source.acknowledgeRead()
        source.read()
        ResearchPersistenceGate.stop {
            ResearchErasureFence(context).erase(setOf(CollectionModuleId.APP_NETWORK_USAGE),
                ownerKey = ResearchErasureFence.enrollmentKey(server))
            CollectionLoopCoordinator(context).replayPendingErasures()
        }
        assertFalse(checkpoint.contains("pending_end_millis"))
        assertFalse(checkpoint.contains("last_end_millis"))
        source.acknowledgeRead()
        assertFalse("acknowledgment cannot undo module erasure", checkpoint.contains("last_end_millis"))
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


    @Test fun retryKeepsTheExactWindowWithinOneEnrollment() {
        source.read()
        val first = StatsManager.windows.toList()
        source.rejectRead()
        StatsManager.windows.clear()
        source.read()
        assertEquals(first, StatsManager.windows)
    }

    @Test fun backwardClockPollDoesNotReopenAcknowledgedNetworkWindow() {
        source.read()
        source.acknowledgeRead()
        // An acknowledged endpoint ahead of the current clock models a backward correction.
        val sentThrough = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)
        checkpoint.edit().putLong("last_end_millis", sentThrough).commit()
        StatsManager.windows.clear()

        repeat(2) {
            assertTrue(source.read().isEmpty())
            source.acknowledgeRead()
            assertEquals(sentThrough, checkpoint.getLong("last_end_millis", -1))
            assertFalse(checkpoint.contains("pending_end_millis"))
            assertTrue("acknowledged traffic must not be queried again", StatsManager.windows.isEmpty())
        }

        val nextEnd = sentThrough + TimeUnit.MINUTES.toMillis(1)
        checkpoint.edit().putLong("pending_end_millis", nextEnd).commit()
        source.read()
        assertEquals(listOf(sentThrough to nextEnd, sentThrough to nextEnd), StatsManager.windows)
        source.acknowledgeRead()
        assertEquals(nextEnd, checkpoint.getLong("last_end_millis", -1))
    }


    @Implements(NetworkStatsManager::class)
    class StatsManager {
        companion object {
            val windows = mutableListOf<Pair<Long, Long>>()
            var afterQuery: (() -> Unit)? = null
        }
        @Implementation fun querySummary(@Suppress("UNUSED_PARAMETER") type: Int,
            @Suppress("UNUSED_PARAMETER") subscriber: String?, start: Long, end: Long): NetworkStats {
            windows += start to end
            afterQuery?.invoke()
            return ReflectionHelpers.newInstance(NetworkStats::class.java)
        }
    }

    @Implements(NetworkStats::class)
    class EmptyStats {
        @Implementation fun hasNextBucket(): Boolean = false
        @Implementation fun close() = Unit
    }
}
