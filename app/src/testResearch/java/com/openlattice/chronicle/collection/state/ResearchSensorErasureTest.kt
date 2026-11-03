package com.openlattice.chronicle.collection.state

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import com.openlattice.chronicle.R
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.directboot.DirectBootSnapshotWriter
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.services.notifications.NotificationListener
import com.openlattice.chronicle.storage.notificationActivitySampleDao
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric

class ResearchSensorErasureTest : ResearchErasureTestFixture() {
    @Test fun acceptedSensorRamSurvivesTemporaryClosureAndServiceReplacement() {
        val first = runtime(retain = true)
        first.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f, 2f, 3f), 3)
        consent(CollectionModuleId.SENSOR_ACCELEROMETER, ParticipantDecision.ACCEPTED, enabled = false)
        assertTrue(first.flushBuffer() is ModuleResult.Retry)
        first.stop(isServiceDestroy = true)
        consent(CollectionModuleId.SENSOR_ACCELEROMETER, ParticipantDecision.ACCEPTED)
        val replacement = runtime(retain = true)
        assertEquals(ModuleResult.Ok(1), replacement.flushBuffer())
        assertEquals(1, db.sensorSampleDao().count())
    }

    @Test fun erasedSensorRegistrationCannotCaptureAfterReacceptance() {
        val module = CollectionModuleId.SENSOR_ACCELEROMETER
        val registration = ResearchPersistenceGate.captureObservation(context, module)
        eraseAndReaccept(module)
        val controller = runtime()
        controller.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3, origin = registration)
        assertEquals(0, controller.bufferedCount)
        assertEquals(ModuleResult.Ok(0), controller.flushBuffer())
    }

    @Test fun erasedPersistentRegistrationIsReplacedEvenWhenReacceptedBeforeReconciliation() {
        val sensor = AndroidSensorType.proximity
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler(executeImmediately = false)
        val controller = SensorRuntimeController(gateway, FakeSensorRuntimeSettings(sensors = setOf(sensor)),
            SensorSampleSink(db.sensorSampleDao(), ResearchPersistenceGate.guard(context)), scheduler,
            collectionGate = { ResearchPersistenceGate.collectsNow(context, SensorCollectionModules.moduleFor(it)) },
            observationAdmission = { ResearchPersistenceGate.captureObservation(context, SensorCollectionModules.moduleFor(it)) },
            sampleLease = { ResearchPersistenceGate.withReadLease(it) })
        controller.start()
        scheduler.runNextExecution()
        assertEquals(1, gateway.persistentRegistrationAttempts.size)
        eraseAndReaccept(SensorCollectionModules.moduleFor(sensor))
        controller.reconcile()
        scheduler.runNextExecution()
        assertEquals(2, gateway.persistentRegistrationAttempts.size)
        assertEquals(listOf(sensor), gateway.registeredPersistent)
        controller.stop(isServiceDestroy = true)
    }

    @Test fun sleepRegistrationAndDelayedReceiverAdmissionAreInvalidatedByErasure() {
        val module = CollectionModuleId.SLEEP
        val stamp = ResearchPersistenceGate.observationScope(context, module)!!.key
        val received = ResearchPersistenceGate.guardForRegistration(context, module, stamp)
        eraseAndReaccept(module)
        assertFalse(received.persist { db.queueEntryData().insertEntry(row()) })
        assertFalse(ResearchPersistenceGate.guardForRegistration(context, module, stamp).isCurrent())
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun snapshotRefreshDuringWithdrawalCannotRestoreDirectBootPermission() {
        SensorSettings(context).applyResolvedSensors(mapOf(AndroidSensorType.accelerometer to
            AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer))))
        DirectBootSnapshotWriter.refresh(context)
        assertEquals(setOf(AndroidSensorType.accelerometer), DirectBootSensorSnapshot(context).collectableSensors())
        prefs.edit().putString(PARTICIPATION_STATUS, ParticipationStatus.NOT_ENROLLED.name).commit()
        DirectBootSensorSnapshot(context).clear()
        DirectBootSnapshotWriter.refresh(context)
        assertTrue(DirectBootSensorSnapshot(context).collectableSensors().isEmpty())
    }

    @Test fun participationRetirementWaitsForAnAdmittedSnapshotWrite() {
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val refresh = pool.submit {
                ResearchPersistenceGate.withReadLease {
                    writing.countDown()
                    check(finishWrite.await(5, TimeUnit.SECONDS))
                    DirectBootSensorSnapshot(context).write(mapOf(AndroidSensorType.accelerometer to DirectBootSensorSnapshot.SensorConfig()))
                }
            }
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val retirement = pool.submit {
                EnrollmentSettings(context).setParticipationStatus(ParticipationStatus.NOT_ENROLLED)
                retired.countDown()
            }
            assertFalse(retired.await(150, TimeUnit.MILLISECONDS))
            finishWrite.countDown()
            refresh.get(5, TimeUnit.SECONDS)
            retirement.get(5, TimeUnit.SECONDS)
            assertTrue(DirectBootSensorSnapshot(context).collectableSensors().isEmpty())
        } finally { finishWrite.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Suppress("DEPRECATION")
    @Test fun notificationObservationQueuedBeforeErasureCannotPersistAfterReacceptance() {
        val service = Robolectric.buildService(NotificationListener::class.java).create().get()
        val notification = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        val sbn = StatusBarNotification("example.app", "example.app", 1, null, 1, 1, 0, notification,
            Process.myUserHandle(), System.currentTimeMillis())
        try {
            onPersistenceWorker {
                ResearchPersistenceGate.stop {
                    service.onNotificationPosted(sbn)
                    decideOnWorker(CollectionModuleId.NOTIFICATION_ACTIVITY, false)
                    decideOnWorker(CollectionModuleId.NOTIFICATION_ACTIVITY, true)
                }
            }
            val current = StatusBarNotification("current.app", "current.app", 2, null, 1, 1, 0, notification,
                Process.myUserHandle(), System.currentTimeMillis())
            service.onNotificationPosted(current)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (db.notificationActivitySampleDao().count() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(listOf("current.app"), db.notificationActivitySampleDao().getOldest(10).map { it.packageName })
        } finally { service.onDestroy() }
    }
}
