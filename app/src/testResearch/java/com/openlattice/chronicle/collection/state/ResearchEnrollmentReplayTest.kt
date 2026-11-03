package com.openlattice.chronicle.collection.state

import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.work.ListenableWorker
import com.google.android.gms.common.GooglePlayServicesUtilLight
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.enrollment.EnrollmentMonitoringWorker
import com.openlattice.chronicle.services.sensors.HardwareSensorService
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.clearAccessLossEpisodes
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

class ResearchEnrollmentReplayTest : ResearchErasureTestFixture() {
    @Test fun offlineAcceptIsDeliveredBeforeNewerDeclineAndCannotReturnAfterward() {
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val expected = owner.copy(disclosureVersion = "reviewed-v1", manifestDigest = "a".repeat(64))
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        consent(module, ParticipantDecision.DECLINED)
        val delivered = mutableListOf<CollectionAcknowledgment>()
        var online = false
        withStudyApi(onCall = { args ->
            if (online) delivered += args[4] as CollectionAcknowledgment
        }) { method ->
            check(method == "reportCollectionAck")
            if (!online) throw IOException("offline")
            OK()
        }.use {
            assertFalse(onPersistenceWorker { coordinator.applyDecisions(setOf(module), emptySet(), ConsentTrigger.PARTICIPANT_TOGGLE) })
            assertEquals(1, CollectionAckRetryQueue.of(context).load().size)
            online = true
            assertTrue(onPersistenceWorker { coordinator.applyDecisions(emptySet(), setOf(module), ConsentTrigger.PARTICIPANT_TOGGLE) })
            assertEquals(listOf(setOf(module), emptySet<CollectionModuleId>()), delivered.map { it.acknowledgedModules })
            assertEquals(setOf(module), delivered.last().declinedModules)
            assertTrue(CollectionAckRetryQueue.of(context).load().isEmpty())
            assertFalse(CollectionLoopStore.of(context).collects(module))
        }
    }

    /**
     * Mark not enrolled answers NOT_ENROLLED on the status read. The enrollment monitor alone, starting from
     * ENROLLED, applies it: collection stops and nothing queued uploads. The
     * enrollment and its credentials stay for a re-enrollment; a 401 changes nothing.
     */
    @Test fun enrollmentMonitorAloneAppliesServerNotEnrolledButA401ChangesNothing() {
        val expected = owner.copy(apiKey = "test-key")
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        db.queueEntryData().insertEntry(row())

        var unauthorized = true
        withStudyApi { method ->
            if (unauthorized) throw ChronicleCallException("GET", "url", "", 401)
            if (method == "getDeviceParticipationStatus") polled(ParticipationStatus.NOT_ENROLLED) else error(method)
        }.use {
            val monitor = EnrollmentMonitoringWorker(context, auditWorkerParameters())
            assertEquals(ListenableWorker.Result.failure(), onPersistenceWorker { monitor.doWork() })
            assertEquals(ParticipationStatus.ENROLLED, EnrollmentSettings(context).getParticipationStatus())
            assertTrue("a 401 must not stop collection", ResearchPersistenceGate.isActiveEnrollment(context))
            assertEquals("the refused status check reaches the study diagnostics", listOf(401), onPersistenceWorker {
                LocalUploadDiagnosticsStore.of(context).recent()
                    .filter { it.moduleFamily == LocalUploadModuleFamily.APP_RUNTIME.name }.map { it.httpStatus }
            })

            unauthorized = false
            assertEquals(ListenableWorker.Result.success(), onPersistenceWorker { monitor.doWork() })
            assertEquals(ParticipationStatus.NOT_ENROLLED, EnrollmentSettings(context).getParticipationStatus())
            assertFalse("NOT_ENROLLED stops collection", ResearchPersistenceGate.isActiveEnrollment(context))
            assertFalse(ResearchPersistenceGate.captureObservation(context, CollectionModuleId.USAGE_EVENTS)
                .persist { db.queueEntryData().insertEntry(row()) })
            assertEquals("Mark not enrolled clears what is queued", 0, db.queueEntryData().getSize())
            assertEquals(expected, db.uploadServerDao().getConfiguredServer())
        }
    }

