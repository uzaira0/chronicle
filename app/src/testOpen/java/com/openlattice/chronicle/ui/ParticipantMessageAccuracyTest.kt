package com.openlattice.chronicle.ui

import com.openlattice.chronicle.preferences.IDENTIFY_USER
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.descendants
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.state.CollectionConsentCopy
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.SettingsApplicationOutcome
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.CollectionOrientationActivity
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.serialization.ChronicleJson
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.UserIdentificationActivity
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "en")
class ParticipantMessageAccuracyTest {
    /** A Pilot participant who declined the required usage module sees, on Data Sharing, why collection stopped and how to resume. */
    @Test fun haltedDashboardExplainsHowToResumeTheRequiredModule() {
        install(enrolled = true)
        onPersistenceWorker {
            val dao = ChronicleDb.getInstance(context).collectionModuleStateDao()
            dao.upsert(dao.get(CollectionModuleId.USAGE_EVENTS.id)!!.copy(requiredApplied = true,
                decision = ParticipantDecision.DECLINED.name))
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            ResearchPersistenceGate.initialize(context)
        }
        val controller = Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, DataSharingFragment()).commitNow()
        controller.start().resume().visible()
        try {
            val expected = context.getString(R.string.ds_status_paused_required)
            fun shown() = (controller.get().findViewById<View>(android.R.id.content) as android.view.ViewGroup).allTexts()
            awaitDashboardUi { shown().any { expected in it } }
            assertTrue(shown().toString(), shown().any { expected in it })
        } finally { controller.pause().stop().destroy() }
    }

    /**
     * a3-state-changes-6: the study makes an optional module the participant declined required. The next settings
     * revision halts the whole study at once, Data Sharing names the required module and how to resume, and its
     * Accept action opens that module's review.
     */
    @Test fun aDeclinedOptionalModuleMadeRequiredHaltsTheStudyAndExplainsHowToResume() {
        install(enrolled = true)
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val db = ChronicleDb.getInstance(context)
        db.collectionModuleStateDao().upsert(db.collectionModuleStateDao().get(module.id)!!.copy(decision = ParticipantDecision.DECLINED.name))
        val owner = db.uploadServerDao().getConfiguredServer()!!
        val settings = requireNotNull(ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java)
            .fromJson(requireNotNull(owner.studyDisclosureJson))).collectionSettings
        val required = settings.copy(settingsVersion = settings.settingsVersion + 1,
            modules = settings.effectiveModules().mapValues { (id, setting) -> if (id == module) setting.copy(required = true) else setting })
        assertEquals(SettingsApplicationOutcome.APPLIED, onPersistenceWorker {
            CollectionLoopCoordinator(context).applyFetchedSettings(owner, ResearchErasureFence(context).settingsGeneration(), required)
        })
        val store = CollectionLoopStore.of(context)
        for (id in listOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.DEVICE_LIFECYCLE, module)) {
            assertFalse("$id must stop collecting", store.collects(id))
        }
        val controller = Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, DataSharingFragment()).commitNow()
        controller.start().resume().visible()
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
            val expected = context.getString(R.string.ds_status_paused_required)
            awaitDashboardUi { root.allTexts().any { expected in it } }
            assertTrue(root.allTexts().toString(), root.allTexts().any { expected in it })
            val pending = controller.get().findViewById<ViewGroup>(R.id.pendingRequiredList)
            awaitDashboardUi { pending.childCount > 0 }
            val label = CollectionConsentCopy.label(context, module)
            assertTrue(pending.allTexts().toString(), pending.allTexts().any { label in it })
            assertTrue(pending.descendants.filterIsInstance<MaterialButton>()
                .single { it.text == context.getString(R.string.ds_accept) }.performClick())
            awaitDashboardUi { shadowOf(controller.get()).peekNextStartedActivityForResult() != null }
            assertEquals(CollectionOrientationActivity::class.java.name,
                shadowOf(controller.get()).nextStartedActivityForResult.intent.component!!.className)
        } finally { controller.pause().stop().destroy() }
    }

    private fun android.view.ViewGroup.allTexts(): List<String> = (0 until childCount).flatMap { index ->
        when (val child = getChildAt(index)) {
            is android.widget.TextView -> listOf(child.text.toString()).filter { child.isShown }
            is android.view.ViewGroup -> child.allTexts()
            else -> emptyList()
        }
    }
    @get:Rule val workManagerRule = WorkManagerRule()
    class Host : AppCompatActivity() {
        override fun onCreate(state: Bundle?) { setTheme(R.style.AppTheme); super.onCreate(state) }
    }
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun close() = onPersistenceWorker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        StorageAdmission.clear(context)
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ShadowToast.reset()
    }

    private fun install(enrolled: Boolean) {
        AuditStores.install(context, enrolled)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        StorageAdmission.clear(context)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        ShadowToast.reset()
    }

    private fun awaitToast(message: Int) = awaitDashboardUi {
        ShadowToast.getTextOfLatestToast() == context.getString(message)
    }

    @Test fun obsoleteIdentificationPromptExplainsThatIdentificationIsOff() {
        install(enrolled = true)
        EncryptedPrefsHelper.getEncryptedPrefs(context).edit().putBoolean(IDENTIFY_USER, false).commit()
        val controller = Robolectric.buildActivity(UserIdentificationActivity::class.java).setup()
        try {
            controller.get().findViewById<View>(R.id.participant_user_btn).performClick()
            awaitToast(R.string.user_identification_unavailable)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun settingsDeviceUserExplainsThatIdentificationIsOff() {
        install(enrolled = true)
        EncryptedPrefsHelper.getEncryptedPrefs(context).edit().putBoolean(IDENTIFY_USER, false).commit()
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val fragment = SettingsHomeFragment()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start()
        try {
            fragment.requireView().findViewById<View>(R.id.deviceUserParticipant).performClick()
            awaitToast(R.string.user_identification_unavailable)
        } finally { controller.destroy() }
    }

    private fun failTargetWrites() {
        install(enrolled = true)
        ChronicleDb.getInstance(context).openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_user_write BEFORE INSERT ON userQueue
            BEGIN SELECT RAISE(ABORT, 'device-user write failed'); END
        """.trimIndent())
    }

    @Test fun identificationPromptReportsAnActualPersistenceFailure() {
        failTargetWrites()
        val controller = Robolectric.buildActivity(UserIdentificationActivity::class.java).setup()
        try {
            controller.get().findViewById<View>(R.id.participant_user_btn).performClick()
            awaitToast(R.string.user_save_failed)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun settingsDeviceUserReportsAnActualPersistenceFailure() {
        failTargetWrites()
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val fragment = SettingsHomeFragment()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start()
        try {
            fragment.requireView().findViewById<View>(R.id.deviceUserParticipant).performClick()
            awaitToast(R.string.user_save_failed)
        } finally { controller.destroy() }
    }

    @Test fun identificationControlWithoutEnrollmentExplainsMissingEnrollment() =
        controlFailure(R.string.settings_control_needs_enrollment, enrolled = false)

    @Test fun identificationControlBlockedByStorageExplainsLowStorage() =
        controlFailure(R.string.settings_control_storage_paused, storagePaused = true)

    @Test fun identificationControlOutsideStudyScopePointsToDataSharing() =
        controlFailure(R.string.settings_identification_unavailable, removeStudyScope = true)

    @Test fun identificationControlReportsAPreferenceWriteFailure() =
        controlFailure(R.string.settings_identification_save_failed, failPreference = true)

    private fun controlFailure(
        message: Int,
        enrolled: Boolean = true,
        storagePaused: Boolean = false,
        removeStudyScope: Boolean = false,
        failPreference: Boolean = false,
    ) {
        install(enrolled = true)
        Utils.createNotificationChannel(context)
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val fragment = SettingsHomeFragment()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start().resume().visible()
        try {
            val toggle = fragment.requireView().findViewById<MaterialSwitch>(R.id.identifyUserSwitch)
            awaitDashboardUi { toggle.isShown && toggle.isEnabled && toggle.isChecked }
            if (!enrolled) onPersistenceWorker {
                val owner = checkNotNull(ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer())
                assertTrue(ResearchPersistenceGate.applyParticipationStatus(context, owner,
                    ResearchErasureFence(context).settingsGeneration(), ParticipationStatus.NOT_ENROLLED, emptyList()))
            }
            if (failPreference) {
                val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
                class FailingPreferences : SharedPreferences by prefs {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = prefs.edit()
                        class FailingEditor : SharedPreferences.Editor by editor {
                            private var fail = false
                            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                                fail = key == IDENTIFY_USER
                                editor.putBoolean(key, value)
                                return this
                            }
                            override fun commit(): Boolean = !fail && editor.commit()
                        }
                        return FailingEditor()
                    }
                }
                EnrollmentTestStores.prefs = FailingPreferences()
            }
            if (removeStudyScope) {
                ChronicleDb.getInstance(context).collectionModuleStateDao()
                    .get(CollectionModuleId.USER_IDENTIFICATION.id)!!.let {
                        ChronicleDb.getInstance(context).collectionModuleStateDao()
                            .upsertAll(listOf(it.copy(serverEnabled = false)))
                    }
            }
            if (storagePaused) {
                StorageAdmission.allowed(context, 0)
            }
            assertTrue(toggle.performClick())
            awaitToast(message)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun uploadQueuedAppearsOnlyAfterTheWorkManagerTransactionCompletes() = uploadEnqueue(fail = false)
    @Test fun uploadEnqueueFailureExplainsThatWorkWasNotQueued() = uploadEnqueue(fail = true)

    private fun uploadEnqueue(fail: Boolean) {
        install(enrolled = true)
        val hold = AtomicBoolean(false)
        val tasks = LinkedBlockingQueue<Runnable>()
        val release = {
            hold.set(false)
            onPersistenceWorker { while (true) (tasks.poll() ?: break).run() }
        }
        val manager = onPersistenceWorker {
            workManagerRule.install(Configuration.Builder().setExecutor(Executor { })
                .setTaskExecutor(Executor { if (hold.get()) tasks.add(it) else it.run() }).build())
        }
        hold.set(true)
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val fragment = UploadsFragment()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start()
        try {
            fragment.requireView().findViewById<View>(R.id.uploadNowButton).performClick()
            awaitDashboardUi { tasks.isNotEmpty() }
            assertTrue("enqueue must still be pending", tasks.isNotEmpty())
            assertNull(ShadowToast.getTextOfLatestToast())
            if (fail) manager.workDatabase.close()
            release()
            awaitToast(if (fail) R.string.upload_queue_failed else R.string.upload_queued)
        } finally {
            // WorkManagerRule's close joins WorkManager's coroutines, which dispatch on this executor.
            release()
            controller.destroy()
        }
    }
}
