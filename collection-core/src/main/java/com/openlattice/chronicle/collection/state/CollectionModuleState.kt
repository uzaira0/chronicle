package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId

/**
 * The participant's standing decision for a module (per-module consent design §4.1).
 *
 *  - [UNDECIDED] — the study enables the module but the participant has not yet
 *    accepted or declined it (the consent prompt is pending).
 *  - [ACCEPTED] — the participant agreed; the module may collect (subject to enrollment).
 *  - [DECLINED] — the participant declined an **optional** module; it does not collect.
 */
public enum class ParticipantDecision { UNDECIDED, ACCEPTED, DECLINED }

/**
 * The device's persisted, per-module view of the collection loop (collection loop
 * closure design §5.5, generalized to the per-module consent design §4.1). This is the
 * on-device source of truth for **gating** and for computing **transitions** when
 * freshly-resolved server settings arrive.
 *
 * Pure data — no Android types, no clocks — so the [CollectionStateMachine] that
 * consumes it is fully JVM-testable. Time is an epoch-millis [Long] (the Room layer
 * persists it as a column; the API boundary converts to `OffsetDateTime`).
 *
 * Invariants the state machine maintains:
 *  - [decision] is [ParticipantDecision.UNDECIDED] whenever [serverEnabled] is false:
 *    any server-driven disable clears the decision, so a later re-enable requires a
 *    fresh decision (decision 3 = ALL newly-enabled modules require a decision).
 *  - [decidedAtEpochMillis] is non-null only when [decision] is `ACCEPTED` or `DECLINED`.
 *  - [requiredApplied] mirrors the study's `required` flag from the last applied
 *    settings version, so [CollectionStateMachine.reconcile] can detect a
 *    required↔optional flip.
 */
public data class CollectionModuleState(
    val moduleId: CollectionModuleId,
    /** Whether the server's resolved setting currently enables this module. */
    val serverEnabled: Boolean,
    /** The participant's standing decision for this module. */
    val decision: ParticipantDecision,
    /** When the participant last decided (accepted/declined); null while UNDECIDED. */
    val decidedAtEpochMillis: Long?,
    /** The study's `required` flag from the last applied settings version. */
    val requiredApplied: Boolean,
    /** The [com.openlattice.chronicle.collection.AndroidDataCollectionSetting] version last applied. */
    val appliedVersion: Int,
    /** Stable snapshot of the collection policy covered by the standing decision. */
    val appliedPolicySnapshot: String?,
    /** The disposition applied on the most recent ACTIVE -> INACTIVE transition, if any. */
    val lastDisposition: CollectionDataDisposition?,
) {
    /** Whether the participant has accepted the current enable epoch. */
    public val accepted: Boolean get() = decision == ParticipantDecision.ACCEPTED

    /** Lifecycle phase derived from [serverEnabled] + [decision]. */
    public val phase: CollectionModulePhase
        get() = when {
            !serverEnabled -> CollectionModulePhase.INACTIVE
            decision == ParticipantDecision.ACCEPTED -> CollectionModulePhase.ACTIVE
            decision == ParticipantDecision.DECLINED -> CollectionModulePhase.DECLINED
            else -> CollectionModulePhase.AWAITING_DECISION
        }

    /**
     * Whether collection may run for this module **assuming the participant is enrolled**.
     * The full device gate is `enrolled() && collectsWhenEnrolled` — enrollment is
     * enforced by the existing module seam, this adds the server-enabled + accepted
     * requirements (so "enabled without participant consent" is structurally impossible).
     */
    public val collectsWhenEnrolled: Boolean
        get() = serverEnabled && decision == ParticipantDecision.ACCEPTED

    /**
     * Needs the participant's attention in the Data Sharing surface: the study enables and
     * **requires** this module, but it is not accepted — either a grace-window [UNDECIDED]
     * (others keep collecting; this one doesn't until accepted) or a halting [DECLINED].
     * Drives which modules show an Accept/Decline affordance.
     */
    public val requiredButNotAccepted: Boolean
        get() = serverEnabled && requiredApplied && decision != ParticipantDecision.ACCEPTED

    /**
     * Halts ALL collection (per-module consent design §5): the participant **explicitly
     * declined** a study-required module. Reversible — re-accepting it resumes collection.
     * A merely [UNDECIDED] required module does **not** halt (grace window: already-accepted
     * modules keep collecting until the participant accepts or declines). Mirrors the
     * `countRequiredDeclined` gate query in `CollectionModuleStateDao`; keep the two in sync.
     */
    public val requiredAndDeclined: Boolean
        get() = serverEnabled && requiredApplied && decision == ParticipantDecision.DECLINED

    public companion object {
        /**
         * True when collection is globally halted because the participant explicitly declined
         * a study-required module ([requiredAndDeclined]). While true, nothing collects — not
         * even already-accepted modules — until that module is re-accepted.
         */
        @JvmStatic
        public fun collectionHalted(states: Collection<CollectionModuleState>): Boolean =
            states.any { it.requiredAndDeclined }

        /** The initial state for a never-before-seen module (fresh install / first enrollment). */
        @JvmStatic
        public fun initial(moduleId: CollectionModuleId): CollectionModuleState =
            CollectionModuleState(
                moduleId = moduleId,
                serverEnabled = false,
                decision = ParticipantDecision.UNDECIDED,
                decidedAtEpochMillis = null,
                requiredApplied = false,
                appliedVersion = 0,
                appliedPolicySnapshot = null,
                lastDisposition = null,
            )
    }
}

/**
 * INACTIVE = server-disabled (not collecting). AWAITING_DECISION = server-enabled but the
 * participant has not yet decided (NOT collecting). ACTIVE = server-enabled and accepted
 * (collecting, subject to enrollment). DECLINED = server-enabled but the participant
 * declined an optional module (NOT collecting).
 */
public enum class CollectionModulePhase { INACTIVE, AWAITING_DECISION, ACTIVE, DECLINED }
