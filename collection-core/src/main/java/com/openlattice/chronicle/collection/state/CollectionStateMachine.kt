package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.moduleDecisionSurvives
import com.openlattice.chronicle.collection.requiredDeclineStands

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.settings.ResolvedModuleSetting

/**
 * The kind of change a [CollectionModuleState] undergoes when fresh settings arrive
 * (per-module consent design §5). Each value maps to exactly one participant-facing
 * outcome the coordinator dispatches.
 */
public enum class ModuleTransitionType {
    /** Server-disabled and stays disabled. No effect. */
    UNCHANGED_INACTIVE,

    /**
     * An **optional** module became (or stayed) server-enabled while the participant is
     * undecided. Prompt for a decision; do NOT collect until accepted.
     */
    NEEDS_DECISION,

    /** Still server-enabled and still undecided. Keep awaiting a decision. */
    STILL_AWAITING_DECISION,

    /** Still server-enabled and accepted (no required-flag change). Ensure the module runs. */
    STILL_ACTIVE,

    /** Still server-enabled, still optional, still declined. No change. */
    STILL_DECLINED,

    /**
     * The module is now **required** but the participant has not accepted it (was declined,
     * undecided, or newly added). Surface the mandatory "Accept or Leave the study" screen;
     * do NOT collect until accepted.
     */
    NEWLY_REQUIRED_NEEDS_CONSENT,

    /**
     * An already-accepted module became **required** (was optional). It keeps collecting;
     * inform the participant it can no longer be turned off. The toggle locks.
     */
    NOW_REQUIRED_INFORM,

    /**
     * An already-accepted module became **optional** (was required). It keeps collecting by
     * default; inform the participant they may now turn it off. The toggle unlocks.
     */
    NOW_OPTIONAL_INFORM,

    /**
     * The study no longer collects this module. Stop collecting; what it already captured
     * still uploads (C5: a study disable flushes). Informational.
     */
    FORCIBLY_DISABLED,
}

/** One module's computed transition: the [type] and the [newState] to persist. */
public data class ModuleTransition(
    val moduleId: CollectionModuleId,
    val type: ModuleTransitionType,
    val newState: CollectionModuleState,
) {
    /** The participant must make a **mandatory** accept-or-leave decision before collection. */
    public val requiresMandatoryConsent: Boolean
        get() = type == ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT

    /** The participant is asked to decide an **optional** module (not collecting until accepted). */
    public val requestsOptionalDecision: Boolean
        get() = type == ModuleTransitionType.NEEDS_DECISION

    /** A server-initiated change the participant is merely **informed** of (no decision needed). */
    public val isInformationalNotice: Boolean
        get() = type == ModuleTransitionType.NOW_REQUIRED_INFORM ||
            type == ModuleTransitionType.NOW_OPTIONAL_INFORM ||
            type == ModuleTransitionType.FORCIBLY_DISABLED
}

/** Result of applying participant decisions to a set of modules. */
public data class DecisionResult(
    val newStates: Map<CollectionModuleId, CollectionModuleState>,
    /** Modules that transitioned to ACTIVE as a result (start these + report them). */
    val activated: Set<CollectionModuleId>,
    /** Modules that transitioned to DECLINED as a result (stop these + report them). */
    val deactivated: Set<CollectionModuleId>,
)

/**
 * Pure transition engine for the collection loop (collection loop closure design §5.5,
 * §6; per-module consent design §4-§5). Holds no state, no Android types, no clock —
 * every input is a parameter — so the full lifecycle (INACTIVE / AWAITING_DECISION /
 * ACTIVE / DECLINED) is exhaustively JVM-testable.
 *
 * Two entry points, mirroring the two drivers of change:
 *  - [reconcile] — **server-driven**: fresh resolved settings vs. persisted state.
 *  - [decide] — **participant-driven**: an on-device accept/decline.
 *
 * The absolute gate (no module collects without server-enable AND an explicit ACCEPTED
 * decision) is enforced by construction: a server enable only ever produces
 * AWAITING_DECISION / NEEDS_DECISION / NEWLY_REQUIRED_NEEDS_CONSENT, never ACTIVE — only
 * [decide] (or an already-accepted module re-affirmed by [reconcile]) activates.
 */
public object CollectionStateMachine {

    /**
     * The modules whose on-device collection is consent-gated — i.e. exactly the modules
     * whose collection seam consults `CollectionGate`. Only these enter the consent
     * lifecycle and surface on the consent screens / Data Sharing tab.
     *
     * The operational modules — `upload_telemetry` (diagnostics), `sensor_availability`
     * (device capability), `questionnaire` (local notification scheduling; the
     * questionnaire itself is a web form) — are deliberately **excluded**: their behaviour
     * is not gated by `CollectionGate`, so listing them on a consent screen would imply a
     * control the decision does not exert (misleading consent) while changing nothing about
     * whether they run.
     *
     * `user_identification` is consent-gated as well as locally enabled. The authoritative
     * collection decision controls whether unlock prompts and participant labels may run; the
     * local "Identify user" switch can only narrow that authorization, never expand it.
     *
     * Each hardware sensor is its own consent-gated module: every `sensor_*` id is included individually, so a participant decides
     * each sensor on its own.
     *
     * **Invariant:** this set MUST equal the set of `CollectionGate.collects(...)` call
     * sites in the module holders.
     */
    @JvmStatic
    public val ACK_GATED_MODULES: Set<CollectionModuleId> =
        AndroidDataCollectionSetting.androidSupportedModuleIds.filterTo(LinkedHashSet()) { it.consentGated }

