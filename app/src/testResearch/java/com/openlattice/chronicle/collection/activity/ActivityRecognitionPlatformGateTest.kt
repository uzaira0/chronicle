package com.openlattice.chronicle.collection.activity

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.ChronicleDb
import com.google.android.gms.tasks.Tasks
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActivityRecognitionPlatformGateTest {
    @get:org.junit.Rule val backgroundPersistence = BackgroundPersistenceRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() {
        AuditStores.install(app, true, mapOf(CollectionModuleId.SLEEP to CollectionModuleSetting(enabled = true),
            CollectionModuleId.ACTIVITY_RECOGNITION to CollectionModuleSetting(enabled = true)))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(app)
    }

    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ChronicleDb.getInstance(app).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
    }

    private fun registrations(): Map<CollectionModuleId, PendingIntent> {
        val requested = linkedMapOf<CollectionModuleId, PendingIntent>()
        SleepActivityCaptureController.ensureRegistration(app, { true }) { module, intent ->
            requested[module] = intent
            Tasks.forResult(null)
        }
        return requested
    }

    @Test @Config(sdk = [Build.VERSION_CODES.P, 29, 36])
    fun activityRequestsUseThePermissionForTheCurrentPlatform() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permission = if (Build.VERSION.SDK_INT < 29) ModulePermissions.GMS_ACTIVITY_RECOGNITION
            else ModulePermissions.ACTIVITY_RECOGNITION
        shadowOf(app).denyPermissions(permission)
        assertTrue(registrations().isEmpty())
        shadowOf(app).grantPermissions(permission)
        assertEquals(setOf(CollectionModuleId.SLEEP, CollectionModuleId.ACTIVITY_RECOGNITION), registrations().keys)
    }

    @Test @Config(sdk = [30, Build.VERSION_CODES.S, 36])
    fun mutableActivityCallbackIsExplicitAndUsesTheAndroid12Flag() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(ModulePermissions.ACTIVITY_RECOGNITION)
        val pending = registrations().getValue(CollectionModuleId.SLEEP)
        assertEquals(ComponentName(app, SleepActivityReceiver::class.java), shadowOf(pending).savedIntent.component)
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, shadowOf(pending).flags and PendingIntent.FLAG_MUTABLE != 0)
        assertFalse(shadowOf(pending).isImmutable)
    }
}
