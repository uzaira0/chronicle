package com.openlattice.chronicle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.collection.state.validateEnrollmentModulePartition
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.upload.UNPROCESSABLE_PAYLOAD_STATUS
import com.openlattice.chronicle.storage.ChronicleDb
import java.net.HttpURLConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Pilot: a reinstalled app re-enrolled against a server that answered enroll with a permanent 400 (an app/server
 * header contract mismatch), and the participant was told to check their connection while the attempt was retried for
 * 24 hours. What the participant sees for each kind of server answer, on submit and on reopening the app.
 */
@RunWith(RobolectricTestRunner::class)
class EnrollmentServerRefusalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val accessCode = "a".repeat(40)
    private lateinit var db: ChronicleDb

    private var submitted: ActivityController<Enrollment>? = null

    @After fun closeStores() {
        submitted?.pause()?.stop()?.destroy()
        if (::db.isInitialized) db.close()
    }

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("UI did not settle", condition())
    }

    private fun invitation(preview: EnrollmentPreviewResponse) = Intent(context, Enrollment::class.java)
        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(
            "chronicle://enroll?studyId=${preview.manifest.studyId}&participantId=${preview.manifest.participantId}" +
                "&serverUrl=https%3A%2F%2Flocalhost#accessCode=$accessCode"))

    private fun refusal(method: String, status: Int): Nothing =
        throw ChronicleCallException(method, "request", "", status)

    @Test fun anEnrollThisServerCannotAcceptEndsTheAttemptWithTheStudyTeamMessage() {
        val requests = mutableListOf<String>()
        submit { method -> requests += method; refusal(method, HttpURLConnection.HTTP_BAD_REQUEST) }
        assertShown(R.string.enrollment_server_incompatible)
        assertEquals("one request, no retry and no withdrawal", listOf("enroll"), requests)
        assertNull(db.uploadServerDao().getRecoverablePendingEnrollment())
    }

    @Test fun aBusyServerKeepsTheAttemptForItsRetry() {
        submit { method -> refusal(method, HttpURLConnection.HTTP_UNAVAILABLE) }
        assertShown(R.string.enrollment_pending_retry)
        assertNotNull(db.uploadServerDao().getRecoverablePendingEnrollment())
    }

    @Test fun aRejectedOrExpiredInvitationKeepsItsMessage() {
        submit { method ->
            refusal(method, if (method == "enroll") HttpURLConnection.HTTP_UNAUTHORIZED else HttpURLConnection.HTTP_GONE)
        }
        assertShown(R.string.enrollment_attempt_rejected)
        assertNull(db.uploadServerDao().getRecoverablePendingEnrollment())
    }

    /** An attempt kept by an earlier outage is replayed on reopen; the server now refusing this version ends it. */
    @Test fun reopeningWithAnAttemptTheServerNowRefusesEndsItWithTheStudyTeamMessage() {
        db = EnrollmentTestStores.install(context)
        val preview = EnrollmentTestStores.enrollmentPreview()
        val settings = preview.manifest.collectionSettings
        onPersistenceWorker { reserveEnrollment(context, EnrollmentSubmission(
            preview = preview,
            partition = validateEnrollmentModulePartition(settings, settings.effectiveEnabledModuleIds(), emptySet(), emptySet()),
            serverUrl = preview.manifest.serverOrigin,
            enrollmentAccessCode = accessCode,
        )) }
        EnrollmentTestStores.api = studyApi { method, _ -> refusal(method, UNPROCESSABLE_PAYLOAD_STATUS) }
        val controller = Robolectric.buildActivity(Enrollment::class.java, Intent(context, Enrollment::class.java)).setup()
        try {
            assertShown(R.string.enrollment_server_incompatible, controller.get())
            assertTrue(controller.get().findViewById<View>(R.id.button).isEnabled)
        } finally { controller.pause().stop().destroy() }
        assertNull(db.uploadServerDao().getRecoverablePendingEnrollment())
    }

    @Test fun aPreviewTheServerCannotServeSendsTheParticipantToTheStudyTeam() {
        assertPreviewMessage(HttpURLConnection.HTTP_NOT_FOUND, R.string.enrollment_server_incompatible)
        assertPreviewMessage(HttpURLConnection.HTTP_FORBIDDEN, R.string.enrollment_server_rejected)
        assertPreviewMessage(HttpURLConnection.HTTP_CONFLICT, R.string.enrollment_server_unavailable)
        assertPreviewMessage(HttpURLConnection.HTTP_MOVED_TEMP, R.string.enrollment_server_unavailable)
        assertPreviewMessage(HttpURLConnection.HTTP_UNAUTHORIZED, R.string.enrollment_invitation_unverified)
    }

    private fun assertShown(message: Int, activity: Activity? = submitted?.get()) {
        val status = requireNotNull(activity).findViewById<TextView>(R.id.statusMessage)
        await { status.visibility == View.VISIBLE && status.text.toString() == context.getString(message) }
    }

    /** Reviews the invitation, accepts the disclosure and lets [enroll] answer every later request. */
    private fun submit(enroll: (String) -> Any?) {
        db = EnrollmentTestStores.install(context)
        val preview = EnrollmentTestStores.enrollmentPreview()
        EnrollmentTestStores.api = studyApi { method, _ -> if (method == "getEnrollmentPreview") preview else error(method) }
        val controller = Robolectric.buildActivity(Enrollment::class.java, invitation(preview)).setup()
        val activity = controller.get()
        await { activity.findViewById<View>(R.id.button).isEnabled }
        activity.findViewById<View>(R.id.button).performClick()
        await { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val disclosure = shadowOf(activity).nextStartedActivityForResult.intent
        EnrollmentTestStores.api = studyApi { method, _ -> enroll(method) }
        shadowOf(activity).receiveResult(disclosure, Activity.RESULT_OK, null)
        submitted = controller
    }

    private fun assertPreviewMessage(status: Int, message: Int) {
        db = EnrollmentTestStores.install(context)
        EnrollmentTestStores.api = studyApi { method, _ -> refusal(method, status) }
        val controller = Robolectric.buildActivity(Enrollment::class.java, invitation(EnrollmentTestStores.enrollmentPreview())).setup()
        try {
            val button = controller.get().findViewById<View>(R.id.button)
            await { button.isEnabled }
            button.performClick()
            assertShown(message, controller.get())
        } finally { controller.pause().stop().destroy(); db.close() }
    }
}
