package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import com.google.gson.Gson
import com.openlattice.chronicle.R
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.data.ParticipationStatus
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
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.utils.Utils.createNotificationChannel
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import org.apache.olingo.commons.api.edm.FullQualifiedName
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import java.util.*
import java.util.concurrent.TimeUnit

const val NOTIFICATIONS_INTERVAL_MIN = 15L
const val NOTIFICATION_DELETED_ACTION = "NOTIFICATION_DELETED"
const val CHANNEL_ID = "Chronicle"
const val NOTIFICATION_DETAILS = "NOTIFICATION_DETAILS"
const val SURVEY_NOTIFICATION_ACTION = "SURVEY_NOTIFICATION_ACTION"

val TAG = NotificationsWorker::class.java.simpleName

class NotificationsWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    private lateinit var enrollmentSettings: EnrollmentSettings
    private lateinit var studyId: UUID
    private lateinit var participantId: String

    private var participationStatus: ParticipationStatus = ParticipationStatus.UNKNOWN
    private var studyQuestionnaires: Map<UUID, Map<FullQualifiedName, Set<Any>>> = mapOf()
    private var notificationsEnabled: Boolean = false

    private lateinit var chronicleApi: ChronicleStudyApi

    override fun doWork(): Result {

        if (!BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(NOTIFICATIONS_WORK_NAME)
            EnrollmentSettings(applicationContext).apply {
                setMobileReminderRequestCodes(emptySet())
                setAwarenessNotificationsEnabled(false)
            }
            return Result.success()
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
                return Result.success()
            }

            workHelper()
        } catch (e: Exception) {
            Log.i(javaClass.name, "Exception happened! ", e)
            LocalTelemetry.recordException(e)
            LocalTelemetry.logEvent(TelemetryEvents.NOTIFICATIONS_FAILURE, null)
            return Result.failure()
        }
        return Result.success()
    }

    private fun reconcileApiKeyReminders(
        serverUrl: String,
        sourceDeviceId: String,
        apiKey: String,
    ) {
        Log.i(TAG, "Reconciling device-bound participant form reminders")
        val configuration = chronicleApi.getMobileReminderConfiguration(
            studyId,
            participantId,
            sourceDeviceId,
            apiKey,
        )
        participationStatus = configuration.participationStatus
        enrollmentSettings.setParticipationStatus(participationStatus)

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

        notifications.forEach { notification -> handleNotification(notification, cancel = false) }
        val currentCodes = notifications.map(NotificationDetails::requestCode).toSet()
        val staleCodes = enrollmentSettings.getMobileReminderRequestCodes() - currentCodes
        staleCodes.forEach(::cancelScheduledNotificationByRequestCode)
        enrollmentSettings.setMobileReminderRequestCodes(currentCodes)
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

        enrollmentSettings.setParticipationStatus(
            participationStatus
        )
        enrollmentSettings.setAwarenessNotificationsEnabled(notificationsEnabled)

        Log.i(javaClass.name, "Participation status: $participationStatus")
        Log.i(javaClass.name, "Study questionnaires: $studyQuestionnaires")
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
        handleNotification(
            notification,
            cancel = true,
        )
        cancelLegacyQuestionnaireNotifications()
        enrollmentSettings.getMobileReminderRequestCodes().forEach(::cancelScheduledNotificationByRequestCode)
        enrollmentSettings.setMobileReminderRequestCodes(emptySet())
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

        val intent = createNotificationIntent(notification)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            notification.requestCode(),
            intent,
            getPendingIntentMutabilityFlag(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        try {
            val date = getNextRecurringDate(notification.recurrenceRule)!!
            Log.i(javaClass.name, "notification time: $date")

            val calendar = Calendar.getInstance()
            calendar.time = date

            val alarmManager: AlarmManager =
                applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canScheduleExactAlarms) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Log.e(
                        javaClass.name,
                        "Exact alarm permission not granted... falling back to in exact "
                    )
                    LocalTelemetry.logEvent(
                        TelemetryEvents.EXACT_ALARM_PERMISSION_DENIED, null)
                }

                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
                return
            }

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )

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
    }

    private fun cancelScheduledNotificationByRequestCode(requestCode: Int) {
        val intent = Intent(applicationContext, SurveyNotificationsReceiver::class.java).apply {
            action = SURVEY_NOTIFICATION_ACTION
        }
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            requestCode,
            intent,
            getPendingIntentMutabilityFlag(PendingIntent.FLAG_NO_CREATE),
        ) ?: return
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    // generate next date from a rfc 5545 recurrence string
    // https://tools.ietf.org/html/rfc5545#section-3.3.10
    private fun getNextRecurringDate(recurrenceRule: String): Date? {
        try {
            val rule = RecurrenceRule(recurrenceRule)
            val iterator = rule.iterator(DateTime.now().timestamp, TimeZone.getDefault())
            val nextTimestamp: Long = iterator.nextMillis()
            return Date(nextTimestamp)
        } catch (e: Exception) {
            Log.i(javaClass.name, "caught exception", e)
        }
        return null
    }

    private fun createNotificationIntent(notification: NotificationDetails): Intent {
        return Intent(applicationContext, SurveyNotificationsReceiver::class.java).apply {
            putExtra(NOTIFICATION_DETAILS, Gson().toJson(notification))
            putExtra(STUDY_ID, enrollmentSettings.getStudyId().toString())
            putExtra(PARTICIPANT_ID, enrollmentSettings.getParticipantId())
            action = SURVEY_NOTIFICATION_ACTION
        }
    }
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
            .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        NOTIFICATIONS_WORK_NAME,
        ExistingPeriodicWorkPolicy.REPLACE,
        workRequest
    )
}

private const val NOTIFICATIONS_WORK_NAME = "notifications"
