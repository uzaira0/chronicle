package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.security.LeaseBoundWorker
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.enrollment.enqueueEnrollmentStatusRefresh
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.completeServerForIdentity
import com.openlattice.chronicle.services.upload.isTransportFailure
import com.openlattice.chronicle.storage.LifecycleStateEntity
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.checkLocalStoreWrite
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.utils.Utils.createNotificationChannel
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.TimeUnit
import org.dmfs.rfc5545.recur.RecurrenceRule

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
    LeaseBoundWorker(context, workerParameters) {

    private lateinit var enrollmentSettings: EnrollmentSettings
    private lateinit var studyId: UUID
    private lateinit var participantId: String

    private var armingFailed = false
    private lateinit var chronicleApi: ChronicleStudyApi

    override fun runWork(): Result = try {
        val expected = ResearchPersistenceGate.captureOwner(applicationContext)
        if (expected == null) Result.success()
        else ResearchPersistenceGate.runIfExpectedOwner(applicationContext, expected) { workAdmitted() } ?: Result.success()
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
            )

            reconcileApiKeyReminders(
                serverUrl = server.url,
                sourceRandomDeviceId = server.sourceRandomDeviceId,
                apiKey = requireNotNull(server.apiKey),
            )
            return if (armingFailed) Result.retry() else Result.success()
        } catch (e: Exception) {
            // A transport failure or gateway error is retried; runWork logs it once.
            if (isTransportFailure(e) || generateSequence<Throwable>(e) { it.cause }
                    .any { it is ChronicleCallException && it.code in 502..504 }) throw e
            Log.e(TAG, "Reminder reconciliation failed", e)
            LocalTelemetry.recordException(e)
            LocalTelemetry.logEvent(TelemetryEvents.NOTIFICATIONS_FAILURE, null)
            return Result.failure()
        }
    }

    private fun reconcileApiKeyReminders(
        serverUrl: String,
        sourceRandomDeviceId: String,
        apiKey: String,
    ) {
        Log.i(TAG, "Reconciling device-bound participant form reminders")
        val configuration = chronicleApi.getMobileReminderSchedule(studyId, participantId, sourceRandomDeviceId, apiKey)
        // The status poll owns every status apply: it carries the capture windows this manifest lacks.
        if (configuration.participationStatus != enrollmentSettings.getParticipationStatus()) {
            enqueueEnrollmentStatusRefresh(applicationContext)
        }

        val notifications = configuration.forms.mapNotNull { form ->
            val type = when (form.formKind) {
                ParticipantFormKind.APP_USAGE -> {
                    // The survey asks who used a shared phone: this phone owns whether it is shared. Dropped here,
                    // an armed survey alarm is cancelled with the other stale codes below.
                    if (!enrollmentSettings.isUserIdentificationEnabled()) return@mapNotNull null
                    NotificationType.AWARENESS
                }
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
                timeZone = form.timeZone,
                unansweredDays = form.unansweredDays,
            )
        }

        synchronized(REMINDER_SCHEDULE_LOCK) {
            notifications.forEach(::scheduleNotification)
            val currentCodes = notifications.map(NotificationDetails::requestCode).toSet()
            val staleCodes = enrollmentSettings.getMobileReminderRequestCodes() - currentCodes
            staleCodes.forEach(::cancelScheduledNotificationByRequestCode)
            enrollmentSettings.setMobileReminderRequestCodes(currentCodes intersect enrollmentSettings.getMobileReminderRequestCodes())
        }
        enrollmentSettings.setAwarenessNotificationsEnabled(
            notifications.any { it.type == NotificationType.AWARENESS }
        )
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
            armingFailed = true
            reportReminderArmFailure(applicationContext, notification, reminderAlarmIntent(applicationContext), e)
        }
    }


    private fun cancelScheduledNotificationByRequestCode(requestCode: Int) =
        cancelReminderByRequestCode(applicationContext, requestCode)

    private fun createNotificationIntent(notification: NotificationDetails): Intent {
        return reminderAlarmIntent(applicationContext).apply {
            val scope = ResearchPersistenceGate.observationScope(
                applicationContext, CollectionModuleId.QUESTIONNAIRE,
            ) ?: error("Reminder enrollment retired")
            putExtra(SURVEY_ENROLLMENT_SCOPE, scope.key)
            putExtra(NOTIFICATION_DETAILS, Gson().toJson(notification))
            putExtra(STUDY_ID, enrollmentSettings.getStudyId().toString())
            putExtra(PARTICIPANT_ID, enrollmentSettings.getParticipantId())
        }
    }
}

