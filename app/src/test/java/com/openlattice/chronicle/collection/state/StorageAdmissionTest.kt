package com.openlattice.chronicle.collection.state

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.storedInstant
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class StorageAdmissionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lock = ReentrantReadWriteLock(true)
    private lateinit var db: ChronicleDb
    private lateinit var prefs: SharedPreferences
    private lateinit var server: UploadServerEntity

    @Before fun setUp() {
        prefs = context.getSharedPreferences("storage-admission-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant").commit()
        EnrollmentTestStores.prefs = prefs
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        EnrollmentTestStores.db = db
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "test", url = "https://localhost", studyId = prefs.getString(STUDY_ID, "")!!,
            participantId = "participant", sourceRandomDeviceId = "device", createdAt = "2026-09-01T00:00:00Z",
        ))
        server = db.uploadServerDao().getById(id)!!
        StorageAdmission.clear(context)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context, ResearchPersistenceBarrier(lock)) }
    }

    @After fun tearDown() {
        EnrollmentTestStores.prefs = prefs
        StorageAdmission.clear(context)
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    @Test fun pauseCommitFailureRefusesWithoutThrowing() {
        installFailingCommits()
        assertFalse(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES - 1))
    }

    @Test fun resumeCommitFailureRefusesWithoutThrowing() {
        assertFalse(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES - 1))
        installFailingCommits()
        assertFalse(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES))
    }

    @Test fun callbackAdmissionRefusesWhenPreferenceReadThrows() {
        EnrollmentTestStores.prefs = prefs
        StorageAdmission.clear(context)
        val failing = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, _, _ -> error("preferences unavailable") } as SharedPreferences
        EnrollmentTestStores.prefs = failing
        assertFalse(StorageAdmission.allowed(context))
    }

    private fun installFailingCommits() {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, _ ->
            if (method.name == "commit") false else proxy
        } as SharedPreferences.Editor
        val failing = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "edit") editor else method.invoke(prefs, *(args ?: emptyArray()))
        } as SharedPreferences
        EnrollmentTestStores.prefs = failing
    }

    private fun diagnostics() = db.uploadDiagnosticDao().forEnrollment(
        server.studyId, server.participantId, server.sourceRandomDeviceId, "${server.id}:${server.createdAt}",
    )

    @Test fun pauseRecordingDoesNotHoldAdmissionMonitorWhileWaitingBehindStop() {
        val paused = CountDownLatch(1)
        val recordNow = CountDownLatch(1)
        val readerLeased = CountDownLatch(1)
        val checkAdmission = CountDownLatch(1)
        val writerThread = AtomicReference<Thread>()
        val hookedPrefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "getBoolean" && args?.get(0) == "collection_storage_pause_episode_recorded" &&
                Thread.currentThread().name == "pause-recorder") {
                paused.countDown()
                check(recordNow.await(5, TimeUnit.SECONDS))
            }
            method.invoke(prefs, *(args ?: emptyArray()))
        } as SharedPreferences
        EnrollmentTestStores.prefs = hookedPrefs
        val executor = Executors.newFixedThreadPool(3)
        val reader = executor.submit {
            ResearchPersistenceGate.withReadLease {
                readerLeased.countDown()
                check(checkAdmission.await(5, TimeUnit.SECONDS))
                StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES)
            }
        }
        assertTrue(readerLeased.await(5, TimeUnit.SECONDS))
        val admission = executor.submit {
            Thread.currentThread().name = "pause-recorder"
            StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES - 1)
        }
        assertTrue(paused.await(5, TimeUnit.SECONDS))
        val writer = executor.submit {
            writerThread.set(Thread.currentThread())
            lock.writeLock().lockInterruptibly()
            lock.writeLock().unlock()
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writerThread.get()?.let(lock::hasQueuedThread) != true && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertTrue("stop writer must be queued", writerThread.get()?.let(lock::hasQueuedThread) == true)
            checkAdmission.countDown()
            recordNow.countDown()
            reader.get(3, TimeUnit.SECONDS)
            admission.get(3, TimeUnit.SECONDS)
            writer.get(3, TimeUnit.SECONDS)
            assertEquals(1, diagnostics().size)
            assertEquals(1, diagnostics().single().count)
        } finally {
            recordNow.countDown()
            checkAdmission.countDown()
            writer.cancel(true)
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        }
    }

    @Test fun failedPauseDiagnosticIsRecordedOnceOnRecoveryBeforeEpisodeClears() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_diagnostic BEFORE INSERT ON upload_diagnostics BEGIN SELECT RAISE(ABORT, 'diagnostics down'); END",
        )
        assertFalse(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES - 1))
        val episode = prefs.getString("collection_storage_pause_episode_id", null)
        val occurredAt = prefs.getString("collection_storage_pause_episode_at", null)
        assertNotNull(episode)
        assertTrue(diagnostics().isEmpty())

        assertTrue(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES))
        assertEquals("failed recovery recording must retain the episode", episode,
            prefs.getString("collection_storage_pause_episode_id", null))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_diagnostic")

        assertTrue(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES))
        assertTrue(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES))
        val diagnostic = diagnostics().single()
        assertEquals(episode, diagnostic.id)
        assertEquals("COLLECTION_PAUSED_STORAGE", diagnostic.issueCode)
        assertEquals(storedInstant(requireNotNull(occurredAt)), diagnostic.firstOccurredAt)
        assertEquals(1, diagnostic.count)
        assertNull(prefs.getString("collection_storage_pause_episode_id", null))
    }

    @Test fun recoveryRetriesWithSameIdWhenDiagnosticWasWrittenButAcknowledgmentWasSkipped() {
        assertFalse(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES - 1))
        val episode = diagnostics().single().id
        prefs.edit().putBoolean("collection_storage_pause_episode_recorded", false).commit()

        assertTrue(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES))

        assertEquals(episode, diagnostics().single().id)
        assertEquals(1, diagnostics().single().count)
        assertNull(prefs.getString("collection_storage_pause_episode_id", null))
    }

    @Test fun expiredAdmissionKeepsCollectingWhileItsRefreshRuns() {
        assertTrue(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES))
        val testThread = Thread.currentThread()
        val refreshStarted = CountDownLatch(1)
        val finishRefresh = CountDownLatch(1)
        EnrollmentTestStores.prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (Thread.currentThread() !== testThread) {
                refreshStarted.countDown()
                check(finishRefresh.await(5, TimeUnit.SECONDS))
            }
            method.invoke(prefs, *(args ?: emptyArray()))
        } as SharedPreferences
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        try {
            assertTrue("free space was admitted; an expired cache must not drop samples",
                StorageAdmission.cachedAllowed(context))
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
            assertTrue(StorageAdmission.cachedAllowed(context))
        } finally {
            finishRefresh.countDown()
        }
    }

    @Test fun clearedAdmissionFailsClosedUntilARefreshDecides() {
        StorageAdmission.clear(context)
        assertFalse(StorageAdmission.cachedAllowed(context))
    }

    @Test fun pauseIsOneEpisodeUntilStorageRecovers() {
        val low = LOCAL_STORAGE_RESERVE_BYTES - 1
        assertTrue(StorageAdmission.beginsPauseEpisode(false, low))
        assertFalse(StorageAdmission.beginsPauseEpisode(true, low))
        assertFalse(StorageAdmission.shouldPause(LOCAL_STORAGE_RESERVE_BYTES))
        assertTrue(StorageAdmission.beginsPauseEpisode(false, low))
    }
}
