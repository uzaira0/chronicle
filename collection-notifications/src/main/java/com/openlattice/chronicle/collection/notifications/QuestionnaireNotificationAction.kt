package com.openlattice.chronicle.collection.notifications

/**
 * The reconciliation decision [QuestionnaireCollectionModule] reaches for one
 * [QuestionnaireSchedule] — whether its `AlarmManager` notification should be
 * (re)scheduled or cancelled (refactor plan §9, Phase 9).
 *
 * This is the *intent*, free of any Android type. [QuestionnaireCollectionModule]
 * computes it; the platform seams ([QuestionnaireScheduler]) translate it into the
 * actual `NotificationDetails` + `PendingIntent` + `AlarmManager` calls in `:app`.
 * Keeping the action as a plain value type is what lets `:collection-notifications`
 * stay free of `android.app.AlarmManager` and the `:app` `NotificationDetails`.
 *
 * It mirrors the legacy `handleNotification(notification, cancel)` branch exactly:
 *  - [Schedule] ⇔ the `cancel == false` branch (`scheduleNotification`);
 *  - [Cancel]   ⇔ the `cancel == true` branch (`cancelScheduledNotification`).
 *
 */
public sealed class QuestionnaireNotificationAction {

    /** The questionnaire id this action concerns (the legacy `NotificationDetails.id`). */
    public abstract val questionnaireId: String

    /** The single RFC-5545 recurrence rule clause this action concerns. */
    public abstract val recurrenceRule: String

    /**
     * Schedule (or update) the questionnaire notification on its recurrence — the
     * questionnaire is active and the participant is enrolled.
     */
    public data class Schedule(
        override val questionnaireId: String,
        val name: String,
        override val recurrenceRule: String,
    ) : QuestionnaireNotificationAction()

    /**
     * Cancel any previously scheduled questionnaire notification — the questionnaire is
     * inactive, or the participant is not enrolled.
     */
    public data class Cancel(
        override val questionnaireId: String,
        val name: String,
        override val recurrenceRule: String,
    ) : QuestionnaireNotificationAction()
}

/**
 * The platform seam that carries out a [QuestionnaireNotificationAction].
 *
 * Production wires this in [QuestionnaireModuleHolder] over `AlarmManager` +
 * `PendingIntent` + the `:app` `NotificationDetails` / `SurveyNotificationsReceiver`
 * — exactly the `scheduleNotification` / `cancelScheduledNotification` code that lived
 * inline in `NotificationsWorker`. Tests pass a recording fake. This is the only place
 * the questionnaire path touches an Android `Context`; the module never holds one.
 *
 */
public interface QuestionnaireScheduler {

    /**
     * Schedules (via an exact-or-inexact `AlarmManager` alarm) the questionnaire
     * notification described by [action], at the next occurrence of its recurrence rule.
     * Equivalent to the legacy `NotificationsWorker.scheduleNotification`.
     */
    public fun schedule(action: QuestionnaireNotificationAction.Schedule)

    /**
     * Cancels any previously scheduled `AlarmManager` alarm for the questionnaire
     * notification described by [action]. Equivalent to the legacy
     * `NotificationsWorker.cancelScheduledNotification`.
     */
    public fun cancel(action: QuestionnaireNotificationAction.Cancel)
}
