package com.openlattice.chronicle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.EnrollmentResponse
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.collection.state.validateEnrollmentModulePartition
import com.openlattice.chronicle.security.InvitedServerCa
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.study.EnrollmentWithdrawalResponse
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import java.io.IOException
import java.security.cert.CertificateException
import java.util.Base64
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.tls.HeldCertificate
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [EnrollmentTestStores.Database::class, EnrollmentTestStores.Preferences::class, EnrollmentTestStores.StudyApi::class])
class EnrollmentFailureAuditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val accessCode = "a".repeat(40)
    private lateinit var db: ChronicleDb
    private fun installApi(action: (String) -> Any?) {
        EnrollmentTestStores.api = studyApi { method, _ -> action(method) }
    }
    private fun installEmptyStores() {
        db = EnrollmentTestStores.install(context)
    }
    @After fun closeStores() { if (::db.isInitialized) db.close() }
    private fun await(condition: () -> Boolean) {
        repeat(100) { shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(20) }
        assertTrue("UI did not settle", condition())
    }


    private fun invitation(preview: EnrollmentPreviewResponse) = Intent(context, Enrollment::class.java)
        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(
            "chronicle://enroll?studyId=${preview.manifest.studyId}&participantId=${preview.manifest.participantId}" +
                "&serverUrl=https%3A%2F%2Flocalhost#accessCode=$accessCode"))

    @Test fun previewCompletedAfterRotationLaunchesDisclosureOnTheCurrentActivity() {
        installEmptyStores()
        val preview = EnrollmentTestStores.enrollmentPreview()
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        installApi {
            check(it == "getEnrollmentPreview")
            entered.countDown()
            check(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
            preview
        }
        val controller = Robolectric.buildActivity(Enrollment::class.java, invitation(preview)).setup()
        try {
            await { controller.get().findViewById<View>(R.id.button).isEnabled }
            controller.get().findViewById<View>(R.id.button).performClick()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val original = controller.get()
            controller.recreate()
            released.countDown()
            await { shadowOf(controller.get()).peekNextStartedActivityForResult() != null }
            assertEquals(StudyDisclosureActivity::class.java.name,
                shadowOf(controller.get()).nextStartedActivityForResult.intent.component!!.className)
            assertEquals(null, shadowOf(original).peekNextStartedActivityForResult())
        } finally { released.countDown(); controller.pause().stop().destroy(); db.close() }
    }

    @Test fun temporaryPreviewFailureRetainsInvitationAndRetryOpensDisclosure() {
        installEmptyStores()
        val preview = EnrollmentTestStores.enrollmentPreview()
        var attempts = 0
        installApi { method -> if (method == "getEnrollmentPreview") {
            attempts++; if (attempts == 1) throw IOException("unique-private-transport-sentinel") else preview
        } else null }
        val controller = Robolectric.buildActivity(Enrollment::class.java, invitation(preview)).setup()
        val activity = controller.get()
        try {
            val button = activity.findViewById<View>(R.id.button)
            await { button.isEnabled }
            button.performClick()
            val status = activity.findViewById<TextView>(R.id.statusMessage)
            await { attempts == 1 && button.isEnabled && status.visibility == View.VISIBLE }
            assertTrue(status.text.toString(), status.text.contains("connection", ignoreCase = true))
            assertFalse(status.text.contains("sentinel"))
            assertEquals(accessCode, ViewModelProvider(activity)[Enrollment.WizardState::class.java].accessCode)
            button.performClick()
            await { shadowOf(activity).peekNextStartedActivityForResult() != null }
            assertEquals(StudyDisclosureActivity::class.java.name, shadowOf(activity).nextStartedActivityForResult.intent.component?.className)
            assertEquals(2, attempts)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun invitationDuringDisclosureIsScrubbedWithoutReplacingReviewedState() {
        installEmptyStores()
        val preview = EnrollmentTestStores.enrollmentPreview()
        installApi { if (it == "getEnrollmentPreview") preview else null }
        val controller = Robolectric.buildActivity(Enrollment::class.java, invitation(preview)).setup()
        val activity = controller.get()
        try {
            await { activity.findViewById<View>(R.id.button).isEnabled }
            activity.findViewById<View>(R.id.button).performClick()
            await { shadowOf(activity).peekNextStartedActivityForResult() != null }
            val wizard = ViewModelProvider(activity)[Enrollment.WizardState::class.java]
            val reviewed = wizard.step as WizardStep.Reviewed
            val incoming = invitation(preview)
            controller.newIntent(incoming)
            assertEquals(null, incoming.data?.fragment)
            assertTrue(wizard.step === reviewed)
            assertEquals(context.getString(R.string.enrollment_finish_current_first),
                activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            assertFalse(activity.findViewById<View>(R.id.button).isEnabled)
            assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.enrollmentProgress).visibility)
        } finally { controller.pause().stop().destroy(); db.close() }
    }

    @Test fun previewHttpFailuresDescribeTheRespondingServerAndRetainTheInvitation() {
        listOf(408, 429, 503).forEach { code ->
            assertPreviewFailure(ChronicleCallException("GET", "url", "", code),
                R.string.enrollment_server_unavailable)
        }
    }

    @Test fun unreadablePreviewRequestsStudySupportInsteadOfConnectionRepair() {
        assertPreviewFailure(JsonDataException("unreadable"), R.string.enrollment_response_unexpected)
        assertPreviewFailure(JsonEncodingException("malformed"), R.string.enrollment_response_unexpected)
    }

    @Test fun rejectedPreviewRequestsANewInvitation() {
        assertPreviewFailure(ChronicleCallException("GET", "url", "", 401),
            R.string.enrollment_invitation_unverified)
    }

    @Test fun unknownPreviewFailureDoesNotInventAConnectionOrInvitationCause() {
        assertPreviewFailure(IllegalStateException("request outcome unknown"), R.string.enrollment_request_unconfirmed)
    }

    @Test fun rejectedServerCertificateAsksForTheInvitationAgain() {
        assertPreviewFailure(SSLHandshakeException("untrusted").apply { initCause(CertificateException("no anchor")) },
            R.string.enrollment_server_certificate_untrusted)
        assertPreviewFailure(SSLPeerUnverifiedException("Hostname localhost not verified"),
            R.string.enrollment_server_certificate_untrusted)
    }

    @Test fun anInvitationCarryingTheServerCaStoresItForThatServerBeforeThePreview() {
        installEmptyStores()
        installApi { throw IOException("preview not needed") }
        val root = HeldCertificate.Builder().certificateAuthority(1).build().certificate
        val parameter = Base64.getUrlEncoder().withoutPadding().encodeToString(root.encoded)
        val preview = EnrollmentTestStores.enrollmentPreview()
        val link = Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(
            "chronicle://enroll?studyId=${preview.manifest.studyId}&participantId=${preview.manifest.participantId}" +
                "&serverUrl=https%3A%2F%2Flocalhost&serverCa=$parameter#accessCode=$accessCode"))
        val controller = Robolectric.buildActivity(Enrollment::class.java, link).setup()
        try {
            await { controller.get().findViewById<View>(R.id.button).isEnabled }
            assertEquals(root, InvitedServerCa.rootFor("https://localhost"))
            assertEquals(null, InvitedServerCa.rootFor("https://localhost:8443"))
        } finally { controller.pause().stop().destroy(); db.close() }
    }

    private fun assertPreviewFailure(error: Exception, message: Int) {
        installEmptyStores()
        installApi { throw error }
        val controller = Robolectric.buildActivity(Enrollment::class.java, invitation(EnrollmentTestStores.enrollmentPreview())).setup()
        val activity = controller.get()
        try {
            val button = activity.findViewById<View>(R.id.button)
            await { button.isEnabled }
            button.performClick()
            val status = activity.findViewById<TextView>(R.id.statusMessage)
            await { status.visibility == View.VISIBLE && button.isEnabled }
            assertEquals(context.getString(message), status.text.toString())
            assertEquals(accessCode, ViewModelProvider(activity)[Enrollment.WizardState::class.java].accessCode)
        } finally { controller.pause().stop().destroy(); db.close() }
    }

    @Test fun localEnrollmentSlotFailureDoesNotBlameIdentifiers() {
        installEmptyStores()
        val controller = Robolectric.buildActivity(Enrollment::class.java, invitation(EnrollmentTestStores.enrollmentPreview())).setup()
        val activity = controller.get()
        try {
            val button = activity.findViewById<View>(R.id.button)
            await { button.isEnabled }
            db.close()
            db = EnrollmentTestStores.installUnreadable(context)
            button.performClick()
            val status = activity.findViewById<TextView>(R.id.statusMessage)
            await { status.visibility == View.VISIBLE && button.isEnabled }
            assertEquals(context.getString(R.string.device_enroll_failure), status.text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun recoveryReadFailureDoesNotClaimServerAcceptance() {
        installEmptyStores()
        db.close()
        db = EnrollmentTestStores.installUnreadable(context)
        val controller = Robolectric.buildActivity(Enrollment::class.java, invitation(EnrollmentTestStores.enrollmentPreview())).setup()
        try {
            val status = controller.get().findViewById<TextView>(R.id.statusMessage)
            await { status.visibility == View.VISIBLE }
            assertEquals(context.getString(R.string.enrollment_recovery_unavailable), status.text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun localReservationFailureDoesNotBlameIdentifiers() {
        reviewedEnrollmentFailure(R.string.device_enroll_failure) { db.close() }
    }

    @Test
    fun pendingRequestTimingFailureNeverClaimsServerAcceptance() {
        var requests = 0
        reviewedEnrollmentFailure(R.string.enrollment_recovery_unavailable) {
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_request_timing BEFORE UPDATE OF pendingEnrollmentFirstRequestAtEpochMillis ON upload_servers
                WHEN NEW.pendingEnrollmentFirstRequestAtEpochMillis IS NOT NULL
                BEGIN SELECT RAISE(ABORT, 'timing write failed'); END
            """.trimIndent())
            installApi { requests++; error("No enrollment request should be sent") }
        }
        assertEquals(0, requests)
    }

    @Test
    fun issuedCredentialsWithFailedLocalSetupShowAcceptedButIncomplete() {
        reviewedEnrollmentFailure(R.string.enrollment_issued_retry) {
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_activation BEFORE UPDATE OF enabled ON upload_servers
                WHEN NEW.enabled = 1 AND NEW.enrollmentIssuedAtEpochMillis IS NOT NULL
                BEGIN SELECT RAISE(ABORT, 'activation failed'); END
            """.trimIndent())
            installApi { method ->
                if (method == "enroll") EnrollmentResponse(UUID.randomUUID(),
                    apiKey = db.uploadServerDao().getRecoverablePendingEnrollment()!!.pendingProposedApiKey)
                else Unit
            }
        }
    }

    /** The phone's clock reads the disclosure as expired an hour ago; the server's clock decides, and accepts. */
    @Test
    fun aPhoneClockPastTheDisclosureExpiryStillEnrollsWhenTheServerAccepts() {
        val requests = mutableListOf<String>()
        reviewedEnrollmentFailure(R.string.device_enroll_success,
            EnrollmentTestStores.enrollmentPreview(issuedAt = OffsetDateTime.now().minusHours(2))) {
            installApi { method ->
                requests += method
                if (method == "enroll") EnrollmentResponse(UUID.randomUUID(),
                    apiKey = db.uploadServerDao().getRecoverablePendingEnrollment()!!.pendingProposedApiKey)
                else Unit
            }
        }
        assertEquals("enroll", requests.first())
    }

    private fun reviewedEnrollmentFailure(message: Int,
                                          preview: EnrollmentPreviewResponse = EnrollmentTestStores.enrollmentPreview(),
                                          beforeEnroll: () -> Unit = {}) {
        installEmptyStores()
        val controller = Robolectric.buildActivity(Enrollment::class.java, invitation(preview)).setup()
        val activity = controller.get()
        try {
            await { activity.findViewById<View>(R.id.button).isEnabled }
            installApi { if (it == "getEnrollmentPreview") preview else null }
            activity.findViewById<View>(R.id.button).performClick()
            await { shadowOf(activity).peekNextStartedActivityForResult() != null }
            val disclosure = shadowOf(activity).nextStartedActivityForResult.intent
            beforeEnroll()
            shadowOf(activity).receiveResult(disclosure, Activity.RESULT_OK, null)
            val status = activity.findViewById<TextView>(R.id.statusMessage)
            await { status.visibility == View.VISIBLE }
            assertEquals(context.getString(message), status.text.toString())
        } finally { controller.pause().stop().destroy(); db.close() }
    }

    /**
     * The enrollment request went out but its answer was lost: reopening the app offers Cancel. Cancelling while the
     * server cannot confirm keeps the attempt and says so; once the server confirms, the attempt is gone.
     */
    @Test fun aStuckEnrollmentAttemptIsCancelledOnlyOnceTheServerConfirms() {
        installEmptyStores()
        val preview = EnrollmentTestStores.enrollmentPreview()
        val settings = preview.manifest.collectionSettings
        onPersistenceWorker { reserveEnrollment(context, EnrollmentSubmission(preview = preview,
            partition = validateEnrollmentModulePartition(
                settings, settings.effectiveEnabledModuleIds(), emptySet(), emptySet()),
            serverUrl = preview.manifest.serverOrigin, enrollmentAccessCode = accessCode)) }
        var serverReachable = false
        EnrollmentTestStores.api = studyApi { method, args ->
            when (method) {
                "enroll" -> throw IOException("reply lost")
                "withdrawCurrentEnrollment" -> if (serverReachable) {
                    EnrollmentWithdrawalResponse(UUID.fromString(args!![2] as String))
                } else throw IOException("offline")
                else -> error("unexpected API $method")
            }
        }
        val controller = Robolectric.buildActivity(Enrollment::class.java, Intent(context, Enrollment::class.java)).setup()
        val activity = controller.get()
        try {
            val status = activity.findViewById<TextView>(R.id.statusMessage)
            val cancel = activity.findViewById<TextView>(R.id.doneButton)
            await { status.text.toString() == context.getString(R.string.enrollment_pending_retry) && cancel.visibility == View.VISIBLE }
            assertEquals(context.getString(R.string.cancel_pending_enrollment), cancel.text.toString())

            cancel.performClick()
            await { status.text.toString() == context.getString(R.string.enrollment_cancel_failed) }
            assertTrue(db.uploadServerDao().getRecoverablePendingEnrollment() != null)

            serverReachable = true
            cancel.performClick()
            await { status.text.toString() == context.getString(R.string.enrollment_attempt_cancelled) }
            assertEquals(null, db.uploadServerDao().getRecoverablePendingEnrollment())
        } finally { controller.pause().stop().destroy(); db.close() }
    }

    @Test fun sameEnrollmentAppearingBeforePreviewReturnsToChronicle() {
        installEmptyStores()
        val preview = EnrollmentTestStores.enrollmentPreview()
        val controller = Robolectric.buildActivity(Enrollment::class.java, invitation(preview)).setup()
        val activity = controller.get()
        try {
            await { activity.findViewById<View>(R.id.button).isEnabled }
            db.uploadServerDao().insert(UploadServerEntity(name = "study",
                url = "https://localhost", studyId = preview.manifest.studyId.toString(),
                participantId = preview.manifest.participantId, sourceRandomDeviceId = "test-device"))
            activity.findViewById<View>(R.id.button).performClick()
            val status = activity.findViewById<TextView>(R.id.statusMessage)
            await { status.visibility == View.VISIBLE }
            assertEquals(context.getString(R.string.enrollment_already_enrolled), status.text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.doneButton).visibility)
        } finally { controller.pause().stop().destroy(); db.close() }
    }
}
