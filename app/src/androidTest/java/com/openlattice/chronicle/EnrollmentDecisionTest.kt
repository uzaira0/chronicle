package com.openlattice.chronicle

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.CoreMatchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnrollmentDecisionTest {

    @Before
    fun setUp() {
        AppTestState.resetPrefs()
        AppTestState.clearMutableTables()
        AppTestState.setUsageStatsAppOp()
    }

    @Test
    fun invalidStudyIdDeepLinkShowsErrorAndDoesNotPopulateFields() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(AppTestState.context, Enrollment::class.java)
            data = Uri.parse("chronicle://enroll?studyId=not-a-uuid&participantId=participant")
        }

        ActivityScenario.launch<Enrollment>(intent).use { scenario ->
            onView(withId(R.id.statusMessage)).check(matches(withText("Invalid study ID format.")))
            scenario.onActivity { activity ->
                assertEquals("", activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.studyIdText).text.toString())
                assertEquals("", activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.participantIdText).text.toString())
            }
        }
    }

    @Test
    fun insecureServerDeepLinkIsRejectedBeforeEnrollmentCanRun() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(AppTestState.context, Enrollment::class.java)
            data = Uri.parse(
                "chronicle://enroll?studyId=28d661b8-a45a-41b6-aec4-ed9988fa28dc" +
                    "&participantId=participant&serverUrl=http%3A%2F%2Fexample.test"
            )
        }

        ActivityScenario.launch<Enrollment>(intent).use {
            onView(withId(R.id.statusMessage)).check(matches(withText("Untrusted server URL rejected.")))
        }
    }

    @Test
    fun missingServerDeepLinkNeverFallsBackToAPublisherDestination() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(AppTestState.context, Enrollment::class.java)
            data = Uri.parse(
                "chronicle://enroll?studyId=28d661b8-a45a-41b6-aec4-ed9988fa28dc" +
                    "&participantId=participant",
            )
        }

        ActivityScenario.launch<Enrollment>(intent).use { scenario ->
            onView(withId(R.id.statusMessage)).check(
                matches(withText("Study invitation is missing its public HTTPS server.")),
            )
            scenario.onActivity { activity ->
                assertEquals(
                    "",
                    activity.findViewById<com.google.android.material.textfield.TextInputEditText>(
                        R.id.serverUrlText,
                    ).text.toString(),
                )
            }
        }
    }

    @Test
    fun secondInvitationIsRejectedWithoutReplacingTheActiveStudyOrRetainingItsCredential() {
        AppTestState.enrollActiveStudy()
        val accessCode = "A".repeat(64)
        val secondInvitation = Intent(Intent.ACTION_VIEW).apply {
            setClass(AppTestState.context, Enrollment::class.java)
            data = Uri.parse(
                "chronicle://enroll?studyId=44444444-4444-4444-4444-444444444444" +
                    "&participantId=second-participant" +
                    "&serverUrl=https%3A%2F%2Fanother-study.example" +
                    "#accessCode=$accessCode",
            )
        }

        ActivityScenario.launch<Enrollment>(
            Intent(AppTestState.context, Enrollment::class.java),
        ).use { scenario ->
            waitForStatus(AppTestState.context.getString(R.string.device_enroll_success))
            scenario.onActivity { activity ->
                // Enrollment is singleTask. Delivering a second invitation through the already
                // owned instance exercises onNewIntent without confusing ActivityScenario when
                // production immediately strips the capability-bearing URI fragment.
                activity.startActivity(secondInvitation)
            }
            val rejection =
                "This device already has an active Chronicle study. Withdraw from it before opening another study invitation."
            waitForStatus(rejection)
            onView(withId(R.id.statusMessage)).check(matches(withText(rejection)))
            scenario.onActivity { activity ->
                assertNull(activity.intent.data?.fragment)
            }
        }

        val configured = AppTestState.db().uploadServerDao().getAll()
        assertEquals(1, configured.size)
        assertEquals(AppTestState.STUDY_ID.toString(), configured.single().studyId)
        assertEquals(AppTestState.PARTICIPANT_ID, configured.single().participantId)
        assertEquals(AppTestState.SERVER_ORIGIN, configured.single().url)
    }

    @Test
    fun blankSubmitMarksStudyAndParticipantInputsInvalidWithoutNetwork() {
        ActivityScenario.launch<Enrollment>(
            Intent(AppTestState.context, Enrollment::class.java)
        ).use { scenario ->
            onView(withId(R.id.button)).perform(click())
            scenario.onActivity { activity ->
                val study = activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.studyIdText)
                val participant = activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.participantIdText)
                assertEquals(activity.getString(R.string.invalid_study_id_blank), study.error.toString())
                assertEquals(activity.getString(R.string.invalid_participant), participant.error.toString())
            }
        }
    }

    @Test
    fun doneDecisionRoutesToMainWhenSuccessStateIsShowing() {
        ActivityScenario.launch<Enrollment>(
            Intent(AppTestState.context, Enrollment::class.java)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.doneButton).visibility = View.VISIBLE
                activity.findViewById<View>(R.id.doneButton).performClick()
                assertTrue(activity.isFinishing)
            }
        }
    }

    @Test
    fun httpServerTypedByUserIsRejectedAfterValidIdsWithoutCallingBackend() {
        ActivityScenario.launch<Enrollment>(
            Intent(AppTestState.context, Enrollment::class.java)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.studyIdText)
                    .setText("28d661b8-a45a-41b6-aec4-ed9988fa28dc")
                activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.participantIdText)
                    .setText("participant")
                activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.serverUrlText)
                    .setText("http://example.test")
            }
            onView(withId(R.id.button)).perform(click())
            waitForStatus("Untrusted server URL rejected")
            onView(withId(R.id.statusMessage)).check(matches(withText(containsString("Untrusted server URL rejected"))))
        }
    }

    private fun waitForStatus(text: String) {
        repeat(20) {
            try {
                onView(withId(R.id.statusMessage)).check(matches(withText(containsString(text))))
                return
            } catch (_: AssertionError) {
                Thread.sleep(100)
            }
        }
        onView(withId(R.id.statusMessage)).check(matches(withText(containsString(text))))
    }
}
