package com.openlattice.chronicle.collection.notifications

/**
 * A single study questionnaire's notification schedule, in a form free of the
 * olingo `FullQualifiedName` / EDM map shape the legacy `getStudyQuestionnaires`
 * endpoint returns (refactor plan §9, Phase 9 — questionnaire-notification module).
 *
 * `NotificationsWorker` receives study questionnaires as
 * `Map<UUID, Map<FullQualifiedName, Set<Any>>>`. That olingo-coupled shape stays in
 * `:app`: the worker (or [QuestionnaireModuleHolder]) flattens each entry into a
 * [QuestionnaireSchedule] before handing it to [QuestionnaireCollectionModule], so the
 * collection module never imports olingo and `:collection-notifications` carries no
 * `:app` dependency.
 *
 * A study questionnaire's `RECURRENCE_RULE` property can carry **several** RFC-5545
 * `RRULE:` clauses in one string; each clause becomes its own [QuestionnaireSchedule]
 * with the same [id] and [name]. This mirrors the legacy
 * `recurrenceRuleSet.split("RRULE:")` loop in `NotificationsWorker.workHelper`.
 *
 * @property id the study questionnaire id, as the legacy `NotificationDetails.id`
 *   string (the map key `UUID.toString()`).
 * @property name the questionnaire display name (legacy `NAME` property).
 * @property recurrenceRule a single RFC-5545 recurrence rule clause (no `RRULE:` prefix).
 * @property active whether the questionnaire is active on the study; an inactive
 *   questionnaire's notification is cancelled rather than scheduled.
 *
 */
public data class QuestionnaireSchedule(
    val id: String,
    val name: String,
    val recurrenceRule: String,
    val active: Boolean,
)
