package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.*
import com.google.gson.Gson
import com.openlattice.chronicle.R
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.sensors.NAME
import com.openlattice.chronicle.sensors.RECURRENCE_RULE
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.services.upload.completeServerForIdentity
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.utils.Utils.createNotificationChannel
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import org.apache.olingo.commons.api.edm.FullQualifiedName
import org.dmfs.rfc5545.recur.RecurrenceRule
import com.openlattice.chronicle.serialization.ChronicleCallException
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.concurrent.TimeUnit

const val NOTIFICATIONS_INTERVAL_MIN = 15L
const val NOTIFICATION_DELETED_ACTION = "NOTIFICATION_DELETED"
const val CHANNEL_ID = "Chronicle"

/** Unlock "who is using the device" prompts: own channel so muting surveys never mutes them. */
const val IDENTIFY_USER_CHANNEL_ID = "chronicle_identify_user"

/** Ongoing foreground notification of the unlock monitor: silent, so participants do not mute Chronicle. */
const val UNLOCK_MONITORING_CHANNEL_ID = "chronicle_unlock_monitoring"
const val NOTIFICATION_DETAILS = "NOTIFICATION_DETAILS"
const val SURVEY_NOTIFICATION_ACTION = "SURVEY_NOTIFICATION_ACTION"

val TAG = NotificationsWorker::class.java.simpleName