internal const val REMINDER_ALARM_STATE_PREFS = "reminder_alarm_schedule"
private const val REMINDER_ARM_FAILURE_PREFS = "reminder_arm_failures"

// RFC 5545 recurrence iterator for the next occurrence in [zone] strictly after afterMillis.
// The iterator's start is truncated to the second and is itself an instance when it matches the
// rule (always, for a bare FREQ=DAILY), so its first value can be at or before afterMillis; an
// alarm armed there fires at once and re-arms itself in a loop.
internal fun getNextRecurringDate(recurrenceRule: String, afterMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long? = try {
    val iterator = RecurrenceRule(recurrenceRule).iterator(afterMillis, zone)
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
@VisibleForTesting
internal var reminderClock: () -> Long = System::currentTimeMillis

/**
 * Decides and arms [notification]'s next occurrence under [REMINDER_SCHEDULE_LOCK]; returns the armed
 * trigger, or null when none was armed. The rule recurs in the notification's zone (the study zone for the
 * survey), else the phone's. The receiver passes the occurrence its alarm just consumed as [consumedAtMillis].
 * A sync refresh passes null and re-arms the stored occurrence while it is still ahead and holds for this rule
 * and zone and is not beyond the next one (a clock corrected backwards): recomputing from now would slide a bare
 * FREQ=DAILY forward every sync, and re-arming is idempotent, so it also restores an alarm a reboot dropped. A
 * stored occurrence that has passed is replaced by the next one: the survey reminder repeats until every day is
 * answered, so a lost or late occurrence is covered by the next.
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
        val zone = notification.timeZone?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
        val triggerAt = selectReminderOccurrence(notification, prefs, key, now, zone, consumedAtMillis)
        if (triggerAt == null || triggerAt <= now) return@synchronized ReminderArmOutcome.NotDue

        val alarmIntent = Intent(intent).putExtra(
            NOTIFICATION_DETAILS,
            Gson().toJson(notification.copy(scheduledAtMillis = triggerAt)),
        )
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notification.requestCode(),
            alarmIntent,
            getPendingIntentMutabilityFlag(PendingIntent.FLAG_UPDATE_CURRENT),
        )
        if (!scheduleReminderAlarm(context, triggerAt, pendingIntent)) return@synchronized ReminderArmOutcome.NotDue
        ResearchPersistenceGate.captureOwner(context)?.let { owner ->
            ChronicleDb.getInstance(context).lifecycleStateDao().upsert(LifecycleStateEntity(
                reminderCacheKey(owner, notification.requestCode()), alarmIntent.toUri(Intent.URI_INTENT_SCHEME), now))
        }
        checkLocalStoreWrite(
            prefs.edit().putLong(key, triggerAt).putString(ruleKey(key), notification.recurrenceRule)
                .putString(zoneKey(key), zone.id).commit(),
        ) { "Unable to persist reminder occurrence" }
        endReminderFailureEpisode(context, notification.requestCode())
        ReminderArmOutcome.Armed(triggerAt)
    }
} catch (error: Exception) {
    reportReminderArmFailure(context, notification, intent, error)
    ReminderArmOutcome.Failed
}

private fun reminderCacheKey(owner: UploadServerEntity, requestCode: Int): String =
    "reminder_intent:${ResearchErasureFence.enrollmentKey(owner)}:$requestCode"

/**
 * Restores the last authorized alarms locally; manifest refresh keeps its network constraint. The one
 * owner of local re-arming, called on boot, on app update and at every process start: Android
 * cancels a process's alarms when it kills it, e.g. when the participant revokes "Alarms & reminders"
 * (https://developer.android.com/develop/background-work/services/alarms#using-the-schedule_exact_alarm-permission).
 * Without exact-alarm access the occurrence is re-armed inexactly. An occurrence that came due while it could not
 * be delivered (the alarm fired before authorization was published, or Android dropped it with the permission) is
 * not posted late: the next one is armed instead. Runs only on the authorization worker, so restores never interleave:
 * process start calls it there, every other caller goes through [restoreReminderAlarmsAsync].
 */
internal fun restoreReminderAlarms(context: Context) {
    check(ResearchPersistenceGate.isOnAuthorizationWorker()) { "Reminder restores run on the authorization worker" }
    if (!BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) return
    val owner = ResearchPersistenceGate.captureOwner(context) ?: return
    ResearchPersistenceGate.runIfExpectedOwner(context, owner) {
        if (EnrollmentSettings(context).getParticipationStatus() != ParticipationStatus.ENROLLED) return@runIfExpectedOwner
        synchronized(REMINDER_SCHEDULE_LOCK) {
            val cache = ChronicleDb.getInstance(context).lifecycleStateDao()
            EnrollmentSettings(context).getMobileReminderRequestCodes().forEach { code ->
                val (intent, notification) = cachedReminder(cache.get(reminderCacheKey(owner, code))?.value) ?: return@forEach
                armReminderOutcome(context, notification, intent)
            }
        }
    }
}

private fun cachedReminder(uri: String?): Pair<Intent, NotificationDetails>? = try {
    uri?.let { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }?.let { intent ->
        Gson().fromJson(intent.getStringExtra(NOTIFICATION_DETAILS), NotificationDetails::class.java)?.let { intent to it }
    }
} catch (error: Exception) {
    // The next network refresh re-arms this code from the manifest.
    Log.e(TAG, "Cached reminder unreadable: ${error.javaClass.simpleName}")
    null
}

/** Queues [restoreReminderAlarms] on the authorization worker; what it cannot finish waits for the next process start. */
internal fun restoreReminderAlarmsAsync(context: Context) = ResearchPersistenceGate.executeAsync {
    try { restoreReminderAlarms(context) }
    catch (error: Exception) { Log.e(TAG, "Reminder restore waits for the next process start: ${error.javaClass.simpleName}") }
}

/**
 * Claims [notification]'s occurrence for posting; false when it was already posted (a clock set back re-armed
 * it). Call under [REMINDER_SCHEDULE_LOCK], after arming the successor.
 */
internal fun claimReminderOccurrence(context: Context, notification: NotificationDetails): Boolean {
    val occurrence = notification.scheduledAtMillis ?: return true
    val prefs = context.getSharedPreferences(REMINDER_ALARM_STATE_PREFS, Context.MODE_PRIVATE)
    val key = postedKey(notification.requestCode().toString())
    if (prefs.getLong(key, Long.MIN_VALUE) == occurrence) return false
    checkLocalStoreWrite(prefs.edit().putLong(key, occurrence).commit()) { "Unable to record a posted reminder" }
    return true
}

private fun selectReminderOccurrence(
    notification: NotificationDetails,
    prefs: SharedPreferences,
    key: String,
    now: Long,
    zone: TimeZone,
    consumedAtMillis: Long?,
): Long? {
    if (consumedAtMillis != null) return getNextRecurringDate(notification.recurrenceRule, maxOf(consumedAtMillis, now), zone)
    val storedAt = prefs.getLong(key, 0L)
    val next = getNextRecurringDate(notification.recurrenceRule, now, zone)
    val kept = storedAt > now && next != null && storedAt <= next &&
        prefs.getString(ruleKey(key), null) == notification.recurrenceRule && prefs.getString(zoneKey(key), null) == zone.id
    return if (kept) storedAt else next
}

internal fun reminderAlarmIntent(context: Context) =
    Intent(context, SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION)

private fun reportReminderArmFailure(context: Context, notification: NotificationDetails, intent: Intent, error: Exception) {
    Log.e(TAG, "Unable to arm reminder occurrence: ${error.javaClass.simpleName}")
    val settings = EnrollmentSettings(context)
    settings.setMobileReminderRequestCodes(settings.getMobileReminderRequestCodes() - notification.requestCode())
    runCatching {
        val pending = PendingIntent.getBroadcast(context, notification.requestCode(), intent,
            getPendingIntentMutabilityFlag(PendingIntent.FLAG_NO_CREATE))
        if (pending != null) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }
    }
    runCatching { clearReminderAlarmState(context, notification.requestCode()) }
    val owner = ResearchPersistenceGate.captureOwner(context)
    if (owner != null) runCatching {
        ResearchPersistenceGate.runIfExpectedOwner(context, owner) {
            recordReminderUndelivered(context, owner, notification.requestCode())
            true
        }
    }
}

/**
 * Records that the participant will not see reminder [requestCode] (its alarm could not be armed,
 * or Android blocks notifications), once per episode; arming the reminder again ends the episode.
 * The episode ("scope|id|startInstant") is checkpointed first, so a call after a process death
 * records the same episode at its own start; one written by build 79 ("scope|id") has no start
 * instant and is reported when it is read. Call under [owner]'s persistence lease.
 */
internal fun recordReminderUndelivered(context: Context, owner: UploadServerEntity, requestCode: Int) {
    val prefs = context.getSharedPreferences(REMINDER_ARM_FAILURE_PREFS, Context.MODE_PRIVATE)
    val key = requestCode.toString()
    val scope = ResearchErasureFence.enrollmentKey(owner)
    val previous = prefs.getString(key, null)
    val episode = previous?.takeIf { it.startsWith(scope + "|") }
        ?: "$scope|${UUID.randomUUID()}|${OffsetDateTime.now()}".also { checkLocalStoreWrite(prefs.edit().putString(key, it).commit()) }
    val startedAt = runCatching { OffsetDateTime.parse(episode.substringAfterLast('|')) }.getOrElse { OffsetDateTime.now() }
    LocalUploadDiagnosticsStore.of(context).recordOperationalOnce(
        // nosemgrep: chronicle-uuid-from-string -- Persisted reminder failure episode needs one diagnostic ID across subsequent attempts.
        UUID.nameUUIDFromBytes(episode.toByteArray()).toString(),
        LocalUploadModuleFamily.APP_RUNTIME,
        LocalOperationalIssue.COLLECTION_ACCESS_MISSING, startedAt,
        ownerScope = "${owner.studyId}:${owner.participantId}",
    )
}

/**
 * Ends reminder [requestCode]'s failure episode once its diagnostic is durable. The process may have died
 * between the episode checkpoint and its diagnostic, so this owner's open episode is recorded (idempotently)
 * first; on any failure the checkpoint stays for the next successful arm. Never throws: the reminder is armed.
 */
private fun endReminderFailureEpisode(context: Context, requestCode: Int) {
    runCatching {
        val prefs = context.getSharedPreferences(REMINDER_ARM_FAILURE_PREFS, Context.MODE_PRIVATE)
        val key = requestCode.toString()
        val episode = prefs.getString(key, null) ?: return
        val owner = ResearchPersistenceGate.captureOwner(context)
        if (owner != null && episode.startsWith(ResearchErasureFence.enrollmentKey(owner) + "|")) {
            ResearchPersistenceGate.runIfExpectedOwner(context, owner) { recordReminderUndelivered(context, owner, requestCode) }
                ?: return
        }
        checkLocalStoreWrite(prefs.edit().remove(key).commit())
    }.onFailure { Log.w(TAG, "Reminder failure episode kept for the next arm: ${it.javaClass.simpleName}") }
}

private fun ruleKey(key: String) = "$key.rule"
private fun zoneKey(key: String) = "$key.zone"
private fun postedKey(key: String) = "$key.posted"

/** The alarm Android holds for reminder [requestCode], if any. */
private fun armedAlarm(context: Context, requestCode: Int): PendingIntent? = PendingIntent.getBroadcast(
    context, requestCode, reminderAlarmIntent(context), getPendingIntentMutabilityFlag(PendingIntent.FLAG_NO_CREATE))

internal fun cancelReminderByRequestCode(context: Context, requestCode: Int) {
        NotificationManagerCompat.from(context).cancel(SURVEY_NOTIFICATION_TAG, requestCode)
        val pendingIntent = armedAlarm(context, requestCode) ?: run {
            clearReminderAlarmState(context, requestCode)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        clearReminderAlarmState(context, requestCode)
    }


internal fun clearReminderAlarmState(context: Context, requestCode: Int? = null) {
    ResearchPersistenceGate.captureOwner(context)?.let { owner ->
        val codes = requestCode?.let(::setOf) ?: EnrollmentSettings(context).getMobileReminderRequestCodes()
        codes.forEach { ChronicleDb.getInstance(context).lifecycleStateDao().delete(reminderCacheKey(owner, it)) }
    }
    val editor = context.getSharedPreferences(REMINDER_ALARM_STATE_PREFS, Context.MODE_PRIVATE).edit()
    if (requestCode == null) editor.clear() else requestCode.toString().let {
        editor.remove(it).remove(ruleKey(it)).remove(zoneKey(it)).remove(postedKey(it))
    }
    checkLocalStoreWrite(editor.commit()) { "Unable to clear reminder occurrence" }
}

fun scheduleNotificationsWorker(context: Context, policy: ExistingPeriodicWorkPolicy) {
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
        policy,
        workRequest
    )
}

internal const val NOTIFICATIONS_WORK_NAME = "notifications"
