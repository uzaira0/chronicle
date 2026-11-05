package com.openlattice.chronicle.services.lifecycle

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.lifecycle.LIFECYCLE_DEDUPE_WINDOW_MS
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.IDENTIFY_USER
import com.openlattice.chronicle.receivers.lifecycle.StartOnBoot
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.deviceMonitoringMayRun
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.ui.awaitDashboardUi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Device state (battery and charging, Battery Saver, network, low memory) is sampled by [DeviceStateSampler] at boot,
 * when Chronicle is opened and on each periodic sync; no foreground service is kept running for it. The unlock
 * monitor runs only for user identification: with device state collected and identification off, nothing starts it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], shadows = [EnrollmentTestStores.LifecycleModules::class])
class DeviceStateSamplingTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val activityManager = app.getSystemService(ActivityManager::class.java)

    @Before fun setUp() = onPersistenceWorker {
        AuditStores.install(app, enrolled = true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(app)
    }

    @After fun tearDown() = onPersistenceWorker {
        ChronicleDb.getInstance(app).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    @Test fun deviceStateWithIdentificationOffRunsNoUnlockMonitor() {
        onPersistenceWorker { EncryptedPrefsHelper.getEncryptedPrefs(app).edit().putBoolean(IDENTIFY_USER, false).commit() }
        assertFalse("device state alone must not keep a foreground service", onPersistenceWorker { deviceMonitoringMayRun(app) })
        generateSequence { shadowOf(app).nextStartedService }.toList()
        onPersistenceWorker { DeviceUnlockMonitoringService.reconcile(app) }
        assertNull("no unlock monitor start", shadowOf(app).nextStartedService)
        // A sticky restart Android delivers anyway stops at once and registers nothing.
        val controller = Robolectric.buildService(DeviceUnlockMonitoringService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            awaitDashboardUi(attempts = 1000) { shadowOf(controller.get()).isStoppedBySelf }
            assertTrue(shadowOf(app).registeredReceivers.none { it.broadcastReceiver.javaClass.`package`?.name ==
                "com.openlattice.chronicle.receivers.lifecycle" })
        } finally { controller.destroy() }
    }

    @Test fun bootRecordsTheCurrentDeviceState() {
        batterySaverOn()
        StartOnBoot().runBootTask(app) {}
        awaitLifecycleWrites()
        val classes = lifecycleEvents().mapNotNull { it.activityClass }
        assertTrue(classes.toString(), "power-save:on" in classes)
        assertTrue(classes.toString(), classes.any { it.startsWith("network:") })
    }

    @Test fun openingChronicleRecordsTheCurrentDeviceState() {
        batterySaverOn()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            awaitDashboardUi(attempts = 1000) {
                awaitLifecycleWrites()
                lifecycleEvents().any { it.activityClass == "power-save:on" }
            }
        } finally { controller.pause().stop().destroy() }
    }

    /** Low memory is a sampled state too: one row when it starts, none while it lasts, a new one the next time. */
    @Test fun lowMemoryIsRecordedOncePerEpisode() {
        fun sample(low: Boolean) {
            shadowOf(activityManager).setMemoryInfo(ActivityManager.MemoryInfo().apply { lowMemory = low })
            DeviceLifecycleEventRecorder.recordObserved(app) { DeviceStateSampler(app).poll() }
            awaitLifecycleWrites()
            Thread.sleep(LIFECYCLE_DEDUPE_WINDOW_MS + 1)
        }
        listOf(true, true, false, true).forEach(::sample)
        assertEquals(listOf(LOW_MEMORY_SAMPLE, LOW_MEMORY_SAMPLE),
            lifecycleEvents().filter { it.interactionType == INTERACTION_LOW_MEMORY }.map { it.activityClass })
    }

    /** Battery rows follow the same sampler (LifecycleEventMapper.batteryStateEvent, covered by its own tests). */
    private fun batterySaverOn() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
    }

    private fun awaitLifecycleWrites() {
        // An observation can enqueue its persistence after the first callback.
        repeat(2) {
            val delivered = CountDownLatch(1)
            DeviceLifecycleEventRecorder.recordObserved(app) { delivered.countDown(); emptyList() }
            assertTrue("lifecycle observations reached persistence", delivered.await(10, TimeUnit.SECONDS))
        }
    }

    private fun lifecycleEvents() = ChronicleDb.getInstance(app).queueEntryData().getNextEntries(Int.MAX_VALUE)
        .flatMap { JsonSerializer.deserializeQueueEntry(it.data) }.filterIsInstance<ExtractedUsageEvent>()
}
