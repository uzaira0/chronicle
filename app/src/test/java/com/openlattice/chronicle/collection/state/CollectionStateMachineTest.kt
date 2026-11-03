package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.settings.ResolutionSource
import com.openlattice.chronicle.collection.settings.ResolvedModuleSetting
import com.openlattice.chronicle.serialization.ChronicleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openlattice.chronicle.collection.withConsentEpochs

/**
 * Exhaustive tests for the pure collection-loop transition engine (collection loop
 * closure design §6; per-module consent design §4-§5). On-device verification is blocked
 * (emulator SIGSEGV; androidTest is destructive), so this truth-table + transition-matrix
 * coverage is the primary correctness evidence for the gate and the state machine.
 */
class CollectionStateMachineTest {

    private val moduleId = CollectionModuleId.BATTERY_TELEMETRY

    @Test fun sharedConsentContract() {
        ConsentContractCase.all().forEach { row ->
            val transition = row.reconcile()
            assertEquals(row.name, row.survives,
                transition.newState.decision == row.decision && row.decision != ParticipantDecision.UNDECIDED)
            assertEquals(row.name, row.halted,
                CollectionModuleState.collectionHalted(listOf(transition.newState)))
        }
    }

    private fun state(
        serverEnabled: Boolean,
        decision: ParticipantDecision,
        required: Boolean = false,
        version: Int = 1,
        id: CollectionModuleId = moduleId,
    ) = CollectionModuleState(
        moduleId = id,
        serverEnabled = serverEnabled,
        decision = decision,
        decidedAtEpochMillis = if (decision == ParticipantDecision.UNDECIDED) null else 100L,
        requiredApplied = required,
        appliedVersion = version,
    )

    private fun resolved(
        enabled: Boolean,
        required: Boolean = false,
        id: CollectionModuleId = moduleId,
    ): Map<CollectionModuleId, ResolvedModuleSetting> = mapOf(
        id to ResolvedModuleSetting(
            moduleId = id,
            setting = CollectionModuleSetting(enabled = enabled, required = required),
            source = ResolutionSource.GENERALIZED,
            valid = true,
        ),
    )

    private fun reconcileOne(
        prev: CollectionModuleState?,
        enabled: Boolean,
        required: Boolean = false,
    ) = CollectionStateMachine.reconcile(
        previous = if (prev == null) emptyMap() else mapOf(moduleId to prev),
        resolved = resolved(enabled, required),
        settingVersion = 2,
    ).single()

    // ===== Gate truth table: collect only when enrolled AND serverEnabled AND ACCEPTED =====

    @Test fun testGateTruthTable() {
        for (enrolled in listOf(false, true)) {
            for (serverEnabled in listOf(false, true)) {
                for (decision in ParticipantDecision.entries) {
                    val s = state(serverEnabled, decision)
                    val collects = enrolled && s.collectsWhenEnrolled
                    val expected = enrolled && serverEnabled && decision == ParticipantDecision.ACCEPTED
                    assertEquals(
                        "enrolled=$enrolled serverEnabled=$serverEnabled decision=$decision",
                        expected,
                        collects,
                    )
                }
            }
        }
    }

    @Test fun testPhaseDerivation() {
        assertEquals(CollectionModulePhase.INACTIVE, state(false, ParticipantDecision.UNDECIDED).phase)
        assertEquals(CollectionModulePhase.INACTIVE, state(false, ParticipantDecision.ACCEPTED).phase)
        assertEquals(CollectionModulePhase.AWAITING_DECISION, state(true, ParticipantDecision.UNDECIDED).phase)
        assertEquals(CollectionModulePhase.ACTIVE, state(true, ParticipantDecision.ACCEPTED).phase)
        assertEquals(CollectionModulePhase.DECLINED, state(true, ParticipantDecision.DECLINED).phase)
    }

    // ===== reconcile(): → NOT COLLECTED =====

    @Test fun testInactiveStaysInactive() {
        val t = reconcileOne(state(false, ParticipantDecision.UNDECIDED), enabled = false)
        assertEquals(ModuleTransitionType.UNCHANGED_INACTIVE, t.type)
        assertEquals(CollectionModulePhase.INACTIVE, t.newState.phase)
    }

