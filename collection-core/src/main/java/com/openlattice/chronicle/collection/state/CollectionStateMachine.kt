package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.SensorCollectionModules
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
     * The study no longer collects this module. Stop collecting and — when the module was
     * ACTIVE — apply [ModuleTransition.disposition] to its on-device queue. Informational.
     */
    FORCIBLY_DISABLED,
}

/**
 * One module's computed transition: the [type], the [newState] to persist, and — for a
 * mid-study disable of an active module — the [disposition] the device must apply to
 * that module's pending on-device queue.
 */
public data class ModuleTransition(
    val moduleId: CollectionModuleId,
    val type: ModuleTransitionType,
    val newState: CollectionModuleState,
    val disposition: CollectionDataDisposition? = null,
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

    /** The no-data-loss default applied when a disabled module carries no explicit disposition. */
    public val DEFAULT_DISPOSITION: CollectionDataDisposition = CollectionDataDisposition.FLUSH_THEN_STOP

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
     * Each hardware sensor is its own consent-gated module (per-sensor consent redesign,
     * 2026-06-11): every `sensor_*` id is included individually, so a participant decides
     * each sensor on its own — there is no grouped `hardware_sensors` entry.
     *
     * **Invariant:** this set MUST equal the set of `CollectionGate.collects(...)` call
     * sites in the module holders.
     */
    @JvmStatic
    public val ACK_GATED_MODULES: Set<CollectionModuleId> = setOf(
        CollectionModuleId.USAGE_EVENTS,
        CollectionModuleId.DEVICE_LIFECYCLE,
        CollectionModuleId.USER_IDENTIFICATION,
        CollectionModuleId.BATTERY_TELEMETRY,
        CollectionModuleId.INTERACTION_EVENTS,
        CollectionModuleId.IN_APP_ACTIVITY_CLASS,
        CollectionModuleId.AUDIO_ACTIVITY,
        CollectionModuleId.AUDIO_CONTENT,
        CollectionModuleId.NOTIFICATION_ACTIVITY,
        // Sensing-expansion modules (2026-06-19): each has a CollectionGate.collects(...) call
        // site (device holders for the pull modules; SleepActivityCaptureController +
        // SleepActivityReceiver for the GMS push modules), so the invariant below requires them
        // here — otherwise reconcile() filters them out and they are inert (no consent state, no
        // NEEDS_DECISION notification, never collect even when the study enables them).
        CollectionModuleId.SLEEP,
        CollectionModuleId.ACTIVITY_RECOGNITION,
        CollectionModuleId.HEALTH_CONNECT,
        CollectionModuleId.CONNECTIVITY_STATE,
        CollectionModuleId.APP_NETWORK_USAGE,
        CollectionModuleId.DEVICE_SETTINGS,
    ) + SensorCollectionModules.sensorModuleIds

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
            val prev = previous[moduleId] ?: CollectionModuleState.initial(moduleId)
            val newEnabled = resolvedSetting.enabled
            val newRequired = resolvedSetting.required
            val policySnapshot = resolvedSetting.setting.consentPolicySnapshot()
            when {
                !newEnabled -> disableTransition(moduleId, prev, resolvedSetting, settingVersion, policySnapshot)
                newRequired -> requiredTransition(moduleId, prev, settingVersion, policySnapshot)
                else -> optionalTransition(moduleId, prev, settingVersion, policySnapshot)
            }
        }

    /** Study intent = NOT COLLECTED. */
    private fun disableTransition(
        moduleId: CollectionModuleId,
        prev: CollectionModuleState,
        resolvedSetting: ResolvedModuleSetting,
        version: Int,
        policySnapshot: String,
    ): ModuleTransition = when (prev.phase) {
        CollectionModulePhase.INACTIVE ->
            transition(moduleId, ModuleTransitionType.UNCHANGED_INACTIVE, prev.toInactive(version, policySnapshot))
        CollectionModulePhase.ACTIVE -> {
            val disposition = resolvedSetting.setting.disableDisposition ?: DEFAULT_DISPOSITION
            ModuleTransition(
                moduleId = moduleId,
                type = ModuleTransitionType.FORCIBLY_DISABLED,
                newState = prev.toInactive(version, policySnapshot).copy(lastDisposition = disposition),
                disposition = disposition,
            )
        }
        // Enabled-but-never-collecting (awaiting a decision, or declined) → settle to
        // INACTIVE. Nothing collected, so no disposition; the coordinator suppresses the
        // "turned off" notice for these (prev was not ACTIVE).
        CollectionModulePhase.AWAITING_DECISION, CollectionModulePhase.DECLINED ->
            transition(moduleId, ModuleTransitionType.FORCIBLY_DISABLED, prev.toInactive(version, policySnapshot))
    }

    /** Study intent = REQUIRED. */
    private fun requiredTransition(
        moduleId: CollectionModuleId,
        prev: CollectionModuleState,
        version: Int,
        policySnapshot: String,
    ): ModuleTransition = when (prev.decision) {
        ParticipantDecision.ACCEPTED -> {
            val policyChanged = prev.appliedPolicySnapshot != policySnapshot
            val type = if (policyChanged) {
                ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT
            } else if (!prev.requiredApplied) {
                ModuleTransitionType.NOW_REQUIRED_INFORM
            } else {
                ModuleTransitionType.STILL_ACTIVE
            }
            val state = if (policyChanged) {
                prev.toAwaiting(version, required = true, policySnapshot)
            } else {
                prev.toAccepted(version, required = true, policySnapshot)
            }
            transition(moduleId, type, state)
        }
        // Already explicitly declined a required module → keep it DECLINED (the global halt
        // persists). Must NOT reset to UNDECIDED, or the next settings poll would silently
        // lift the halt. No re-prompt; the participant resumes by re-accepting in Data Sharing.
        ParticipantDecision.DECLINED ->
            transition(moduleId, ModuleTransitionType.STILL_DECLINED, prev.toRequiredDeclined(version, policySnapshot))
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
            transition(moduleId, type, prev.toAwaiting(version, required = true, policySnapshot))
        }
    }

    /** Study intent = OPTIONAL. */
    private fun optionalTransition(
        moduleId: CollectionModuleId,
        prev: CollectionModuleState,
        version: Int,
        policySnapshot: String,
    ): ModuleTransition = when (prev.decision) {
        ParticipantDecision.ACCEPTED -> {
            val policyChanged = prev.appliedPolicySnapshot != policySnapshot
            val type = if (policyChanged) {
                ModuleTransitionType.NEEDS_DECISION
            } else if (prev.requiredApplied) {
                ModuleTransitionType.NOW_OPTIONAL_INFORM
            } else {
                ModuleTransitionType.STILL_ACTIVE
            }
            val state = if (policyChanged) {
                prev.toAwaiting(version, required = false, policySnapshot)
            } else {
                prev.toAccepted(version, required = false, policySnapshot)
            }
            transition(moduleId, type, state)
        }
        ParticipantDecision.DECLINED ->
            transition(moduleId, ModuleTransitionType.STILL_DECLINED, prev.toDeclined(version, policySnapshot))
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
            transition(moduleId, type, prev.toAwaiting(version, required = false, policySnapshot))
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
    private fun CollectionModuleState.toInactive(version: Int, policySnapshot: String): CollectionModuleState =
        copy(
            serverEnabled = false,
            decision = ParticipantDecision.UNDECIDED,
            decidedAtEpochMillis = null,
            requiredApplied = false,
            appliedVersion = version,
            appliedPolicySnapshot = policySnapshot,
        )

    /** Server-enabled but undecided (a fresh decision is required). */
    private fun CollectionModuleState.toAwaiting(
        version: Int,
        required: Boolean,
        policySnapshot: String,
    ): CollectionModuleState =
        copy(
            serverEnabled = true,
            decision = ParticipantDecision.UNDECIDED,
            decidedAtEpochMillis = null,
            requiredApplied = required,
            appliedVersion = version,
            appliedPolicySnapshot = policySnapshot,
        )

    /** Server-enabled and accepted; preserves the original decision timestamp. */
    private fun CollectionModuleState.toAccepted(
        version: Int,
        required: Boolean,
        policySnapshot: String,
    ): CollectionModuleState =
        copy(
            serverEnabled = true,
            decision = ParticipantDecision.ACCEPTED,
            requiredApplied = required,
            appliedVersion = version,
            appliedPolicySnapshot = policySnapshot,
        )

    /** Server-enabled and declined (optional only); preserves the decision timestamp. */
    private fun CollectionModuleState.toDeclined(version: Int, policySnapshot: String): CollectionModuleState =
        copy(
            serverEnabled = true,
            decision = ParticipantDecision.DECLINED,
            requiredApplied = false,
            appliedVersion = version,
            appliedPolicySnapshot = policySnapshot,
        )

    /**
     * Server-enabled, REQUIRED, and declined (the global-halt state); preserves the decision
     * timestamp. Distinct from [toDeclined] in keeping `requiredApplied = true`, so reconcile
     * does not silently lift the halt on the next settings poll.
     */
    private fun CollectionModuleState.toRequiredDeclined(
        version: Int,
        policySnapshot: String,
    ): CollectionModuleState =
        copy(
            serverEnabled = true,
            decision = ParticipantDecision.DECLINED,
            requiredApplied = true,
            appliedVersion = version,
            appliedPolicySnapshot = policySnapshot,
        )

    private fun transition(
        moduleId: CollectionModuleId,
        type: ModuleTransitionType,
        newState: CollectionModuleState,
    ): ModuleTransition = ModuleTransition(moduleId, type, newState)
}
