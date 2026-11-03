package com.openlattice.chronicle.collection.state

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteException
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.checkedSQLiteOpenHelperFactory
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.LocalStoreRecoveryManager
import com.openlattice.chronicle.storage.SingleEnrollmentConflictException
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.UsagePollCheckpointEntity
import com.openlattice.chronicle.ui.participantStorageWrite
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidSweepReview3RegressionTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var prefs: SharedPreferences
    private lateinit var original: UploadServerEntity
    private val fence get() = ResearchErasureFence(context)
    private val failReads = AtomicBoolean()
    private val mainQueries = AtomicInteger()
    private val module = CollectionModuleId.BATTERY_TELEMETRY

    private fun <T> worker(action: () -> T): T {
        val result = CompletableFuture<T>()
        ResearchPersistenceGate.executeAsync {
            try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        return try { result.get(10, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw error.cause ?: error }
    }

    @Before fun setUp() = worker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        prefs = context.getSharedPreferences("sweep-review3", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant-A").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EnrollmentTestStores.prefs = prefs
        context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .openHelperFactory(checkedSQLiteOpenHelperFactory { sql ->
                if ("upload_servers" in sql) {
                    if (Thread.currentThread() === Looper.getMainLooper().thread) mainQueries.incrementAndGet()
                    if (failReads.get()) throw SQLiteException("transient enrollment read")
                }
            }).build()
        EnrollmentTestStores.db = db
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost", apiKey = "test-device-key",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant-A", sourceRandomDeviceId = "device"))
        original = db.uploadServerDao().getConfiguredServer()!!
        CollectionModuleId.values().filter { it.active }.forEach {
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(it.id, true,
                ParticipantDecision.ACCEPTED.name, 0, false, 1)))
        }
        ResearchPersistenceGate.initialize(context)
        assertTrue(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES))
        Unit
    }

    @After fun tearDown() = worker {
        failReads.set(false)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        StorageAdmission.clear(context)
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    @Test fun untaggedStorageFailureReopensOnSuccessfulStopButPrivacyFailureRequiresCompletion() = worker {
        val sink = BatterySampleSink(db.batterySampleDao(), persistenceGuard = ResearchPersistenceGate.guard(context, module).capture())
        val batch = listOf(BatterySampleEntry("accepted", "2026-09-30T00:00:00Z", "UTC", 50,
            "DISCHARGING", "NONE", 200, 4000, "GOOD"))
        failReads.set(true)
        assertThrows(SQLiteException::class.java) { ResearchPersistenceGate.stop { } }
        assertFalse(ResearchPersistenceGate.collectsNow(context, module))
        failReads.set(false)
        assertTrue("accepted data must retry through a temporary storage latch", sink.write(batch) is ModuleResult.Retry)
        assertEquals(0, db.batterySampleDao().count())
        ResearchPersistenceGate.stop { }
        assertTrue("successful stop must clear an untagged failure", ResearchPersistenceGate.collectsNow(context, module))
        assertEquals(ModuleResult.Ok(1), sink.write(batch))
        assertEquals(1, db.batterySampleDao().count())
        val privacy = ResearchPersistenceGate.PrivacyOperation(ResearchPersistenceGate.PrivacyOperationKind.PARTICIPANT_DECISION, module.id,
            ResearchErasureFence.enrollmentKey(original))
        assertThrows(IOException::class.java) { ResearchPersistenceGate.stop(privacy) { throw IOException("write interrupted") } }
        ResearchPersistenceGate.stop { }
        ResearchPersistenceGate.stop(privacy, completed = { false }) { }
        assertFalse(ResearchPersistenceGate.collectsNow(context, module))
        ResearchPersistenceGate.stop(privacy) { }
        assertTrue(ResearchPersistenceGate.collectsNow(context, module))
    }

    @Test fun expectedEnrollmentConflictAndValidationNeverLatchHealthyCollection() = worker {
        assertThrows(SingleEnrollmentConflictException::class.java) {
            ResearchPersistenceGate.enrollmentMutation(context) { db.uploadServerDao().reserveSingleEnrollment(original) }
        }
        assertTrue("rejected enrollment must preserve the current owner's admission", ResearchPersistenceGate.collectsNow(context, module))
        assertThrows(IllegalArgumentException::class.java) {
            ResearchPersistenceGate.stop { require(false) { "invalid request" } }
        }
        assertThrows(IllegalStateException::class.java) {
            ResearchPersistenceGate.stop { check(false) { "precondition rejected" } }
        }
        assertTrue(ResearchPersistenceGate.collectsNow(context, module))
        assertEquals(original, db.uploadServerDao().getConfiguredServer())
        kotlinx.coroutines.runBlocking {
            assertTrue(participantStorageWrite<Unit> { throw SingleEnrollmentConflictException() }.isFailure)
        }
        assertTrue("participant UI rejection must not add a storage latch", ResearchPersistenceGate.collectsNow(context, module))
    }

    @Test fun mainLifecycleAndStopReturnWhileAuthorizationWorkerIsBusy() {
        val stale = EnrollmentSettings(context)
        worker {
            db.uploadServerDao().update(original.copy(participantId = "participant-B"))
            prefs.edit().putString(PARTICIPANT_ID, "participant-B").commit()
            ResearchPersistenceGate.initialize(context)
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val prompt = AtomicBoolean()
        val mutated = AtomicBoolean()
        ResearchPersistenceGate.executeAsync { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val observer = Executors.newSingleThreadExecutor()
        observer.execute { prompt.set(returned.await(400, TimeUnit.MILLISECONDS)); release.countDown() }
        try {
            assertFalse(stale.isEnrolled())
            ResearchPersistenceGate.stop { mutated.set(true) }
            assertFalse("MAIN must enqueue gate mutation", mutated.get())
            returned.countDown()
        } finally {
            returned.countDown()
            observer.shutdown()
            assertTrue(observer.awaitTermination(5, TimeUnit.SECONDS))
            release.countDown()
            worker { }
        }
        assertTrue("MAIN waited behind authorization work", prompt.get())
        assertTrue(mutated.get())
        assertEquals(0, mainQueries.get())
        assertEquals("participant-B", prefs.getString(PARTICIPANT_ID, null))
    }

    private fun scope(owner: UploadServerEntity): String = MessageDigest.getInstance("SHA-256")
        .digest("${owner.studyId}\u0000${owner.participantId}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun archive(name: String, owner: UploadServerEntity): File =
        File(context.noBackupFilesDir, "chronicle-recovery/$name").apply {
            mkdirs()
            File(this, "manifest.txt").writeText(
                "owner_scope_sha256=${scope(owner)}\ndiagnostics_separated=true\ndiagnostic_artifact=diagnostic-snapshot.enc\n")
            File(this, "artifact-chronicle.db.enc").writeText("research payload")
            File(this, "diagnostic-snapshot.enc").writeText("diagnostics")
        }

    @Test fun recoveryReplayUsesArchiveOwnerEvenAfterReplacement() = worker {
        val replacement = original.copy(participantId = "participant-B")
        db.uploadServerDao().update(replacement)
        prefs.edit().putString(PARTICIPANT_ID, replacement.participantId).commit()
        listOf("first", "replay").forEach { attempt ->
            val old = archive("old-$attempt", original)
            val current = archive("current-$attempt", replacement)
            val editor = context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit()
                .putString("pending_recovery_scope", scope(original))
            editor.commit()
            ResearchPersistenceGate.stop { fence.replayRecoveryErasure(context) }
            assertFalse("explicit old-owner archive erasure must finish", old.exists())
            assertTrue("another verified archive owner must be preserved", current.exists())
            assertFalse(fence.hasPendingErasures())
        }
    }

    @Test fun enrollmentErasureDeletesRecoveryAndPreviouslyPreservedDiagnostics() = worker {
        val split = archive("split", original)
        val unsplit = File(context.noBackupFilesDir, "chronicle-recovery/unsplit").apply { mkdirs() }
        File(unsplit, "artifact-chronicle.db.enc").writeText("mixed research and diagnostics")
        File(unsplit, "artifact-diagnostics.bin.enc").writeText("separate diagnostic journal")
        LocalStoreRecoveryManager.erasePendingRecoveryArtifacts(context, scope(original))
        val diagnosticRoot = File(context.noBackupFilesDir, "chronicle-diagnostics-recovery")
        assertTrue(File(diagnosticRoot, "split/diagnostic-snapshot.enc").isFile)
        archive("fresh", original)
        LocalStoreRecoveryManager.eraseForEnrollment(context, original.studyId, original.participantId)
        assertFalse(split.exists())
        assertFalse("an unsplit archive cannot indefinitely defeat explicit erasure", unsplit.exists())
        assertTrue(diagnosticRoot.listFiles().orEmpty().isEmpty())
        assertFalse(File(context.noBackupFilesDir, "chronicle-recovery/fresh").exists())
    }

    @Test fun replacementInstallationRetriesErasureBeforeInstallingWithoutRowInventories() = worker {
        ResearchPersistenceGate.stop { fence.installEnrollment(original) }
        db.batterySampleDao().insertAll((1..1000).map { BatterySampleEntry("old-$it", "2026-09-01T00:00:00Z",
            "UTC", 50, "DISCHARGING", "NONE", 200, 4000, "GOOD") })
        db.usagePollCheckpointDao().upsert(UsagePollCheckpointEntity("old-owner-checkpoint", 100))
        val oldArchive = archive("retiring-owner", original)
        ResearchPersistenceGate.stop { fence.erase(setOf(module, CollectionModuleId.USAGE_EVENTS),
            ownerKey = ResearchErasureFence.enrollmentKey(original))
            fence.markRecoveryErasure(scope(original))
        }
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_retirement BEFORE DELETE ON battery_samples BEGIN SELECT RAISE(ABORT, 'retry'); END")
        val replacement = original.copy(participantId = "participant-B")
        assertThrows(RuntimeException::class.java) { ResearchPersistenceGate.stop { fence.installEnrollment(replacement) } }
        assertEquals(ResearchErasureFence.enrollmentKey(original), context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE)
            .getString("installed_enrollment", null))
        assertEquals(original, db.uploadServerDao().getConfiguredServer())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_retirement")
        ResearchPersistenceGate.stop {
            assertTrue(fence.installEnrollment(replacement))
            db.uploadServerDao().update(replacement)
            prefs.edit().putString(PARTICIPANT_ID, replacement.participantId).commit()
        }
        assertFalse(fence.hasPendingErasures())
        assertEquals(0, db.batterySampleDao().count())
        assertFalse(oldArchive.exists())
        assertNull(db.usagePollCheckpointDao().getLastPollTimestamp("old-owner-checkpoint"))
        assertTrue(context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).all.keys.none { it.contains("_rows:") })
        db.batterySampleDao().insertAll(listOf(BatterySampleEntry("new", "2026-09-30T00:00:00Z", "UTC", 50,
            "DISCHARGING", "NONE", 200, 4000, "GOOD")))
        assertEquals(listOf("new"), ResearchPersistenceGate.runIfActive(context) { db.batterySampleDao().getOldest(1).map { it.id } })
    }
}