    @Test fun testActiveDisabledIsForciblyDisabledAndClearsTheDecision() {
        val t = reconcileOne(state(true, ParticipantDecision.ACCEPTED), enabled = false)
        assertEquals(ModuleTransitionType.FORCIBLY_DISABLED, t.type)
        assertEquals(CollectionModulePhase.INACTIVE, t.newState.phase)
        assertEquals("decision cleared on disable", ParticipantDecision.UNDECIDED, t.newState.decision)
    }

    @Test fun testAwaitingDisabledIsForciblyDisabled() {
        val t = reconcileOne(state(true, ParticipantDecision.UNDECIDED), enabled = false)
        assertEquals(ModuleTransitionType.FORCIBLY_DISABLED, t.type)
        assertEquals(CollectionModulePhase.INACTIVE, t.newState.phase)
    }

    @Test fun testDeclinedDisabledIsForciblyDisabled() {
        val t = reconcileOne(state(true, ParticipantDecision.DECLINED), enabled = false)
        assertEquals(ModuleTransitionType.FORCIBLY_DISABLED, t.type)
        assertEquals(CollectionModulePhase.INACTIVE, t.newState.phase)
    }

    // ===== reconcile(): → OPTIONAL =====

    @Test fun testInactiveToOptionalNeedsDecision() {
        val t = reconcileOne(state(false, ParticipantDecision.UNDECIDED), enabled = true, required = false)
        assertEquals(ModuleTransitionType.NEEDS_DECISION, t.type)
        assertEquals(CollectionModulePhase.AWAITING_DECISION, t.newState.phase)
        assertFalse("must not collect until accepted", t.newState.collectsWhenEnrolled)
        assertTrue(t.requestsOptionalDecision)
    }

    @Test fun testFreshInstallOptionalNeedsDecision() {
        val t = reconcileOne(prev = null, enabled = true, required = false)
        assertEquals(ModuleTransitionType.NEEDS_DECISION, t.type)
        assertEquals(CollectionModulePhase.AWAITING_DECISION, t.newState.phase)
    }

    @Test fun testAwaitingStaysAwaitingOptional() {
        // Already awaiting an OPTIONAL decision from a prior sync → STILL_AWAITING_DECISION, NOT a
        // fresh NEEDS_DECISION. The in-app prompt persists, but the coordinator must not re-post the
        // "a data collection option was added" notification on every settings poll.
        val t = reconcileOne(state(true, ParticipantDecision.UNDECIDED), enabled = true, required = false)
        assertEquals(ModuleTransitionType.STILL_AWAITING_DECISION, t.type)
        assertEquals(CollectionModulePhase.AWAITING_DECISION, t.newState.phase)
        assertFalse("must not re-request an optional decision once already awaiting", t.requestsOptionalDecision)
    }

    @Test fun testAwaitingStaysAwaitingRequired() {
        // Already awaiting a REQUIRED decision from a prior sync → STILL_AWAITING_DECISION, NOT a
        // fresh NEWLY_REQUIRED_NEEDS_CONSENT every poll. The mandatory in-app surface persists.
        val prev = state(true, ParticipantDecision.UNDECIDED, required = true)
        val t = reconcileOne(prev, enabled = true, required = true)
        assertEquals(ModuleTransitionType.STILL_AWAITING_DECISION, t.type)
        assertEquals(CollectionModulePhase.AWAITING_DECISION, t.newState.phase)
        assertTrue(t.newState.requiredApplied)
        assertFalse(t.requiresMandatoryConsent)
    }

    @Test fun testOptionalAwaitingBecomesRequiredRePrompts() {
        // Still-undecided module flips optional → required: the flavor changed, so re-prompt with the
        // mandatory surface — NOT a silent STILL_AWAITING_DECISION.
        val prev = state(true, ParticipantDecision.UNDECIDED, required = false)
        val t = reconcileOne(prev, enabled = true, required = true)
        assertEquals(ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT, t.type)
        assertTrue(t.newState.requiredApplied)
    }

    @Test fun testRequiredAwaitingBecomesOptionalRePrompts() {
        // Still-undecided module flips required → optional: the flavor changed, so re-prompt with the
        // optional decision.
        val prev = state(true, ParticipantDecision.UNDECIDED, required = true)
        val t = reconcileOne(prev, enabled = true, required = false)
        assertEquals(ModuleTransitionType.NEEDS_DECISION, t.type)
        assertFalse(t.newState.requiredApplied)
    }

