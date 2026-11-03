package com.openlattice.chronicle

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteException
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploader
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast

/** Research "Delete Server" withdraws; it never drops the credential before the server confirms. */
@RunWith(RobolectricTestRunner::class)
class ServerDeleteWithdrawalTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val studyId = "11111111-1111-1111-1111-111111111111"
    private lateinit var db: ChronicleDb

    @Before fun setUp() {
        val prefs = context.getSharedPreferences("server-delete-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, studyId).putString(PARTICIPANT_ID, "p1")
            .putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name).commit()
        EnrollmentTestStores.prefs = prefs
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        EnrollmentTestStores.db = db
        // Work is enqueued but never executed, so no withdrawal request leaves the test.
    }

    private fun enroll(apiKey: String?) = db.uploadServerDao().insert(UploadServerEntity(
        name = "study", url = "https://example.invalid", studyId = studyId, participantId = "p1",
        sourceRandomDeviceId = "d1", apiKey = apiKey,
    ))

    @Test fun unavailableUploadHistoryShowsAStorageFailure() {
        val id = enroll(apiKey = "key-1")
        db.openHelper.writableDatabase.execSQL("DROP TABLE upload_stats")
        ShadowLog.clear()
        val intent = Intent(context, ServerEnrollmentActivity::class.java)
            .putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, id)
        val controller = Robolectric.buildActivity(ServerEnrollmentActivity::class.java, intent).setup()
        try {
            val status = controller.get().findViewById<TextView>(R.id.statsText)
            val expected = context.getString(R.string.server_upload_history_failed)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (status.text.toString() != expected && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (status.text.toString() != expected) Thread.sleep(10)
            }
            assertEquals(View.VISIBLE, status.visibility)
            assertEquals(expected, status.text.toString())
            assertEquals(View.VISIBLE, controller.get().findViewById<View>(R.id.statsContainer).visibility)
            assertEquals(View.INVISIBLE, controller.get().findViewById<View>(R.id.serverStatusMessage).visibility)
            assertEquals("study", controller.get().findViewById<TextView>(R.id.serverNameText).text.toString())
            val logs = ShadowLog.getLogs().filter { it.throwable is SQLiteException }
            assertEquals("one log owns the storage failure", 1, logs.size)
            assertEquals(Log.ERROR, logs.single().type)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun clickDelete(serverId: Long) {
        val activity = Robolectric.buildActivity(
            ServerEnrollmentActivity::class.java,
            Intent(context, ServerEnrollmentActivity::class.java).putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, serverId),
        ).setup().get()
        activity.findViewById<View>(R.id.serverDeleteButton).performClick()
        settle()
    }

    /** The activity hops to its own executor and back; wait for the main-thread callback. */
    private fun settle() = repeat(50) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun serverWithoutADeletionCredentialIsLeftUntouched() {
        val id = enroll(apiKey = null)

        clickDelete(id)

        assertNull("no withdrawal is offered that could never complete", ShadowDialog.getLatestDialog())
        assertNotNull(db.uploadServerDao().getById(id))
        assertEquals(WithdrawalState.NONE, WithdrawalStateStore(context).state())
    }

    @Test fun confirmingStartsWithdrawalAndKeepsTheCredentialUntilTheServerAcknowledges() {
        val id = enroll(apiKey = "key-1")
        val capturedStore = LocalUploadDiagnosticsStore.of(context)
        db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(
            id = "before", studyId = studyId, participantId = "p1", randomDeviceId = "d1",
            enrollmentEpoch = "$id:${db.uploadServerDao().getById(id)!!.createdAt}",
            day = "2026-09-28", moduleFamily = "APP_RUNTIME",
            issueCode = "APP_CRASH", count = 1, firstOccurredAt = "2026-09-28T00:00:00Z",
            lastOccurredAt = "2026-09-28T00:00:00Z", httpStatus = null, errorType = null,
        ))
        db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
            id = "quarantined", sourceTable = CollectionModuleId.AUDIO_CONTENT.id, sourceId = "bad",
            studyId = studyId, participantId = "p1", randomDeviceId = "d1",
            rawData = "sample".toByteArray(), reason = "SAMPLE_QUARANTINED",
            createdAt = "2026-09-28T00:00:00Z",
        ))

        clickDelete(id)
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())
        assertEquals("key-1", db.uploadServerDao().getById(id)?.apiKey)
        assertNull("withdrawal clears pending diagnostics at once", db.uploadDiagnosticDao().get("before"))
        assertNotNull("the local quarantine never uploads and stays for support",
            db.localDataQuarantineDao().get(CollectionModuleId.AUDIO_CONTENT.id, "bad"))
        capturedStore.recordOperationalOnce("late", LocalUploadModuleFamily.APP_RUNTIME,
            LocalOperationalIssue.APP_CRASH, OffsetDateTime.parse("2026-09-28T01:00:00Z"))
        assertNull(db.uploadDiagnosticDao().get("late"))
        assertEquals(0, UploadDiagnosticsUploader(context, db).execute())

        // The withdrawal worker performs these steps only after server acknowledgment.
        LocalUploadDiagnosticsStore.of(context).clear()
        db.clearAllTables()
        WithdrawalStateStore(context).setState(WithdrawalState.COMPLETE)
        assertNull(db.localDataQuarantineDao().get(CollectionModuleId.AUDIO_CONTENT.id, "bad"))
    }

    @Test fun partialWithdrawalNeverClaimsNothingChanged() {
        val id = enroll(apiKey = "key-1")
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
        class FailingPreferences : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                class FailingEditor : SharedPreferences.Editor by editor {
                    private var fail = false
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        fail = fail || key == PARTICIPATION_STATUS
                        editor.putString(key, value)
                        return this
                    }
                    override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean = !fail && editor.commit()
                }
                return FailingEditor()
            }
        }
        EnrollmentTestStores.prefs = FailingPreferences()
        try {
            clickDelete(id)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            settle()
            assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())
            assertEquals(context.getString(R.string.server_delete_failed),
                ShadowToast.getTextOfLatestToast())
            assertNotNull(db.uploadServerDao().getById(id))
        } finally {
            EnrollmentTestStores.prefs = prefs
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        }
    }

}
