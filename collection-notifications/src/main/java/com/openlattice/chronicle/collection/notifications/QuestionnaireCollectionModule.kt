package com.openlattice.chronicle.collection.notifications

import android.util.Log
import com.openlattice.chronicle.collection.core.ModuleResult

private const val TAG = "QuestionnaireCollectionModule"

/**
 * The questionnaire data collection module (design §1A `questionnaire`, refactor plan §9).
 *
 * **This wraps an existing orchestration — it does not introduce native questionnaire
 * capture.** The questionnaire "collection" is the per-study-questionnaire notification
 * scheduling that has always lived inline in `NotificationsWorker`: that worker polls
 * `getStudyQuestionnaires`, and for each active study questionnaire schedules an
 * `AlarmManager` alarm on the questionnaire's RFC-5545 recurrence so
 * `SurveyNotificationsReceiver` can deep-link the participant to the **web**
 * `/questionnaire` form. The instrument itself is completed in the web app; there is no
 * native capture and this module adds none.
 *
 * Wraps exactly the `for ((key, value) in studyQuestionnaires)` loop and its
 * `handleNotification` branch of `NotificationsWorker`, **without changing any observable behaviour**:
 *
 *  - **Reconciliation decision preserved.** [reconcile] applies the legacy
 *    `handleNotification(notification, cancel)` rule per [QuestionnaireSchedule]: a
 *    notification is **cancelled** when the questionnaire is inactive *or* the
 *    participant is not enrolled, and **scheduled** otherwise — see [cancelFor]. The
 *    cancel/schedule split is byte-identical to the inline worker code.
 *  - **One action per recurrence clause.** A study questionnaire may carry several
 *    `RRULE:` clauses in one recurrence string; `NotificationsWorker` already splits
 *    them and emits one `NotificationDetails` per clause. The caller performs that split
 *    when it builds the [QuestionnaireSchedule] list (one schedule per clause), so this
 *    module emits exactly one [QuestionnaireNotificationAction] per schedule — the same
 *    one-alarm-per-clause behaviour.
 *  - **Platform work stays behind a seam.** The actual `NotificationDetails` +
 *    `PendingIntent` + `AlarmManager` `setExact…` / `cancel` calls run in the
 *    [QuestionnaireScheduler] seam (wired by `QuestionnaireModuleHolder` in `:app`).
 *    This module decides *what* to do; the seam does *how*. The module therefore holds
 *    no Android `Context` and no `:app` type — `:collection-notifications` carries no
 *    `:app` dependency, exactly like `:collection-usage` / `:collection-lifecycle`.
 *  - **Per-action failure isolation preserved.** The legacy `scheduleNotification`
 *    swallowed a per-notification exception (`catch (e: Exception)`) so one bad
 *    recurrence rule could not abort the rest of the loop. [reconcile] preserves that:
 *    a seam call that throws is logged, the remaining
 *    schedules are still processed, and the overall result is [ModuleResult.Failed]
 *    only if at least one action failed.
 *
 * This module is **worker-driven**, not pull-style: `NotificationsWorker` calls
 * [reconcile] directly with the parsed schedules.
 *
 * Plain class holding only its scheduler seam — no Android `Context`.
 *
 */