    @Test fun testAcceptedOptionalStaysActive() {
        val prev = state(true, ParticipantDecision.ACCEPTED, required = false)
        val t = reconcileOne(prev, enabled = true, required = false)
        assertEquals(ModuleTransitionType.STILL_ACTIVE, t.type)
        assertEquals(CollectionModulePhase.ACTIVE, t.newState.phase)
        assertEquals(prev.decidedAtEpochMillis, t.newState.decidedAtEpochMillis)
        assertEquals(2, t.newState.appliedVersion)
    }

    @Test fun testAcceptedOptionalPolicyEditsKeepTheDecision() {
        val priorSetting = CollectionModuleSetting(enabled = true, required = false)
        val changedSettings = listOf(
            priorSetting.copy(collectionCadence = CollectionCadence(intervalSeconds = 60)),
            priorSetting.copy(sensorPolicy = AndroidSensorSetting(samplingRateHz = 10)),
            priorSetting.copy(interactionPolicy = InteractionPolicy(gridRows = 5)),
        )
        val prev = state(serverEnabled = true, decision = ParticipantDecision.ACCEPTED)

        changedSettings.forEach { changedSetting ->
            val transition = reconcileSetting(prev, changedSetting)
            assertEquals(ModuleTransitionType.STILL_ACTIVE, transition.type)
            assertEquals(ParticipantDecision.ACCEPTED, transition.newState.decision)
            assertTrue("a policy edit keeps collecting", transition.newState.collectsWhenEnrolled)
        }
    }

    @Test fun testAcceptedRequiredPolicyEditKeepsTheDecisionWithoutMandatoryConsent() {
        val priorSetting = CollectionModuleSetting(enabled = true, required = true)
        val prev = state(serverEnabled = true, decision = ParticipantDecision.ACCEPTED, required = true)

        val transition = reconcileSetting(prev, priorSetting.copy(collectionCadence = CollectionCadence(intervalSeconds = 1_800)))

        assertEquals(ModuleTransitionType.STILL_ACTIVE, transition.type)
        assertFalse(transition.requiresMandatoryConsent)
        assertEquals(CollectionModulePhase.ACTIVE, transition.newState.phase)
    }

    @Test fun testADisableThenReenableEpochVoidsTheAcceptance() {
        val prev = state(serverEnabled = true, decision = ParticipantDecision.ACCEPTED, version = 1)

        val transition = reconcileSetting(prev, CollectionModuleSetting(enabled = true, consentEpoch = 2))

        assertEquals(ModuleTransitionType.NEEDS_DECISION, transition.type)
        assertFalse(transition.newState.collectsWhenEnrolled)
    }

    private fun reconcileSetting(
        prev: CollectionModuleState,
        setting: CollectionModuleSetting,
        id: CollectionModuleId = moduleId,
    ) = CollectionStateMachine.reconcile(
        previous = mapOf(id to prev),
        resolved = mapOf(id to ResolvedModuleSetting(id, setting, ResolutionSource.GENERALIZED, valid = true)),
        settingVersion = 2,
    ).single()

    @Test fun testHealthConnectRecordScopeEditKeepsTheDecision() {
        val healthConnect = CollectionModuleId.HEALTH_CONNECT
        val expandedSetting = CollectionModuleSetting(
            enabled = true,
            healthConnectRecordTypes = setOf(HealthConnectRecordType.STEPS, HealthConnectRecordType.HEART_RATE),
        )
        val previous = state(serverEnabled = true, decision = ParticipantDecision.ACCEPTED, id = healthConnect)

        val transition = reconcileSetting(previous, expandedSetting, healthConnect)

        assertEquals(ModuleTransitionType.STILL_ACTIVE, transition.type)
        assertTrue(transition.newState.collectsWhenEnrolled)
    }

    @Test fun testAcceptedOptionalStaysActiveWhenOnlySettingsVersionChanges() {
        val setting = CollectionModuleSetting(enabled = true, required = false)
        val prev = state(
            serverEnabled = true,
            decision = ParticipantDecision.ACCEPTED,
            version = 1,
        )

        val transition = CollectionStateMachine.reconcile(
            previous = mapOf(moduleId to prev),
            resolved = mapOf(
                moduleId to ResolvedModuleSetting(
                    moduleId = moduleId,
                    setting = setting,
                    source = ResolutionSource.GENERALIZED,
                    valid = true,
                ),
            ),
            settingVersion = 2,
        ).single()

        assertEquals(ModuleTransitionType.STILL_ACTIVE, transition.type)
        assertEquals(CollectionModulePhase.ACTIVE, transition.newState.phase)
    }

