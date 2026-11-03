package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.storage.ChronicleDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [EnrollmentTestStores.Database::class, EnrollmentTestStores.Preferences::class])
class EnrollmentMessageAccuracyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun reopeningTheSameInvitationReturnsToChronicle() = existingInvitation(R.string.enrollment_already_enrolled)
    @Test fun anotherStudyPointsToSettingsWithdrawal() =
        existingInvitation(R.string.enrollment_existing_study_rejection, studyId = "22222222-2222-2222-2222-222222222222")
    @Test fun anotherParticipantPointsToSettingsWithdrawal() =
        existingInvitation(R.string.enrollment_existing_study_rejection, participantId = "another-participant")
    @Test fun anotherServerPointsToSettingsWithdrawal() =
        existingInvitation(R.string.enrollment_existing_study_rejection, serverUrl = "https://example.invalid")

    private fun existingInvitation(message: Int, studyId: String? = null, participantId: String? = null, serverUrl: String? = null) {
        EnrollmentTestStores.install(context, enrolled = true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        val db = ChronicleDb.getInstance(context)
        val server = db.uploadServerDao().getConfiguredServer()!!
        val link = Uri.Builder().scheme("chronicle").authority("enroll")
            .appendQueryParameter("studyId", studyId ?: server.studyId)
            .appendQueryParameter("participantId", participantId ?: server.participantId)
            .appendQueryParameter("serverUrl", serverUrl ?: server.url)
            .fragment("accessCode=${"a".repeat(40)}").build()
        val controller = Robolectric.buildActivity(Enrollment::class.java,
            Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW).setData(link)).setup()
        try {
            val activity = controller.get()
            val status = activity.findViewById<TextView>(R.id.statusMessage)
            repeat(100) {
                shadowOf(Looper.getMainLooper()).idle()
                if (status.visibility != View.VISIBLE) Thread.sleep(20)
            }
            assertEquals(context.getString(message), status.text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.doneButton).visibility)
            assertEquals(null, activity.intent.data?.fragment)
            assertEquals(server, db.uploadServerDao().getConfiguredServer())
            activity.findViewById<View>(R.id.doneButton).performClick()
            assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            assertTrue(activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
            onPersistenceWorker { ResearchPersistenceGate.resetAfterLocalStoreRecovery() }
            db.close()
        }
    }
}
