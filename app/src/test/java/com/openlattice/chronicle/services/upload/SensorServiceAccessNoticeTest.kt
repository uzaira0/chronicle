package com.openlattice.chronicle.services.upload

import android.app.ActivityManager
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val SENSOR_SERVICE_CLASS = "com.openlattice.chronicle.services.sensors.HardwareSensorService"
private const val ACCESS_NOTICE_ID = 47_004

/**
 * The "Action needed" access notice for the sensor family means the sensor foreground service cannot run. Right after
 * enrollment the accepted sensor module is stored a moment before its service start is requested, and the first sync
 * runs in between: that sync must start the service, not tell the participant collection is lost. A notice that was
 * right (Android refused the start) clears itself once the service runs again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SensorServiceAccessNoticeTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    @get:Rule val backgroundPersistence = BackgroundPersistenceRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private var refuseStarts = false
    private var sensorStarts = 0
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(intent: Intent): ComponentName? {
            if (intent.component?.className == SENSOR_SERVICE_CLASS) sensorStarts++
            if (refuseStarts) throw ForegroundServiceStartNotAllowedException("Test background start refusal")
            return intent.component
        }
    }

    @Before fun setUp() {
        assumeTrue(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)
        AuditStores.install(app, enrolled = true, manifestOverrides = mapOf(
            CollectionModuleId.SENSOR_ACCELEROMETER to CollectionModuleSetting(enabled = true)))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(app)
        assertTrue("fixture collects the accepted accelerometer", CollectionLoopCoordinator(app).anySensorCollects())
        clearAccessLossEpisodes(app)
    }

    @After fun tearDown() {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) return
        val drained = CompletableFuture<Unit>()
        ResearchPersistenceGate.executeAsync { drained.complete(Unit) }
        drained.get(10, TimeUnit.SECONDS)
        notifications.cancelAll()
        ChronicleDb.getInstance(app).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    private fun accessNotice() = shadowOf(notifications).getNotification(ACCESS_NOTICE_ID)

    private fun sensorServiceRuns(running: Boolean) {
        val service = ActivityManager.RunningServiceInfo().apply { service = ComponentName(app, SENSOR_SERVICE_CLASS) }
        shadowOf(app.getSystemService(ActivityManager::class.java)).setServices(if (running) listOf(service) else emptyList())
    }

    @Test fun aSyncBeforeTheFirstSensorStartStartsTheServiceAndPostsNoActionNeeded() {
        sensorServiceRuns(false)

        recordCollectionAccessLoss(context)

        assertNull("no 'Action needed' while the sensor service can run", accessNotice())
        assertEquals("the sync starts the accepted sensor service", 1, sensorStarts)
    }

    @Test fun aRefusedSensorRestartPostsTheNoticeAndItClearsOnceTheServiceRuns() {
        sensorServiceRuns(false)
        refuseStarts = true

        recordCollectionAccessLoss(context)

        val notice = checkNotNull(accessNotice()) { "a refused restart tells the participant to open Chronicle" }
        assertEquals(app.getString(R.string.notif_action_needed), notice.extras.getString("android.title"))

        // The participant opens Chronicle, which restarts the service; the next check finds it running.
        refuseStarts = false
        sensorServiceRuns(true)
        recordCollectionAccessLoss(context)

        assertNull("the notice clears once the sensor service runs again", accessNotice())
    }
}