class NotificationsWorker(context: Context, workerParameters: WorkerParameters) :
    com.openlattice.chronicle.security.LeaseBoundWorker(context, workerParameters) {

    private lateinit var enrollmentSettings: EnrollmentSettings
    private lateinit var studyId: UUID
    private lateinit var participantId: String

    private var armingFailed = false
    private var statusFetched = false
    private var participationStatus: ParticipationStatus = ParticipationStatus.UNKNOWN
    private var studyQuestionnaires: Map<UUID, Map<FullQualifiedName, Set<Any>>> = mapOf()
    private var notificationsEnabled: Boolean = false

    private lateinit var chronicleApi: ChronicleStudyApi

    override fun runWork(): Result = try {
        val gate = com.openlattice.chronicle.collection.state.ResearchPersistenceGate
        val expected = gate.captureOwner(applicationContext)
        if (expected == null) Result.success() else {
            val generation = com.openlattice.chronicle.collection.state.ResearchErasureFence(applicationContext).settingsGeneration()
            val result = gate.runIfExpectedOwner(applicationContext, expected) { workAdmitted() } ?: Result.success()
            if (statusFetched) gate.applyParticipationStatus(applicationContext, expected, generation, participationStatus)
            result
        }
    } catch (error: Exception) {
        Log.w(TAG, "Reminder reconciliation failed", error)
        Result.retry()
    }

    private fun workAdmitted(): Result {

        if (!BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(NOTIFICATIONS_WORK_NAME)
            eraseSurveyArtifacts(applicationContext)
            EnrollmentSettings(applicationContext).apply {
                setMobileReminderRequestCodes(emptySet())
                setAwarenessNotificationsEnabled(false)
            }
            return if (armingFailed) Result.retry() else Result.success()
        }

        // required by android 8.0 and higher.
        createNotificationChannel(applicationContext)

        try {
            enrollmentSettings = EnrollmentSettings(applicationContext)
            studyId = enrollmentSettings.getStudyId()
            participantId = enrollmentSettings.getParticipantId()
            val server = completeServerForIdentity(
                ChronicleDb.getInstance(applicationContext).uploadServerDao().getConfiguredServer(),
                studyId,
                participantId,
            )
            if (server == null) {
                Log.w(TAG, "Skipping reminders without a complete matching study server")
                LocalTelemetry.logEvent(TelemetryEvents.NOTIFICATIONS_FAILURE, null)
                return Result.failure()
            }
            chronicleApi = UploadWorker.getChronicleStudyApi(
                server.url,
                server.mobileSigningSecretOverride,
            )

            if (server.authMode == AUTH_MODE_API_KEY) {
                reconcileApiKeyReminders(
                    serverUrl = server.url,
                    sourceDeviceId = server.sourceDeviceId,
                    apiKey = requireNotNull(server.apiKey) { "API-key enrollment is missing its credential" },
                )
                return if (armingFailed) Result.retry() else Result.success()
            }

            workHelper()
        } catch (e: Exception) {
            Log.i(javaClass.name, "Exception happened! ", e)
            LocalTelemetry.recordException(e)
            LocalTelemetry.logEvent(TelemetryEvents.NOTIFICATIONS_FAILURE, null)
            return Result.failure()
        }
        return if (armingFailed) Result.retry() else Result.success()
    }

    private fun reconcileApiKeyReminders(
        serverUrl: String,
        sourceDeviceId: String,
        apiKey: String,
    ) {
        Log.i(TAG, "Reconciling device-bound participant form reminders")
        val configuration = fetchReminderSchedule(chronicleApi, studyId, participantId, sourceDeviceId, apiKey)
        participationStatus = configuration.participationStatus
        statusFetched = true

        val notifications = configuration.forms.mapNotNull { form ->
            val type = when (form.formKind) {
                ParticipantFormKind.APP_USAGE -> NotificationType.AWARENESS
                ParticipantFormKind.QUESTIONNAIRE -> {
                    if (!BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
                        Log.w(TAG, "Ignoring questionnaire reminder outside the approved distribution boundary")
                        return@mapNotNull null
                    }
                    NotificationType.QUESTIONNAIRE
                }
                ParticipantFormKind.ENROLLMENT,
                ParticipantFormKind.TIME_USE_DIARY,
                ParticipantFormKind.PORTAL -> error("Server returned unsupported Android reminder kind")
            }
            NotificationDetails(
                id = form.resourceId?.toString() ?: studyId.toString(),
                type = type,
                recurrenceRule = form.recurrenceRule,
                title = form.title,
                message = applicationContext.getString(
                    if (type == NotificationType.AWARENESS) R.string.reminder_tap_survey else R.string.reminder_tap_questionnaire,
                ),
                serverUrl = serverUrl,
                accessCode = form.accessCode,
            )
        }

        synchronized(REMINDER_SCHEDULE_LOCK) {
            notifications.forEach { notification -> handleNotification(notification, cancel = false) }
            val currentCodes = notifications.map(NotificationDetails::requestCode).toSet()
            val staleCodes = enrollmentSettings.getMobileReminderRequestCodes() - currentCodes
            staleCodes.forEach(::cancelScheduledNotificationByRequestCode)
            enrollmentSettings.setMobileReminderRequestCodes(currentCodes)
        }
        enrollmentSettings.setAwarenessNotificationsEnabled(
            notifications.any { it.type == NotificationType.AWARENESS }
        )
    }

    private fun workHelper() {

        Log.i(TAG, "Notifications worker started")
        LocalTelemetry.logEvent(TelemetryEvents.NOTIFICATIONS_START, null)

        participationStatus = chronicleApi.getParticipationStatus(studyId, participantId)
            ?: ParticipationStatus.UNKNOWN
        notificationsEnabled = chronicleApi.isNotificationsEnabled(studyId) ?: false
        studyQuestionnaires = if (BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
            chronicleApi.getStudyQuestionnaires(studyId) ?: mapOf()
        } else {
            emptyMap()
        }

        statusFetched = true
        enrollmentSettings.setAwarenessNotificationsEnabled(notificationsEnabled)

        Log.i(javaClass.name, "Participation status: $participationStatus")
        Log.i(javaClass.name, "Study questionnaire count: ${studyQuestionnaires.size}")
        Log.i(javaClass.name, "Notification enabled: $notificationsEnabled")

        // Legacy device-id enrollments cannot obtain device-bound participant access codes.
        // Cancel their old bare-link reminders instead of emitting a link that either fails closed
        // at the web boundary or exposes participant identifiers without a capability. API-key
        // enrollments return above through reconcileApiKeyReminders().
        val notification = NotificationDetails(
            studyId.toString(),
            NotificationType.AWARENESS,
            "FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0",
            applicationContext.getString(R.string.reminder_survey_title),
            applicationContext.getString(R.string.reminder_tap_survey)
        )
        synchronized(REMINDER_SCHEDULE_LOCK) {
            handleNotification(
                notification,
                cancel = true,
            )
            cancelLegacyQuestionnaireNotifications()
            enrollmentSettings.getMobileReminderRequestCodes().forEach(::cancelScheduledNotificationByRequestCode)
            enrollmentSettings.setMobileReminderRequestCodes(emptySet())
        }
    }

    private fun cancelLegacyQuestionnaireNotifications() {
        for ((key, value) in studyQuestionnaires) {
            val recurrenceRuleSet = value[RECURRENCE_RULE]?.iterator()?.next()?.toString()
            val name = value[NAME]?.iterator()?.next()?.toString()
            if (!recurrenceRuleSet.isNullOrEmpty() && !name.isNullOrEmpty()) {
                recurrenceRuleSet.split("RRULE:").filter(String::isNotEmpty).forEach { rule ->
                    cancelScheduledNotification(
                        NotificationDetails(
                            key.toString(),
                            NotificationType.QUESTIONNAIRE,
                            rule,
                            name,
                            applicationContext.getString(R.string.reminder_tap_questionnaire),
                        ),
                    )
                }
            }
        }
    }

    private fun handleNotification(notification: NotificationDetails, cancel: Boolean) {
        if (cancel) {
            cancelScheduledNotification(notification)
        } else {
            scheduleNotification(notification)
        }
    }

    private fun scheduleNotification(notification: NotificationDetails) {
        Log.i(javaClass.name, "notification to schedule: $notification")

        enrollmentSettings.setMobileReminderRequestCodes(
            enrollmentSettings.getMobileReminderRequestCodes() + notification.requestCode())
        try {
            when (val outcome = armReminderOutcome(applicationContext, notification, createNotificationIntent(notification))) {
                is ReminderArmOutcome.Armed -> Log.i(javaClass.name, "notification time: ${Date(outcome.triggerAtMillis)}")
                ReminderArmOutcome.NotDue -> Unit
                ReminderArmOutcome.Failed -> armingFailed = true
            }
        } catch (e: Exception) {
            Log.i(javaClass.name, "caught exception", e)
        }
    }


    private fun cancelScheduledNotification(notification: NotificationDetails) {
        Log.i(javaClass.name, "Notification to cancel: $notification")

        val intent = createNotificationIntent(notification)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            notification.requestCode(),
            intent,
            getPendingIntentMutabilityFlag(PendingIntent.FLAG_NO_CREATE)
        )

        val alarmManager: AlarmManager =
            applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
        clearReminderAlarmState(applicationContext, notification.requestCode())
    }

    private fun cancelScheduledNotificationByRequestCode(requestCode: Int) =
        cancelReminderByRequestCode(applicationContext, requestCode)

    private fun createNotificationIntent(notification: NotificationDetails): Intent {
        return Intent(applicationContext, SurveyNotificationsReceiver::class.java).apply {
            val scope = com.openlattice.chronicle.collection.state.ResearchPersistenceGate.observationScope(
                applicationContext, com.openlattice.chronicle.collection.CollectionModuleId.QUESTIONNAIRE,
            ) ?: error("Reminder enrollment retired")
            putExtra(SURVEY_ENROLLMENT_SCOPE, scope.first)
            putExtra(NOTIFICATION_DETAILS, Gson().toJson(notification))
            putExtra(STUDY_ID, enrollmentSettings.getStudyId().toString())
            putExtra(PARTICIPANT_ID, enrollmentSettings.getParticipantId())
            action = SURVEY_NOTIFICATION_ACTION
        }
    }
}

