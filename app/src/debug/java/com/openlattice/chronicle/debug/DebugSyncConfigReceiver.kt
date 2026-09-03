package com.openlattice.chronicle.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.device.collectExpansionSamples
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.CollectionSettingsSyncWorker
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.DeviceInstanceIdentity
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.getDevice
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.sync.ChronicleSyncStrategy
import com.openlattice.chronicle.services.sync.SyncRuntimeConfig
import com.openlattice.chronicle.services.sync.enqueueImmediateAuxiliaryUploads
import com.openlattice.chronicle.services.sync.scheduleChronicleSyncWork
import com.openlattice.chronicle.services.sync.triggerImmediateChronicleSync
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.PendingUploadCounter
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.AUTH_MODE_DEVICE_ID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.utils.Utils
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.OffsetDateTime
import java.util.UUID
import javax.net.ssl.SSLException

private const val TAG = "DebugSyncConfigReceiver"
private const val ACTION_SET_SYNC_CONFIG = "com.openlattice.chronicle.debug.SET_SYNC_CONFIG"
private const val ACTION_DUMP_LOCAL_STATE = "com.openlattice.chronicle.debug.DUMP_LOCAL_STATE"
private const val ACTION_ENROLL_SERVER = "com.openlattice.chronicle.debug.ENROLL_SERVER"
private const val ACTION_SET_SERVER_ENABLED = "com.openlattice.chronicle.debug.SET_SERVER_ENABLED"
private const val ACTION_ASSERT_SINGLE_DESTINATION = "com.openlattice.chronicle.debug.ASSERT_SINGLE_DESTINATION"
private const val ACTION_TRIGGER_VALIDATION_WORK = "com.openlattice.chronicle.debug.TRIGGER_VALIDATION_WORK"
private const val EXTRA_STRATEGY = "strategy"
private const val EXTRA_INTERVAL_MINUTES = "interval_minutes"
private const val EXTRA_REQUIRES_BATTERY_NOT_LOW = "requires_battery_not_low"
private const val EXTRA_RUN_NOW = "run_now"
private const val EXTRA_RESCHEDULE = "reschedule"
private const val EXTRA_SERVER_ID = "server_id"
private const val EXTRA_SERVER_NAME = "server_name"
private const val EXTRA_SERVER_URL = "server_url"
private const val EXTRA_STUDY_ID = "study_id"
private const val EXTRA_PARTICIPANT_ID = "participant_id"
private const val EXTRA_MOBILE_SIGNING_SECRET_OVERRIDE = "mobile_signing_secret_override"
private const val EXTRA_ENABLED = "enabled"
private const val EXTRA_SET_PRIMARY = "set_primary"
private const val EXTRA_ACCEPT_OPTIONAL_MODULES = "accept_optional_modules"
private const val EXTRA_EXPECTED_ENABLED_SERVERS = "expected_enabled_servers"
private const val EXTRA_RUN_SYNC = "run_sync"
private const val EXTRA_COLLECT_EXPANSION = "collect_expansion"
private const val EXTRA_UPLOAD_EXPANSION = "upload_expansion"

private data class DebugBroadcastResult(
    val ok: Boolean,
    val data: String,
)

class DebugSyncConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_SYNC_CONFIG -> handleSyncConfig(context, intent)
            ACTION_DUMP_LOCAL_STATE,
            ACTION_ENROLL_SERVER,
            ACTION_SET_SERVER_ENABLED,
            ACTION_ASSERT_SINGLE_DESTINATION,
            ACTION_TRIGGER_VALIDATION_WORK -> handleDebugAutomation(context, intent)
            else -> {
                Log.w(TAG, "Ignoring unsupported action=${intent.action}")
                return
            }
        }
    }

    private fun handleSyncConfig(context: Context, intent: Intent) {
        val strategy = if (intent.hasExtra(EXTRA_STRATEGY)) {
            ChronicleSyncStrategy.fromConfigValue(intent.getStringExtra(EXTRA_STRATEGY))
        } else {
            null
        }
        val interval = if (intent.hasExtra(EXTRA_INTERVAL_MINUTES)) {
            intent.getLongExtra(EXTRA_INTERVAL_MINUTES, 15L)
        } else {
            null
        }
        val batteryNotLow = if (intent.hasExtra(EXTRA_REQUIRES_BATTERY_NOT_LOW)) {
            intent.getBooleanExtra(EXTRA_REQUIRES_BATTERY_NOT_LOW, false)
        } else {
            null
        }

        val config = SyncRuntimeConfig.save(
            context = context,
            strategy = strategy,
            intervalMinutes = interval,
            requiresBatteryNotLow = batteryNotLow
        )
        Log.i(TAG, "Saved sync config strategy=${config.strategy.configValue} interval=${config.intervalMinutes}m batteryNotLow=${config.requiresBatteryNotLow}")

        if (intent.getBooleanExtra(EXTRA_RESCHEDULE, true)) {
            scheduleChronicleSyncWork(context)
            Log.i(TAG, "Rescheduled sync work")
        }
        if (intent.getBooleanExtra(EXTRA_RUN_NOW, false)) {
            triggerImmediateChronicleSync(context)
            Log.i(TAG, "Triggered immediate sync work")
        }
    }

    private fun handleDebugAutomation(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            val result = try {
                when (intent.action) {
                    ACTION_DUMP_LOCAL_STATE -> dumpLocalState(appContext, "dump_local_state")
                    ACTION_ENROLL_SERVER -> enrollServer(appContext, intent)
                    ACTION_SET_SERVER_ENABLED -> setServerEnabled(appContext, intent)
                    ACTION_ASSERT_SINGLE_DESTINATION -> assertSingleDestination(appContext, intent)
                    ACTION_TRIGGER_VALIDATION_WORK -> triggerValidationWork(appContext, intent)
                    else -> DebugBroadcastResult(false, "unsupported action=${intent.action}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Debug automation action failed action=${intent.action}", e)
                DebugBroadcastResult(false, "error=${e.javaClass.simpleName}: ${e.message ?: "unknown"}")
            }

            if (result.ok) {
                Log.i(TAG, result.data)
                pendingResult.setResultCode(Activity.RESULT_OK)
            } else {
                Log.w(TAG, result.data)
                pendingResult.setResultCode(Activity.RESULT_CANCELED)
            }
            pendingResult.setResultData(result.data)
            pendingResult.finish()
        }.start()
    }

    private fun enrollServer(context: Context, intent: Intent): DebugBroadcastResult {
        val rawUrl = intent.getStringExtra(EXTRA_SERVER_URL)
            ?: return DebugBroadcastResult(false, "server_url is required")
        val url = Utils.normalizeTrustedServerUrl(rawUrl)
            ?: return DebugBroadcastResult(false, "server_url is not trusted")
        val studyId = intent.getStringExtra(EXTRA_STUDY_ID)
            ?.takeIf { Utils.isValidUUID(it) }
            ?.let(UUID::fromString)
            ?: return DebugBroadcastResult(false, "valid study_id is required")
        val participantId = intent.getStringExtra(EXTRA_PARTICIPANT_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return DebugBroadcastResult(false, "participant_id is required")
        val mobileSigningSecretOverride = intent
            .getStringExtra(EXTRA_MOBILE_SIGNING_SECRET_OVERRIDE)
            ?.takeIf { it.isNotBlank() }
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
        val setPrimary = intent.getBooleanExtra(EXTRA_SET_PRIMARY, false)
        val acceptOptional = intent.getBooleanExtra(EXTRA_ACCEPT_OPTIONAL_MODULES, true)
        val serverName = intent.getStringExtra(EXTRA_SERVER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: DebugAutomationStateFormatter.hostOnly(url)

        val studyApi = try {
            UploadWorker.getChronicleStudyApi(url, mobileSigningSecretOverride)
        } catch (e: Exception) {
            Log.w(TAG, "Debug enrollment could not create trusted API client: ${e.javaClass.simpleName}")
            return DebugBroadcastResult(false, "enroll_server failed: api_client")
        }
        val deviceInstanceId = DeviceInstanceIdentity.getOrCreate(context)
        val db = ChronicleDb.getInstance(context)
        val reservation = try {
            db.uploadServerDao().reserveSingleEnrollment(
                UploadServerEntity(
                    name = serverName,
                    url = url,
                    studyId = studyId.toString(),
                    participantId = participantId,
                    sourceDeviceId = deviceInstanceId,
                    mobileSigningSecretOverride = mobileSigningSecretOverride,
                    enabled = false,
                    createdAt = OffsetDateTime.now().toString(),
                ),
            )
        } catch (e: Exception) {
            return DebugBroadcastResult(false, "enroll_server failed: enrollment_conflict")
        }
        val response = try {
            studyApi.enroll(studyId, participantId, deviceInstanceId, getDevice(deviceInstanceId))
        } catch (e: Exception) {
            db.uploadServerDao().releaseEnrollmentReservation(reservation)
            val reason = classifyEnrollmentFailure(e)
            Log.w(TAG, "Debug enrollment request failed: $reason")
            return DebugBroadcastResult(false, "enroll_server failed: enroll:$reason")
        }

        val authMode = if (response.apiKey != null) AUTH_MODE_API_KEY else AUTH_MODE_DEVICE_ID
        db.uploadServerDao().finalizeSingleEnrollment(
            reservation = reservation,
            requestedUrl = url,
            requestedStudyId = studyId.toString(),
            requestedParticipantId = participantId,
            name = serverName,
            sourceDeviceId = deviceInstanceId,
            authMode = authMode,
            apiKey = response.apiKey,
            mobileSigningSecretOverride = mobileSigningSecretOverride,
        )
        if (!enabled) {
            db.uploadServerDao().setEnabled(reservation.serverId, false)
        }

        if (setPrimary) {
            val fetched = try {
                studyApi.getDataCollectionSettings(studyId)
            } catch (e: Exception) {
                Log.w(TAG, "Debug enrollment could not fetch collection settings: ${e.javaClass.simpleName}")
                return DebugBroadcastResult(false, "enroll_server failed: collection_settings")
            }
            val enrollmentSettings = EnrollmentSettings(context)
            enrollmentSettings.setStudyId(studyId)
            enrollmentSettings.setParticipantId(participantId)
            enrollmentSettings.setParticipationStatus(ParticipationStatus.ENROLLED)
            CollectionSettingsSyncWorker.schedulePeriodic(context)
            val coordinator = CollectionLoopCoordinator(context)
            val plan = coordinator.consentPlanFor(fetched)
            val accepted = linkedSetOf<CollectionModuleId>().apply {
                addAll(plan.required)
                if (acceptOptional) addAll(plan.optional)
            }
            val declined = if (acceptOptional) emptySet() else plan.optional.toSet()
            val partition = coordinator.enrollmentModulePartitionFor(fetched, accepted, declined)
            coordinator.seedAndApplyDecisions(
                studyId,
                fetched,
                partition.accepted,
                partition.declined,
                partition.unavailable,
            )
            CollectionSettingsSyncWorker.enqueueNow(context)
        }

        return dumpLocalState(db, "enroll_server")
    }

    private fun classifyEnrollmentFailure(error: Throwable): String {
        generateSequence(error) { throwable ->
            when (throwable) {
                is UndeclaredThrowableException -> throwable.undeclaredThrowable
                is InvocationTargetException -> throwable.targetException
                else -> throwable.cause
            }
        }.forEach { throwable ->
            when (throwable) {
                is ChronicleCallException -> return "http_${throwable.code}"
                is SSLException -> return "ssl_exception"
                is SocketTimeoutException -> return "timeout"
                is UnknownHostException -> return "unknown_host"
                is IOException -> return "io_exception"
            }
        }
        return error.javaClass.simpleName
    }

    private fun setServerEnabled(context: Context, intent: Intent): DebugBroadcastResult {
        val db = ChronicleDb.getInstance(context)
        val dao = db.uploadServerDao()
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
        val id = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
        val url = intent.getStringExtra(EXTRA_SERVER_URL)

        val updated = when {
            id > 0L -> dao.setEnabled(id, enabled)
            !url.isNullOrBlank() -> dao.getByUrl(url)?.let { dao.setEnabled(it.id, enabled) } ?: 0
            else -> throw IllegalArgumentException("server_id or server_url is required")
        }
        if (updated == 0) throw IllegalArgumentException("no upload server matched")

        return dumpLocalState(db, "set_server_enabled")
    }

    private fun assertSingleDestination(context: Context, intent: Intent): DebugBroadcastResult {
        val db = ChronicleDb.getInstance(context)
        val servers = db.uploadServerDao().getAll()
        val expectedEnabled = intent.getIntExtra(EXTRA_EXPECTED_ENABLED_SERVERS, 1)
        val enabledCount = servers.count { it.enabled }
        val distinctHosts = servers.map { DebugAutomationStateFormatter.hostOnly(it.url) }.toSet().size
        val passed = servers.size == 1 &&
            enabledCount == expectedEnabled &&
            distinctHosts == 1

        val assertion = DebugDestinationAssertion(
            expectedTotalServers = 1,
            expectedEnabledServers = expectedEnabled,
            actualTotalServers = servers.size,
            actualEnabledServers = enabledCount,
            distinctHosts = distinctHosts,
            passed = passed,
        )
        val data = DebugAutomationStateFormatter.stateJson(
            operation = "assert_single_destination",
            servers = servers,
            queueDepths = queueDepths(db),
            assertion = assertion,
        )
        return DebugBroadcastResult(passed, data)
    }

    private fun triggerValidationWork(context: Context, intent: Intent): DebugBroadcastResult {
        val runSync = intent.getBooleanExtra(EXTRA_RUN_SYNC, true)
        val collectExpansion = intent.getBooleanExtra(EXTRA_COLLECT_EXPANSION, true)
        val uploadExpansion = intent.getBooleanExtra(EXTRA_UPLOAD_EXPANSION, true)

        if (collectExpansion && !uploadExpansion && !runSync) {
            collectExpansionSamples(context)
        }
        if (uploadExpansion && !runSync) {
            enqueueImmediateAuxiliaryUploads(
                WorkManager.getInstance(context),
                collectExpansionBeforeUpload = collectExpansion,
            )
        }
        if (runSync) {
            triggerImmediateChronicleSync(context)
        }

        val db = ChronicleDb.getInstance(context)
        val data = DebugAutomationStateFormatter.stateJson(
            operation = "trigger_validation_work",
            servers = db.uploadServerDao().getAll(),
            queueDepths = queueDepths(db),
            triggered = mapOf(
                "sync" to runSync,
                "collectExpansion" to collectExpansion,
                "uploadExpansion" to uploadExpansion,
            ),
        )
        return DebugBroadcastResult(true, data)
    }

    private fun dumpLocalState(context: Context, operation: String): DebugBroadcastResult =
        dumpLocalState(ChronicleDb.getInstance(context), operation)

    private fun dumpLocalState(db: ChronicleDb, operation: String): DebugBroadcastResult =
        DebugBroadcastResult(
            true,
            DebugAutomationStateFormatter.stateJson(
                operation = operation,
                servers = db.uploadServerDao().getAll(),
                queueDepths = queueDepths(db),
            ),
        )

    private fun queueDepths(db: ChronicleDb): Map<String, Int> {
        val pending = PendingUploadCounter.snapshot(db)
        val restricted = pending.auxiliary.restricted
        return linkedMapOf(
            "usageQueue" to pending.usageAndLifecycle,
            "userQueue" to pending.localParticipantLabels,
            "sensorSamples" to pending.sensorSamples,
            "batterySamples" to pending.batterySamples,
            "interactionSamples" to (restricted?.interactionEvents ?: 0),
            "audioActivitySamples" to (restricted?.audioActivity ?: 0),
            "audioContentSamples" to (restricted?.audioContent ?: 0),
            "notificationActivitySamples" to (restricted?.notificationActivity ?: 0),
            "sleepSamples" to (restricted?.sleep ?: 0),
            "activityRecognitionSamples" to (restricted?.activityRecognition ?: 0),
            "healthMetricSamples" to (restricted?.healthMetrics ?: 0),
            "connectivityStateSamples" to pending.auxiliary.connectivityState,
            "appNetworkUsageSamples" to pending.auxiliary.appNetworkUsage,
            "deviceSettingsSamples" to pending.auxiliary.deviceSettings,
        )
    }
}