public class QuestionnaireCollectionModule(
    /**
     * Platform seam that carries out the schedule/cancel `AlarmManager` work. Production
     * supplies an `AlarmManager`-backed implementation via `QuestionnaireModuleHolder`;
     * tests pass a recording fake.
     */
    private val scheduler: QuestionnaireScheduler,
) {

    /**
     * Reconciles the `AlarmManager` questionnaire notifications against [schedules],
     * given the participant's [enrolled] state. This is the module equivalent of the
     * `for ((key, value) in studyQuestionnaires)` loop in `NotificationsWorker.workHelper`.
     *
     * For each [QuestionnaireSchedule] it computes a [QuestionnaireNotificationAction]
     * with [actionFor] and dispatches it through the [QuestionnaireScheduler] seam:
     *  - [QuestionnaireNotificationAction.Cancel] when the questionnaire is inactive or
     *    the participant is not enrolled — the legacy `cancel == true` branch;
     *  - [QuestionnaireNotificationAction.Schedule] otherwise — the legacy
     *    `cancel == false` branch.
     *
     * An empty [schedules] list is a no-op success ([ModuleResult.Ok]`(0)`) — the legacy
     * loop simply did nothing when the study had no questionnaires.
     *
     * A per-schedule seam failure is logged and does **not**
     * abort the remaining schedules (mirrors the legacy per-notification
     * `catch (e: Exception)`). The call returns [ModuleResult.Failed] if any action
     * failed, otherwise [ModuleResult.Ok] with the count of actions dispatched.
     */
    public fun reconcile(schedules: List<QuestionnaireSchedule>, enrolled: Boolean): ModuleResult {
        var scheduled = 0
        var cancelled = 0
        var failed = 0
        var firstError: Throwable? = null

        for (schedule in schedules) {
            val action = actionFor(schedule, enrolled)
            try {
                when (action) {
                    is QuestionnaireNotificationAction.Schedule -> {
                        scheduler.schedule(action)
                        scheduled++
                    }
                    is QuestionnaireNotificationAction.Cancel -> {
                        scheduler.cancel(action)
                        cancelled++
                    }
                }
            } catch (e: Exception) {
                failed++
                if (firstError == null) firstError = e
                // Per-notification failure isolation: one bad recurrence rule must not
                // abort the rest of the reconciliation (legacy scheduleNotification
                // swallowed its own exception). Logged + counted, never silently lost.
                Log.e(
                    TAG,
                    "Failed to ${if (action is QuestionnaireNotificationAction.Schedule) "schedule" else "cancel"} " +
                        "questionnaire notification (clause failed; continuing)",
                    e,
                )
            }
        }

        return if (failed > 0) {
            val redacted = "questionnaire reconcile: $failed of ${schedules.size} action(s) failed"
            Log.w(TAG, redacted)
            ModuleResult.Failed(firstError ?: IllegalStateException(redacted), redactedMessage = redacted)
        } else {
            Log.i(
                TAG,
                "questionnaire reconcile: scheduled $scheduled, cancelled $cancelled " +
                    "(${schedules.size} schedule(s), enrolled=$enrolled)",
            )
            ModuleResult.Ok(scheduled + cancelled)
        }
    }

    /**
     * Computes the [QuestionnaireNotificationAction] for one [schedule] — the legacy
     * `handleNotification(notification, cancel)` decision, lifted out so it is unit
     * testable without a [QuestionnaireScheduler].
     *
     * The legacy `cancel` predicate was
     * `active == null || !active || participationStatus == NOT_ENROLLED`. Here
     * [QuestionnaireSchedule.active] is already a non-null `Boolean` (the caller resolved
     * `active == null` to `false` when flattening the EDM map), so the predicate reduces
     * to `!schedule.active || !enrolled`.
     */
    public fun actionFor(
        schedule: QuestionnaireSchedule,
        enrolled: Boolean,
    ): QuestionnaireNotificationAction = if (cancelFor(schedule, enrolled)) {
        QuestionnaireNotificationAction.Cancel(
            questionnaireId = schedule.id,
            name = schedule.name,
            recurrenceRule = schedule.recurrenceRule,
        )
    } else {
        QuestionnaireNotificationAction.Schedule(
            questionnaireId = schedule.id,
            name = schedule.name,
            recurrenceRule = schedule.recurrenceRule,
        )
    }

    /**
     * Whether [schedule]'s notification should be **cancelled** rather than scheduled —
     * the legacy `handleNotification` `cancel` argument.
     *
     * `true` when the questionnaire is inactive **or** the participant is not enrolled.
     */
    public fun cancelFor(schedule: QuestionnaireSchedule, enrolled: Boolean): Boolean =
        !schedule.active || !enrolled
}