/**
 * The side-effect-free GET, or the code-minting POST on servers without it: those answer 404/405,
 * and releases through 2026.10.3 answer 500 from their catch-all exception handler. Auth and
 * rate-limit refusals would refuse the POST as well.
 */
internal fun fetchReminderSchedule(
    api: ChronicleStudyApi,
    studyId: UUID,
    participantId: String,
    sourceDeviceId: String,
    apiKey: String,
): MobileReminderConfiguration = try {
    api.getMobileReminderSchedule(studyId, participantId, sourceDeviceId, apiKey)
} catch (error: ChronicleCallException) {
    if (error.code == 401 || error.code == 403 || error.code == 429) throw error
    api.getMobileReminderConfiguration(studyId, participantId, sourceDeviceId, apiKey)
}

internal const val REMINDER_ALARM_STATE_PREFS = "reminder_alarm_schedule"
internal val REMINDER_OVERDUE_GRACE_MS = TimeUnit.HOURS.toMillis(2)

// RFC 5545 recurrence iterator for the next local alarm occurrence strictly after afterMillis.
// The iterator's start is truncated to the second and is itself an instance when it matches the
// rule (always, for a bare FREQ=DAILY), so its first value can be at or before afterMillis; an
// alarm armed there fires at once and re-arms itself in a loop.
internal fun getNextRecurringDate(recurrenceRule: String, afterMillis: Long): Long? = try {
    val iterator = RecurrenceRule(recurrenceRule).iterator(afterMillis, TimeZone.getDefault())
    var next: Long? = null
    while (iterator.hasNext()) {
        val candidate = iterator.nextMillis()
        if (candidate > afterMillis) {
            next = candidate
            break
        }
    }
    next
} catch (error: Exception) {
    Log.i(TAG, "Unable to resolve reminder recurrence", error)
    null
}

