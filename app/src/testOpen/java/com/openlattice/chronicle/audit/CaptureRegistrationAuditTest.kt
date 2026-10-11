package com.openlattice.chronicle.audit

import android.content.Context
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.activity.SleepActivityCaptureController
import com.openlattice.chronicle.collection.capability.CollectionCapabilityResolver
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.ChronicleDb
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@Suppress("DEPRECATION")
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner::class)
class CaptureRegistrationAuditTest {
    @get:org.junit.Rule val persistence = BackgroundPersistenceRule()
    @Test fun failedRegistrationOpensOneAccessEpisodeAndRecoveryClosesIt() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        AuditStores.install(context,true,mapOf(CollectionModuleId.SLEEP to CollectionModuleSetting(true),
            CollectionModuleId.ACTIVITY_RECOGNITION to CollectionModuleSetting(true)))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery();ResearchPersistenceGate.initialize(context)
        shadowOf(context as Application).grantPermissions("android.permission.ACTIVITY_RECOGNITION")
        var registrationTask = Tasks.forException<Void>(java.io.IOException("unique-private-gms-sentinel"))
        fun register() = SleepActivityCaptureController.ensureRegistration(context, { true }) { module, pending ->
            val intent = shadowOf(pending).savedIntent
            assertEquals(module.id, intent.getStringExtra(com.openlattice.chronicle.collection.activity.ACTIVITY_REGISTRATION_MODULE_EXTRA))
            val scope = intent.getStringExtra(com.openlattice.chronicle.collection.activity.ACTIVITY_REGISTRATION_SCOPE_EXTRA)
            assertFalse(scope.isNullOrBlank())
            assertEquals(context.getSharedPreferences(com.openlattice.chronicle.collection.activity.ACTIVITY_REGISTRATION_PREFS,
                Context.MODE_PRIVATE).getString(module.id, null), scope)
            registrationTask
        }
        fun losses():Int=ChronicleDb.getInstance(context).openHelper.readableDatabase.query(
            "SELECT coalesce(sum(count),0) FROM upload_diagnostics WHERE issueCode='COLLECTION_ACCESS_MISSING' AND moduleFamily IN " +
            "('SLEEP','ACTIVITY_RECOGNITION')").use{it.moveToFirst();it.getInt(0)}
        run {
            repeat(2){register();shadowOf(Looper.getMainLooper()).idle()}
            repeat(100) { if (losses() < 2) Thread.sleep(20) }
            assertEquals(2,losses())
            val failed=CollectionCapabilityResolver.resolve(CollectionModuleId.SLEEP,CollectionCapabilityResolver.snapshot(context).copy(
                googleServicesAvailable=true))
            assertFalse(failed.canCollectNow);assertTrue(failed.message.contains("retry",ignoreCase=true))
            registrationTask = Tasks.forResult<Void>(null)
            register();shadowOf(Looper.getMainLooper()).idle()
            val flushed = java.util.concurrent.CountDownLatch(1)
            ResearchPersistenceGate.executeAsync { flushed.countDown() }
            assertTrue(flushed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val recovered=CollectionCapabilityResolver.resolve(CollectionModuleId.SLEEP,CollectionCapabilityResolver.snapshot(context).copy(
                googleServicesAvailable=true))
            assertTrue(recovered.canCollectNow);assertEquals(2,losses())
            registrationTask = Tasks.forException<Void>(java.io.IOException("synthetic"))
            register();shadowOf(Looper.getMainLooper()).idle()
            repeat(100) { if (losses() < 4) Thread.sleep(20) }
            assertEquals(4,losses())
        }
    }

    @Test fun unavailablePlayServicesReportsBothEnabledCaptureModules() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AuditStores.install(context, true, mapOf(
            CollectionModuleId.SLEEP to CollectionModuleSetting(true),
            CollectionModuleId.ACTIVITY_RECOGNITION to CollectionModuleSetting(true),
        ))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery(); ResearchPersistenceGate.initialize(context)
        repeat(2) { SleepActivityCaptureController.ensureRegistration(context, { false }) { _, _ -> error("registration must not run") } }
        val db = ChronicleDb.getInstance(context)
        db.openHelper.readableDatabase.query(
            "SELECT moduleFamily,sum(count),studyId,participantId FROM upload_diagnostics " +
                "WHERE issueCode='COLLECTION_ACCESS_MISSING' GROUP BY moduleFamily",
        ).use {
            assertEquals(2, it.count)
            while (it.moveToNext()) {
                assertTrue(it.getString(0) in setOf("SLEEP", "ACTIVITY_RECOGNITION"))
                assertEquals(1, it.getInt(1)); assertNotNull(it.getString(2)); assertNotNull(it.getString(3))
            }
        }
    }
}
