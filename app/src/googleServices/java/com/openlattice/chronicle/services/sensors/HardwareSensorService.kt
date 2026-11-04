package com.openlattice.chronicle.services.sensors

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import androidx.annotation.CheckResult
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.directboot.DirectBootDrainWorker
import com.openlattice.chronicle.collection.directboot.DirectBootSampleBuffer
import com.openlattice.chronicle.collection.directboot.DirectBootSnapshotWriter
import com.openlattice.chronicle.collection.sensors.AndroidSensorGateway
import com.openlattice.chronicle.collection.sensors.ExecutorSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorGateway
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sensors.SensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.SensorSettingsRuntimeSettings
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.receivers.lifecycle.DeviceLifecycleReceiver
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.deviceLifecycleIntentFilter
import com.openlattice.chronicle.services.promoteForegroundServiceIfPermitted
import com.openlattice.chronicle.services.startForegroundServiceIfPermitted
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.recordCollectionAccessLoss
import com.openlattice.chronicle.services.upload.recordForExpectedOwner
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import com.openlattice.chronicle.utils.registerSystemBroadcastReceiver
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val TAG = HardwareSensorService::class.java.simpleName

/**
 * The Android service shell for hardware sensor collection. The SensorManager listener,
 * duty cycle, buffer, power-save and battery logic live in [SensorRuntimeController];
 * this class is only the Android coupling that the controller cannot own because it has
 * no `Context`:
 *
 *  - the foreground service lifecycle (`onCreate` / `onStartCommand` / `onDestroy`);
 *  - the foreground notification + channel;
 *  - the `batteryReceiver` (`ACTION_BATTERY_CHANGED`) and `lifecycleReceiver` registration;
 *  - the `onTrimMemory` low-memory lifecycle event;
 *  - asynchronous service-destroy flush.
 *
 * Everything the controller owns it owns through seams: [SensorGateway] (SensorManager /
 * BatteryManager / PowerManager), [SensorSampleSink] (the sanctioned `sensor_samples`
 * writer), [SensorSettingsRuntimeSettings] (the duty-cycle config) and
 * [ExecutorSensorRuntimeScheduler] (the duty-cycle scheduler). Every collected sample is
 * written through the sink.
 *
 * `startService`/`stopService` are the entry points.
 *
 * **Direct-boot mode:** when started before the user's first unlock (a locked
 * boot, via `LockedBootReceiver`), credential-encrypted storage is unreadable, so the
 * controller is built over the device-protected snapshot/buffer seams instead
 * ([buildDirectBootController]) and Room-backed side paths (lifecycle receiver, trim-memory
 * recorder, gate reads) are deferred. The `ACTION_USER_UNLOCKED` handover ([onUserUnlocked])
 * flushes and replays the buffer into the normal queue and rebuilds the controller over the
 * credential-encrypted seams, in place. In normal mode this service also *maintains* the
 * direct-boot state: every start/reconcile rewrites the snapshot from live gate reads and
 * drains any leftover buffer.
 *
 * **Collection-loop startup gate (design §7):** because legacy direct callers
 * (`StartOnBoot`, `PowerSaveModeReceiver`, `Enrollment`, ...) can still start this service,
 * `onCreate` re-checks [CollectionGate] off the main thread once the foreground
 * notification is up, and `stopSelf()`s when `hardware_sensors` is not server-enabled AND
 * acknowledged. This stops an idle foreground service (and its "collecting" notification)
 * from running when nothing is being collected — the per-sample persistence gate in the
 * controller already blocks un-acknowledged writes; this stops the empty service shell too.
 *
 */
