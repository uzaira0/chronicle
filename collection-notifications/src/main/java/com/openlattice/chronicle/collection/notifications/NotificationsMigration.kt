package com.openlattice.chronicle.collection.notifications

/**
 * Internal migration switch for the questionnaire-notification path of
 * `NotificationsWorker` (Phase 9, refactor plan §9 / design §1C.4).
 *
 * Phase 9 introduces a second questionnaire-notification path inside
 * `NotificationsWorker.workHelper`: the *module path*, which routes the per-study-
 * questionnaire schedule/cancel reconciliation through [QuestionnaireCollectionModule]
 * and the [QuestionnaireScheduler] seam. While both the legacy inline
 * `for ((key, value) in studyQuestionnaires)` loop and the new module path coexist,
 * exactly one of them runs per worker execution — the worker branches on
 * [USE_MODULE_MANAGER_QUESTIONNAIRE_PATH]. There is no third path and no
 * double-schedule.
 *
 * **The module path is active.** [USE_MODULE_MANAGER_QUESTIONNAIRE_PATH] is `true`:
 * `NotificationsWorker` routes the questionnaire loop through
 * [QuestionnaireCollectionModule] + [QuestionnaireScheduler]. Its parity tests pass and
 * the flag was flipped on as a deliberate, separately-reviewed step (mirrors
 * `UsageWorkerMigration` / `LifecycleWorkerMigration`).
 *
 * The switch gates **only** the questionnaire loop. The daily 7-PM `AWARENESS`
 * notification scheduled earlier in `workHelper` is unaffected by this flag — it is not
 * part of the questionnaire module's responsibility and keeps running unchanged on both
 * branches.
 *
 * This is a compile-time constant, not a server/remote setting — it gates the migration
 * during development only and carries no privacy or wire-shape implication.
 *
 */
public object NotificationsMigration {

    /**
     * `false` ⇒ `NotificationsWorker` runs the legacy inline questionnaire-notification
     * loop (current behaviour — the regression baseline). `true` ⇒ it runs the new
     * module-manager path through [QuestionnaireCollectionModule] + [QuestionnaireScheduler].
     *
     * Set to `true`: the module path is active now that parity is proven.
     */
    public const val USE_MODULE_MANAGER_QUESTIONNAIRE_PATH: Boolean = true
}
