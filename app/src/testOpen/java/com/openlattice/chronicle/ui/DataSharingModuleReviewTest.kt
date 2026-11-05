package com.openlattice.chronicle.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.descendants
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.openlattice.chronicle.R
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.withStudyApi
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.state.CollectionConsentCopy
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.CollectionOrientationActivity
import com.openlattice.chronicle.collection.state.ConsentPlan
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.SensorBuilder

@RunWith(RobolectricTestRunner::class)
class DataSharingModuleReviewTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val module = CollectionModuleId.DEVICE_SETTINGS

    @Test fun requiredAcceptanceSurvivesHostRecreation() = restoredReview(processDeath = false)

    @Test fun requiredAcceptanceSurvivesProcessDeath() = restoredReview(processDeath = true)

    @Test fun requiredAcceptanceRejectsChangedSettingsAfterProcessDeath() = restoredReview(processDeath = true, stale = true)

    @Test fun requiredAcceptanceKeepsTheRestoredFragmentWhenItsTabIsSelectedAgain() {
        AuditStores.install(context, true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val host = controller.get()
        waitUntil { host.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null }
        host.selectTab(R.id.nav_data_sharing)
        host.supportFragmentManager.executePendingTransactions()
        val fragment = host.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) as DataSharingFragment
        try {
            host.selectTab(R.id.nav_data_sharing)
            host.supportFragmentManager.executePendingTransactions()
            assertSame("Restored tab selection must retain the result owner", fragment,
                host.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer))
        } finally {
            finishReview(controller, host.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) as DataSharingFragment)
        }
    }

    private fun restoredReview(processDeath: Boolean, stale: Boolean = false) {
        val module = CollectionModuleId.BATTERY_TELEMETRY
        AuditStores.install(context, true)
        val db = ChronicleDb.getInstance(context)
        db.collectionModuleStateDao().upsert(db.collectionModuleStateDao().get(module.id)!!.copy(
            requiredApplied = true, decision = ParticipantDecision.DECLINED.name))
        onPersistenceWorker {
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            ResearchPersistenceGate.initialize(context)
        }
        var controller = Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
        var host = controller.get()
        var fragment = DataSharingFragment()
        host.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start().resume().visible()
        val acknowledgments = AtomicInteger()
        withStudyApi(onCall = { check(it == ChronicleStudyApi::reportCollectionAck.name); acknowledgments.incrementAndGet(); OK() }) {
            try {
                requestReview(host, fragment, required = true, moduleId = module)
                val review = shadowOf(host).nextStartedActivityForResult
                if (processDeath) {
                    val state = Bundle()
                    controller.pause().saveInstanceState(state).stop().destroy()
                    controller = Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java)
                        .create(state).start().restoreInstanceState(state).resume().visible()
                } else {
                    controller.recreate()
                }
                host = controller.get()
                fragment = host.supportFragmentManager.findFragmentById(android.R.id.content) as DataSharingFragment
                if (stale) {
                    val row = db.collectionModuleStateDao().get(module.id)!!
                    db.collectionModuleStateDao().upsert(row.copy(appliedVersion = row.appliedVersion + 1))
                }
                host.activityResultRegistry.dispatchResult(review.requestCode, Activity.RESULT_OK, Intent()
                    .putStringArrayListExtra(CollectionOrientationActivity.EXTRA_ACCEPTED_IDS, arrayListOf(module.id)))
                if (stale) {
                    waitUntil { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == context.getString(R.string.ds_policy_changed) }
                    assertFalse(CollectionLoopStore.of(context).collects(module))
                    assertEquals(0, acknowledgments.get())
                } else {
                    waitUntil { acknowledgments.get() > 0 && CollectionLoopStore.of(context).collects(module) }
                }
            } finally { finishReview(controller, fragment) }
        }
    }

    @Test fun requiredAcceptanceRetainsEarlierWizardDecisionsOnProcessDeath() {
        AuditStores.install(context, false)
        val intent = CollectionOrientationActivity.intent(context,
            ConsentPlan(
                required = listOf(CollectionModuleId.USAGE_EVENTS), optional = listOf(CollectionModuleId.BATTERY_TELEMETRY)))
        val controller = Robolectric.buildActivity(CollectionOrientationActivity::class.java, intent).setup()
        controller.get().findViewById<View>(R.id.orientationAccept).performClick()
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        val restored = Robolectric.buildActivity(CollectionOrientationActivity::class.java, intent)
            .create(saved).start().restoreInstanceState(saved).resume().visible()
        try {
            assertTrue(restored.get().findViewById<TextView>(R.id.orientationStep).text.startsWith("2 of 2 · "))
            restored.get().findViewById<View>(R.id.orientationAccept).performClick()
            assertEquals(setOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.BATTERY_TELEMETRY),
                CollectionOrientationActivity.acceptedFrom(shadowOf(restored.get()).resultIntent))
        } finally { restored.pause().stop().destroy() }
    }

    @Test fun requiredAndOptionalAcceptanceWaitForDisclosureAndCancellationDoesNotCollect() {
        for (required in listOf(true, false)) {
            AuditStores.install(context, true)
            val db = ChronicleDb.getInstance(context)
            db.collectionModuleStateDao().upsert(db.collectionModuleStateDao().get(module.id)!!.copy(
                requiredApplied = required, decision = ParticipantDecision.UNDECIDED.name, decidedAtEpochMillis = null))
            onPersistenceWorker {
                ResearchPersistenceGate.resetAfterLocalStoreRecovery()
                ResearchPersistenceGate.initialize(context)
            }
            val controller = Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
            val host = controller.get()
            val fragment = DataSharingFragment()
            host.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
            controller.start().resume().visible()
            val acknowledgments = AtomicInteger()
            withStudyApi(onCall = { method ->
                check(method == ChronicleStudyApi::reportCollectionAck.name)
                acknowledgments.incrementAndGet()
                OK()
            }) {
                try {
                    requestReview(host, fragment, required)
                    val canceled = shadowOf(host).nextStartedActivityForResult.intent
                    assertEquals(CollectionOrientationActivity::class.java.name, canceled.component!!.className)
                    assertFalse(CollectionLoopStore.of(context).collects(module))
                    shadowOf(host).receiveResult(canceled, Activity.RESULT_CANCELED, null)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(CollectionLoopStore.of(context).collects(module))

                    requestReview(host, fragment, required)
                    val intent = shadowOf(host).nextStartedActivityForResult.intent
                    val orientationController = Robolectric.buildActivity(CollectionOrientationActivity::class.java, intent).setup()
                    val orientation = orientationController.get()
                    try {
                        val collects = orientation.findViewById<TextView>(R.id.orientationCollects).text.toString()
                        assertTrue(collects.contains("storage", ignoreCase = true))
                        assertTrue(collects.contains("location services", ignoreCase = true))
                        assertTrue(orientation.findViewById<TextView>(R.id.orientationNotCollect).text.isNotBlank())
                        assertFalse(CollectionLoopStore.of(context).collects(module))
                        assertTrue(orientation.findViewById<View>(R.id.orientationAccept).performClick())
                        shadowOf(host).receiveResult(intent, shadowOf(orientation).resultCode, shadowOf(orientation).resultIntent)
                        waitUntil { acknowledgments.get() > 0 && CollectionLoopStore.of(context).collects(module) }
                    } finally { orientationController.pause().stop().destroy() }
                } finally { finishReview(controller, fragment) }
            }
        }
    }

    private fun requestReview(host: AppCompatActivity, fragment: DataSharingFragment, required: Boolean,
        moduleId: CollectionModuleId = module) {
        val view = fragment.requireView()
        if (required) {
            val list = view.findViewById<LinearLayout>(R.id.pendingRequiredList)
            waitUntil { list.childCount > 0 }
            list.descendants.filterIsInstance<MaterialButton>()
                .single { it.text == context.getString(R.string.ds_accept) }.performClick()
        } else {
            val list = view.findViewById<LinearLayout>(R.id.appUsageModuleList)
            waitUntil { list.childCount > 0 }
            val row = (0 until list.childCount).map { list.getChildAt(it) as LinearLayout }.first {
                (it.getChildAt(0) as TextView).text.contains(
                    CollectionConsentCopy.label(context, moduleId), ignoreCase = true)
            }
            val switch = row.getChildAt(1) as MaterialSwitch
            assertTrue(switch.isEnabled)
            assertFalse(switch.isChecked)
            switch.performClick()
        }
        waitUntil { shadowOf(host).peekNextStartedActivityForResult() != null }
    }

    private fun finishReview(controller: ActivityController<out AppCompatActivity>, fragment: DataSharingFragment) {
        val work = fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job]!!
        val decisions = ViewModelProvider(fragment)[ModuleDisclosureReview::class.java].viewModelScope.coroutineContext[Job]!!
        controller.pause()
        // A saved decision or acknowledgment can precede the coroutine's final snapshot read.
        // Keep its database and API installed until that work and the refresh job have finished.
        waitUntil { work.children.none() && decisions.children.none() }
        controller.stop().destroy()
        ChronicleDb.getInstance(context).close()
    }

    @Test fun turningASensorBackOnWaitsForItsDisclosure() {
        val sensor = CollectionModuleId.SENSOR_ACCELEROMETER
        AuditStores.install(context, true, mapOf(sensor to CollectionModuleSetting(enabled = true)))
        shadowOf(context.getSystemService(SensorManager::class.java))
            .addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build())
        val db = ChronicleDb.getInstance(context)
        db.collectionModuleStateDao().upsert(db.collectionModuleStateDao().get(module.id)!!.copy(
            moduleId = sensor.id, requiredApplied = false, decision = ParticipantDecision.DECLINED.name))
        onPersistenceWorker {
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            ResearchPersistenceGate.initialize(context)
        }
        val controller = Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
        val host = controller.get()
        val fragment = DataSharingFragment()
        host.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start().resume().visible()
        try {
            val list = fragment.requireView().findViewById<LinearLayout>(R.id.sensorToggleList)
            waitUntil { list.childCount > 0 }
            val row = (0 until list.childCount).map { list.getChildAt(it) as LinearLayout }.first {
                (it.getChildAt(0) as TextView).text.contains("Accelerometer", ignoreCase = true)
            }
            val switch = row.getChildAt(1) as MaterialSwitch
            assertTrue(switch.isEnabled)
            assertFalse(switch.isChecked)
            switch.performClick()
            waitUntil { shadowOf(host).peekNextStartedActivityForResult() != null }
            val review = shadowOf(host).nextStartedActivityForResult.intent
            assertEquals(CollectionOrientationActivity::class.java.name, review.component!!.className)
            assertFalse(CollectionLoopStore.of(context).collects(sensor))
            shadowOf(host).receiveResult(review, Activity.RESULT_CANCELED, null)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(CollectionLoopStore.of(context).collects(sensor))
        } finally { finishReview(controller, fragment) }
    }

    @Test fun dontAllowInTheDisclosureRecordsTheDecline() {
        AuditStores.install(context, true)
        val db = ChronicleDb.getInstance(context)
        db.collectionModuleStateDao().upsert(db.collectionModuleStateDao().get(module.id)!!.copy(
            requiredApplied = false, decision = ParticipantDecision.UNDECIDED.name, decidedAtEpochMillis = null))
        onPersistenceWorker {
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            ResearchPersistenceGate.initialize(context)
        }
        val controller = Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
        val host = controller.get()
        val fragment = DataSharingFragment()
        host.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start().resume().visible()
        withStudyApi(onCall = { OK() }) {
            try {
                val list = fragment.requireView().findViewById<LinearLayout>(R.id.appUsageModuleList)
                waitUntil { list.childCount > 0 }
                val row = (0 until list.childCount).map { list.getChildAt(it) as LinearLayout }.first {
                    (it.getChildAt(0) as TextView).text.contains("Device settings", ignoreCase = true)
                }
                (row.getChildAt(1) as MaterialSwitch).performClick()
                waitUntil { shadowOf(host).peekNextStartedActivityForResult() != null }
                val review = shadowOf(host).nextStartedActivityForResult.intent
                shadowOf(host).receiveResult(review, Activity.RESULT_OK, Intent()
                    .putStringArrayListExtra(CollectionOrientationActivity.EXTRA_ACCEPTED_IDS, arrayListOf())
                    .putStringArrayListExtra(CollectionOrientationActivity.EXTRA_DECLINED_IDS, arrayListOf(module.id)))
                waitUntil {
                    onPersistenceWorker { db.collectionModuleStateDao().get(module.id)!!.decision } == ParticipantDecision.DECLINED.name
                }
                assertFalse(CollectionLoopStore.of(context).collects(module))
            } finally { finishReview(controller, fragment) }
        }
    }

    /**
     * H-CONSENT-DEFER-TRAP: an optional module declined in its disclosure stays reachable after the participant leaves
     * and the app restarts. Its switch opens the disclosure again, and accepting there turns on exactly that module, with
     * the acknowledgment sent to the server.
     */
    @Test fun aDeclinedOptionalModuleCanBeReviewedAndAcceptedAfterARestart() {
        AuditStores.install(context, true)
        val db = ChronicleDb.getInstance(context)
        db.collectionModuleStateDao().upsert(db.collectionModuleStateDao().get(module.id)!!.copy(
            requiredApplied = false, decision = ParticipantDecision.DECLINED.name))
        onPersistenceWorker {
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            ResearchPersistenceGate.initialize(context)
        }
        val others = onPersistenceWorker { db.collectionModuleStateDao().getAll().filter { it.moduleId != module.id } }
        val controller = Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
        val host = controller.get()
        val fragment = DataSharingFragment()
        host.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start().resume().visible()
        val acknowledgments = AtomicInteger()
        withStudyApi(onCall = { check(it == ChronicleStudyApi::reportCollectionAck.name); acknowledgments.incrementAndGet(); OK() }) {
            try {
                requestReview(host, fragment, required = false)
                val intent = shadowOf(host).nextStartedActivityForResult.intent
                val orientationController = Robolectric.buildActivity(CollectionOrientationActivity::class.java, intent).setup()
                try {
                    val orientation = orientationController.get()
                    assertTrue(orientation.findViewById<TextView>(R.id.orientationCollects).text.isNotBlank())
                    assertTrue(orientation.findViewById<View>(R.id.orientationAccept).performClick())
                    shadowOf(host).receiveResult(intent, shadowOf(orientation).resultCode, shadowOf(orientation).resultIntent)
                    waitUntil { acknowledgments.get() > 0 && CollectionLoopStore.of(context).collects(module) }
                } finally { orientationController.pause().stop().destroy() }
                assertEquals("only the reviewed module changes", others,
                    onPersistenceWorker { db.collectionModuleStateDao().getAll().filter { it.moduleId != module.id } })
            } finally { finishReview(controller, fragment) }
        }
    }

    /** A Pilot participant turns battery telemetry off: it stops at once, the decline is acknowledged, usage goes on. */
    @Test fun turningAnAcceptedOptionalModuleOffStopsItAndKeepsTheRest() {
        val battery = CollectionModuleId.BATTERY_TELEMETRY
        AuditStores.install(context, true)
        val db = ChronicleDb.getInstance(context)
        db.collectionModuleStateDao().upsert(db.collectionModuleStateDao().get(battery.id)!!.copy(
            requiredApplied = false, decision = ParticipantDecision.ACCEPTED.name))
        onPersistenceWorker {
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            ResearchPersistenceGate.initialize(context)
        }
        assertTrue(CollectionLoopStore.of(context).collects(battery))
        val (controller, fragment) = dataSharing()
        val acknowledgments = AtomicInteger()
        withStudyApi(onCall = { check(it == ChronicleStudyApi::reportCollectionAck.name); acknowledgments.incrementAndGet(); OK() }) {
            try {
                val list = fragment.requireView().findViewById<LinearLayout>(R.id.appUsageModuleList)
                waitUntil { list.childCount > 0 }
                val switch = (0 until list.childCount).map { list.getChildAt(it) as LinearLayout }.first {
                    (it.getChildAt(0) as TextView).text.contains(
                        CollectionConsentCopy.label(context, battery), ignoreCase = true)
                }.getChildAt(1) as MaterialSwitch
                assertTrue(switch.isChecked)
                switch.performClick()
                waitUntil { acknowledgments.get() > 0 && !CollectionLoopStore.of(context).collects(battery) }
                assertEquals(ParticipantDecision.DECLINED.name,
                    onPersistenceWorker { db.collectionModuleStateDao().get(battery.id)!!.decision })
                assertTrue(CollectionLoopStore.of(context).collects(CollectionModuleId.USAGE_EVENTS))
            } finally { finishReview(controller, fragment) }
        }
    }

    /** A study adds a required module and the participant declines it: every module pauses, as the button says. */
    @Test fun decliningANewlyRequiredModulePausesAllCollection() {
        val battery = CollectionModuleId.BATTERY_TELEMETRY
        AuditStores.install(context, true)
        val db = ChronicleDb.getInstance(context)
        db.collectionModuleStateDao().upsert(db.collectionModuleStateDao().get(battery.id)!!.copy(
            requiredApplied = true, decision = ParticipantDecision.UNDECIDED.name, decidedAtEpochMillis = null))
        onPersistenceWorker {
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            ResearchPersistenceGate.initialize(context)
        }
        assertTrue(CollectionLoopStore.of(context).collects(CollectionModuleId.USAGE_EVENTS))
        val (controller, fragment) = dataSharing()
        withStudyApi(onCall = { check(it == ChronicleStudyApi::reportCollectionAck.name); OK() }) {
            try {
                val list = fragment.requireView().findViewById<LinearLayout>(R.id.pendingRequiredList)
                waitUntil { list.childCount > 0 }
                list.descendants.filterIsInstance<MaterialButton>()
                    .single { it.text == context.getString(R.string.ds_decline) }.performClick()
                waitUntil {
                    onPersistenceWorker { db.collectionModuleStateDao().get(battery.id)!!.decision } == ParticipantDecision.DECLINED.name
                }
                assertFalse(CollectionLoopStore.of(context).collects(CollectionModuleId.USAGE_EVENTS))
            } finally { finishReview(controller, fragment) }
        }
    }

    private fun dataSharing(): Pair<ActivityController<DataSharingRefreshAuditTest.Host>, DataSharingFragment> {
        val controller = Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
        val fragment = DataSharingFragment()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start().resume().visible()
        return controller to fragment
    }

    @Test fun aDisclosureForAnOlderPolicyCannotAuthorizeChangedSettings() = onPersistenceWorker {
        AuditStores.install(context, true)
        val db = ChronicleDb.getInstance(context)
        val old = db.collectionModuleStateDao().get(module.id)!!.copy(decision = ParticipantDecision.UNDECIDED.name)
        db.collectionModuleStateDao().upsert(old)
        val reviewed = CollectionLoopStore.of(context).loadAll().getValue(module)
        db.collectionModuleStateDao().upsert(old.copy(appliedVersion = old.appliedVersion + 1))
        assertFalse(CollectionLoopCoordinator(context).applyReviewedModuleAcceptance(reviewed,
            db.uploadServerDao().getConfiguredServer()!!, ConsentTrigger.SETTINGS_CHANGE))
        assertFalse(CollectionLoopStore.of(context).collects(module))
        db.close()
    }

    private fun waitUntil(condition: () -> Boolean) = awaitDashboardUi(condition = condition)
}
