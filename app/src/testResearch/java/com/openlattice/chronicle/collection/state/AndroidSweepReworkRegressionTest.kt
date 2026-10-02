@file:Suppress("DEPRECATION")

package com.openlattice.chronicle.collection.state

import android.app.Application
import android.app.Notification
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.hardware.*
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.*
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.device.*
import com.openlattice.chronicle.collection.interaction.InteractionCollectionService
import com.openlattice.chronicle.collection.sensors.*
import com.openlattice.chronicle.collection.sink.*
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.preferences.*
import com.openlattice.chronicle.receivers.lifecycle.DeviceLifecycleReceiver
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.notifications.NotificationListener
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.services.usage.UsageModuleCollectionDelegate
import com.openlattice.chronicle.sensors.LAST_USAGE_QUERY_TIMESTAMP
import com.openlattice.chronicle.sensors.USAGE_EVENTS_SENSOR_CHECKPOINT
import com.openlattice.chronicle.storage.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowUsageStatsManager
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [AndroidSweepReworkRegressionTest.UsageProvider::class,
    AndroidSweepReworkRegressionTest.TriggerManager::class, AndroidSweepReworkRegressionTest.SystemTriggerManager::class])
class AndroidSweepReworkRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var prefs: android.content.SharedPreferences
    private val fence get() = ResearchErasureFence(context)
    private val owner get() = db.uploadServerDao().getConfiguredServer()!!
    private val queries = AtomicInteger()
    private var failQueries = false

    @Implements(UsageStatsManager::class)
    class UsageProvider : ShadowUsageStatsManager() {
        companion object {
            var onRead: (() -> Unit)? = null
            val starts = mutableListOf<Long>()
        }
        @Implementation override fun queryEvents(start: Long, end: Long): UsageEvents {
            starts += start
            onRead?.invoke()
            return super.queryEvents(start, end)
        }
    }

    @Implements(SensorManager::class)
    open class TriggerManager : ShadowSensorManager() {
        @Implementation fun requestTriggerSensor(@Suppress("UNUSED_PARAMETER") listener: TriggerEventListener,
                                                 @Suppress("UNUSED_PARAMETER") sensor: Sensor): Boolean = true
        @Implementation fun cancelTriggerSensor(@Suppress("UNUSED_PARAMETER") listener: TriggerEventListener,
                                                @Suppress("UNUSED_PARAMETER") sensor: Sensor): Boolean = true
    }

    @Implements(className = "android.hardware.SystemSensorManager")
    class SystemTriggerManager : TriggerManager()

    @Before fun setUp() {
        awaitStorageRefresh()
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        prefs = context.getSharedPreferences("sweep-rework-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant-A").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, prefs)
        context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant-A", sourceDeviceId = "device"))
        CollectionModuleId.values().filter { it.active }.forEach { setConsent(it) }
        countDao("_uploadServerDao", UploadServerDao::class.java, db.uploadServerDao())
        countDao("_collectionModuleStateDao", CollectionModuleStateDao::class.java, db.collectionModuleStateDao())
        // Bind both the public API and concrete service to this test's trigger shadow.
        assertTrue(shadowOf(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager) is TriggerManager)
        // Reflection keeps the same behavioral tests executable against the pre-fix fixture.
        publish()
        if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().setExecutor(Executor { }).build())
        StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES)
        StorageAdmission::class.java.getDeclaredField("lastAllowed").apply { isAccessible = true }.setBoolean(StorageAdmission, true)
        StorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(StorageAdmission, SystemClock.elapsedRealtime())
        queries.set(0)
        UsageProvider.starts.clear(); UsageProvider.onRead = null
    }

    private fun <T : Any> countDao(field: String, type: Class<T>, delegate: T) {
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            queries.incrementAndGet()
            if (failQueries) throw IllegalStateException("transient DAO read failure")
            try { method.invoke(delegate, *(args ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        db.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(db, lazyOf(proxy))
    }

    private fun publish() = onPersistenceWorker {
        val method = ResearchPersistenceGate::class.java.declaredMethods.firstOrNull { !java.lang.reflect.Modifier.isPrivate(it.modifiers) && it.name.startsWith("initialize$") && it.parameterCount == 1 && it.parameterTypes[0] == Context::class.java }
        if (method == null) stopOnPersistenceWorker { } else { method.isAccessible = true; method.invoke(ResearchPersistenceGate, context) }
    }

    private fun setConsent(module: CollectionModuleId, enabled: Boolean = true) {
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(module.id, enabled,
            ParticipantDecision.ACCEPTED.name, 0, false, 1, null, null)))
    }

    private fun hold(module: CollectionModuleId, enabled: Boolean) = stopOnPersistenceWorker { setConsent(module, enabled) }

    @After fun tearDown() {
        failQueries = false; UsageProvider.onRead = null
        awaitStorageRefresh()
        AndroidSensorType.values().forEach { SensorSampleWriter.discardSensorSamples(it.name) }
        InteractionPolicySettings.invalidateMemoryCache()
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    private fun awaitStorageRefresh() {
        val worker = StorageAdmission::class.java.getDeclaredField("refreshExecutor").apply { isAccessible = true }
            .get(StorageAdmission) as ExecutorService
        worker.submit { }.get(5, TimeUnit.SECONDS)
    }

    private fun duringStop(callback: () -> Unit) {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newScheduledThreadPool(2)
        val stop = pool.submit { stopOnPersistenceWorker { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queries.set(0)
            // Bounds the old callback's lock wait so a regression fails without wedging MAIN.
            pool.schedule({ release.countDown() }, 400, TimeUnit.MILLISECONDS)
            val start = System.nanoTime()
            callback()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("callback waited ${elapsed}ms for persistence", elapsed < 100)
            assertEquals("framework callback must perform zero DAO calls", 0, queries.get())
        } finally { release.countDown(); stop.get(5, TimeUnit.SECONDS); pool.shutdownNow() }
    }

    private fun sensorGateway(runtime: SensorRuntimeController? = null,
                              onLost: (AndroidSensorType) -> Unit = { runtime?.onPersistentRegistrationLost(it) }): AndroidSensorGateway {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadowOf(manager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        shadowOf(manager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_SIGNIFICANT_MOTION))
        return AndroidSensorGateway(context, object : SensorGateway.SampleListener {
            override fun onSample(sensorType: AndroidSensorType, values: FloatArray, accuracy: Int, timestamp: OffsetDateTime) = Unit
            override fun onTrigger(sensorType: AndroidSensorType, values: FloatArray, timestamp: OffsetDateTime) = Unit
            override fun onCapturedSample(sensorType: AndroidSensorType, values: FloatArray, accuracy: Int?, timestamp: OffsetDateTime,
                                          origin: CollectionPersistenceGuard) {
                runtime?.recordSample(sensorType, values, accuracy, timestamp, origin)
            }
            override fun onPersistentRegistrationLost(sensorType: AndroidSensorType) { onLost(sensorType) }
        }, captureAdmission = { ResearchPersistenceGate.captureObservation(context, SensorCollectionModules.moduleFor(it)) })
    }

    private fun sensorCallback(gateway: AndroidSensorGateway): () -> Unit {
        assertTrue(gateway.registerContinuousSensor(AndroidSensorType.accelerometer, 200_000, 0))
        @Suppress("UNCHECKED_CAST")
        val listeners = gateway.javaClass.getDeclaredField("continuousListeners").apply { isAccessible = true }.get(gateway)
            as Map<AndroidSensorType, SensorEventListener>
        val event = ReflectionHelpers.callConstructor(SensorEvent::class.java, ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 3))
        ReflectionHelpers.setField(event, "sensor", (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
        return { listeners.getValue(AndroidSensorType.accelerometer).onSensorChanged(event) }
    }

    private fun runtime(scheduler: ManualSensorRuntimeScheduler = ManualSensorRuntimeScheduler(false)) = SensorRuntimeController(
        FakeSensorGateway(), FakeSensorRuntimeSettings(), SensorSampleSink(db.sensorSampleDao(), NoOpCollectionLog, ResearchPersistenceGate.guard(context)),
        scheduler, collectionGate = { ResearchPersistenceGate.collectsNow(context, SensorCollectionModules.moduleFor(it)) },
        observationAdmission = { ResearchPersistenceGate.captureObservation(context, SensorCollectionModules.moduleFor(it)) },
        sampleLease = { ResearchPersistenceGate.withReadLease(it) }, log = NoOpCollectionLog)

    @Test fun sensorCallbackReturnsOnMainWhileStopHoldsWriteLockWithoutDaoCalls() {
        val runtime = runtime(); val gateway = sensorGateway(runtime)
        try { duringStop(sensorCallback(gateway)); assertEquals(1, runtime.bufferedCount) } finally { gateway.unregisterAll() }
    }

    @Test fun accessibilityCallbackReturnsOnMainWhileStopHoldsWriteLockWithoutDaoCalls() {
        val policy = InteractionPolicySettings(context)
        assertTrue(policy.save(java.util.UUID.fromString(owner.studyId), 1, true, InteractionPolicy.DEFAULT))
        val service = Robolectric.buildService(InteractionCollectionService::class.java).create().get()
        service.javaClass.getDeclaredField("policySettings").apply { isAccessible = true }.set(service, policy)
        val node = AccessibilityNodeInfo.obtain().apply { setBoundsInScreen(Rect(0, 0, 100, 100)) }
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "example.app"; className = "android.widget.Button"; eventTime = 1
            shadowOf(this).setSourceNode(node)
        }
        try { duringStop { service.onAccessibilityEvent(event) } } finally { service.onDestroy() }
    }

    @Test fun notificationCallbackReturnsOnMainWhileStopHoldsWriteLockWithoutDaoCalls() {
        val service = Robolectric.buildService(NotificationListener::class.java).create().get()
        val notification = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        val sbn = StatusBarNotification("example.app", "example.app", 1, null, 1, 1, 0, notification,
            android.os.Process.myUserHandle(), System.currentTimeMillis())
        try { duringStop { service.onNotificationPosted(sbn) } } finally { service.onDestroy() }
    }

    @Test fun lifecycleReceiverReturnsOnMainWhileStopHoldsWriteLockWithoutDaoCalls() {
        val receiver = DeviceLifecycleReceiver()
        duringStop { receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED)) }
        // Drain queued work before disposing of the in-memory database.
        val executor = Class.forName("com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventsKt")
            .getDeclaredField("lifecycleExecutor").apply { isAccessible = true }.get(null) as ExecutorService
        executor.submit { }.get(5, TimeUnit.SECONDS); executor.submit { }.get(5, TimeUnit.SECONDS)
    }

    @Test fun capturedCurrentAndLiveAdmissionIgnoreTransientDaoFailuresOnCallbacks() {
        val origin = ResearchPersistenceGate.captureObservation(context, CollectionModuleId.SENSOR_ACCELEROMETER)
        queries.set(0); failQueries = true
        try {
            repeat(50) {
                assertTrue(origin.isCurrent())
                assertTrue(ResearchPersistenceGate.captureObservation(context, CollectionModuleId.SENSOR_ACCELEROMETER).isCurrent())
                assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.SENSOR_ACCELEROMETER))
            }
            assertEquals(0, queries.get())
        } finally { failQueries = false }
    }

    @Test fun runIfActiveUnderReadLeaseWithPendingIntentReturnsWithinBoundedTimeout() {
        val expected = owner
        stopOnPersistenceWorker { fence.erase(setOf(CollectionModuleId.BATTERY_TELEMETRY), ownerKey = ResearchErasureFence.enrollmentKey(expected)) }
        val pool = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val task = pool.submit {
            ResearchPersistenceGate.withReadLease {
                assertNull(ResearchPersistenceGate.runIfActive(context) { "unexpected" })
                assertNull(ResearchPersistenceGate.runIfExpectedOwner(context, expected) { "unexpected" })
                assertNull(ResearchPersistenceGate.runIfExpectedEnrollment(context, expected) { "unexpected" })
            }
        }
        try { task.get(1, TimeUnit.SECONDS) } finally { task.cancel(true); pool.shutdownNow() }
        publish()
    }

    @Test fun pendingReplayDoesNotUpgradeReadLeaseWithQueuedWriter() {
        val expected = owner
        stopOnPersistenceWorker { fence.erase(setOf(CollectionModuleId.BATTERY_TELEMETRY), ownerKey = ResearchErasureFence.enrollmentKey(expected)) }
        val barrier = ResearchPersistenceGate::class.java.getDeclaredField("barrier").apply { isAccessible = true }.get(ResearchPersistenceGate)
        val lock = barrier.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(barrier) as ReentrantReadWriteLock
        val readEntered = CountDownLatch(1); val invoke = CountDownLatch(1); val readFinished = CountDownLatch(1)
        val writerThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val pool = Executors.newFixedThreadPool(2)
        val read = pool.submit { ResearchPersistenceGate.withReadLease {
            readEntered.countDown(); check(invoke.await(5, TimeUnit.SECONDS))
            try { assertNull(ResearchPersistenceGate.runIfActive(context) { "unexpected" }) }
            finally { readFinished.countDown() }
        } }
        assertTrue(readEntered.await(5, TimeUnit.SECONDS))
        val writer = pool.submit {
            writerThread.set(Thread.currentThread())
            lock.writeLock().lockInterruptibly()
            try { check(readFinished.await(1, TimeUnit.SECONDS)) { "read lease was upgraded before its operation finished" } }
            finally { lock.writeLock().unlock() }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writerThread.get()?.let(lock::hasQueuedThread) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(writerThread.get()?.let(lock::hasQueuedThread) == true)
            invoke.countDown(); read.get(2, TimeUnit.SECONDS); writer.get(2, TimeUnit.SECONDS)
        } finally {
            invoke.countDown(); writer.cancel(true); pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
        publish()
    }

    @Test fun stopFailsFastWhenCallerHoldsReadLease() {
        val barrier = ResearchPersistenceBarrier()
        barrier.withReadLease { assertThrows(IllegalStateException::class.java) { barrier.stop { } } }
        barrier.stop { barrier.withReadLease { barrier.stop { } } }
    }

    @Test fun retiredOwnerPendingIntentDoesNotBlockReplacementCollection() {
        val previous = owner
        stopOnPersistenceWorker {
            fence.erase(setOf(CollectionModuleId.BATTERY_TELEMETRY), ownerKey = ResearchErasureFence.enrollmentKey(previous))
            db.uploadServerDao().update(previous.copy(participantId = "participant-B", createdAt = OffsetDateTime.now().plusSeconds(1).toString()))
            prefs.edit().putString(PARTICIPANT_ID, "participant-B").commit()
        }
        onPersistenceWorker { CollectionLoopCoordinator(context).replayPendingErasures() }
        assertTrue(fence.pending().isEmpty())
        assertEquals("collected", ResearchPersistenceGate.runIfActive(context) { "collected" })
    }

    @Test fun wholeEnrollmentRetirementRequiresVerifiedResetToCompleteModuleIntents() {
        stopOnPersistenceWorker {
            fence.erase(setOf(CollectionModuleId.BATTERY_TELEMETRY), ownerKey = ResearchErasureFence.enrollmentKey(owner))
            fence.erase(CollectionModuleId.values().toSet(), durableIntent = false)
        }
        assertTrue(fence.pending().isNotEmpty())
        stopOnPersistenceWorker { fence.completeAfterVerifiedReset() }
        assertTrue(fence.pending().isEmpty())
    }

    @Test fun successfulRetryOfSameOperationReopensTransientFailureLatch() {
        val operation = ResearchPersistenceGate.PrivacyOperation("withdrawal")
        assertThrows(java.io.IOException::class.java) { stopOnPersistenceWorker(operation) { throw java.io.IOException("transient deletion failure") } }
        assertNull(ResearchPersistenceGate.runIfActive(context) { "closed" })
        stopOnPersistenceWorker { }
        assertNull(ResearchPersistenceGate.runIfActive(context) { "still closed" })
        stopOnPersistenceWorker(operation) { }
        assertEquals("open", ResearchPersistenceGate.runIfActive(context) { "open" })
    }

    @Test fun successfulReplayReopensOnlyItsOwnFailedErasureOperation() {
        val operation = ResearchPersistenceGate.PrivacyOperation("erasure-replay", ownerKey = ResearchPersistenceGate.currentOwnerKey())
        assertThrows(java.io.IOException::class.java) { stopOnPersistenceWorker(operation) { throw java.io.IOException("transient failure") } }
        stopOnPersistenceWorker { fence.erase(setOf(CollectionModuleId.BATTERY_TELEMETRY), ownerKey = ResearchErasureFence.enrollmentKey(owner)) }
        assertFalse(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.USAGE_EVENTS))
        onPersistenceWorker { CollectionLoopCoordinator(context).replayPendingErasures() }
        assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.USAGE_EVENTS))
    }

    @Test fun legacyUsageCheckpointIsAdoptedForInitialEnrollmentEpoch() {
        val legacy = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)
        db.usagePollCheckpointDao().upsert(UsagePollCheckpointEntity(USAGE_EVENTS_SENSOR_CHECKPOINT, legacy))
        context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit().remove("legacy_checkpoint_checked").commit()
        publish()
        stopOnPersistenceWorker { fence.installEnrollment(owner) }
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.USAGE_EVENTS)!!
        assertEquals(legacy, DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), scope.first, scope.second, fence.legacyCheckpointOwner()).readPreviousPollTimestamp())
        assertEquals(legacy, db.usagePollCheckpointDao().getLastPollTimestamp("$USAGE_EVENTS_SENSOR_CHECKPOINT:${scope.first}"))
    }

    @Test fun refusedFirstUsageWindowKeepsItsAcceptedStartAcrossDelegateReplacement() {
        UsageProvider.onRead = { hold(CollectionModuleId.USAGE_EVENTS, false) }
        assertTrue(UsageModuleCollectionDelegate(context).execute())
        val firstStart = UsageProvider.starts.single()
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.USAGE_EVENTS)!!
        assertEquals(firstStart, DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), scope.first, scope.second).readPreviousPollTimestamp())
        prefs.edit().putLong(LAST_USAGE_QUERY_TIMESTAMP, firstStart + TimeUnit.MINUTES.toMillis(30)).commit()
        UsageProvider.onRead = null; hold(CollectionModuleId.USAGE_EVENTS, true)
        assertTrue(UsageModuleCollectionDelegate(context).execute())
        assertEquals(firstStart, UsageProvider.starts.last())
    }

    @Test fun acceptedOptionalUsageFieldsSurviveTemporaryHold() {
        val sink = ResearchPersistenceGate.usageSink(context)
        hold(CollectionModuleId.IN_APP_ACTIVITY_CLASS, false); hold(CollectionModuleId.USER_IDENTIFICATION, false)
        val event = com.openlattice.chronicle.models.ExtractedUsageEvent(appPackageName = "example.app", interactionType = "Activity Resumed", timestamp = OffsetDateTime.now(),
            timezone = "UTC", user = "accepted-user", applicationLabel = "Example", activityClass = "AcceptedActivity")
        assertEquals(ModuleResult.Ok(1), sink.write(listOf(QueueEntry(1, 1, JsonSerializer.serializeQueueEntry(listOf(event))))))
        val stored = JsonSerializer.deserializeQueueEntry(db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10).single().data)
            .single() as com.openlattice.chronicle.models.ExtractedUsageEvent
        assertEquals("accepted-user", stored.user); assertEquals("AcceptedActivity", stored.activityClass)
    }

    @Test fun usageDelegateKeepsActivityFieldAcceptedBeforeProviderHold() {
        val manager = context.getSystemService(UsageStatsManager::class.java)
        shadowOf(manager).addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
            .setPackage("example.app").setClass("AcceptedActivity")
            .setTimeStamp(System.currentTimeMillis() - 5_000)
            .setEventType(UsageEvents.Event.MOVE_TO_FOREGROUND).build())
        UsageProvider.onRead = { hold(CollectionModuleId.IN_APP_ACTIVITY_CLASS, false) }
        assertTrue(UsageModuleCollectionDelegate(context).execute())
        val stored = db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10)
            .flatMap { JsonSerializer.deserializeQueueEntry(it.data) }
            .filterIsInstance<com.openlattice.chronicle.models.ExtractedUsageEvent>().single()
        assertEquals("AcceptedActivity", stored.activityClass)
    }

    @Test fun healthReadWithoutGrantedTypesAcknowledgesWithoutRefusal() {
        stopOnPersistenceWorker { fence.installEnrollment(owner) }
        HealthConnectScopeStore.of(context).replace(emptySet())
        val source = AndroidHealthMetricSource(context)
        assertTrue(source.read().isEmpty())
        source.acknowledgeRead()
    }

    @Test fun legacyHealthCheckpointIsAdoptedBeyondDefaultBackfill() {
        val previous = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)
        val checkpoint = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        checkpoint.edit().clear().putLong("last_end_millis", previous).commit()
        stopOnPersistenceWorker { fence.installEnrollment(owner) }
        val source = AndroidHealthMetricSource(context)
        val reader = source.javaClass.getDeclaredField("readCoordinator").apply { isAccessible = true }.get(source) as HealthMetricReadCoordinator
        reader.read<String>(System.currentTimeMillis()) { start, _ -> assertEquals(previous, start); emptyList() }
        source.rejectRead()
        assertEquals(ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!.first,
            checkpoint.getString("consent_scope", null))
    }

    @Test fun healthProviderBindingAndReadRunOutsidePersistenceLease() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        val source = AndroidHealthMetricSource(context)
        val barrier = ResearchPersistenceGate::class.java.getDeclaredField("barrier").apply { isAccessible = true }.get(ResearchPersistenceGate)
        val lock = barrier.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(barrier) as ReentrantReadWriteLock
        val permissionType = androidx.health.connect.client.PermissionController::class.java
        val permissions = Proxy.newProxyInstance(permissionType.classLoader, arrayOf(permissionType)) { _, method, _ ->
            check(method.name == "getGrantedPermissions")
            assertEquals("provider permissions must not hold a lease", 0, lock.readLockCount)
            setOf(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.StepsRecord::class))
        } as androidx.health.connect.client.PermissionController
        var reads = 0
        val clientType = androidx.health.connect.client.HealthConnectClient::class.java
        val client = Proxy.newProxyInstance(clientType.classLoader, arrayOf(clientType)) { _, method, _ ->
            when (method.name) {
                "getPermissionController" -> permissions
                "readRecords" -> {
                    assertEquals("provider records must not hold a lease", 0, lock.readLockCount)
                    reads++
                    ReflectionHelpers.callConstructor(androidx.health.connect.client.response.ReadRecordsResponse::class.java,
                        ReflectionHelpers.ClassParameter.from(List::class.java, emptyList<androidx.health.connect.client.records.StepsRecord>()),
                        ReflectionHelpers.ClassParameter.from(String::class.java, null))
                }
                else -> error("unexpected provider method ${method.name}")
            }
        } as androidx.health.connect.client.HealthConnectClient
        var bindingObserved = false
        val provider: () -> androidx.health.connect.client.HealthConnectClient? = {
            bindingObserved = true
            assertEquals("binding must not retain a read lease", 0, lock.readHoldCount)
            client
        }
        source.javaClass.getDeclaredField("clientProvider").apply { isAccessible = true }.set(source, provider)
        assertTrue(source.read().isEmpty()); assertTrue(bindingObserved); assertEquals(1, reads)
        source.acknowledgeRead()
    }

    @Test fun healthErasureCanRetireAWindowWhileProviderReadIsStillRunning() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        var checkpoint: Long? = 10
        var scope = "owner:1" to 0L
        val coordinator = HealthMetricReadCoordinator(object : HealthMetricCheckpoint {
            override fun read(): Long? = checkpoint
            override fun write(endMillis: Long) { checkpoint = endMillis }
        }, consentScope = { scope })
        val pool = Executors.newFixedThreadPool(2)
        val read = pool.submit<List<String>> { coordinator.read(100) { _, _ ->
            entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); listOf("retired-record")
        } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val erase = pool.submit { scope = "owner:2" to 90L; coordinator.reject() }
            erase.get(1, TimeUnit.SECONDS)
            finish.countDown(); assertTrue(read.get(5, TimeUnit.SECONDS).isEmpty())
            coordinator.acknowledge(); assertEquals(10L, checkpoint)
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    @Test fun healthAcknowledgementCannotOverwriteAnAdvancedCursor() {
        var checkpoint: Long? = 10
        val reader = HealthMetricReadCoordinator(object : HealthMetricCheckpoint {
            override fun read(): Long? = checkpoint
            override fun write(endMillis: Long) { checkpoint = endMillis }
        })
        reader.read<String>(100) { _, _ -> emptyList() }
        checkpoint = 200
        reader.acknowledge()
        assertEquals(200L, checkpoint)
    }

    @Test fun pendingRecoveryErasureDeletesArchivedPayloadAndRetiresActiveStoreIntent() {
        val bundle = File(context.noBackupFilesDir, "chronicle-recovery/test-pending")
        assertTrue(bundle.mkdirs())
        File(bundle, "manifest.txt").writeText("owner_scope_sha256=unknown\n")
        File(bundle, "artifact.enc").writeText("archived-erased-payload")
        stopOnPersistenceWorker { fence.erase(setOf(CollectionModuleId.BATTERY_TELEMETRY), ownerKey = ResearchErasureFence.enrollmentKey(owner)) }
        val coordinator = CollectionLoopCoordinator(context)
        onPersistenceWorker { coordinator.reconcileVerifiedRecoveryErasures(owner.studyId to owner.participantId) }
        assertFalse(bundle.exists()); assertTrue(fence.pending().isEmpty())
    }

    @Test fun recoveryErasureIntentSurvivesResetAndOwnerRetirementUntilArchiveReplay() {
        val previous = owner
        val bundle = File(context.noBackupFilesDir, "chronicle-recovery/interrupted-reset")
        assertTrue(bundle.mkdirs())
        File(bundle, "manifest.txt").writeText("owner_scope_sha256=unknown\n")
        File(bundle, "artifact.enc").writeText("pending-erased-payload")
        val coordinator = CollectionLoopCoordinator(context)
        stopOnPersistenceWorker {
            fence.erase(setOf(CollectionModuleId.BATTERY_TELEMETRY), ownerKey = ResearchErasureFence.enrollmentKey(previous))
            coordinator.javaClass.declaredMethods.first { it.name.startsWith("prepareRecoveryErasures$") }
                .invoke(coordinator, previous.studyId to previous.participantId)
            // Process dies after the store was replaced; the archive intent must outlive it.
            fence.erase(CollectionModuleId.values().toSet(), durableIntent = false)
            db.uploadServerDao().update(previous.copy(participantId = "participant-B"))
            prefs.edit().putString(PARTICIPANT_ID, "participant-B").commit()
        }
        assertNull("pending old-owner erasure closes admission until replay", ResearchPersistenceGate.captureOwner(context))
        onPersistenceWorker { CollectionLoopCoordinator(context).replayPendingErasures() }
        assertFalse(bundle.exists())
        assertFalse(fence.hasPendingErasures())
        assertEquals("collected", ResearchPersistenceGate.runIfActive(context) { "collected" })
    }

    @Test fun oneSensorOriginIsValidatedOncePerBatchRatherThanPerEntry() {
        val runtime = runtime()
        val token = ResearchPersistenceGate.captureObservation(context, CollectionModuleId.SENSOR_ACCELEROMETER)
        repeat(100) { runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3, origin = token) }
        queries.set(0)
        assertEquals(ModuleResult.Ok(100), runtime.flushBuffer())
        assertTrue("validation must be per token: ${queries.get()} queries", queries.get() <= 5)
    }

    @Test fun drainedSensorBatchSurvivesAuthorizationValidationException() {
        var fail = false
        val token = object : CollectionPersistenceGuard {
            override fun persist(persist: () -> Unit): Boolean { persist(); return true }
            override fun isCurrent(): Boolean { if (fail) error("transient origin validation"); return true }
        }
        val runtime = runtime()
        runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3, origin = token)
        fail = true
        assertTrue(runtime.flushBuffer() is ModuleResult.Failed)
        assertEquals(1, runtime.bufferedCount)
        fail = false
        assertEquals(ModuleResult.Ok(1), runtime.flushBuffer())
    }

    @Test fun drainedSensorBatchSurvivesAuthoritativeDaoValidationFailure() {
        val runtime = runtime()
        runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3)
        failQueries = true
        try {
            assertTrue(runtime.flushBuffer() is ModuleResult.Failed)
            assertEquals(1, runtime.bufferedCount)
        } finally { failQueries = false }
        assertEquals(ModuleResult.Ok(1), runtime.flushBuffer())
    }

    @Test fun validationFailureKeepsDrainedBatchWhenCallbacksTryToRefillCapacity() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        var fail = true
        val token = object : CollectionPersistenceGuard {
            override fun persist(persist: () -> Unit): Boolean { persist(); return true }
            override fun isCurrent(): Boolean {
                if (Thread.currentThread().name == "validation-worker" && fail) {
                    entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); error("transient validation failure")
                }
                return true
            }
        }
        val runtime = runtime()
        repeat(SensorRuntimeController.MAX_BUFFERED_SAMPLES) {
            runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3, origin = token)
        }
        val pool = Executors.newSingleThreadExecutor { Thread(it, "validation-worker") }
        val flush = pool.submit<ModuleResult> { runtime.flushBuffer() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(SensorRuntimeController.MAX_BUFFERED_SAMPLES) {
                runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(9f), 3)
            }
            finish.countDown(); assertTrue(flush.get(5, TimeUnit.SECONDS) is ModuleResult.Failed)
            fail = false
            assertEquals(ModuleResult.Ok(SensorRuntimeController.MAX_BUFFERED_SAMPLES), runtime.flushBuffer())
            assertTrue("the uncertain original batch must retain its capacity and ownership",
                db.sensorSampleDao().getOldest(SensorRuntimeController.MAX_BUFFERED_SAMPLES).all { it.x == 1f })
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    @Test fun replacementWaitsForInFlightFlushBeforeTransferringAcceptedRam() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val stopped = CountDownLatch(1)
        val old = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(), SensorSampleWriter {
            entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); ModuleResult.Retry("temporary append refusal")
        }, ManualSensorRuntimeScheduler(false), log = NoOpCollectionLog)
        old.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3)
        val token = ResearchPersistenceGate.captureObservation(context, CollectionModuleId.SENSOR_ACCELEROMETER)
        val transfer: (SensorSampleEntry) -> CollectionPersistenceGuard = { token }
        val stop = old.javaClass.declaredMethods.first { it.name == "stop" && it.parameterCount == 2 }
        val pool = Executors.newFixedThreadPool(2)
        val flushing = pool.submit<ModuleResult> { old.flushBuffer() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val stopping = pool.submit { stop.invoke(old, true, transfer); stopped.countDown() }
        try {
            assertFalse("replacement must wait for the old flush to relinquish its batch", stopped.await(100, TimeUnit.MILLISECONDS))
            finish.countDown(); flushing.get(5, TimeUnit.SECONDS); stopping.get(5, TimeUnit.SECONDS)
            assertEquals(0, old.bufferedCount)
            val replacement = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(),
                SensorSampleSink(db.sensorSampleDao(), NoOpCollectionLog, ResearchPersistenceGate.guard(context)),
                ManualSensorRuntimeScheduler(false), retainOnStop = true, log = NoOpCollectionLog)
            assertEquals(1, replacement.bufferedCount)
            assertEquals(ModuleResult.Ok(1), replacement.flushBuffer())
        } finally { finish.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun scheduledSensorCycleRetriesAfterTransientAdmissionException() {
        var fail = false
        val scheduler = ManualSensorRuntimeScheduler()
        val gateway = FakeSensorGateway()
        val runtime = SensorRuntimeController(gateway, FakeSensorRuntimeSettings(), SensorSampleWriter { ModuleResult.Ok(it.size) }, scheduler,
            collectionGate = { if (fail) error("transient authorization query"); true }, log = NoOpCollectionLog)
        runtime.start(); scheduler.runNext(); fail = true
        scheduler.runNext()
        assertTrue(scheduler.scheduled.isNotEmpty())
        fail = false; scheduler.runNext()
        assertTrue(runtime.isCollecting); runtime.stop()
    }

    @Test fun schedulerRefreshesExpiredStorageAdmissionBeforeEveryActivePhase() {
        val scheduler = ManualSensorRuntimeScheduler()
        val gateway = FakeSensorGateway()
        val runtime = SensorRuntimeController(gateway, FakeSensorRuntimeSettings(), SensorSampleWriter { ModuleResult.Ok(it.size) }, scheduler,
            collectionAdmission = { StorageAdmission.cachedAllowed(context) },
            prepareCollection = { StorageAdmission.allowed(context) }, log = NoOpCollectionLog)
        runtime.start(); scheduler.runNext()
        StorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }.setLong(StorageAdmission, Long.MIN_VALUE)
        scheduler.runNext()
        assertTrue("healthy storage must not skip its active phase forever", runtime.isCollecting)
        runtime.stop()
    }

    @Test fun consumedTriggerRelinquishesOwnershipDuringStoragePauseAndRecovers() {
        val scheduler = ManualSensorRuntimeScheduler(false)
        lateinit var runtime: SensorRuntimeController
        val gateway = sensorGateway(onLost = { runtime.onPersistentRegistrationLost(it) })
        val type = com.openlattice.chronicle.sensors.SensorTypeMapping.fromAndroidType(Sensor.TYPE_SIGNIFICANT_MOTION)!!
        assertNotNull((context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION))
        assertTrue("trigger consent must be admitted before registration", ResearchPersistenceGate.captureObservation(context,
            SensorCollectionModules.moduleFor(type)).isCurrent())
        assertTrue("storage must be admitted before registration", StorageAdmission.cachedAllowed(context))
        val registrationLog = com.openlattice.chronicle.collection.core.RecordingCollectionLog()
        runtime = SensorRuntimeController(gateway, FakeSensorRuntimeSettings(sensors = setOf(type)),
            SensorSampleWriter { ModuleResult.Ok(it.size) }, scheduler,
            collectionAdmission = { StorageAdmission.cachedAllowed(context) }, log = registrationLog)
        runtime.start(); scheduler.runNextExecution()
        assertTrue(registrationLog.problems.joinToString { "${it.message}: ${it.error?.stackTraceToString()}" }, registrationLog.problems.isEmpty())
        @Suppress("UNCHECKED_CAST")
        val registrations = gateway.javaClass.getDeclaredField("triggerListeners").apply { isAccessible = true }.get(gateway) as Map<Sensor, TriggerEventListener>
        val registration = registrations.entries.single()
        val event = ReflectionHelpers.callConstructor(TriggerEvent::class.java, ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1))
        ReflectionHelpers.setField(event, "sensor", registration.key)
        StorageAdmission::class.java.getDeclaredField("lastAllowed").apply { isAccessible = true }.setBoolean(StorageAdmission, false)
        duringStop { registration.value.onTrigger(event) }
        assertTrue("consumed triggers cannot remain marked armed", registrations.isEmpty())
        scheduler.runNextExecution(); scheduler.runNext()
        StorageAdmission::class.java.getDeclaredField("lastAllowed").apply { isAccessible = true }.setBoolean(StorageAdmission, true)
        scheduler.runNext()
        assertEquals("storage recovery must rearm the trigger", 1, registrations.size)
        runtime.stop()
    }

    @Test fun directBootUnlockHandoverRetainsAcceptedRamAcrossTemporaryHold() {
        val module = CollectionModuleId.SENSOR_ACCELEROMETER
        val stamp = ResearchPersistenceGate.observationScope(context, module)!!.first
        val old = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(),
            SensorSampleWriter { ModuleResult.Retry("temporary append refusal") }, ManualSensorRuntimeScheduler(false),
            log = NoOpCollectionLog)
        old.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3)
        hold(module, false)
        val method = ResearchPersistenceGate::class.java.declaredMethods.first { it.name.startsWith("guardForRetainedRegistration$") }
        val transfer: (SensorSampleEntry) -> CollectionPersistenceGuard = {
            method.invoke(ResearchPersistenceGate, context, module, stamp) as CollectionPersistenceGuard
        }
        val stop = old.javaClass.declaredMethods.first { it.name == "stop" && it.parameterCount == 2 }
        stop.invoke(old, true, transfer)
        assertEquals(0, old.bufferedCount)
        val replacement = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(),
            SensorSampleSink(db.sensorSampleDao(), NoOpCollectionLog, ResearchPersistenceGate.guard(context)),
            ManualSensorRuntimeScheduler(false), collectionGate = { ResearchPersistenceGate.collectsNow(context, module) },
            retainOnStop = true, sampleLease = { ResearchPersistenceGate.withReadLease(it) }, log = NoOpCollectionLog)
        assertEquals(1, replacement.bufferedCount)
        hold(module, true)
        assertEquals(ModuleResult.Ok(1), replacement.flushBuffer())
        assertEquals(1, db.sensorSampleDao().count())
    }
}
