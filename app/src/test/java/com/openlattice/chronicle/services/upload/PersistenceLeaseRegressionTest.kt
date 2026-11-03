package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.state.onPersistenceWorker
import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.FakeStorageQueue
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.ResearchPersistenceBarrier
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.usage.UsageModulePersistence
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.UPLOAD_EVIDENCE_CALLBACK
import com.openlattice.chronicle.storage.UsagePollCheckpointEntity
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.services.usage.buildUsageQueueEntries
import com.openlattice.chronicle.serialization.JsonSerializer
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersistenceLeaseRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lock = ReentrantReadWriteLock(true)
    private lateinit var db: ChronicleDb
    private lateinit var store: LocalUploadDiagnosticsStore
    private lateinit var server: UploadServerEntity
    private val at = OffsetDateTime.parse("2026-09-29T00:00:00Z")

    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val prefs = context.getSharedPreferences("persistence-lease-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant")
            .putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EnrollmentTestStores.prefs = prefs
        StorageAdmission.clear(context)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        EnrollmentTestStores.db = db
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "study", url = "https://localhost", apiKey = "test-device-key", studyId = prefs.getString(STUDY_ID, "")!!,
            participantId = "participant", sourceRandomDeviceId = "device", createdAt = at.toString(),
        ))
        server = db.uploadServerDao().getById(id)!!
        store = LocalUploadDiagnosticsStore.of(context)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context, ResearchPersistenceBarrier(lock)) }
    }

    @After fun tearDown() {
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    @Test fun liftingARequiredDeclinePreservesCaptureScopesAndQueues() = onPersistenceWorker {
        val states = db.collectionModuleStateDao()
        states.upsertAll(listOf(
            CollectionModuleStateEntity(CollectionModuleId.USAGE_EVENTS.id, true, ParticipantDecision.ACCEPTED.name, 1, true, 1),
            CollectionModuleStateEntity(CollectionModuleId.BATTERY_TELEMETRY.id, true, ParticipantDecision.DECLINED.name, 1, true, 1)))
        val haltedAt = System.currentTimeMillis()
        ResearchPersistenceGate.initialize(context)
        Thread.sleep(1)
        val during = System.currentTimeMillis()
        db.queueEntryData().insertEntry(QueueEntry(1, 1, byteArrayOf(1)))
        val reminderScope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)
        // Capture identity (enrollment generation and floor); the halt itself becomes a suspended interval.
        fun captureScopes() = CollectionModuleId.values().associateWith {
            ResearchPersistenceGate.observationScope(context, it)?.let { scope -> scope.key to scope.floorMillis }
        }
        val scopes = captureScopes()
        Thread.sleep(1)
        ResearchPersistenceGate.stop {
            states.upsert(states.get(CollectionModuleId.BATTERY_TELEMETRY.id)!!.copy(decision = ParticipantDecision.ACCEPTED.name))
        }
        val liftedAt = System.currentTimeMillis()
        assertEquals(scopes, captureScopes())
        val usage = ResearchPersistenceGate.observationScope(context, CollectionModuleId.USAGE_EVENTS)!!
        assertTrue("captured during the halt", usage.suspends(during))
        Assert.assertFalse("captured before the halt", usage.suspends(haltedAt - 1))
        Assert.assertFalse("captured after the lift", usage.suspends(liftedAt + 1))
        assertEquals(1, db.queueEntryData().getSize())
        assertEquals(reminderScope?.key, ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)?.key)
    }

    @Test fun dataDrainsRejectNotEnrolledAndRequiredDeclineButKeepStoppedBacklog() = onPersistenceWorker {
        val prefs = requireNotNull(EnrollmentTestStores.prefs)
        for (status in ParticipationStatus.values()) {
            prefs.edit().putString(PARTICIPATION_STATUS, status.name).commit()
            val expected = status in setOf(ParticipationStatus.ENROLLED,
                ParticipationStatus.PAUSED,
                ParticipationStatus.COLLECTION_COMPLETED)
            assertEquals(status.name, expected, ResearchPersistenceGate.runIfEnrolled(context) { true } == true)
        }
        prefs.edit().putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name).commit()
        db.collectionModuleStateDao().upsert(CollectionModuleStateEntity(
            CollectionModuleId.BATTERY_TELEMETRY.id, true, ParticipantDecision.DECLINED.name, 1, true, 1))
        Assert.assertNull(ResearchPersistenceGate.runIfEnrolled(context) { true })
    }

    @Test fun persistenceGateCountsDirectWriteFailuresAndPropagatesSinkFailures() =
        onPersistenceWorker {
            val directFailure = IOException("direct write failed")
            Assert.assertFalse(ResearchPersistenceGate.persistIfActive(context, records = 7, expectedOwner = server) {
                throw directFailure
            })
            fun lost() = db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId,
                server.sourceRandomDeviceId, "${server.id}:${server.createdAt}")
                .filter { it.issueCode == LocalOperationalIssue.LOCAL_WRITE_FAILED.name }.sumOf { it.count }
            assertEquals(7, lost())
            val queue = FakeStorageQueue().apply { failNextInsert = true }
            val result = UsageEventSink(queue, ResearchPersistenceGate.guard(context))
                .write(listOf(QueueEntry(1, 1, byteArrayOf(1))))
            assertTrue(result is ModuleResult.Failed)
            assertEquals("simulated dataQueue write failure", (result as ModuleResult.Failed).error.message)
            assertEquals("a retryable sink failure is not a direct-write loss", 7, lost())
        }

    @Test fun usageSingleConnectionAdmissionCompletesWithQueuedWriter() {
        val workers = Executors.newFixedThreadPool(2)
        val transactionEntered = CountDownLatch(1)
        val insertNow = CountDownLatch(1)
        val writerThread = AtomicReference<Thread>()
        val guard = ResearchPersistenceGate.guard(context)
        val usage = workers.submit {
            UsageModulePersistence.persist(listOf(QueueEntry(2, 2, byteArrayOf(2))), 200,
                UsageEventSink(db.queueEntryData(), guard),
                { db.usagePollCheckpointDao().upsert(UsagePollCheckpointEntity("single-connection", it)) },
                { block ->
                    db.runInTransaction { transactionEntered.countDown(); check(insertNow.await(5, TimeUnit.SECONDS)); block() }
                })
        }
        assertTrue(transactionEntered.await(5, TimeUnit.SECONDS))
        val writer = workers.submit {
            writerThread.set(Thread.currentThread())
            lock.writeLock().lockInterruptibly()
            lock.writeLock().unlock()
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writerThread.get()?.let(lock::hasQueuedThread) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(writerThread.get()?.let(lock::hasQueuedThread) == true)
            insertNow.countDown()
            usage.get(3, TimeUnit.SECONDS)
            writer.get(3, TimeUnit.SECONDS)
            assertEquals(200L, db.usagePollCheckpointDao().getLastPollTimestamp("single-connection"))
            assertEquals(1, db.queueEntryData().getSize())
        } finally {
            insertNow.countDown()
            usage.cancel(true)
            writer.cancel(true)
            workers.shutdown()
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow()
        }
    }

    @Test fun recordCompletesAheadOfQueuedStop() = withQueuedWriter {
        store.record(LocalUploadModuleFamily.SENSOR, UploadDestinationIssue.DESTINATION_DISABLED)
    }

    @Test fun recordOnceCompletesAheadOfQueuedStop() = withQueuedWriter {
        store.recordOperationalOnce("once", LocalUploadModuleFamily.SENSOR,
            LocalOperationalIssue.LOCAL_WRITE_FAILED, at)
    }

    @Test fun recordOperationalCompletesAheadOfQueuedStop() = withQueuedWriter {
        store.recordOperational(LocalUploadModuleFamily.SENSOR, LocalOperationalIssue.LOCAL_WRITE_FAILED)
    }

    @Test fun usageLeasesBeforeRoomTransactionAndHoldsLeaseThroughCheckpoint() {
        val atWrite = CountDownLatch(1)
        val writeNow = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val writerThread = AtomicReference<Thread>()
        var checkpoint = 0L
        val guard = CollectionPersistenceGuard { persist ->
            lock.readLock().lockInterruptibly()
            try { persist(); true } finally { lock.readLock().unlock() }
        }
        val sink = UsageEventSink(db.queueEntryData(), guard)
        val executor = Executors.newFixedThreadPool(2)
        val usage = executor.submit {
            UsageModulePersistence.persist(listOf(QueueEntry(1, 1, byteArrayOf(1))), 100, sink,
                {
                    db.usagePollCheckpointDao().upsert(UsagePollCheckpointEntity("usage-lease", it))
                    checkpoint = it
                }, { block -> db.runInTransaction {
                    atWrite.countDown()
                    check(writeNow.await(5, TimeUnit.SECONDS))
                    block()
                } })
        }
        assertTrue(atWrite.await(5, TimeUnit.SECONDS))
        val writer = executor.submit {
            writerThread.set(Thread.currentThread())
            writerStarted.countDown()
            lock.writeLock().lockInterruptibly()
            try {
                db.runInTransaction { db.collectionModuleStateDao().getAll() }
                assertEquals(100L, checkpoint)
                assertEquals(100L, db.usagePollCheckpointDao().getLastPollTimestamp("usage-lease"))
            } finally { lock.writeLock().unlock() }
        }
        try {
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writerThread.get()?.let(lock::hasQueuedThread) != true && !lock.isWriteLocked &&
                System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue("writer must have reached the gate", lock.isWriteLocked ||
                writerThread.get()?.let(lock::hasQueuedThread) == true)
            writeNow.countDown()
            usage.get(3, TimeUnit.SECONDS)
            writer.get(3, TimeUnit.SECONDS)
            assertEquals(1, db.queueEntryData().getSize())
        } finally {
            writeNow.countDown()
            usage.cancel(true)
            writer.cancel(true)
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        }
    }

    /**
     * The checkpoint write fails after every queue chunk of the window was inserted: the one transaction must undo the
     * chunks and their capture evidence and keep the old checkpoint, so the retry re-polls and stores the whole window.
     */
    @Test fun checkpointFailureRollsBackEveryQueuedChunkAndEvidence() {
        val evidenced = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .addCallback(UPLOAD_EVIDENCE_CALLBACK).allowMainThreadQueries().build()
        try {
            val checkpoints = DaoUsagePollCheckpointStore(evidenced.usagePollCheckpointDao(), "scope")
            checkpoints.commitPollTimestamp(100)
            val events = (0 until 2_500).map { i -> ExtractedUsageEvent(eventType = fromInteractionType("Activity Resumed"),
                appPackageName = "example.app$i", interactionType = "Activity Resumed", timestamp = at.plusSeconds(i.toLong()),
                timezone = "UTC", user = "participant", applicationLabel = "Example", activityClass = "MainActivity") }
            var id = 0L
            val chunks = buildUsageQueueEntries(events, 1) { ++id }
            assertTrue("the window spans several queue chunks", chunks.size >= 3)
            val sink = UsageEventSink(evidenced.queueEntryData(), CollectionPersistenceGuard { persist -> persist(); true })
            fun persist() = UsageModulePersistence.persist(chunks, 200, sink, checkpoints::commitPollTimestamp) { body ->
                evidenced.runInTransaction(body)
            }
            fun count(table: String) = evidenced.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`")
                .use { it.moveToFirst(); it.getInt(0) }
            evidenced.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_checkpoint BEFORE INSERT ON usage_poll_checkpoints " +
                "BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END")
            assertTrue(runCatching { persist() }.isFailure)
            assertEquals("the checkpoint stays at the window start", 100L, checkpoints.readPreviousPollTimestamp())
            assertEquals("no chunk survives", 0, count("dataQueue"))
            assertEquals("no evidence survives", 0, count("upload_queue_evidence"))

            evidenced.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_checkpoint")
            assertTrue(persist())
            assertEquals(200L, checkpoints.readPreviousPollTimestamp())
            assertEquals(chunks.size, count("dataQueue"))
            assertEquals(chunks.size, count("upload_queue_evidence"))
            val stored = evidenced.queueEntryData().getNextEntries(chunks.size).flatMap {
                JsonSerializer.deserializeQueueEntry(it.data)
            }
            assertEquals("the retry stores the whole window", events.map { it.appPackageName },
                stored.map { (it as ExtractedUsageEvent).appPackageName })
        } finally { evidenced.close() }
    }

    private fun withQueuedWriter(record: () -> Unit) {
        assertTrue(lock.isFair)
        val executor = Executors.newFixedThreadPool(2)
        val readerLeased = CountDownLatch(1)
        val recordNow = CountDownLatch(1)
        val writerThread = AtomicReference<Thread>()
        val reader = executor.submit {
            ResearchPersistenceGate.withReadLease {
                readerLeased.countDown()
                check(recordNow.await(5, TimeUnit.SECONDS))
                record()
            }
        }
        assertTrue(readerLeased.await(5, TimeUnit.SECONDS))
        // Interruptible acquisition lets the test unwind the pre-fix deadlock after timeout.
        val writer = executor.submit {
            writerThread.set(Thread.currentThread())
            lock.writeLock().lockInterruptibly()
            try {
                assertEquals(1, db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId,
                    server.sourceRandomDeviceId, "${server.id}:${server.createdAt}").size)
            } finally {
                lock.writeLock().unlock()
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writerThread.get()?.let(lock::hasQueuedThread) != true && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertTrue("writer must be queued behind the caller's read lease",
                writerThread.get()?.let(lock::hasQueuedThread) == true)
            recordNow.countDown()
            reader.get(3, TimeUnit.SECONDS)
            writer.get(3, TimeUnit.SECONDS)
        } finally {
            recordNow.countDown()
            writer.cancel(true)
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        }
    }

    @Test fun withdrawalAndReplacementStillRefuseDiagnostics() {
        WithdrawalStateStore(context).setState(WithdrawalState.PENDING)
        store.recordOperational(LocalUploadModuleFamily.SENSOR, LocalOperationalIssue.LOCAL_WRITE_FAILED)
        WithdrawalStateStore(context).setState(WithdrawalState.NONE)
        db.uploadServerDao().update(server.copy(createdAt = "2026-09-30T00:00:00Z"))
        store.recordOperationalOnce("old-owner", LocalUploadModuleFamily.SENSOR,
            LocalOperationalIssue.LOCAL_WRITE_FAILED, at)
        assertTrue(db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId,
            server.sourceRandomDeviceId, "${server.id}:${server.createdAt}").isEmpty())
    }
}
