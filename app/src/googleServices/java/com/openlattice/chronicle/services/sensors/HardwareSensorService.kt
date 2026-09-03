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
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.directboot.DirectBootDrainWorker
import com.openlattice.chronicle.collection.directboot.DirectBootProcessInit
import com.openlattice.chronicle.collection.directboot.DirectBootRuntimeSettings
import com.openlattice.chronicle.collection.directboot.DirectBootSampleBuffer
import com.openlattice.chronicle.collection.directboot.DirectBootSnapshotWriter
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.sensors.AndroidSensorGateway
import com.openlattice.chronicle.collection.sensors.ExecutorSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorGateway
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sensors.SensorSettingsRuntimeSettings
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.receivers.lifecycle.DeviceLifecycleReceiver
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.deviceLifecycleIntentFilter
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

private val TAG = HardwareSensorService::class.java.simpleName

/**
 * The Android service shell for hardware sensor collection (refactor plan §9.1 step 3 —
 * "keep `HardwareSensorService` as the Android service shell").
 *
 * Phase 6A extracted the SensorManager-listener / duty-cycle / buffer / power-save /
 * battery logic into [SensorRuntimeController]. This class is now only the Android
 * coupling that the controller cannot own because it has no `Context`:
 *
 *  - the foreground service lifecycle (`onCreate` / `onStartCommand` / `onDestroy`);
 *  - the foreground notification + channel;
 *  - the `batteryReceiver` (`ACTION_BATTERY_CHANGED`) and `lifecycleReceiver` registration;
 *  - the `onTrimMemory` low-memory lifecycle event;
 *  - the bounded main-thread destroy-flush.
 *
 * Everything the controller owns it owns through seams: [SensorGateway] (SensorManager /
 * BatteryManager / PowerManager), [SensorSampleSink] (the sanctioned `sensor_samples`
 * writer), [SensorSettingsRuntimeSettings] (the duty-cycle config) and
 * [ExecutorSensorRuntimeScheduler] (the duty-cycle scheduler). Every collected sample is
 * written through the sink — this service never calls `sensorSampleDao().insertAll`
 * directly any more.
 *
 * `startService`/`stopService` remain the entry points; per design §1C.4 they should be
 * called only via `HardwareSensorsCollectionModule` / the module manager (the Phase 6B
 * switch migrates the legacy direct callers).
 *
 * **Direct-boot mode (2026-07-15):** when started before the user's first unlock (a locked
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
class HardwareSensorService : Service() {

    @Volatile
    private lateinit var controller: SensorRuntimeController
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

    // Runs the one-shot startup collection-gate read off the main thread (the gate reads
    // Room, which forbids main-thread access). Single-thread: there is exactly one gate
    // read per service creation.
    private val startupExecutor = Executors.newSingleThreadExecutor()

    // Set in onDestroy so the async startup gate read does not start the controller after
    // the service has been torn down (the gate read widens the start-after-destroy window).
    @Volatile
    private var destroyed = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                controller.onBatteryLevel(pct)
            }
        }
    }

    companion object {
        fun startService(context: Context) {
            tryStartService(context)
        }

        fun tryStartService(context: Context): Boolean {
            val intent = Intent(context, HardwareSensorService::class.java)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: IllegalStateException) {
                Log.w(
                    TAG,
                    "Hardware sensor foreground service start deferred by OS background-start policy: ${e.message}"
                )
                false
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, HardwareSensorService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        directBootMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            getSystemService(UserManager::class.java)?.isUserUnlocked == false
        controller = if (directBootMode) {
            buildDirectBootController(applicationContext)
        } else {
            buildController(applicationContext)
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (directBootMode) {
            // The lifecycle receiver records through Room (credential-encrypted) — deferred
            // to the unlock transition. Watch for first unlock to hand over to the normal
            // pipeline without a service restart.
            registerUnlockReceiver()
        } else {
            registerLifecycleReceiver()
        }

        // Android's foreground-service contract requires startForeground() within ~5s of
        // startForegroundService(), and the collection gate reads Room (forbidden on the
        // main thread). So promote to foreground first, then re-check the gate off the main
        // thread. If hardware_sensors is not server-enabled AND acknowledged on this device
        // (design §7), there is nothing to collect — stop the service so no idle
        // "collecting" notification is left showing. The sample-persistence gate inside the
        // controller already prevents an un-acknowledged sample from being written; this
        // additionally stops the idle foreground service so it isn't running for nothing.
        // Fail-closed: a gate read error returns false (CollectionGate), so the service stops.
        startForegroundNotification()
        if (directBootMode) {
            startupExecutor.execute {
                if (destroyed) return@execute
                val snapshot = DirectBootSensorSnapshot(applicationContext)
                if (snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS)) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                getSystemService(UserManager::class.java)?.isUserUnlocked == true
            ) {
                onUserUnlocked()
            }
            return
        }
        startupExecutor.execute {
            // Normal mode owns direct-boot maintenance: replay any pre-unlock buffer into the
            // Room queue, and rewrite the device-protected snapshot from the live gate state
            // (this start is re-issued on every consent toggle / settings sync).
            if (!DirectBootSampleBuffer(applicationContext).isEmpty()) {
                DirectBootDrainWorker.enqueue(applicationContext)
            }
            DirectBootSnapshotWriter.refresh(applicationContext)
            if (anySensorCollects(applicationContext)) {
                // Guard the start-after-destroy race the gate-read delay introduces: an
                // external stopService during the gate read can run onDestroy ->
                // controller.stop() -> scheduler.shutdownNow() before this branch starts the
                // controller, which would reject the duty-cycle task. The destroyed flag is
                // the primary guard; the catch is the backstop for the residual TOCTOU.
                if (destroyed) {
                    Log.i(TAG, "Service destroyed before startup gate read completed; not starting controller")
                    return@execute
                }
                try {
                    controller.start()
                } catch (e: RejectedExecutionException) {
                    Log.i(TAG, "Sensor runtime shut down during startup gate read; not starting", e)
                }
            } else {
                Log.i(TAG, "no sensor enabled/acknowledged; stopping idle foreground service")
                stopIdleService()
            }
        }
    }

    private fun stopIdleService() {
        ContextCompat.getMainExecutor(this).execute {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun registerLifecycleReceiver() {
        if (lifecycleReceiverRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lifecycleReceiver, deviceLifecycleIntentFilter(), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(lifecycleReceiver, deviceLifecycleIntentFilter())
        }
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
        // ACTION_USER_UNLOCKED is a protected system broadcast; NOT_EXPORTED still receives it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
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
        // This process started in direct-boot mode, so the androidx.startup initializers
        // were skipped for its lifetime — re-run them (idempotent; main thread here).
        DirectBootProcessInit.reinitializeAfterUnlock(applicationContext)
        registerLifecycleReceiver()
        startupExecutor.execute {
            if (destroyed) return@execute
            controller.stop() // flush remaining direct-boot samples into the DE buffer
            DirectBootDrainWorker.enqueue(applicationContext)
            DirectBootSnapshotWriter.refresh(applicationContext)
            controller = buildController(applicationContext)
            if (anySensorCollects(applicationContext)) {
                if (destroyed) return@execute
                try {
                    controller.start()
                } catch (e: RejectedExecutionException) {
                    Log.i(TAG, "Sensor runtime shut down during unlock transition; not starting", e)
                }
            } else {
                Log.i(TAG, "no sensor enabled/acknowledged after unlock; stopping idle foreground service")
                stopIdleService()
            }
        }
    }

    /**
     * Builds the [SensorRuntimeController] with its production seams. The
     * [SensorGateway.SampleListener] feeds every continuous and trigger sample straight
     * into the controller's buffer — exactly the path the legacy `onSensorChanged` /
     * `recordSample` took.
     */
    private fun buildController(appContext: Context): SensorRuntimeController {
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

                override fun onPersistentRegistrationLost(sensorType: AndroidSensorType) {
                    built.onPersistentRegistrationLost(sensorType)
                }
            },
        )
        built = SensorRuntimeController(
            gateway = gateway,
            settings = SensorSettingsRuntimeSettings(appContext),
            sink = SensorSampleSink(
                db.sensorSampleDao(),
                persistenceGuard = ResearchPersistenceGate.guard(appContext),
                sampleAllowedAtPersistence = { sample ->
                    runCatching { AndroidSensorType.valueOf(sample.sensorType) }
                        .getOrNull()
                        ?.let { sensorType ->
                            CollectionLoopStore.of(appContext).collects(
                                SensorCollectionModules.moduleFor(sensorType),
                            )
                        } == true
                },
            ),
            scheduler = ExecutorSensorRuntimeScheduler(),
            // Per-sensor collection gate (design §7, per-sensor consent redesign): even if a
            // legacy path started this service, no sample for a given sensor is persisted until
            // that sensor's module is server-enabled AND acknowledged on-device. Fail-closed.
            collectionGate = { sensorType ->
                CollectionGate.collects(appContext, SensorCollectionModules.moduleFor(sensorType))
            },
        )
        return built
    }

    /**
     * Builds the controller for the direct-boot window: same gateway/scheduler, but settings
     * and the per-sensor gate come from the device-protected [DirectBootSensorSnapshot]
     * (written from live gate reads while unlocked) and samples land in the encrypted
     * [DirectBootSampleBuffer] instead of the credential-encrypted Room queue. Nothing here
     * may touch [ChronicleDb], `SensorSettings`, or [CollectionGate].
     */
    private fun buildDirectBootController(appContext: Context): SensorRuntimeController {
        val snapshot = DirectBootSensorSnapshot(appContext)
        val collectable = snapshot.collectableSensors()
        val buffer = DirectBootSampleBuffer(appContext)
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

                override fun onPersistentRegistrationLost(sensorType: AndroidSensorType) {
                    built.onPersistentRegistrationLost(sensorType)
                }
            },
        )
        built = SensorRuntimeController(
            gateway = gateway,
            settings = DirectBootRuntimeSettings(snapshot),
            sink = SensorSampleWriter { samples -> buffer.append(samples) },
            scheduler = ExecutorSensorRuntimeScheduler(),
            // The snapshot set already has the consent gate applied (it is written from live
            // gate reads in unlocked mode), and gate state cannot change while still locked.
            collectionGate = { sensorType -> sensorType in collectable },
        )
        return built
    }

    /** Whether any per-sensor module is currently collectable (server-enabled AND acknowledged). */
    private fun anySensorCollects(context: Context): Boolean =
        SensorCollectionModules.sensorModuleIds.any { CollectionGate.collects(context, it) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Hardware sensor service started")
        // A start while the service is already running means consent or study settings changed
        // the per-sensor set (a Data Sharing toggle / settings sync re-issues startService).
        // Reconcile so a newly-enabled sensor begins collecting without a full service restart;
        // the controller arms/schedules only the sensors not already running. On first creation
        // the controller is not yet started (its start is gated behind the async startup read),
        // so this is a no-op then — start() does the initial scheduling.
        // Direct-boot mode skips this: consent/settings cannot change while still locked, and
        // the snapshot rewrite reads credential-encrypted state.
        if (!directBootMode && ::controller.isInitialized && controller.isStarted) {
            controller.reconcile()
            startupExecutor.execute {
                if (!destroyed) DirectBootSnapshotWriter.refresh(applicationContext)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Hardware sensor service stopping")
        // Signal the async startup gate read (if still pending) not to start the controller
        // after teardown, then stop accepting new startup tasks.
        destroyed = true
        startupExecutor.shutdown()
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

        // The controller's stop() drains the buffer through the sink. onDestroy runs on
        // the main thread, where Room forbids DB access, so the flush is done on a
        // short-lived executor with a bounded wait — exactly the legacy behaviour. A
        // timeout or exception is surfaced into the controller's diagnostics so a
        // destroy-flush failure is never silently swallowed (refactor plan §9.1 guardrail 3).
        val flushExecutor = Executors.newSingleThreadExecutor()
        val future = flushExecutor.submit {
            controller.stop(isServiceDestroy = true)
        }
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            val message = "destroy-flush timed out or failed: ${e.javaClass.simpleName}"
            Log.e(TAG, message, e)
            controller.recordDestroyFlushFailure(message)
        }
        flushExecutor.shutdown()

        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The recorder writes through Room (credential-encrypted) — unreachable while locked.
        if (directBootMode) return
        DeviceLifecycleEventRecorder.recordAsync(
            applicationContext,
            DeviceLifecycleEventRecorder.lowMemoryEvent(level),
        )
    }

    private fun startForegroundNotification() {
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
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(ContextCompat.getColor(applicationContext, R.color.colorPrimary))
            .setContentIntent(pendingIntent)
            .build()

        val notificationId = applicationContext.resources.getInteger(R.integer.sensor_service_notification_id)
        startForeground(notificationId, notification)
    }

    /**
     * A dedicated MIN-importance channel for the sensor foreground-service notification, so turning a
     * sensor on never produces an alert. A foreground service must keep a persistent notification;
     * MIN importance + silent makes it a quiet, collapsed status entry rather than the heads-up alert
     * the shared (HIGH-importance) Chronicle channel produced.
     */
    private fun ensureSilentSensorChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SENSOR_SERVICE_CHANNEL_ID,
                getString(R.string.sensor_notification_title),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        return SENSOR_SERVICE_CHANNEL_ID
    }
}

private const val SENSOR_SERVICE_CHANNEL_ID = "chronicle_sensor_collection"