    /** A decision commits with its acknowledgment: when the acknowledgment cannot be recorded, the decision is not applied. */
    @Test fun aDecisionIsNotAppliedUnlessItsAcknowledgmentIsRecorded() {
        val module = CollectionModuleId.BATTERY_TELEMETRY
        consent(module, ParticipantDecision.DECLINED)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_ack BEFORE INSERT ON pending_collection_acks " +
            "BEGIN SELECT RAISE(ABORT, 'acknowledgment store down'); END")
        withStudyApi { OK() }.use {
            runCatching { onPersistenceWorker { coordinator.applyDecisions(setOf(module), emptySet(), ConsentTrigger.PARTICIPANT_TOGGLE) } }
        }
        assertEquals(ParticipantDecision.DECLINED, CollectionLoopStore.of(context).loadAll().getValue(module).decision)
    }

    /** A status check that fails after withdrawal and re-enrollment belongs to the enrollment that sent it. */
    @Test fun aStatusFailureAfterReenrollmentIsNotRecordedAgainstTheNewEnrollment() {
        withStudyApi { method ->
            check(method == "getDeviceParticipationStatus") { method }
            db.uploadServerDao().update(owner.copy(participantId = "participant-B"))
            check(prefs.edit().putString(PARTICIPANT_ID, "participant-B").commit())
            throw ChronicleCallException("GET", "url", "", 401)
        }.use {
            val monitor = EnrollmentMonitoringWorker(context, auditWorkerParameters())
            assertEquals(ListenableWorker.Result.failure(), onPersistenceWorker { monitor.doWork() })
        }
        assertEquals(emptyList<Int?>(), onPersistenceWorker {
            LocalUploadDiagnosticsStore.of(context).recent()
                .filter { it.moduleFamily == LocalUploadModuleFamily.APP_RUNTIME.name }.map { it.httpStatus }
        })
    }

    @Test fun serverUnenrollmentStopsPersistentSensorsAndResumeRestartsTheService() {
        val serviceClass = HardwareSensorService::class.java
        val sensor = AndroidSensorType.proximity
        val gateway = FakeSensorGateway()
        val runtime = runtime(gateway = gateway, settings = FakeSensorRuntimeSettings(sensors = setOf(sensor)),
            scheduler = ManualSensorRuntimeScheduler())
        val startup = ScheduledThreadPoolExecutor(1)
        val serviceController = ServiceController.of(HardwareSensorService(runtime, startup), null).create()
        startup.submit {}.get(5, TimeUnit.SECONDS)
        runtime.start()
        assertEquals(listOf(sensor), gateway.registeredPersistent)
        var stopped = false
        var started = false
        val observedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun stopService(intent: Intent): Boolean {
                if (intent.component?.className == serviceClass.name) stopped = true
                return true
            }
            override fun startForegroundService(intent: Intent): ComponentName? {
                if (intent.component?.className == serviceClass.name) started = true
                return intent.component
            }
        }
        try {
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(observedContext, owner, fence.settingsGeneration(),
                    ParticipationStatus.NOT_ENROLLED, emptyList())
            })
            assertTrue("authoritative stop must request service destruction", stopped)
        } finally {
            // Complete the destruction Android performs after Context.stopService.
            serviceController.destroy()
            assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertFalse(runtime.isStarted)
        assertTrue(gateway.registeredPersistent.isEmpty())
        val restartedStartup = ScheduledThreadPoolExecutor(1)
        val restarted = ServiceController.of(HardwareSensorService(startupExecutor = restartedStartup), null).create()
        try {
            restartedStartup.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("boot/activity starts must stop while participation is closed", shadowOf(restarted.get()).isStoppedBySelf)
        } finally {
            restarted.destroy()
            assertTrue(restartedStartup.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(observedContext, owner, fence.settingsGeneration(),
                ParticipationStatus.ENROLLED, emptyList())
        })
        assertTrue("resume must restart accepted sensor collection", started)
    }

    @Config(sdk = [31])
    @Test fun deniedSensorRestartAfterResumeReportsAndNotifiesOnce() {
        // This JVM context has no APK metadata; package availability still comes from PackageManager.
        GooglePlayServicesUtilLight.enableUsingApkIndependentContext()
        shadowOf(context.getSystemService(ActivityManager::class.java)).setServices(emptyList())
        val observedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): ComponentName? {
                error("background foreground-service start denied")
            }
        }
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(observedContext, owner, fence.settingsGeneration(),
                ParticipationStatus.PAUSED, emptyList())
        })
        clearAccessLossEpisodes(context)
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(notificationManager)
        notificationManager.cancelAll()
        val sensorDiagnostics = {
            LocalUploadDiagnosticsStore.of(context).recent().filter {
                it.moduleFamily == LocalUploadModuleFamily.SENSOR.name && it.issue == LocalOperationalIssue.COLLECTION_ACCESS_MISSING.name
            }
        }

        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(observedContext, owner, fence.settingsGeneration(),
                ParticipationStatus.ENROLLED, emptyList())
        })
        // A FIFO barrier waits for the access check queued after the status mutation.
        stopOnPersistenceWorker {}
        assertTrue(ShadowLog.getLogs().filter { it.tag in setOf("AccessLossDiagnostics", "ForegroundServices") }
            .joinToString("\n") { "${it.tag}: ${it.msg} ${it.throwable?.stackTrace?.take(5)}" },
            notifications.allNotifications.any {
                it.extras.getString(Notification.EXTRA_TEXT) == context.getString(R.string.notif_access_missing)
            })
        val notice = notifications.allNotifications.single { notification ->
            notification.extras.getString(Notification.EXTRA_TEXT) == context.getString(R.string.notif_access_missing)
        }
        val intent = shadowOf(notice.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(1, sensorDiagnostics().size)

        notificationManager.cancelAll()
        onPersistenceWorker { CollectionLoopCoordinator(observedContext).updateSensorService() }
        stopOnPersistenceWorker {}
        assertTrue("a repeated refusal must not notify again", notifications.allNotifications.isEmpty())
        assertEquals(1, sensorDiagnostics().size)
    }
}