class HardwareSensorService @JvmOverloads constructor(
    private val providedController: SensorRuntimeController? = null,
    private val startupExecutor: ScheduledThreadPoolExecutor = sensorStartupExecutor(),
) : Service() {

    @Volatile
    private lateinit var controller: SensorRuntimeController
    @Volatile private var foregroundStartFailed = false
    // Read and written on the main thread only (onStartCommand and the stopIdleService runnable).
    private var latestStartId = 0
    private val lifecycleReceiver = DeviceLifecycleReceiver()

    /**
     * True while the service runs before the user's first unlock (direct-boot window, started
     * by `LockedBootReceiver`): credential-encrypted storage — the SQLCipher Room DB,
     * `SensorSettings`, `CollectionGate` — is unreadable, so the controller runs against the
     * device-protected [DirectBootSensorSnapshot]/[DirectBootSampleBuffer] seams instead, and
     * everything Room-backed (lifecycle recorder, gate reads, snapshot rewrite) is skipped
     * until [onUserUnlocked] transitions to the normal pipeline.
     */
    @Volatile
    private var directBootMode = false

    private var unlockReceiver: BroadcastReceiver? = null
    private var lifecycleReceiverRegistered = false

    private val lossExecutor = Executors.newSingleThreadExecutor()

    // Set in onDestroy so the async startup gate read does not start the controller after
    // the service has been torn down (the gate read widens the start-after-destroy window).
    @Volatile
    private var destroyed = false
    private fun executeStartup(attempt: Int = 0, action: () -> Unit) {
        try {
            startupExecutor.execute {
                if (destroyed) return@execute
                try {
                    action()
                } catch (error: Exception) {
                    Log.e(TAG, "Sensor storage/runtime initialization unavailable; retrying locally", error)
                    if (!destroyed) {
                        val delay = (250L shl attempt.coerceAtMost(7)).coerceAtMost(30_000L)
                        try {
                            startupExecutor.schedule({ executeStartup(attempt + 1, action) }, delay, TimeUnit.MILLISECONDS)
                        } catch (stopped: RejectedExecutionException) {
                            Log.i(TAG, "Service destroyed before startup retry was scheduled", stopped)
                        }
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.i(TAG, "Service startup executor already stopped", error)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                if (::controller.isInitialized) controller.onBatteryLevel(pct)
            }
        }
    }

    companion object {
        /** While locked, the snapshot's consent already filtered collection; samples go to the file. */
        internal fun directBootSink(buffer: DirectBootSampleBuffer) = SensorSampleWriter { buffer.append(it) }

        @CheckResult
        fun tryStartService(context: Context): Boolean {
            val intent = Intent(context, HardwareSensorService::class.java)
            val ownerKey = ResearchPersistenceGate.currentOwnerKey()
            if (startForegroundServiceIfPermitted(context, intent)) return true
            ResearchPersistenceGate.executeAsync {
                if (ownerKey != null && ResearchPersistenceGate.currentOwnerKey() == ownerKey) {
                    recordCollectionAccessLoss(context.applicationContext, sensorStartRefused = true)
                }
            }
            return false
        }

        fun stopService(context: Context) {
            val intent = Intent(context, HardwareSensorService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        directBootMode = getSystemService(UserManager::class.java)?.isUserUnlocked == false
        // Promote promptly before the collection gate reads Room (forbidden on the
        // main thread), then re-check the gate off the main
        // thread. If hardware_sensors is not server-enabled AND acknowledged on this device
        // (design §7), there is nothing to collect — stop the service so no idle
        // "collecting" notification is left showing. The sample-persistence gate inside the
        // controller already prevents an un-acknowledged sample from being written; this
        // additionally stops the idle foreground service so it isn't running for nothing.
        // A gate read error does not stop the service: executeStartup retries until the read succeeds.
        if (!promoteOrReject()) return
        registerSystemBroadcastReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (directBootMode) {
            // The lifecycle receiver records through Room (credential-encrypted) — deferred
            // to the unlock transition. Watch for first unlock to hand over to the normal
            // pipeline without a service restart.
            registerUnlockReceiver()
        } else {
            registerLifecycleReceiver()
        }

        if (directBootMode) {
            executeStartup {
                if (!directBootMode) return@executeStartup
                val snapshot = DirectBootSensorSnapshot(applicationContext)
                if (snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS)) {
                    controller = buildDirectBootController(snapshot)
                    if (destroyed) return@executeStartup
                    try {
                        controller.start()
                    } catch (e: RejectedExecutionException) {
                        Log.i(TAG, "Sensor runtime shut down during direct-boot startup; not starting", e)
                    }
                } else {
                    Log.i(TAG, "no usable direct-boot snapshot; stopping idle foreground service")
                    stopIdleService()
                }
            }
            // The user may have unlocked between the mode check above and the receiver
            // registration — the broadcast would then never arrive, so re-check once.
            if (getSystemService(UserManager::class.java)?.isUserUnlocked == true) onUserUnlocked()
            return
        }
        executeStartup {
            ResearchPersistenceGate.initialize(applicationContext)
            if (!::controller.isInitialized) controller = configuredController()
            // Normal mode owns direct-boot maintenance: replay any pre-unlock buffer into the
            // Room queue, and rewrite the device-protected snapshot from the live gate state
            // (this start is re-issued on every consent toggle / settings sync).
            DirectBootDrainWorker.enqueue(applicationContext)
            DirectBootSnapshotWriter.refresh(applicationContext)
            startControllerOrStopIdle("startup gate read")
        }
    }

    private fun promoteOrReject(): Boolean {
        if (startForegroundNotification()) return true
        rejectForegroundStart(IllegalStateException("Foreground promotion refused"))
        return false
    }

    private fun rejectForegroundStart(error: Exception) {
        if (foregroundStartFailed) return
        foregroundStartFailed = true
        Log.w(TAG, "Sensor foreground start unavailable", error)
        val owner = if (directBootMode) null else ResearchPersistenceGate.captureOwner(applicationContext)
        ResearchPersistenceGate.executeAsync {
            recordForExpectedOwner(applicationContext, owner,
                LocalUploadModuleFamily.SENSOR,
                LocalOperationalIssue.COLLECTION_ACCESS_MISSING, 1)
        }
        stopIdleService()
    }

    private fun stopIdleService() {
        ContextCompat.getMainExecutor(this).execute {
            // Only the latest start may stop the service: a start Android has queued since still owes its
            // promotion, and stopping under it crashes the app the same way a missed promotion does. That
            // start decides again (onStartCommand). No explicit demotion: a refused stop must keep the
            // notification, and Android removes it when the service does stop.
            stopSelfResult(latestStartId)
        }
    }

    private fun registerLifecycleReceiver() {
        if (lifecycleReceiverRegistered) return
        registerSystemBroadcastReceiver(this, lifecycleReceiver, deviceLifecycleIntentFilter())
        lifecycleReceiverRegistered = true
        Log.i(TAG, "Device lifecycle receiver registered")
    }

    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED) onUserUnlocked()
            }
        }
        unlockReceiver = receiver
        registerSystemBroadcastReceiver(this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
    }

    /**
     * First-unlock handover from the direct-boot pipeline to the normal one, in place (no
     * service restart — a foreground restart from the background would trip the FGS start
     * policy). Flushes the direct-boot controller into the DE buffer, replays the buffer
     * into the Room queue (gate-rechecked), then rebuilds and restarts the controller over
     * the credential-encrypted seams. Idempotent: only the first call transitions.
     */
    private fun onUserUnlocked() {
        if (!directBootMode) return
        directBootMode = false
        unlockReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        unlockReceiver = null
        Log.i(TAG, "User unlocked: transitioning sensor collection to the normal pipeline")
        registerLifecycleReceiver()
        executeStartup {
            if (destroyed) return@executeStartup
            // Flush pre-unlock RAM into the file; the drain then applies the consent gate.
            ResearchPersistenceGate.initialize(applicationContext)
            if (::controller.isInitialized) controller.stop(isServiceDestroy = true)
            DirectBootDrainWorker.enqueue(applicationContext)
            DirectBootSnapshotWriter.refresh(applicationContext)
            controller = configuredController()
            startControllerOrStopIdle("unlock transition")
        }
    }

    /** Runs on the startup executor: collect when a sensor is enabled and acknowledged, otherwise stop the idle service. */
    private fun startControllerOrStopIdle(phase: String) {
        if (!anySensorCollects(applicationContext)) {
            Log.i(TAG, "no sensor enabled/acknowledged after $phase; stopping idle foreground service")
            stopIdleService()
            return
        }
        // Guard the start-after-destroy race the gate-read delay introduces: an external stopService
        // during the gate read can run onDestroy -> controller.stop() -> scheduler.shutdownNow() before
        // this starts the controller, which would reject the duty-cycle task. The destroyed flag is the
        // primary guard; the catch is the backstop for the residual TOCTOU.
        if (destroyed) {
            Log.i(TAG, "Service destroyed before $phase completed; not starting controller")
            return
        }
        try {
            controller.start()
        } catch (e: RejectedExecutionException) {
            Log.i(TAG, "Sensor runtime shut down during $phase; not starting", e)
        }
    }

    private fun configuredController(): SensorRuntimeController {
        val expectedOwner = ResearchPersistenceGate.captureOwner(applicationContext)
        return providedController ?: buildSensorController(applicationContext, { destroyed }) { code, count ->
            reportSensorLoss(expectedOwner, code, count)
        }
    }

    private fun reportSensorLoss(expectedOwner: UploadServerEntity?, code: String, count: Int) {
        Log.w(TAG, "$count sensor sample(s) affected: $code")
        try {
            lossExecutor.execute {
                recordForExpectedOwner(
                    applicationContext, expectedOwner, LocalUploadModuleFamily.SENSOR,
                    LocalOperationalIssue.valueOf(code), count,
                )
            }
        } catch (error: RejectedExecutionException) {
            Log.e(TAG, "Sensor loss reporter stopped before $count record(s) could be reported", error)
        }
    }

    /**
     * Builds the controller for the direct-boot window: settings and the per-sensor gate come
     * from the device-protected [DirectBootSensorSnapshot] and samples go to the enrollment's
     * [DirectBootSampleBuffer]. While locked nothing here may touch Room or [CollectionGate].
     */
    private fun buildDirectBootController(snapshot: DirectBootSensorSnapshot): SensorRuntimeController {
        val collectable = snapshot.collectableSensors()
        val buffer = DirectBootSampleBuffer(applicationContext, checkNotNull(snapshot.owner()))
        lateinit var built: SensorRuntimeController
        val gateway = AndroidSensorGateway(
            applicationContext,
            object : SensorGateway.SampleListener {
                override fun onSample(
                    sensorType: AndroidSensorType,
                    values: FloatArray,
                    accuracy: Int,
                    timestamp: OffsetDateTime,
                ) {
                    built.recordSample(sensorType, values, accuracy, timestamp)
                }

                override fun onTrigger(
                    sensorType: AndroidSensorType,
                    values: FloatArray,
                    timestamp: OffsetDateTime,
                ) {
                    built.recordSample(sensorType, values, null, timestamp)
                }

                override fun onPersistentRegistrationLost(sensorType: AndroidSensorType) {
                    built.onPersistentRegistrationLost(sensorType)
                }
            },
        )
        built = SensorRuntimeController(
            gateway = gateway,
            settings = object : SensorRuntimeSettings {
                override fun enabledSensors() = collectable
                override fun samplingRateHz(sensor: AndroidSensorType) = snapshot.config(sensor).samplingRateHz
                override fun dutyCycleActiveSeconds(sensor: AndroidSensorType) = snapshot.config(sensor).dutyCycleActiveSeconds
                override fun dutyCyclePeriodSeconds(sensor: AndroidSensorType) = snapshot.config(sensor).dutyCyclePeriodSeconds
            },
            sink = directBootSink(buffer),
            scheduler = ExecutorSensorRuntimeScheduler(),
            collectionGate = { sensorType -> sensorType in collectable },
            reportLoss = { code, count -> Log.w(TAG, "$count direct-boot sensor sample(s) affected: $code") },
            zeroWrittenIsCountedLoss = true,
        )
        return built
    }

    /** Whether any per-sensor module is currently collectable (server-enabled AND acknowledged). */
    // Unlike CollectionGate.collects, a read failure throws so executeStartup retries instead of
    // treating a transient storage error as "no sensor enabled" and stopping the service.
    private fun anySensorCollects(context: Context): Boolean =
        CollectionLoopCoordinator(context).sensorServiceDesired()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService start must promote, not only the one onCreate gated collection on:
        // Android can demote a running service (background usage toggled), and the next start then crashes
        // the app unless it promotes again (https://developer.android.com/develop/background-work/services/fgs/launch).
        latestStartId = startId
        if (!promoteOrReject() || foregroundStartFailed) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        Log.i(TAG, "Hardware sensor service started")
        // A start while the service is already running means consent or study settings changed
        // the per-sensor set (a Data Sharing toggle / settings sync re-issues startService).
        // Reconcile so a newly-enabled sensor begins collecting without a full service restart;
        // the controller arms/schedules only the sensors not already running. On first creation
        // the controller is not yet started (its start is gated behind the async startup read),
        // so this is a no-op then — start() does the initial scheduling.
        // Direct-boot mode skips this: consent/settings cannot change while still locked, and
        // the snapshot rewrite reads credential-encrypted state.
        // An idle service is still here only because a newer start outranked its stop (stopSelfResult
        // returned false), so that start decides again between collecting and stopping.
        if (!directBootMode && ::controller.isInitialized) {
            executeStartup {
                if (controller.isStarted) controller.reconcile() else startControllerOrStopIdle("restart")
                DirectBootDrainWorker.enqueue(applicationContext)
                DirectBootSnapshotWriter.refresh(applicationContext)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Hardware sensor service stopping")
        // Signal the async startup gate read (if still pending) not to start the controller
        // after teardown, then stop accepting new startup tasks.
        destroyed = true
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver may not have been registered
        }
        try {
            unregisterReceiver(lifecycleReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver may not have been registered
        }
        unlockReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver may not have been registered
            }
        }
        unlockReceiver = null

        // MAIN returns immediately even if a persistence writer is queued behind an upload.
        // Queue behind construction/handover so even a late-built controller is stopped.
        startupExecutor.execute {
            try {
                if (::controller.isInitialized) controller.stop(isServiceDestroy = true)
            } catch (error: Exception) {
                Log.e(TAG, "Service-destroy flush failed", error)
                if (::controller.isInitialized) {
                    controller.recordDestroyFlushFailure("destroy-flush failed: ${error.javaClass.simpleName}")
                }
            } finally {
                lossExecutor.shutdown()
            }
        }
        startupExecutor.shutdown()

        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The recorder writes through Room (credential-encrypted) — unreachable while locked.
        if (directBootMode) return
        // Build the event now so it carries the time Android delivered the callback, not the
        // time a busy lifecycle executor got to it.
        DeviceLifecycleEventRecorder.recordAsync(applicationContext, DeviceLifecycleEventRecorder.lowMemoryEvent(level))
    }

    private fun startForegroundNotification(): Boolean {
        val channelId = ensureSilentSensorChannel()

        // Tapping the foreground notification opens the live app on the Data Sharing tab —
        // where sensor collection is managed — not the retired SettingsActivity preferences screen.
        val pendingIntent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_data_sharing)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }.let {
            PendingIntent.getActivity(applicationContext, 0, it, getPendingIntentMutabilityFlag(0))
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(getString(R.string.sensor_notification_title))
            .setContentText(getString(R.string.sensor_notification_message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(ContextCompat.getColor(applicationContext, R.color.colorPrimary))
            .setContentIntent(pendingIntent)
            .build()

        val notificationId = applicationContext.resources.getInteger(R.integer.sensor_service_notification_id)
        return promoteForegroundServiceIfPermitted(this, notificationId, notification)
    }

    /**
     * A dedicated LOW-importance channel for the sensor foreground-service notification, so turning a
     * sensor on never produces an alert. A foreground service must keep a persistent notification;
     * LOW importance + silent makes it a quiet, collapsed status entry rather than the heads-up alert
     * the shared (HIGH-importance) Chronicle channel produced.
     */
    private fun ensureSilentSensorChannel(): String {
        val channel = NotificationChannel(
            SENSOR_SERVICE_CHANNEL_ID,
            getString(R.string.sensor_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
        return SENSOR_SERVICE_CHANNEL_ID
    }
}

private const val SENSOR_SERVICE_CHANNEL_ID = "chronicle_sensor_collection"

// Serialize startup and retry transient storage faults locally, without a network constraint.
private fun sensorStartupExecutor(): ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1).apply {
    setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
}

/**
 * Builds the [SensorRuntimeController] with its production seams. The
 * [SensorGateway.SampleListener] feeds every continuous and trigger sample straight
 * into the controller's buffer — exactly the path the legacy `onSensorChanged` /
 * `recordSample` took.
 */
private fun buildSensorController(appContext: Context, isDestroyed: () -> Boolean, reportLoss: (String, Int) -> Unit): SensorRuntimeController {
    val db = ChronicleDb.getInstance(appContext)
    lateinit var built: SensorRuntimeController
    val gateway = AndroidSensorGateway(
        appContext,
        object : SensorGateway.SampleListener {
            override fun onSample(
                sensorType: AndroidSensorType,
                values: FloatArray,
                accuracy: Int,
                timestamp: OffsetDateTime,
            ) {
                built.recordSample(sensorType, values, accuracy, timestamp)
            }

            override fun onTrigger(
                sensorType: AndroidSensorType,
                values: FloatArray,
                timestamp: OffsetDateTime,
            ) {
                built.recordSample(sensorType, values, null, timestamp)
            }

            override fun onCapturedSample(sensorType: AndroidSensorType, values: FloatArray, accuracy: Int?,
                timestamp: OffsetDateTime,
                origin: CollectionPersistenceGuard) {
                built.recordSample(sensorType, values, accuracy, timestamp, origin)
            }

            override fun onPersistentRegistrationLost(sensorType: AndroidSensorType) {
                built.onPersistentRegistrationLost(sensorType)
            }
        },
        captureAdmission = { ResearchPersistenceGate.captureObservation(appContext, SensorCollectionModules.moduleFor(it)) },
    )
    built = SensorRuntimeController(
        gateway = gateway,
        settings = SensorSettingsRuntimeSettings(appContext),
        retainOnStop = true,
        sampleLease = { ResearchPersistenceGate.withReadLease(it) },
        observationAdmission = { ResearchPersistenceGate.captureObservation(appContext, SensorCollectionModules.moduleFor(it)) },
        sink = SensorSampleSink(
            db.sensorSampleDao(),
            persistenceGuard = ResearchPersistenceGate.guard(appContext),
        ),
        scheduler = ExecutorSensorRuntimeScheduler(),
        // Per-sensor collection gate (design §7, per-sensor consent redesign): even if a
        // legacy path started this service, no sample for a given sensor is persisted until
        // that sensor's module is server-enabled AND acknowledged on-device. Fail-closed.
        collectionGate = { sensorType ->
            ResearchPersistenceGate.collectsNow(appContext, SensorCollectionModules.moduleFor(sensorType))
        },
        collectionAdmission = { !isDestroyed() && StorageAdmission.cachedAllowed(appContext) },
        prepareCollection = { !isDestroyed() && StorageAdmission.allowed(appContext) },
        reportLoss = reportLoss,
    )
    return built
}
