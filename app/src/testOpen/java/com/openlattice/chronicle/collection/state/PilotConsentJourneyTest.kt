package com.openlattice.chronicle.collection.state

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.openlattice.chronicle.Enrollment
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.NotificationRationaleDialog
import com.openlattice.chronicle.R
import com.openlattice.chronicle.StudyDisclosureActivity
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.EnrollmentResponse
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleId.BATTERY_TELEMETRY
import com.openlattice.chronicle.collection.CollectionModuleId.DEVICE_LIFECYCLE
import com.openlattice.chronicle.collection.CollectionModuleId.IN_APP_ACTIVITY_CLASS
import com.openlattice.chronicle.collection.CollectionModuleId.SENSOR_AVAILABILITY
import com.openlattice.chronicle.collection.CollectionModuleId.UPLOAD_TELEMETRY
import com.openlattice.chronicle.collection.CollectionModuleId.USAGE_EVENTS
import com.openlattice.chronicle.collection.CollectionModuleId.USER_IDENTIFICATION
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.ui.awaitDashboardUi
import java.time.OffsetDateTime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The pilot study through the real enrollment, disclosure and orientation screens on the Open build.
 * The consent steps, their order and the resulting accepted/declined partition are checked against the pilot module
 * list written out here, and every decision is checked against what may then collect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [
    EnrollmentTestStores.Database::class, EnrollmentTestStores.Preferences::class, EnrollmentTestStores.StudyApi::class])
class PilotConsentJourneyTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The pilot study's modules. Upload telemetry and sensor availability are operational: no consent step. */
    private val pilot = listOf(USAGE_EVENTS, IN_APP_ACTIVITY_CLASS, DEVICE_LIFECYCLE, USER_IDENTIFICATION,
        UPLOAD_TELEMETRY, SENSOR_AVAILABILITY, BATTERY_TELEMETRY)
    private val consentSteps = setOf(USAGE_EVENTS, IN_APP_ACTIVITY_CLASS, DEVICE_LIFECYCLE, USER_IDENTIFICATION, BATTERY_TELEMETRY)
    private val acks = Collections.synchronizedList(mutableListOf<CollectionAcknowledgment>())
    private var enrollCalls = 0

    private fun preview(required: Set<CollectionModuleId>): EnrollmentPreviewResponse {
        val base = EnrollmentTestStores.enrollmentPreview(enabled = pilot.toSet(), issuedAt = OffsetDateTime.now())
        val modules = CollectionModuleId.values().associateWith {
            CollectionModuleSetting(enabled = it in pilot, required = it in required)
        }
        return base.copy(manifest = base.manifest.copy(collectionSettings = base.manifest.collectionSettings.copy(modules = modules)))
    }

    private fun <T> worker(action: () -> T): T {
        val task = FutureTask(action)
        ResearchPersistenceGate.executeAsync { task.run() }
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun waitUntil(predicate: () -> Boolean) = awaitDashboardUi(attempts = 800, condition = predicate)

    /**
     * Opens the invitation, accepts the disclosure and hands [orientation] the wizard, then runs [verify] while the
     * enrollment screen and the study API are still up (the enroll call follows the wizard asynchronously); returns
     * the database.
     */
    private fun journey(required: Set<CollectionModuleId>, verify: (ChronicleDb) -> Unit = {},
                        orientation: (Enrollment, Intent) -> Unit): ChronicleDb {
        val db = EnrollmentTestStores.install(context, enrolled = false)
        worker { ResearchPersistenceGate.initialize(context) }
        val preview = preview(required)
        EnrollmentTestStores.installApi(studyApi { method, args ->
            when (method) {
                "getEnrollmentPreview" -> preview
                "enroll" -> { enrollCalls++; EnrollmentResponse(UUID.randomUUID(), apiKey = args!![7] as String) }
                "reportCollectionAck" -> { acks += args!![4] as CollectionAcknowledgment; OK() }
                else -> error("unexpected study API $method")
            }
        }).use {
            EnrollmentTestStores.expectedApiRequest = EnrollmentTestStores.ApiRequest(EnrollmentTestStores.TEST_SERVER_URL)
            val invitation = Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(
                "chronicle://enroll?studyId=${preview.manifest.studyId}&participantId=${preview.manifest.participantId}" +
                    "&serverUrl=https%3A%2F%2Flocalhost#accessCode=${"A".repeat(64)}"))
            val controller = Robolectric.buildActivity(Enrollment::class.java, invitation).setup()
            try {
                val enrollment = controller.get()
                waitUntil { enrollment.findViewById<View>(R.id.button).isEnabled }
                assertTrue(enrollment.findViewById<View>(R.id.button).performClick())
                waitUntil { shadowOf(enrollment).peekNextStartedActivityForResult() != null }
                acceptDisclosure(enrollment)
                waitUntil { shadowOf(enrollment).peekNextStartedActivityForResult() != null }
                val wizard = shadowOf(enrollment).nextStartedActivityForResult.intent
                assertEquals(CollectionOrientationActivity::class.java.name, wizard.component!!.className)
                orientation(enrollment, wizard)
                shadowOf(Looper.getMainLooper()).idle()
                verify(db)
            } finally {
                controller.pause().stop().destroy()
            }
        }
        return db
    }

    private fun acceptDisclosure(enrollment: Enrollment) {
        val intent = shadowOf(enrollment).nextStartedActivityForResult.intent
        val controller = Robolectric.buildActivity(StudyDisclosureActivity::class.java, intent).setup()
        val disclosure = controller.get()
        disclosure.findViewById<MaterialCheckBox>(R.id.studyEligibilityAttestation).isChecked = true
        assertTrue(disclosure.findViewById<View>(R.id.acceptStudyDisclosureButton).performClick())
        shadowOf(enrollment).receiveResult(intent, shadowOf(disclosure).resultCode, shadowOf(disclosure).resultIntent)
        controller.pause().stop().destroy()
    }

    private fun shown(activity: CollectionOrientationActivity): CollectionModuleId {
        val title = activity.findViewById<TextView>(R.id.orientationTitle).text.toString()
        return consentSteps.single { title.startsWith(CollectionConsentCopy.label(context, it)) }
    }

    private fun decline(activity: CollectionOrientationActivity, confirm: Boolean) {
        assertTrue(activity.findViewById<View>(R.id.orientationDecline).performClick())
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(if (confirm) DialogInterface.BUTTON_POSITIVE else DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun accept(activity: CollectionOrientationActivity) =
        assertTrue(activity.findViewById<View>(R.id.orientationAccept).performClick())

    /** Walks the wizard: [decide] answers each shown module (true allow, false decline); returns the order shown. */
    private fun walk(controller: ActivityController<CollectionOrientationActivity>,
                     decide: (CollectionModuleId) -> Boolean): List<CollectionModuleId> {
        val activity = controller.get()
        val order = mutableListOf<CollectionModuleId>()
        while (!activity.isFinishing) {
            val module = shown(activity)
            order += module
            if (decide(module)) accept(activity) else decline(activity, confirm = true)
        }
        return order
    }

    private fun finishWizard(enrollment: Enrollment, wizard: Intent, activity: Activity) =
        shadowOf(enrollment).receiveResult(wizard, shadowOf(activity).resultCode, shadowOf(activity).resultIntent)

    private fun View.texts(): List<String> = when (this) {
        is TextView -> listOf(text.toString())
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).texts() }
        else -> emptyList()
    }

    /** The cancel branch ran: the invitation screen says nothing was enrolled, so no enroll call can still follow. */
    private fun awaitCancelled(enrollment: Enrollment) = waitUntil {
        context.getString(R.string.enrollment_cancelled_nothing_collected) in enrollment.window.decorView.texts()
    }

    private fun assertEnrolledWith(db: ChronicleDb, accepted: Set<CollectionModuleId>, declined: Set<CollectionModuleId>) {
        waitUntil {
            worker { db.uploadServerDao().getConfiguredServer()?.enrollmentSetupComplete == true }
        }
        assertEquals(1, enrollCalls)
        waitUntil { acks.isNotEmpty() }
        val ack = acks.first()
        assertEquals("accepted partition", accepted, ack.acknowledgedModules.filterTo(mutableSetOf()) { it in consentSteps })
        assertEquals("declined partition", declined, ack.declinedModules.filterTo(mutableSetOf()) { it in consentSteps })
        for (module in consentSteps) {
            assertEquals("$module collects only when accepted", module in accepted, ResearchPersistenceGate.collectsNow(context, module))
        }
    }

    private fun close(db: ChronicleDb) {
        worker { }
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        db.close()
    }

    /** Usage required, the rest optional: usage comes first, every optional module gets its own step, each decline holds. */
    @Test fun usageRequiredStudyShowsEveryPilotStepRequiredFirstAndEachOptionalDeclineHolds() {
        for (declinedOne in consentSteps - USAGE_EVENTS) {
            acks.clear(); enrollCalls = 0
            var order = emptyList<CollectionModuleId>()
            val db = journey(setOf(USAGE_EVENTS),
                verify = { assertEnrolledWith(it, consentSteps - declinedOne, setOf(declinedOne)) }) { enrollment, wizard ->
                val controller = Robolectric.buildActivity(CollectionOrientationActivity::class.java, wizard).setup()
                order = walk(controller) { it != declinedOne }
                finishWizard(enrollment, wizard, controller.get())
                assertEquals(Activity.RESULT_OK, shadowOf(controller.get()).resultCode)
                controller.pause().stop().destroy()
            }
            try {
                assertEquals("the required module comes first", USAGE_EVENTS, order.first())
                assertEquals("exactly the pilot consent modules, each once", consentSteps, order.toSet())
                assertEquals(consentSteps.size, order.size)
            } finally { close(db) }
        }
    }

    /** Declining usage asks first; "Go back" keeps the step, "Stop enrolling" ends it with nothing enrolled. */
    @Test fun aRequiredDeclineAsksThenStopsEnrollmentWithNothingEnrolled() {
        val db = journey(setOf(USAGE_EVENTS)) { enrollment, wizard ->
            val controller = Robolectric.buildActivity(CollectionOrientationActivity::class.java, wizard).setup()
            val activity = controller.get()
            assertEquals(USAGE_EVENTS, shown(activity))
            decline(activity, confirm = false)
            assertFalse("Go back keeps the participant on the step", activity.isFinishing)
            assertEquals(USAGE_EVENTS, shown(activity))
            decline(activity, confirm = true)
            assertTrue(activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
            finishWizard(enrollment, wizard, activity)
            awaitCancelled(enrollment)
            controller.pause().stop().destroy()
        }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("no enrollment request", 0, enrollCalls)
            assertNull(worker { db.uploadServerDao().getConfiguredServer() })
            assertTrue(acks.isEmpty())
            consentSteps.forEach { assertFalse(ResearchPersistenceGate.collectsNow(context, it)) }
        } finally { close(db) }
    }

    /** All required: any decline ends enrollment; accepting every step enrolls with all of them collecting. */
    @Test fun allRequiredStudyAcceptsEverythingOrEnrollsNothing() {
        for (declinedOne in consentSteps) {
            enrollCalls = 0
            val db = journey(consentSteps) { enrollment, wizard ->
                val controller = Robolectric.buildActivity(CollectionOrientationActivity::class.java, wizard).setup()
                walk(controller) { it != declinedOne }
                assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).resultCode)
                finishWizard(enrollment, wizard, controller.get())
                awaitCancelled(enrollment)
                controller.pause().stop().destroy()
            }
            try {
                assertEquals("$declinedOne declined: no enrollment request", 0, enrollCalls)
                assertNull(worker { db.uploadServerDao().getConfiguredServer() })
            } finally { close(db) }
        }
        acks.clear(); enrollCalls = 0
        var order = emptyList<CollectionModuleId>()
        val db = journey(consentSteps, verify = { assertEnrolledWith(it, consentSteps, emptySet()) }) { enrollment, wizard ->
            val controller = Robolectric.buildActivity(CollectionOrientationActivity::class.java, wizard).setup()
            order = walk(controller) { true }
            finishWizard(enrollment, wizard, controller.get())
            controller.pause().stop().destroy()
        }
        try {
            assertEquals(consentSteps, order.toSet())
        } finally { close(db) }
    }

    /** Back clears the previous decision; recreating the screen keeps the step and the decisions made so far. */
    @Test fun backAndRecreationKeepTheDecisionsConsistent() {
        var order = emptyList<CollectionModuleId>()
        val db = journey(setOf(USAGE_EVENTS), verify = { assertEnrolledWith(it, consentSteps, emptySet()) }) { enrollment, wizard ->
            var controller = Robolectric.buildActivity(CollectionOrientationActivity::class.java, wizard).setup()
            accept(controller.get())
            val second = shown(controller.get())
            decline(controller.get(), confirm = true)
            val third = shown(controller.get())
            controller.get().onBackPressedDispatcher.onBackPressed()
            assertEquals("back returns to the previous step", second, shown(controller.get()))
            controller = controller.recreate()
            assertEquals("recreation keeps the step", second, shown(controller.get()))
            accept(controller.get())
            assertEquals(third, shown(controller.get()))
            order = listOf(USAGE_EVENTS, second) + walk(controller) { true }
            finishWizard(enrollment, wizard, controller.get())
            controller.pause().stop().destroy()
        }
        try {
            assertEquals(consentSteps, order.toSet())
        } finally { close(db) }
    }

    /**
     * H-NOTIFICATION-HARD-GATE: notifications denied before consent and denied again at the dashboard's one ask. The
     * participant still enrolls, reaches the dashboard, is not asked again, and usage still collects.
     */
    @Test @Config(sdk = [34])
    fun deniedNotificationsBlockNeitherEnrollmentNorTheDashboardNorUsage() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val db = journey(setOf(USAGE_EVENTS), verify = { assertEnrolledWith(it, consentSteps, emptySet()) }) { enrollment, wizard ->
            val controller = Robolectric.buildActivity(CollectionOrientationActivity::class.java, wizard).setup()
            walk(controller) { true }
            finishWizard(enrollment, wizard, controller.get())
            controller.pause().stop().destroy()
        }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun rationale() = main.get().supportFragmentManager.findFragmentByTag(NotificationRationaleDialog.TAG) as? DialogFragment
            waitUntil { rationale()?.dialog?.isShowing == true }
            (rationale()!!.dialog as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val request = shadowOf(main.get()).lastRequestedPermission
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
            main.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent()
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, request.requestedPermissions)
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    intArrayOf(PackageManager.PERMISSION_DENIED)))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("a notification denial must not close the dashboard", main.get().isFinishing)
            main.pause().stop().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(main.get().findViewById<View>(R.id.mainBottomNav).isShown)
            assertNull("the dashboard asks once", rationale()?.takeIf { it.dialog?.isShowing == true })
            assertTrue(worker { ResearchPersistenceGate.collectsNow(context, USAGE_EVENTS) })
        } finally {
            main.pause().stop().destroy()
            close(db)
        }
    }
}
