package com.openlattice.chronicle.collection.state

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CollectionSettingsSyncBackgroundStartTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    @get:Rule val backgroundPersistence = BackgroundPersistenceRule()

    @Test
    fun sensorServiceStartDenialDoesNotThrowThroughCollectionSettingsSync() {
        assumeTrue(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sensorModule = CollectionModuleId.SENSOR_ACCELEROMETER
        AuditStores.install(app, enrolled = true,
            manifestOverrides = mapOf(sensorModule to CollectionModuleSetting(enabled = true)))
        shadowOf(app.getSystemService(SensorManager::class.java))
            .addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build())
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(app)
        var starts = 0
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): ComponentName? {
                // Consent survives the edit, so the identification monitor may start too; only sensor starts count.
                if (intent.component?.className == "com.openlattice.chronicle.services.sensors.HardwareSensorService") starts++
                throw ForegroundServiceStartNotAllowedException("Test background start refusal")
            }
        }
        val db = ChronicleDb.getInstance(app)
        try {
            assertFalse(DistributionRestrictedRuntime.tryStartHardwareSensors(context))
            assertEquals(1, starts)
            val server = requireNotNull(db.uploadServerDao().getConfiguredServer())
            val manifest = requireNotNull(ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java)
                .fromJson(requireNotNull(server.studyDisclosureJson)))
            val store = CollectionLoopStore.of(app)
            val fetched = manifest.collectionSettings.copy(settingsVersion = manifest.collectionSettings.settingsVersion + 1)
            val coordinator = CollectionLoopCoordinator(context)
            assertTrue(coordinator.anySensorCollects())
            assertEquals(SettingsApplicationOutcome.APPLIED,
                coordinator.applyFetchedSettings(server, ResearchErasureFence(app).settingsGeneration(), fetched))
            assertEquals(2, starts)
            val state = requireNotNull(store.loadAll()[sensorModule])
            assertEquals(fetched.settingsVersion, state.appliedVersion)
            assertTrue(state.collectsWhenEnrolled)
            assertTrue(coordinator.anySensorCollects())
        } finally {
            val drained = CompletableFuture<Unit>()
            ResearchPersistenceGate.executeAsync { drained.complete(Unit) }
            drained.get(10, TimeUnit.SECONDS)
            db.close()
            EnrollmentTestStores.db = null
            EnrollmentTestStores.prefs = null
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        }
    }
}
