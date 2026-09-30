package com.openlattice.chronicle.services.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.collection.usage.UsageModulePersistence
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UsagePollCheckpointEntity
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

@RunWith(RobolectricTestRunner::class)
class PersistenceLeaseRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var store: LocalUploadDiagnosticsStore
    private lateinit var server: UploadServerEntity
    private val at = OffsetDateTime.parse("2026-09-29T00:00:00Z")

    @Before fun setUp() {
        com.openlattice.chronicle.collection.state.StorageAdmission::class.java.getDeclaredField("lastCheckedAt")
            .apply { isAccessible = true }.setLong(com.openlattice.chronicle.collection.state.StorageAdmission, Long.MIN_VALUE)
        val prefs = context.getSharedPreferences("persistence-lease-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant")
            .putString(com.openlattice.chronicle.preferences.PARTICIPATION_STATUS, "ENROLLED").commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "study", url = "https://localhost", studyId = prefs.getString(STUDY_ID, "")!!,
            participantId = "participant", sourceDeviceId = "device", createdAt = at.toString(),
        ))
        server = db.uploadServerDao().getById(id)!!
        store = LocalUploadDiagnosticsStore.of(context)
    }

    @After fun tearDown() {
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, null)
        db.close()
    }

    @Test fun usageSingleConnectionAdmissionCompletesWithQueuedWriter() {
        val barrier = ResearchPersistenceGate::class.java.getDeclaredField("barrier")
            .apply { isAccessible = true }.get(ResearchPersistenceGate)
        val lock = barrier.javaClass.getDeclaredField("lock").apply { isAccessible = true }
            .get(barrier) as ReentrantReadWriteLock
        val workers = Executors.newFixedThreadPool(2)
        val transactionEntered = CountDownLatch(1)
        val insertNow = CountDownLatch(1)
        val writerThread = AtomicReference<Thread>()
        val guard = ResearchPersistenceGate.guard(context)
        val usage = workers.submit {
            UsageModulePersistence.persist(listOf(QueueEntry(2, 2, byteArrayOf(2))), 200,
                UsageEventSink(db.queueEntryData(), NoOpCollectionLog, guard),
                { db.usagePollCheckpointDao().upsert(UsagePollCheckpointEntity("single-connection", it)) },
                { block ->
                    db.runInTransaction { transactionEntered.countDown(); check(insertNow.await(5, TimeUnit.SECONDS)); block() }
                }, NoOpCollectionLog)
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
        val barrier = ResearchPersistenceGate::class.java.getDeclaredField("barrier")
            .apply { isAccessible = true }.get(ResearchPersistenceGate)
        val lock = barrier.javaClass.getDeclaredField("lock").apply { isAccessible = true }
            .get(barrier) as ReentrantReadWriteLock
        val atWrite = CountDownLatch(1)
        val writeNow = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val writerThread = AtomicReference<Thread>()
        var checkpoint = 0L
        val guard = CollectionPersistenceGuard { persist ->
            lock.readLock().lockInterruptibly()
            try { persist(); true } finally { lock.readLock().unlock() }
        }
        val sink = UsageEventSink(db.queueEntryData(), NoOpCollectionLog, guard)
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
                } }, NoOpCollectionLog)
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

    private fun withQueuedWriter(record: () -> Unit) {
        val barrier = ResearchPersistenceGate::class.java.getDeclaredField("barrier")
            .apply { isAccessible = true }.get(ResearchPersistenceGate)
        val lock = barrier.javaClass.getDeclaredField("lock").apply { isAccessible = true }
            .get(barrier) as ReentrantReadWriteLock
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
                    server.sourceDeviceId, "${server.id}:${server.createdAt}").size)
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
            server.sourceDeviceId, "${server.id}:${server.createdAt}").isEmpty())
    }
}
