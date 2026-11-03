package com.openlattice.chronicle

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.receivers.lifecycle.StartOnBoot
import com.openlattice.chronicle.services.sync.ChronicleSyncWorker
import com.openlattice.chronicle.services.enrollment.ENROLLMENT_REFRESH_WORK_NAME
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * Boot, app update and a manual clock change arrive as real broadcasts to the receiver the packaged manifest declares.
 * Each pilot schedule (an expected set written out in [EnrolledWorkBootTest.PILOT_PERIODIC_WORK], not read from
 * [EnrolledWork]) is live exactly once afterwards, at its cadence, however often the broadcasts repeat; the retired
 * split workers are gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BootBroadcastWorkTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val boot = StartOnBoot()
    private val work get() = WorkManager.getInstance(context)

    @Before fun enroll() {
        onPersistenceWorker {
            AuditStores.install(context, enrolled = true)
            ResearchPersistenceGate.initialize(context)
        }
        // Robolectric never sees the app's manifest receivers (see mergedMainManifest), so the packaged declaration is
        // checked against the three broadcasts and then registered exactly as declared.
        val receivers = mergedMainManifest().getElementsByTagName("receiver")
        val declared = (0 until receivers.length).map { receivers.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NS, "name") == StartOnBoot::class.java.name }
        val actions = declared.getElementsByTagName("action").let { list ->
            (0 until list.length).map { (list.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        }
        assertEquals(setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED),
            actions.toSet())
        assertEquals("true", declared.getAttributeNS(ANDROID_NS, "enabled"))
        assertEquals("true", declared.getAttributeNS(ANDROID_NS, "exported"))
        val permissions = mergedMainManifest().getElementsByTagName("uses-permission")
        assertTrue((0 until permissions.length).any {
            (permissions.item(it) as Element).getAttributeNS(ANDROID_NS, "name") == "android.permission.RECEIVE_BOOT_COMPLETED"
        })
        context.registerReceiver(boot, IntentFilter().apply { actions.forEach(::addAction) },
            Context.RECEIVER_EXPORTED)
    }

    @After fun close() {
        context.unregisterReceiver(boot)
        work.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
    }

    private fun live(name: String) = work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).filterNot { it.state.isFinished }

    private fun deliver(action: String) {
        context.sendBroadcast(Intent(action).setPackage(context.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        if (action != Intent.ACTION_TIME_CHANGED) {
            shadowOf(checkNotNull(shadowOf(boot).originalPendingResult)).future.get(10, TimeUnit.SECONDS)
        }
    }

    private fun assertOneLiveScheduleEach() {
        for ((name, minutes) in EnrolledWorkBootTest.PILOT_PERIODIC_WORK) {
            val infos = live(name)
            assertEquals("exactly one live $name", 1, infos.size)
            val periodicity = checkNotNull(infos.single().periodicityInfo) { "$name is periodic" }
            assertEquals(name, TimeUnit.MINUTES.toMillis(minutes), periodicity.repeatIntervalMillis)
        }
        for (name in EnrolledWorkBootTest.RETIRED_SPLIT_WORK) assertEquals("retired $name", emptyList<WorkInfo>(), live(name))
    }

    @Test fun bootUpdateAndClockChangeLeaveOneLivePilotScheduleEach() {
        for (name in EnrolledWorkBootTest.RETIRED_SPLIT_WORK) {
            work.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ChronicleSyncWorker>(15, TimeUnit.MINUTES).build()).result.get(10, TimeUnit.SECONDS)
        }
        deliver(Intent.ACTION_BOOT_COMPLETED)
        assertOneLiveScheduleEach()

        repeat(2) {
            deliver(Intent.ACTION_MY_PACKAGE_REPLACED)
            deliver(Intent.ACTION_BOOT_COMPLETED)
        }
        work.cancelUniqueWork(ENROLLMENT_REFRESH_WORK_NAME).result.get(10, TimeUnit.SECONDS)
        deliver(Intent.ACTION_TIME_CHANGED)
        assertEquals("a clock correction requests a fresh server clock", 1, live(ENROLLMENT_REFRESH_WORK_NAME).size)
        assertOneLiveScheduleEach()
    }

    /** An update installed over an enrollment whose schedules were lost (cleared app data for WorkManager) restores them. */
    @Test fun anAppUpdateAloneRestoresEverySchedule() {
        assertTrue(EnrolledWorkBootTest.PILOT_PERIODIC_WORK.keys.all { live(it).isEmpty() })
        deliver(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertOneLiveScheduleEach()
    }
}