/** Serializes every reminder alarm decision: worker refreshes with their stale-code cancellation, and receiver re-arms. */
internal val REMINDER_SCHEDULE_LOCK = Any()

/** [armReminder]'s clock; a test replaces it to interleave a refresh with a receiver advance. */
@androidx.annotation.VisibleForTesting
internal var reminderClock: () -> Long = System::currentTimeMillis

/**
 * Decides and arms [notification]'s next occurrence under [REMINDER_SCHEDULE_LOCK]; returns the armed
 * trigger, or null when none was armed. The receiver passes the occurrence its alarm just consumed as
 * [consumedAtMillis]. A sync refresh passes null and re-arms the stored occurrence while it still holds
 * for this rule and zone and is not beyond the next one (a clock corrected backwards): recomputing from
 * now would slide a bare FREQ=DAILY forward every sync, and re-arming is idempotent, so it also restores
 * an alarm a reboot dropped.
 */
internal sealed interface ReminderArmOutcome {
    data class Armed(val triggerAtMillis: Long) : ReminderArmOutcome
    data object NotDue : ReminderArmOutcome
    data object Failed : ReminderArmOutcome
}

internal fun armReminder(context: Context, notification: NotificationDetails, intent: Intent,
                         consumedAtMillis: Long? = null): Long? =
    (armReminderOutcome(context, notification, intent, consumedAtMillis) as? ReminderArmOutcome.Armed)?.triggerAtMillis

internal fun armReminderOutcome(
    context: Context,
    notification: NotificationDetails,
    intent: Intent,
    consumedAtMillis: Long? = null,
): ReminderArmOutcome = try {
    synchronized(REMINDER_SCHEDULE_LOCK) {
        // A retired code (form removed, rule changed) must never be armed again.
        if (notification.requestCode() !in EnrollmentSettings(context).getMobileReminderRequestCodes()) {
            return@synchronized ReminderArmOutcome.NotDue
        }
        val prefs = context.getSharedPreferences(REMINDER_ALARM_STATE_PREFS, Context.MODE_PRIVATE)
        val key = notification.requestCode().toString()
        val now = reminderClock()
        val zone = TimeZone.getDefault().id
        val boot = bootCount(context)
        val triggerAt = if (consumedAtMillis != null) {
            getNextRecurringDate(notification.recurrenceRule, maxOf(consumedAtMillis, now))
        } else {
            val storedAt = prefs.getLong(key, 0L)
            val next = getNextRecurringDate(notification.recurrenceRule, now)
            val sameBoot = prefs.contains(bootKey(key)) && prefs.getInt(bootKey(key), 0) == boot
            when {
                storedAt > now && next != null && storedAt <= next &&
                    prefs.getString(ruleKey(key), null) == notification.recurrenceRule &&
                    prefs.getString(zoneKey(key), null) == zone -> storedAt
                // An overdue alarm may still be on its way (inexact delivery), but a reboot dropped it;
                // past the grace it was lost (force-stop) or declined (study paused).
                sameBoot && storedAt > 0L && now - storedAt in 0 until REMINDER_OVERDUE_GRACE_MS -> return@synchronized ReminderArmOutcome.NotDue
                else -> next
            }
        }
        if (triggerAt == null || triggerAt <= now) return@synchronized ReminderArmOutcome.NotDue

        val localDate = Instant.ofEpochMilli(triggerAt).atZone(ZoneId.of(zone)).toLocalDate().toString()
        val alarmIntent = Intent(intent).putExtra(
            NOTIFICATION_DETAILS,
            Gson().toJson(notification.copy(scheduledAtMillis = triggerAt, localDate = localDate)),
        )
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notification.requestCode(),
            alarmIntent,
            getPendingIntentMutabilityFlag(PendingIntent.FLAG_UPDATE_CURRENT),
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canScheduleExactAlarms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            alarmManager.canScheduleExactAlarms()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canScheduleExactAlarms) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                LocalTelemetry.logEvent(TelemetryEvents.EXACT_ALARM_PERMISSION_DENIED, null)
            }
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        check(
            prefs.edit().putLong(key, triggerAt).putString(ruleKey(key), notification.recurrenceRule)
                .putString(zoneKey(key), zone).putInt(bootKey(key), boot).commit(),
        ) { "Unable to persist reminder occurrence" }
        context.getSharedPreferences("reminder_arm_failures", Context.MODE_PRIVATE).edit()
            .remove(key).commit()
        ReminderArmOutcome.Armed(triggerAt)
    }
} catch (error: Exception) {
    Log.w(TAG, "Unable to arm reminder occurrence", error)
    runCatching {
        val pending = PendingIntent.getBroadcast(context, notification.requestCode(), intent,
            getPendingIntentMutabilityFlag(PendingIntent.FLAG_NO_CREATE))
        if (pending != null) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }
    }
    runCatching { clearReminderAlarmState(context, notification.requestCode()) }
    val owner = com.openlattice.chronicle.collection.state.ResearchPersistenceGate.captureOwner(context)
    if (owner != null) runCatching {
        com.openlattice.chronicle.collection.state.ResearchPersistenceGate.runIfExpectedOwner(context, owner) {
            val prefs = context.getSharedPreferences("reminder_arm_failures", Context.MODE_PRIVATE)
            val key = notification.requestCode().toString()
            val scope = com.openlattice.chronicle.collection.state.ResearchErasureFence.enrollmentKey(owner)
            val previous = prefs.getString(key, null)
            val episode = previous?.takeIf { it.startsWith(scope + "|") }
                ?: "$scope|${UUID.randomUUID()}".also { check(prefs.edit().putString(key, it).commit()) }
            com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore.of(context).recordOperationalOnce(
                UUID.nameUUIDFromBytes(episode.toByteArray()).toString(),
                com.openlattice.chronicle.services.upload.LocalUploadModuleFamily.APP_RUNTIME,
                com.openlattice.chronicle.services.upload.LocalOperationalIssue.COLLECTION_ACCESS_MISSING, java.time.OffsetDateTime.now(),
                ownerScope = "${owner.studyId}:${owner.participantId}",
            )
            true
        }
    }
    ReminderArmOutcome.Failed
}

