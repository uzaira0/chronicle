package com.openlattice.chronicle.collection.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.openlattice.chronicle.R
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.services.notifications.NOTIFICATION_DETAILS
import com.openlattice.chronicle.services.notifications.NotificationDetails
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_ACTION
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

private const val TAG = "QuestionnaireModuleHolder"

/**
 * Holds the single app-scoped [QuestionnaireCollectionModule] instance and constructs it
 * with its production [QuestionnaireScheduler] seam (refactor plan §9, Phase 9).
 *
 * **Why a holder, not an `object` field of `Context`.** Design §1C / refactor plan §6.1
 * guardrail 2 forbid storing an Android `Context` in a singleton field. `:collection-
 * notifications` is therefore `Context`-free; everything that needs a `Context` — the
 * `AlarmManager`, the `PendingIntent`, the `:app` `NotificationDetails` /
 * `SurveyNotificationsReceiver`, the `EnrollmentSettings` lookups — lives in the
 * [QuestionnaireScheduler] this holder wires. The holder builds the module lazily on
 * first use from the application `Context`, wires the seam, and then holds only the
 * module (and an application-`Context` handle inside the seam closure, never on the
 * module). The shared instance lets [QuestionnaireCollectionModule] diagnostics
 * (scheduled/cancelled/failed counts) accumulate across worker runs.
 *
 * The [QuestionnaireScheduler] wired here reproduces the legacy `NotificationsWorker`
 * private methods **byte-for-byte**:
 *  - [scheduleQuestionnaireNotification] ⇔ `scheduleNotification` (`AlarmManager`
 *    `setExactAndAllowWhileIdle` when exact alarms are permitted, otherwise the
 *    `setAndAllowWhileIdle` inexact fallback);
 *  - [cancelQuestionnaireNotification] ⇔ `cancelScheduledNotification`;
 *  - [createNotificationIntent] ⇔ `createNotificationIntent`;
 *  - [getNextRecurringDate] ⇔ `getNextRecurringDate`.
 *
 * The recurrence-rule parsing (`org.dmfs.rfc5545`) is performed here so the collection
 * module never imports dmfs.
 *
 */
public object QuestionnaireModuleHolder {

    @Volatile private var instance: QuestionnaireCollectionModule? = null

    /**
     * Returns the shared [QuestionnaireCollectionModule], building it on first use from
     * the application context of [context]. Subsequent calls return the same instance so
     * its scheduled/cancelled/failed diagnostics accumulate across worker runs.
     */
    public fun get(context: Context): QuestionnaireCollectionModule {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): QuestionnaireCollectionModule =
        QuestionnaireCollectionModule(scheduler = AlarmManagerQuestionnaireScheduler(appContext))

    /**
     * Production [QuestionnaireScheduler] backed by `AlarmManager` + `PendingIntent`.
     *
     * Holds only the application `Context` — never an `Activity`/`Service` context — so
     * it is safe for the app-scoped holder to keep it. The collection module itself
     * holds no `Context`; it only holds this seam.
     */
    private class AlarmManagerQuestionnaireScheduler(
        private val appContext: Context,
    ) : QuestionnaireScheduler {

        override fun schedule(action: QuestionnaireNotificationAction.Schedule) {
            val notification = notificationFor(action.questionnaireId, action.recurrenceRule, action.name)
            scheduleQuestionnaireNotification(notification)
        }

        override fun cancel(action: QuestionnaireNotificationAction.Cancel) {
            val notification = notificationFor(action.questionnaireId, action.recurrenceRule, action.name)
            cancelQuestionnaireNotification(notification)
        }

        /** Builds the legacy [NotificationDetails] for a questionnaire notification. */
        private fun notificationFor(
            questionnaireId: String,
            recurrenceRule: String,
            name: String,
        ): NotificationDetails = NotificationDetails(
            questionnaireId,
            NotificationType.QUESTIONNAIRE,
            recurrenceRule,
            name,
            appContext.getString(R.string.reminder_tap_questionnaire),
        )

        /**
         * Schedules the questionnaire notification — identical to the legacy
         * `NotificationsWorker.scheduleNotification`. A per-notification exception is
         * propagated to [QuestionnaireCollectionModule.reconcile], which logs and counts
         * it without aborting the remaining schedules (the legacy method swallowed it
         * inline; the module path keeps the same isolation while making the failure
         * visible in diagnostics).
         */
        private fun scheduleQuestionnaireNotification(notification: NotificationDetails) {
            Log.i(TAG, "notification to schedule: $notification")

            val intent = createNotificationIntent(notification)
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                notification.requestCode(),
                intent,
                getPendingIntentMutabilityFlag(PendingIntent.FLAG_UPDATE_CURRENT),
            )

            val date = getNextRecurringDate(notification.recurrenceRule)
                ?: throw IllegalStateException("No next occurrence for recurrence rule")
            Log.i(TAG, "notification time: $date")

            val calendar = Calendar.getInstance()
            calendar.time = date

            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canScheduleExactAlarms) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Log.e(TAG, "Exact alarm permission not granted... falling back to inexact")
                }
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent,
                )
                return
            }

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent,
            )
        }

        /**
         * Cancels any previously scheduled questionnaire notification — identical to the
         * legacy `NotificationsWorker.cancelScheduledNotification`.
         */
        private fun cancelQuestionnaireNotification(notification: NotificationDetails) {
            Log.i(TAG, "Notification to cancel: $notification")

            val intent = createNotificationIntent(notification)
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                notification.requestCode(),
                intent,
                getPendingIntentMutabilityFlag(PendingIntent.FLAG_NO_CREATE),
            )

            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
            }
        }

        /**
         * Builds the broadcast [Intent] for [SurveyNotificationsReceiver] — identical to
         * the legacy `NotificationsWorker.createNotificationIntent`. Study/participant
         * ids are read fresh from [EnrollmentSettings], exactly as the worker did via its
         * `enrollmentSettings` field.
         */
        private fun createNotificationIntent(notification: NotificationDetails): Intent {
            val enrollmentSettings = EnrollmentSettings(appContext)
            return Intent(appContext, SurveyNotificationsReceiver::class.java).apply {
                putExtra(NOTIFICATION_DETAILS, Gson().toJson(notification))
                putExtra(STUDY_ID, enrollmentSettings.getStudyId().toString())
                putExtra(PARTICIPANT_ID, enrollmentSettings.getParticipantId())
                action = SURVEY_NOTIFICATION_ACTION
            }
        }

        /**
         * Resolves the next occurrence of an RFC-5545 recurrence rule — identical to the
         * legacy `NotificationsWorker.getNextRecurringDate`.
         * https://tools.ietf.org/html/rfc5545#section-3.3.10
         */
        private fun getNextRecurringDate(recurrenceRule: String): Date? {
            return try {
                val rule = RecurrenceRule(recurrenceRule)
                val iterator = rule.iterator(DateTime.now().timestamp, TimeZone.getDefault())
                Date(iterator.nextMillis())
            } catch (e: Exception) {
                Log.i(TAG, "caught exception", e)
                null
            }
        }
    }
}