    /**
     * Computes the transition for every **consent-gated** module in [resolved] (see
     * [ACK_GATED_MODULES]) against the persisted [previous] state, diffing both the
     * `enabled` and `required` flags (per-module consent design §5). Non-gated operational
     * modules in [resolved] are ignored. A module absent from [previous] is treated as
     * INACTIVE (fresh install / first enrollment).
     *
     * @param settingVersion the version of the `AndroidDataCollectionSetting` being applied.
     */
    @JvmStatic
    public fun reconcile(
        previous: Map<CollectionModuleId, CollectionModuleState>,
        resolved: Map<CollectionModuleId, ResolvedModuleSetting>,
        settingVersion: Int,
    ): List<ModuleTransition> = resolved
        .filterKeys { it in ACK_GATED_MODULES }
        .map { (moduleId, resolvedSetting) ->
            val prior = previous[moduleId] ?: CollectionModuleState.initial(moduleId)
            val declineStands = prior.decision == ParticipantDecision.DECLINED &&
                requiredDeclineStands(prior.serverEnabled, resolvedSetting.setting)
            // C3: a decision survives every edit of an enabled module; only a disable then re-enable (the server's
            // consent epoch past the version this phone last applied) asks again.
            val prev = if (resolvedSetting.enabled && !declineStands &&
                !moduleDecisionSurvives(resolvedSetting.setting, prior.appliedVersion, prior.serverEnabled)) {
                prior.toInactive(settingVersion)
            } else prior
            when {
                !resolvedSetting.enabled -> disableTransition(moduleId, prev, settingVersion)
                resolvedSetting.required -> requiredTransition(moduleId, prev, settingVersion)
                else -> optionalTransition(moduleId, prev, settingVersion)
            }
        }

    /** Study intent = NOT COLLECTED. */
    private fun disableTransition(
        moduleId: CollectionModuleId,
        prev: CollectionModuleState,
        version: Int,
    ): ModuleTransition = transition(moduleId,
        // The coordinator suppresses the "turned off" notice when prev was not ACTIVE.
        if (prev.phase == CollectionModulePhase.INACTIVE) ModuleTransitionType.UNCHANGED_INACTIVE
        else ModuleTransitionType.FORCIBLY_DISABLED,
        prev.toInactive(version))

    /** Study intent = REQUIRED. */
    private fun requiredTransition(
        moduleId: CollectionModuleId,
        prev: CollectionModuleState,
        version: Int
    ): ModuleTransition = when (prev.decision) {
        ParticipantDecision.ACCEPTED -> transition(moduleId,
            if (!prev.requiredApplied) ModuleTransitionType.NOW_REQUIRED_INFORM else ModuleTransitionType.STILL_ACTIVE,
            prev.toAccepted(version, required = true))
        // Already explicitly declined a required module → keep it DECLINED (the global halt
        // persists). Must NOT reset to UNDECIDED, or the next settings poll would silently
        // lift the halt. Notify once when the requirement changes; resume through Data Sharing.
        ParticipantDecision.DECLINED ->
            transition(moduleId, if (prev.requiredApplied) ModuleTransitionType.STILL_DECLINED
                else ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT, prev.toRequiredDeclined(version))
        // Undecided → grace window: prompt for a decision, do NOT halt (already-accepted
        // modules keep collecting). The module itself does not collect until accepted.
        // First time required-but-undecided → NEWLY_REQUIRED_NEEDS_CONSENT (notify once); a module
        // already awaiting a REQUIRED decision from a prior sync → STILL_AWAITING_DECISION so the
        // coordinator does NOT re-post the notification on every settings poll. "Same flavor" =
        // already awaiting AND already required (an optional→required flip while undecided re-prompts).
        ParticipantDecision.UNDECIDED -> {
            val type = if (prev.phase == CollectionModulePhase.AWAITING_DECISION && prev.requiredApplied) {
                ModuleTransitionType.STILL_AWAITING_DECISION
            } else {
                ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT
            }
            transition(moduleId, type, prev.toAwaiting(version, required = true))
        }
    }