    @Test fun testDeclinedOptionalStaysDeclined() {
        val t = reconcileOne(state(true, ParticipantDecision.DECLINED), enabled = true, required = false)
        assertEquals(ModuleTransitionType.STILL_DECLINED, t.type)
        assertEquals(CollectionModulePhase.DECLINED, t.newState.phase)
        assertFalse(t.newState.collectsWhenEnrolled)
    }

    @Test fun testRequiredBecomesOptionalInformsAndKeepsActive() {
        val prev = state(true, ParticipantDecision.ACCEPTED, required = true)
        val t = reconcileOne(prev, enabled = true, required = false)
        assertEquals(ModuleTransitionType.NOW_OPTIONAL_INFORM, t.type)
        assertEquals(CollectionModulePhase.ACTIVE, t.newState.phase)
        assertFalse("toggle unlocks", t.newState.requiredApplied)
        assertTrue(t.isInformationalNotice)
    }

    // ===== reconcile(): → REQUIRED =====

    @Test fun testInactiveToRequiredNeedsMandatoryConsent() {
        val t = reconcileOne(state(false, ParticipantDecision.UNDECIDED), enabled = true, required = true)
        assertEquals(ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT, t.type)
        assertEquals(CollectionModulePhase.AWAITING_DECISION, t.newState.phase)
        assertTrue(t.newState.requiredApplied)
        assertTrue(t.requiresMandatoryConsent)
        assertFalse(t.newState.collectsWhenEnrolled)
    }

    @Test fun testDeclinedModuleBecomingRequiredStaysDeclined() {
        // A previously-declined module the study now requires STAYS declined (the global halt
        // persists). Reconcile must NOT reset it to undecided, or the next settings poll would
        // silently lift the halt.
        val t = reconcileOne(state(true, ParticipantDecision.DECLINED), enabled = true, required = true)
        assertEquals(ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT, t.type)
        assertEquals(CollectionModulePhase.DECLINED, t.newState.phase)
        assertTrue("required flag retained so the halt holds across polls", t.newState.requiredApplied)
        assertTrue(t.newState.requiredAndDeclined)
    }

    @Test fun testAlreadyRequiredDeclineDoesNotNotifyAgain() {
        val t = reconcileOne(state(true, ParticipantDecision.DECLINED, required = true), enabled = true, required = true)
        assertEquals(ModuleTransitionType.STILL_DECLINED, t.type)
        assertTrue(t.newState.requiredAndDeclined)
    }

    @Test fun testAcceptedOptionalBecomesRequiredInformsAndLocks() {
        val prev = state(true, ParticipantDecision.ACCEPTED, required = false)
        val t = reconcileOne(prev, enabled = true, required = true)
        assertEquals(ModuleTransitionType.NOW_REQUIRED_INFORM, t.type)
        assertEquals(CollectionModulePhase.ACTIVE, t.newState.phase)
        assertTrue("toggle locks", t.newState.requiredApplied)
        assertTrue(t.isInformationalNotice)
    }

    @Test fun testAcceptedRequiredStaysActive() {
        val prev = state(true, ParticipantDecision.ACCEPTED, required = true)
        val t = reconcileOne(prev, enabled = true, required = true)
        assertEquals(ModuleTransitionType.STILL_ACTIVE, t.type)
        assertEquals(CollectionModulePhase.ACTIVE, t.newState.phase)
    }

    @Test fun testReEnableAfterPriorAcceptRequiresFreshDecision() {
        // active -> disabled clears the decision...
        val disabled = reconcileOne(state(true, ParticipantDecision.ACCEPTED), enabled = false).newState
        assertEquals(ParticipantDecision.UNDECIDED, disabled.decision)
        // ...so re-enabling that same module demands a fresh decision (decision 3).
        val reEnabled = CollectionStateMachine.reconcile(
            previous = mapOf(moduleId to disabled),
            resolved = resolved(enabled = true, required = false),
            settingVersion = 3,
        ).single()
        assertEquals(ModuleTransitionType.NEEDS_DECISION, reEnabled.type)
        assertFalse(reEnabled.newState.collectsWhenEnrolled)
    }

    // ===== decide(): participant-driven accept / decline =====

