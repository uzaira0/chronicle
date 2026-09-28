package com.openlattice.chronicle

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.Executor

/** Research "Delete Server" withdraws; it never drops the credential before the server confirms. */
@RunWith(RobolectricTestRunner::class)
class ServerDeleteWithdrawalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val studyId = "11111111-1111-1111-1111-111111111111"
    private lateinit var db: ChronicleDb

    @Before fun setUp() {
        val prefs = context.getSharedPreferences("server-delete-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, studyId).putString(PARTICIPANT_ID, "p1")
            .putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name).commit()
        setStatic(EncryptedPrefsHelper::class.java, "instance", EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        setStatic(ChronicleDb::class.java, "INSTANCE", null, db)
        // Work is enqueued but never executed, so no withdrawal request leaves the test.
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(context, Configuration.Builder().setExecutor(Executor { }).build())
        }
    }

    private fun enroll(apiKey: String?) = db.uploadServerDao().insert(UploadServerEntity(
        name = "study", url = "https://example.invalid", studyId = studyId, participantId = "p1",
        sourceDeviceId = "d1", apiKey = apiKey,
    ))

    private fun clickDelete(serverId: Long) {
        val activity = Robolectric.buildActivity(
            ServerEnrollmentActivity::class.java,
            Intent(context, ServerEnrollmentActivity::class.java).putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, serverId),
        ).setup().get()
        activity.findViewById<android.view.View>(R.id.serverDeleteButton).performClick()
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

        clickDelete(id)
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()

        assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())
        assertEquals("key-1", db.uploadServerDao().getById(id)?.apiKey)
    }

    private fun setStatic(owner: Class<*>, field: String, receiver: Any?, value: Any) {
        owner.getDeclaredField(field).apply { isAccessible = true }.set(receiver, value)
    }
}
