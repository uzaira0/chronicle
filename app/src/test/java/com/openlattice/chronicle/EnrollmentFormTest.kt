package com.openlattice.chronicle

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EnrollmentFormTest {
    private data class Rendering(
        val state: EnrollmentScreen,
        val submitVisibility: Int,
        val submitEnabled: Boolean,
        val progressVisibility: Int,
        val doneVisibility: Int = View.INVISIBLE,
        val doneEnabled: Boolean = true,
        val message: Int? = null,
    )

    @Test fun everyScreenRendersItsControlsAndMessage() = withForm { activity, form ->
        val cases = listOf(
            Rendering(EnrollmentScreen.Editing(), View.VISIBLE, true, View.INVISIBLE),
            Rendering(EnrollmentScreen.Reviewing(), View.VISIBLE, false, View.VISIBLE),
            Rendering(EnrollmentScreen.Reviewing(loading = false), View.VISIBLE, false, View.INVISIBLE),
            Rendering(EnrollmentScreen.Submitting(), View.INVISIBLE, false, View.VISIBLE),
            Rendering(EnrollmentScreen.Recovery(RecoveryKind.PENDING), View.VISIBLE, true, View.INVISIBLE,
                doneVisibility = View.VISIBLE, message = R.string.enrollment_pending_retry),
            Rendering(EnrollmentScreen.Recovery(RecoveryKind.ISSUED), View.VISIBLE, true, View.INVISIBLE,
                message = R.string.enrollment_issued_retry),
            Rendering(EnrollmentScreen.Recovery(RecoveryKind.STATUS_UNAVAILABLE), View.VISIBLE, true, View.INVISIBLE,
                message = R.string.enrollment_recovery_unavailable),
            Rendering(EnrollmentScreen.Recovery(RecoveryKind.CANCELLING), View.INVISIBLE, false, View.VISIBLE,
                doneVisibility = View.VISIBLE, doneEnabled = false, message = R.string.enrollment_pending_retry),
            Rendering(EnrollmentScreen.Recovery(RecoveryKind.CANCELLATION_FAILED), View.VISIBLE, true, View.INVISIBLE,
                doneVisibility = View.VISIBLE, message = R.string.enrollment_cancel_failed),
            Rendering(EnrollmentScreen.Editing(R.string.enrollment_attempt_cancelled), View.VISIBLE, true, View.INVISIBLE,
                message = R.string.enrollment_attempt_cancelled),
            Rendering(EnrollmentScreen.Result(R.string.device_enroll_success), View.GONE, false, View.GONE,
                doneVisibility = View.VISIBLE, message = R.string.device_enroll_success),
            Rendering(EnrollmentScreen.Failure(R.string.device_enroll_failure), View.VISIBLE, true, View.INVISIBLE,
                message = R.string.device_enroll_failure),
        )
        cases.forEach { expected ->
            form.render(expected.state)
            assertEquals(expected.submitVisibility, activity.findViewById<View>(R.id.button).visibility)
            assertEquals(expected.submitEnabled, activity.findViewById<View>(R.id.button).isEnabled)
            // Every recovery screen but a running cancellation offers a retry.
            val retries = (expected.state as? EnrollmentScreen.Recovery)?.kind?.let { it != RecoveryKind.CANCELLING } == true
            assertEquals(activity.getString(if (retries) R.string.enrollment_retry else R.string.action_start_collection),
                activity.findViewById<TextView>(R.id.button).text.toString())
            assertEquals(expected.progressVisibility, activity.findViewById<View>(R.id.enrollmentProgress).visibility)
            assertEquals(expected.doneVisibility, activity.findViewById<View>(R.id.doneButton).visibility)
            assertEquals(expected.doneEnabled, activity.findViewById<View>(R.id.doneButton).isEnabled)
            val status = activity.findViewById<TextView>(R.id.statusMessage)
            assertEquals(if (expected.message == null) View.INVISIBLE else View.VISIBLE, status.visibility)
            expected.message?.let { assertEquals(activity.getString(it), status.text.toString()) }
            val fieldsVisibility = if (expected.state is EnrollmentScreen.Result) View.GONE else View.VISIBLE
            listOf(R.id.studyIdTextLayout, R.id.participantIdTextLayout, R.id.studyIdTextView,
                R.id.participantIdTextView).forEach { id ->
                assertEquals(fieldsVisibility, activity.findViewById<View>(id).visibility)
            }
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.serverUrlTextLayout).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.serverUrlTextView).visibility)
        }
    }

    @Test fun prefillSurvivesRendering() = withForm { activity, form ->
        val link = parseEnrollmentLink("chronicle://enroll?studyId=11111111-1111-1111-1111-111111111111" +
            "&participantId=participant&serverUrl=https%3A%2F%2Flocalhost", "a".repeat(40)) as EnrollmentLink.Prefill
        form.prefill(link)
        form.render(EnrollmentScreen.Submitting())
        form.render(EnrollmentScreen.Failure(R.string.device_enroll_failure))
        assertEquals("participant", activity.findViewById<EditText>(R.id.participantIdText).text.toString())
        val invitation = form.enrollmentInvitation(link.accessCode)
        assertEquals("https://localhost", invitation?.serverUrl)
        assertTrue(invitation?.accessCode == link.accessCode)
        activity.findViewById<EditText>(R.id.participantIdText).setText("")
        assertEquals(null, form.enrollmentInvitation(link.accessCode))
        assertFalse(activity.findViewById<EditText>(R.id.participantIdText).error.isNullOrBlank())
    }

    private fun withForm(action: (AppCompatActivity, EnrollmentForm) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        controller.setup()
        activity.setContentView(R.layout.activity_enrollment)
        val form = EnrollmentForm(activity, onSubmit = {}, onDone = {})
        try { action(activity, form) } finally { controller.pause().stop().destroy() }
    }

    @Test fun correctedInputClearsItsPreviousError() = withForm { activity, form ->
        activity.findViewById<EditText>(R.id.studyIdText).setText("invalid")
        activity.findViewById<EditText>(R.id.participantIdText).setText("participant")
        activity.findViewById<EditText>(R.id.serverUrlText).setText("https://localhost")
        assertEquals(null, form.enrollmentInvitation("a".repeat(40)))
        activity.findViewById<EditText>(R.id.studyIdText).setText("11111111-1111-1111-1111-111111111111")
        assertTrue(form.enrollmentInvitation("a".repeat(40)) != null)
    }

    @Test fun missingServerIsRejectedByParserAndRenderedByManualEntry() = withForm { activity, form ->
        val parsed = parseEnrollmentLink("chronicle://enroll?studyId=11111111-1111-1111-1111-111111111111", null)
        assertEquals(EnrollmentLink.Invalid(R.string.enrollment_missing_server_url, EnrollmentField.SERVER), parsed)
        assertTrue(activity.findViewById<EditText>(R.id.serverUrlText).text.isNullOrBlank())
        activity.findViewById<EditText>(R.id.studyIdText).setText("11111111-1111-1111-1111-111111111111")
        activity.findViewById<EditText>(R.id.participantIdText).setText("participant")
        assertEquals(null, form.enrollmentInvitation("a".repeat(40)))
        assertEquals(activity.getString(R.string.enrollment_missing_server_url),
            activity.findViewById<TextView>(R.id.statusMessage).text.toString())
    }
}