    @Test fun testAcceptActivatesAwaitingAndDeclinedModules() {
        val states = mapOf(
            CollectionModuleId.BATTERY_TELEMETRY to state(true, ParticipantDecision.UNDECIDED, id = CollectionModuleId.BATTERY_TELEMETRY),
            CollectionModuleId.USAGE_EVENTS to state(true, ParticipantDecision.DECLINED, id = CollectionModuleId.USAGE_EVENTS),
            CollectionModuleId.SENSOR_ACCELEROMETER to state(false, ParticipantDecision.UNDECIDED, id = CollectionModuleId.SENSOR_ACCELEROMETER),
        )
        val result = CollectionStateMachine.decide(
            previous = states,
            decisions = mapOf(
                CollectionModuleId.BATTERY_TELEMETRY to ParticipantDecision.ACCEPTED,
                CollectionModuleId.USAGE_EVENTS to ParticipantDecision.ACCEPTED,
                CollectionModuleId.SENSOR_ACCELEROMETER to ParticipantDecision.ACCEPTED,
            ),
            nowEpochMillis = 555L,
        )
        // The two server-enabled modules activate; the INACTIVE one cannot be decided into existence.
        assertEquals(setOf(CollectionModuleId.BATTERY_TELEMETRY, CollectionModuleId.USAGE_EVENTS), result.activated)
        assertEquals(555L, result.newStates.getValue(CollectionModuleId.BATTERY_TELEMETRY).decidedAtEpochMillis)
        assertEquals(CollectionModulePhase.ACTIVE, result.newStates.getValue(CollectionModuleId.USAGE_EVENTS).phase)
        assertEquals(CollectionModulePhase.INACTIVE, result.newStates.getValue(CollectionModuleId.SENSOR_ACCELEROMETER).phase)
    }

    @Test fun testDeclineDeactivatesOptionalActiveModule() {
        val states = mapOf(moduleId to state(true, ParticipantDecision.ACCEPTED, required = false))
        val result = CollectionStateMachine.decide(states, mapOf(moduleId to ParticipantDecision.DECLINED), 7L)
        assertEquals(setOf(moduleId), result.deactivated)
        assertEquals(CollectionModulePhase.DECLINED, result.newStates.getValue(moduleId).phase)
    }

    @Test fun testDeclineOnRequiredModuleTripsGlobalHalt() {
        // Declining a required module is now permitted (the mandatory surface offers an explicit
        // Decline). It sets DECLINED while keeping requiredApplied, which trips the global halt.
        val states = mapOf(moduleId to state(true, ParticipantDecision.ACCEPTED, required = true))
        val result = CollectionStateMachine.decide(states, mapOf(moduleId to ParticipantDecision.DECLINED), 7L)
        assertEquals(setOf(moduleId), result.deactivated)
        val newState = result.newStates.getValue(moduleId)
        assertEquals(CollectionModulePhase.DECLINED, newState.phase)
        assertTrue("required flag retained", newState.requiredApplied)
        assertTrue("declining a required module trips the global halt", newState.requiredAndDeclined)
    }

    @Test fun testAcceptActiveModuleIsIdempotent() {
        val states = mapOf(moduleId to state(true, ParticipantDecision.ACCEPTED))
        val result = CollectionStateMachine.decide(states, mapOf(moduleId to ParticipantDecision.ACCEPTED), 999L)
        assertTrue("already-active module is not re-activated", result.activated.isEmpty())
        assertEquals(100L, result.newStates.getValue(moduleId).decidedAtEpochMillis)
    }

    // ===== reconcile() coverage / drift guards =====

    @Test fun testReconcileCoversAllResolvedModules() {
        val resolvedAll = mapOf(
            CollectionModuleId.BATTERY_TELEMETRY to ResolvedModuleSetting(
                CollectionModuleId.BATTERY_TELEMETRY,
                CollectionModuleSetting(enabled = true),
                ResolutionSource.SAFE_DEFAULT,
                true,
            ),
            CollectionModuleId.SENSOR_ACCELEROMETER to ResolvedModuleSetting(
                CollectionModuleId.SENSOR_ACCELEROMETER,
                CollectionModuleSetting(enabled = false),
                ResolutionSource.SAFE_DEFAULT,
                true,
            ),
        )
        val transitions = CollectionStateMachine.reconcile(emptyMap(), resolvedAll, settingVersion = 1)
        assertEquals(2, transitions.size)
        assertEquals(setOf(CollectionModuleId.BATTERY_TELEMETRY, CollectionModuleId.SENSOR_ACCELEROMETER), transitions.map { it.moduleId }.toSet())
    }