private fun ruleKey(key: String) = "$key.rule"
private fun zoneKey(key: String) = "$key.zone"
private fun bootKey(key: String) = "$key.boot"

private fun bootCount(context: Context) =
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

internal fun cancelReminderByRequestCode(context: Context, requestCode: Int) {
        androidx.core.app.NotificationManagerCompat.from(context).cancel(SURVEY_NOTIFICATION_TAG, requestCode)
        val intent = Intent(context, SurveyNotificationsReceiver::class.java).apply {
            action = SURVEY_NOTIFICATION_ACTION
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            getPendingIntentMutabilityFlag(PendingIntent.FLAG_NO_CREATE),
        ) ?: run {
            clearReminderAlarmState(context, requestCode)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        clearReminderAlarmState(context, requestCode)
    }


internal fun clearReminderAlarmState(context: Context, requestCode: Int? = null) {
    val editor = context.getSharedPreferences(REMINDER_ALARM_STATE_PREFS, Context.MODE_PRIVATE).edit()
    if (requestCode == null) editor.clear() else requestCode.toString().let {
        editor.remove(it).remove(ruleKey(it)).remove(zoneKey(it)).remove(bootKey(it))
    }
    check(editor.commit()) { "Unable to clear reminder occurrence" }
}

fun scheduleNotificationsWorker(context: Context) {
    if (!BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
        WorkManager.getInstance(context).cancelUniqueWork(NOTIFICATIONS_WORK_NAME)
        return
    }


    val workRequest: PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<NotificationsWorker>(
            NOTIFICATIONS_INTERVAL_MIN,
            TimeUnit.MINUTES
        )
            // Without network a run fails and backs off; after a reboot that left the day's
            // reminder alarms unscheduled for ~15 minutes. Wait for the network instead.
            .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
            .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        NOTIFICATIONS_WORK_NAME,
        ExistingPeriodicWorkPolicy.REPLACE,
        workRequest
    )
}

private const val NOTIFICATIONS_WORK_NAME = "notifications"