    /** Study intent = OPTIONAL. */
    private fun optionalTransition(
        moduleId: CollectionModuleId,
        prev: CollectionModuleState,
        version: Int
    ): ModuleTransition = when (prev.decision) {
        ParticipantDecision.ACCEPTED -> transition(moduleId,
            if (prev.requiredApplied) ModuleTransitionType.NOW_OPTIONAL_INFORM else ModuleTransitionType.STILL_ACTIVE,
            prev.toAccepted(version, required = false))
        ParticipantDecision.DECLINED ->
            transition(moduleId, ModuleTransitionType.STILL_DECLINED, prev.toDeclined(version))
        // First prompt → NEEDS_DECISION (notify once); a module already awaiting an OPTIONAL
        // decision from a prior sync → STILL_AWAITING_DECISION so the coordinator does NOT re-post
        // the "a data collection option was added" notification on every settings poll. "Same
        // flavor" = already awaiting AND not previously required (a required→optional flip while
        // undecided re-prompts).
        ParticipantDecision.UNDECIDED -> {
            val type = if (prev.phase == CollectionModulePhase.AWAITING_DECISION && !prev.requiredApplied) {
                ModuleTransitionType.STILL_AWAITING_DECISION
            } else {
                ModuleTransitionType.NEEDS_DECISION
            }
            transition(moduleId, type, prev.toAwaiting(version, required = false))
        }
    }

    /**
     * Applies participant [decisions] (ACCEPTED / DECLINED) at [nowEpochMillis]. Only
     * server-enabled modules can be decided. ACCEPTED activates an undecided/declined
     * module; DECLINED deactivates it. Declining a **required** module is permitted here
     * (the Data Sharing mandatory surface offers an explicit Decline): it sets DECLINED while
     * keeping `requiredApplied`, which trips the global halt ([CollectionModuleState.requiredAndDeclined]).
     * The optional per-module toggle only ever declines optional modules (it is locked for
     * required ones), so this path is reached for a required module solely via that explicit
     * Decline. Idempotent: re-asserting the current decision is a no-op.
     */
    @JvmStatic
    public fun decide(
        previous: Map<CollectionModuleId, CollectionModuleState>,
        decisions: Map<CollectionModuleId, ParticipantDecision>,
        nowEpochMillis: Long,
    ): DecisionResult {
        val activated = mutableSetOf<CollectionModuleId>()
        val deactivated = mutableSetOf<CollectionModuleId>()
        val newStates = previous.mapValues { (moduleId, state) ->
            val decision = decisions[moduleId] ?: return@mapValues state
            if (!state.serverEnabled) return@mapValues state
            when (decision) {
                ParticipantDecision.ACCEPTED ->
                    if (state.decision != ParticipantDecision.ACCEPTED) {
                        activated += moduleId
                        state.copy(decision = ParticipantDecision.ACCEPTED, decidedAtEpochMillis = nowEpochMillis)
                    } else {
                        state
                    }
                ParticipantDecision.DECLINED ->
                    // Permitted for required modules too (an explicit Decline trips the global
                    // halt); `requiredApplied` is preserved by copy(). Idempotent.
                    if (state.decision != ParticipantDecision.DECLINED) {
                        deactivated += moduleId
                        state.copy(decision = ParticipantDecision.DECLINED, decidedAtEpochMillis = nowEpochMillis)
                    } else {
                        state
                    }
                ParticipantDecision.UNDECIDED -> state
            }
        }
        return DecisionResult(newStates, activated, deactivated)
    }

    // ----- pure state transforms -----

    /** Settle to INACTIVE: cleared decision, required reset (a disabled module is not required). */
    private fun CollectionModuleState.toInactive(version: Int): CollectionModuleState =
        copy(
            serverEnabled = false,
            decision = ParticipantDecision.UNDECIDED,
            decidedAtEpochMillis = null,
            requiredApplied = false,
            appliedVersion = version,
        )

    /** Server-enabled but undecided (a fresh decision is required). */
    private fun CollectionModuleState.toAwaiting(
        version: Int,
        required: Boolean
    ): CollectionModuleState =
        copy(
            serverEnabled = true,
            decision = ParticipantDecision.UNDECIDED,
            decidedAtEpochMillis = null,
            requiredApplied = required,
            appliedVersion = version,
        )

    /** Server-enabled and accepted; preserves the original decision timestamp. */
    private fun CollectionModuleState.toAccepted(
        version: Int,
        required: Boolean
    ): CollectionModuleState =
        copy(
            serverEnabled = true,
            decision = ParticipantDecision.ACCEPTED,
            requiredApplied = required,
            appliedVersion = version,
        )

    /** Server-enabled and declined (optional only); preserves the decision timestamp. */
    private fun CollectionModuleState.toDeclined(version: Int): CollectionModuleState =
        copy(
            serverEnabled = true,
            decision = ParticipantDecision.DECLINED,
            requiredApplied = false,
            appliedVersion = version,
        )

    /**
     * Server-enabled, REQUIRED, and declined (the global-halt state); preserves the decision
     * timestamp. Distinct from [toDeclined] in keeping `requiredApplied = true`, so reconcile
     * does not silently lift the halt on the next settings poll.
     */
    private fun CollectionModuleState.toRequiredDeclined(
        version: Int
    ): CollectionModuleState =
        copy(
            serverEnabled = true,
            decision = ParticipantDecision.DECLINED,
            requiredApplied = true,
            appliedVersion = version,
        )

    private fun transition(
        moduleId: CollectionModuleId,
        type: ModuleTransitionType,
        newState: CollectionModuleState,
    ): ModuleTransition = ModuleTransition(moduleId, type, newState)
}