    @Test fun testReconcileExcludesNonAckGatedOperationalModules() {
        // upload_telemetry / sensor_availability / questionnaire default-enabled but their
        // collection is NOT CollectionGate-gated, so they must never enter the consent
        // lifecycle — otherwise a consent screen would list modules the decision does not gate.
        val nonGated = setOf(
            CollectionModuleId.UPLOAD_TELEMETRY,
            CollectionModuleId.SENSOR_AVAILABILITY,
            CollectionModuleId.QUESTIONNAIRE,
        )
        val resolvedAll = (nonGated + CollectionModuleId.USAGE_EVENTS).associateWith {
            ResolvedModuleSetting(it, CollectionModuleSetting(enabled = true), ResolutionSource.SAFE_DEFAULT, true)
        }
        val transitions = CollectionStateMachine.reconcile(emptyMap(), resolvedAll, settingVersion = 1)
        assertEquals(listOf(CollectionModuleId.USAGE_EVENTS), transitions.map { it.moduleId })
        assertTrue(transitions.none { it.moduleId in nonGated })
        // The set is exactly the CollectionGate.collects(...) call sites — guards drift. Each
        // per-sensor module is gated individually (per-sensor consent redesign).
        // user_identification is both consent-gated and locally switchable: the local setting can
        // narrow an accepted study policy, but cannot enable collection the participant declined.
        assertEquals(
            setOf(
                CollectionModuleId.USAGE_EVENTS,
                CollectionModuleId.DEVICE_LIFECYCLE,
                CollectionModuleId.USER_IDENTIFICATION,
                CollectionModuleId.BATTERY_TELEMETRY,
                CollectionModuleId.INTERACTION_EVENTS,
                CollectionModuleId.IN_APP_ACTIVITY_CLASS,
                CollectionModuleId.AUDIO_ACTIVITY,
                CollectionModuleId.AUDIO_CONTENT,
                CollectionModuleId.NOTIFICATION_ACTIVITY,
                CollectionModuleId.SLEEP,
                CollectionModuleId.ACTIVITY_RECOGNITION,
                CollectionModuleId.HEALTH_CONNECT,
                CollectionModuleId.CONNECTIVITY_STATE,
                CollectionModuleId.APP_NETWORK_USAGE,
                CollectionModuleId.DEVICE_SETTINGS,
            ) + SensorCollectionModules.sensorModuleIds,
            CollectionStateMachine.ACK_GATED_MODULES,
        )
        assertTrue(CollectionModuleId.USER_IDENTIFICATION in CollectionStateMachine.ACK_GATED_MODULES)
    }
}

/** One row of the shared chronicle-models consent fixture, also run by the models owner and the server. */
internal data class ConsentContractCase(
    val name: String,
    val decision: ParticipantDecision,
    val revisions: List<AndroidDataCollectionSetting>,
    val survives: Boolean,
    val halted: Boolean,
) {
    private val moduleId get() = CollectionModuleId.BATTERY_TELEMETRY

    /** The phone holds [decision] at the first revision, then reconciles the last one with its server epochs. */
    fun reconcile(): ModuleTransition {
        val initial = revisions.first().effectiveModules().getValue(moduleId)
        val adapter = ChronicleJson.moshi
            .adapter(AndroidDataCollectionSetting::class.java)
        val latest = checkNotNull(adapter.fromJson(adapter.toJson(revisions.last().withConsentEpochs(revisions))))
            .effectiveModules().getValue(moduleId)
        val previous = CollectionModuleState(
            moduleId = moduleId,
            serverEnabled = initial.enabled,
            decision = decision,
            decidedAtEpochMillis = if (decision == ParticipantDecision.UNDECIDED) null else 100L,
            requiredApplied = initial.required,
            appliedVersion = 1,
        )
        return CollectionStateMachine.reconcile(mapOf(moduleId to previous), mapOf(moduleId to
            ResolvedModuleSetting(moduleId, latest, ResolutionSource.GENERALIZED, true)), revisions.last().settingsVersion).single()
    }

    companion object {
        fun all(): List<ConsentContractCase> {
            val fixture = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .map { java.io.File(it, "chronicle-models/fixtures/collection-consent.json") }.first { it.isFile }
            val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, ConsentContractCase::class.java)
            return checkNotNull(ChronicleJson.moshi
                .adapter<List<ConsentContractCase>>(type).fromJson(fixture.readText()))
        }
    }
}
