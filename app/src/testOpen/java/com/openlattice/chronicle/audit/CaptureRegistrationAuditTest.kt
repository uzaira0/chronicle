package com.openlattice.chronicle.audit

import android.content.Context
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.activity.SleepActivityCaptureController
import com.openlattice.chronicle.collection.capability.CollectionCapabilityResolver
import com.openlattice.chronicle.collection.state.*
import com.openlattice.chronicle.storage.ChronicleDb
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.*
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
        AuditStores.install(context,true,mapOf(CollectionModuleId.SLEEP to CollectionModuleSetting(true),CollectionModuleId.ACTIVITY_RECOGNITION to CollectionModuleSetting(true)))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery();ResearchPersistenceGate.initialize(context)
        shadowOf(context as Application).grantPermissions("android.permission.ACTIVITY_RECOGNITION")
        SleepActivityCaptureController.availabilityOverride={true}
        SleepActivityCaptureController.registrationOverride={_,_->Tasks.forException<Void>(java.io.IOException("unique-private-gms-sentinel"))}
        fun losses():Int=ChronicleDb.getInstance(context).openHelper.readableDatabase.query("SELECT coalesce(sum(count),0) FROM upload_diagnostics WHERE issueCode='COLLECTION_ACCESS_MISSING' AND moduleFamily IN ('SLEEP','ACTIVITY_RECOGNITION')").use{it.moveToFirst();it.getInt(0)}
        try {
            repeat(2){SleepActivityCaptureController.ensureRegistration(context);shadowOf(Looper.getMainLooper()).idle()}
            repeat(100) { if (losses() < 2) Thread.sleep(20) }
            assertEquals(2,losses())
            val failed=CollectionCapabilityResolver.resolve(CollectionModuleId.SLEEP,CollectionCapabilityResolver.snapshot(context).copy(googleServicesAvailable=true))
            assertFalse(failed.canCollectNow);assertTrue(failed.message.contains("retry",ignoreCase=true))
            SleepActivityCaptureController.registrationOverride={_,_->Tasks.forResult<Void>(null)}
            SleepActivityCaptureController.ensureRegistration(context);shadowOf(Looper.getMainLooper()).idle()
            val flushed = java.util.concurrent.CountDownLatch(1)
            ResearchPersistenceGate.executeAsync { flushed.countDown() }
            assertTrue(flushed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val recovered=CollectionCapabilityResolver.resolve(CollectionModuleId.SLEEP,CollectionCapabilityResolver.snapshot(context).copy(googleServicesAvailable=true))
            assertTrue(recovered.canCollectNow);assertEquals(2,losses())
            SleepActivityCaptureController.registrationOverride={_,_->Tasks.forException<Void>(java.io.IOException("synthetic"))}
            SleepActivityCaptureController.ensureRegistration(context);shadowOf(Looper.getMainLooper()).idle()
            repeat(100) { if (losses() < 4) Thread.sleep(20) }
            assertEquals(4,losses())
        } finally {SleepActivityCaptureController.availabilityOverride=null;SleepActivityCaptureController.registrationOverride=null}
    }
}
