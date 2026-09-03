package com.openlattice.chronicle.collection.state

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.BatteryPolicy
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.NetworkPolicy
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.settings.CollectionSettingsResolver
import com.openlattice.chronicle.collection.settings.EncryptedPrefsSensorSettingSource
import com.openlattice.chronicle.collection.settings.ResolvedModuleSetting
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.sensors.SensorTypeMapping
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.services.notifications.CHANNEL_ID
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.userIdentificationMayRun
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.completeServerForIdentity
import com.openlattice.chronicle.services.upload.exactActiveEnrollmentServer
import com.openlattice.chronicle.services.upload.triggerImmediateUpload
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.utils.Utils
import java.time.OffsetDateTime
import java.util.UUID

internal data class EnrollmentModulePartition(
    val accepted: Set<CollectionModuleId>,
    val declined: Set<CollectionModuleId>,
    val unavailable: Set<CollectionModuleId>,
    val settingsVersion: Int,
)

/**
 * Release-bound policy contract. These legacy knobs were published before the Android runtime
 * implemented them consistently across every collector/uploader. Rejecting them prevents a study
 * from obtaining consent for a policy the device would silently ignore. Removed legacy fields are
 * ignored by the shared wire model and are never interpreted by the mobile runtime.
 */
internal fun requireSupportedCollectionPolicies(fetched: AndroidDataCollectionSetting) {
    val distribution = DistributionChannel.current()
    val requestedEnabledModules = fetched.effectiveEnabledModuleIds() +
        fetched.modules.filterValues { it.enabled }.keys
    val unsupportedModules = requestedEnabledModules
        .filterNot { DistributionModulePolicy.supports(distribution, it) }
    require(unsupportedModules.isEmpty()) {
        "${distribution.name.lowercase()} release does not support enabled modules: " +
            unsupportedModules.joinToString { it.id }
    }
    if (distribution in setOf(DistributionChannel.PLAY, DistributionChannel.AMAZON)) {
        require(fetched.effectiveModules().getValue(CollectionModuleId.UPLOAD_TELEMETRY).enabled) {
            "${distribution.name.lowercase()} release requires disclosed upload diagnostics"
        }
    }

    fetched.effectiveModules().forEach { (moduleId, setting) ->
        val customCollectionCadenceSupported = moduleId in ExpansionPullSchedule.INTERVAL_GATED_MODULES
        require(customCollectionCadenceSupported || setting.collectionCadence == CollectionCadence.DEFAULT_COLLECTION) {
            "${moduleId.id} uses a collection cadence that its runtime collector does not honor"
        }
        require(setting.collectionCadence.jitterSeconds == 0L) {
            "${moduleId.id} uses unsupported collection-cadence jitter"
        }
        require(setting.uploadCadence == CollectionCadence.DEFAULT_UPLOAD) {
            "${moduleId.id} uses an unsupported upload cadence"
        }
        require(setting.batteryPolicy == BatteryPolicy.DEFAULT) {
            "${moduleId.id} uses an unsupported battery policy"
        }
        require(setting.networkPolicy == NetworkPolicy.DEFAULT) {
            "${moduleId.id} uses an unsupported network policy"
        }
    }
}

/** Validates the exact server-stamped enrollment consent/capability partition. */
internal fun validateEnrollmentModulePartition(
    fetched: AndroidDataCollectionSetting,
    accepted: Set<CollectionModuleId>,
    declined: Set<CollectionModuleId>,
    unavailable: Set<CollectionModuleId>,
): EnrollmentModulePartition {
    val effectiveModules = fetched.effectiveModules()
    val enabled = fetched.effectiveEnabledModuleIds()
    require(accepted.intersect(declined).isEmpty()) { "Accepted and declined modules overlap" }
    require(accepted.intersect(unavailable).isEmpty()) { "Accepted and unavailable modules overlap" }
    require(declined.intersect(unavailable).isEmpty()) { "Declined and unavailable modules overlap" }
    require(unavailable.all(SensorCollectionModules::isSensorModule)) {
        "Only per-sensor hardware modules may be unavailable"
    }
    require(accepted + declined + unavailable == enabled) {
        "Enrollment evidence must partition every effective enabled module exactly once"
    }
    val requiredAvailable = enabled.filterTo(linkedSetOf()) { moduleId ->
        effectiveModules.getValue(moduleId).required && moduleId !in unavailable
    }
    require(accepted.containsAll(requiredAvailable)) {
        "Every required available module must be accepted"
    }
    return EnrollmentModulePartition(
        accepted = accepted,
        declined = declined,
        unavailable = unavailable,
        settingsVersion = fetched.settingsVersion,
    )
}

/** Derives unavailable sensors from hardware, then validates the exhaustive partition. */
internal fun enrollmentModulePartition(
    fetched: AndroidDataCollectionSetting,
    availableSensorModules: Set<CollectionModuleId>,
    accepted: Set<CollectionModuleId>,
    declined: Set<CollectionModuleId>,
): EnrollmentModulePartition {
    val enabled = fetched.effectiveEnabledModuleIds()
    val unavailable = enabled.filterTo(linkedSetOf()) { moduleId ->
        SensorCollectionModules.isSensorModule(moduleId) && moduleId !in availableSensorModules
    }
    // The orientation wizard intentionally presents only modules whose runtime collection seam is
    // participant-controlled. Enabled operational modules (for example upload telemetry and sensor
    // availability) are instead covered by the affirmative study-level disclosure immediately
    // before that wizard. Include those acknowledged modules in the exhaustive server evidence,
    // while leaving every ACK-gated, available module dependent on an explicit wizard decision.
    val studyDisclosureAccepted = enabled - CollectionStateMachine.ACK_GATED_MODULES
    return validateEnrollmentModulePartition(
        fetched,
        accepted + studyDisclosureAccepted,
        declined,
        unavailable,
    )
}

/**
 * Runtime orchestrator for the collection loop (collection loop closure design §5.5).
 * The pure decision logic lives in [CollectionStateMachine] (JVM-tested); this class is
 * the Android glue that drives it: fetch the server setting, resolve it, reconcile
 * against persisted state, persist, and dispatch side effects (acknowledgment prompts,
 * scope-change notifications, the hardware-sensor service, and disable dispositions).
 *
 * Two entry points:
 *  - [sync] — called by the settings-sync worker, piggybacked on uploads, and at
 *    enrollment. Reconciles server-driven changes.
 *  - [acknowledge] — called by the acknowledgment screen when the participant accepts
 *    one or more pending modules; activates them and reports the acknowledgment.
 */
class CollectionLoopCoordinator(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "CollectionLoopCoordinator"
        private const val NOTIFICATION_ID_ACK = 47_001
        private const val NOTIFICATION_ID_SCOPE = 47_002
        private const val NOTIFICATION_ID_INFORM = 47_003
        /** Cap on a HOLD_PENDING queue so a held module's data can't grow unbounded. */
        const val HOLD_PENDING_CAP = 5_000

        /**
         * Drops per-sensor modules whose hardware this device does not physically have from
         * [resolved], leaving every non-sensor module and every device-present sensor module
         * intact. [availableSensorModules] is the set of `sensor_*` module ids the device
         * actually carries (see [availableSensorModules]).
         *
         * Sensor consent must be hardware-aware: a study may enable a sensor (for example a
         * Samsung-only grip sensor) that a given device lacks, and the participant must NOT be
         * asked to consent to — nor see a Data Sharing toggle / "needs decision" prompt for — a
         * sensor that can never produce data. Filtering the resolved settings here, before they
         * reach [CollectionStateMachine.reconcile], makes every consumer (the enrollment
         * walkthrough, post-enrollment sync, and decision seeding) hardware-aware from one place.
         * The Data Sharing surface still shows the absent sensor as "Not available on this device"
         * via its own independent hardware check.
         */
        @JvmStatic
        public fun retainCollectableSensors(
            resolved: Map<CollectionModuleId, ResolvedModuleSetting>,
            availableSensorModules: Set<CollectionModuleId>,
        ): Map<CollectionModuleId, ResolvedModuleSetting> =
            resolved.filterKeys { id ->
                !SensorCollectionModules.isSensorModule(id) || id in availableSensorModules
            }

        /**
         * Pure collection-ack delivery core. Local consent is already persisted before this runs.
         * The database invariant permits at most one active study server; callers persist a
         * delivery failure for retry. A non-empty decision with no eligible destination is a
         * failure, never a successful no-op.
         */
        @JvmStatic
        fun reportCollectionAckToServers(
            servers: List<UploadServerEntity>,
            accepted: Set<CollectionModuleId>,
            declined: Set<CollectionModuleId>,
            unavailable: Set<CollectionModuleId> = emptySet(),
            trigger: ConsentTrigger,
            acknowledgedAt: OffsetDateTime,
            settingsVersion: Int? = null,
            report: (server: UploadServerEntity, studyId: UUID, acknowledgment: CollectionAcknowledgment) -> Unit,
            onFailure: (server: UploadServerEntity, error: Exception) -> Unit = { _, _ -> },
        ): Boolean {
            if (accepted.isEmpty() && declined.isEmpty() && unavailable.isEmpty()) return true
            if (servers.isEmpty()) return false

            var allSucceeded = true
            servers.forEach { server ->
                val studyId = runCatching { UUID.fromString(server.studyId) }.getOrNull()
                if (studyId == null) {
                    onFailure(server, IllegalArgumentException("Invalid study id"))
                    allSucceeded = false
                    return@forEach
                }

                val acknowledgment = CollectionAcknowledgment(
                    acknowledgedModules = accepted,
                    acknowledgedAt = acknowledgedAt,
                    declinedModules = declined,
                    unavailableModules = unavailable,
                    trigger = trigger,
                    appVersion = null,
                    settingsVersion = settingsVersion,
                    disclosureVersion = server.disclosureVersion?.takeIf { server.manifestDigest != null },
                    manifestDigest = server.manifestDigest?.takeIf { server.disclosureVersion != null },
                )
                try {
                    report(server, studyId, acknowledgment)
                } catch (e: Exception) {
                    onFailure(server, e)
                    allSucceeded = false
                }
            }
            return allSucceeded
        }

        @JvmStatic
        fun retryPendingCollectionAcks(
            pending: List<PendingCollectionAckRecord>,
            servers: List<UploadServerEntity>,
            report: (server: UploadServerEntity, studyId: UUID, acknowledgment: CollectionAcknowledgment) -> Unit,
            onFailure: (server: UploadServerEntity, error: Exception) -> Unit = { _, _ -> },
            onDiscard: (
                record: PendingCollectionAckRecord,
                reason: PendingCollectionAckDiscardReason,
            ) -> Unit = { _, _ -> },
        ): CollectionAckRetryResult {
            if (pending.isEmpty()) return CollectionAckRetryResult(emptySet(), true)
            val removedStableKeys = linkedSetOf<String>()
            var allAttemptedSucceeded = true

            pending.forEach { record ->
                val acknowledgment = record.toAcknowledgmentOrNull()
                if (acknowledgment == null) {
                    onDiscard(record, PendingCollectionAckDiscardReason.INVALID_ACKNOWLEDGMENT)
                    removedStableKeys += record.stableKey()
                    return@forEach
                }
                val hasCompleteIdentity = record.hasCompleteEnrollmentIdentity()
                if (!hasCompleteIdentity && !record.isLegacyIdentityFree()) {
                    onDiscard(record, PendingCollectionAckDiscardReason.LEGACY_OR_INCOMPLETE_IDENTITY)
                    removedStableKeys += record.stableKey()
                    return@forEach
                }
                if (
                    hasCompleteIdentity &&
                    runCatching { UUID.fromString(requireNotNull(record.studyId)) }.getOrNull() == null
                ) {
                    onDiscard(record, PendingCollectionAckDiscardReason.INVALID_ENROLLMENT_IDENTITY)
                    removedStableKeys += record.stableKey()
                    return@forEach
                }
                if (servers.isEmpty()) {
                    // No authoritative configured row can currently be read. Retain the record;
                    // absence of a mutable Room locator is not proof that the enrollment changed.
                    allAttemptedSucceeded = false
                    return@forEach
                }

                val server = servers.singleOrNull { candidate ->
                    if (hasCompleteIdentity) record.isBoundTo(candidate) else record.isLegacyBoundTo(candidate)
                }
                if (server == null) {
                    onDiscard(record, PendingCollectionAckDiscardReason.ENROLLMENT_IDENTITY_MISMATCH)
                    removedStableKeys += record.stableKey()
                    return@forEach
                }

                val studyId = runCatching { UUID.fromString(server.studyId) }.getOrNull()
                if (studyId == null) {
                    onDiscard(record, PendingCollectionAckDiscardReason.INVALID_ENROLLMENT_IDENTITY)
                    removedStableKeys += record.stableKey()
                    return@forEach
                }
                val participantId = record.participantId ?: server.participantId
                val eligible = completeServerForIdentity(server, studyId, participantId)
                if (eligible == null) {
                    // The exact enrollment still exists, but is not currently safe to contact
                    // (provisional/disabled/non-canonical/credential-incomplete). Retain it.
                    allAttemptedSucceeded = false
                    return@forEach
                }
                try {
                    report(eligible, studyId, acknowledgment)
                    removedStableKeys += record.stableKey()
                } catch (e: Exception) {
                    onFailure(eligible, e)
                    allAttemptedSucceeded = false
                }
            }

            return CollectionAckRetryResult(removedStableKeys, allAttemptedSucceeded)
        }

        /**
         * Resolves the runtime interaction policy from the same generalized setting map used by
         * consent and collection gates. Disabled/absent/legacy settings use the wire default.
         */
        @JvmStatic
        internal fun interactionPolicyFor(
            resolved: Map<CollectionModuleId, ResolvedModuleSetting>,
        ): InteractionPolicy = resolved[CollectionModuleId.INTERACTION_EVENTS]
            ?.takeIf { it.enabled }
            ?.setting
            ?.interactionPolicy
            ?: InteractionPolicy.DEFAULT

        /**
         * Persists every event-loop runtime setting before the collection gate is opened. Sensor
         * configuration is written first and the interaction snapshot last; either failure keeps
         * the caller from publishing the matching gate generation.
         */
        @JvmStatic
        internal fun applyRuntimeSettingsBeforeGate(
            resolved: Map<CollectionModuleId, ResolvedModuleSetting>,
            persistSensors: (Map<AndroidSensorType, AndroidSensorSetting>) -> Boolean,
            persistInteraction: (enabled: Boolean, policy: InteractionPolicy) -> Boolean,
        ): Boolean {
            val perSensor = resolved.values
                .filter { it.enabled }
                .mapNotNull { resolvedSetting ->
                    val sensorType = SensorCollectionModules.sensorTypeOf(resolvedSetting.moduleId)
                        ?: return@mapNotNull null
                    val policy = resolvedSetting.setting.sensorPolicy
                        ?: AndroidSensorSetting(sensors = setOf(sensorType))
                    sensorType to policy
                }
                .toMap()
            if (!persistSensors(perSensor)) return false

            val interactionEnabled = resolved[CollectionModuleId.INTERACTION_EVENTS]?.enabled == true
            return persistInteraction(interactionEnabled, interactionPolicyFor(resolved))
        }
    }

    /**
     * The set of per-sensor module ids whose underlying [com.openlattice.chronicle.android.AndroidSensorType]
     * is present on this device, computed from [SensorManager.getDefaultSensor] (the same
     * capability check [com.openlattice.chronicle.services.sensors.SensorAvailabilityReporter] and
     * the Data Sharing surface use). Sensor modules absent from this set are filtered out of the
     * resolved settings by [retainCollectableSensors].
     */
    private fun availableSensorModules(): Set<CollectionModuleId> {
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return SensorCollectionModules.sensorModuleIds.filterTo(mutableSetOf()) { moduleId ->
            val sensorType = SensorCollectionModules.sensorTypeOf(moduleId) ?: return@filterTo false
            sensorManager.getDefaultSensor(SensorTypeMapping.toAndroidType(sensorType)) != null
        }
    }

    /**
     * Resolves [fetched] and hardware-gates it: every sensor module whose hardware this device
     * lacks is dropped before the settings reach the state machine, so the enrollment walkthrough,
     * post-enrollment sync, and decision seeding are all hardware-aware from one place. Logs the
     * gated-out set for on-device visibility.
     */
    private fun resolveCollectableSettings(
        fetched: AndroidDataCollectionSetting,
    ): Map<CollectionModuleId, ResolvedModuleSetting> {
        val resolver = CollectionSettingsResolver(EncryptedPrefsSensorSettingSource(appContext))
        // Materialize the shared manifest contract before resolving so device-local legacy sensor
        // preferences can never add a module the authoritative enrollment snapshot omitted.
        val authoritative = fetched.copy(modules = fetched.effectiveModules())
        val raw = resolver.resolveAll(generalized = authoritative)
        val resolved = retainCollectableSensors(raw, availableSensorModules())
        val gatedOut = raw.keys.filter { SensorCollectionModules.isSensorModule(it) && it !in resolved }
        if (gatedOut.isNotEmpty()) {
            Log.i(TAG, "Hardware-gated out ${gatedOut.size} device-absent sensor module(s): ${gatedOut.map { it.id }}")
        }
        return resolved
    }

    internal fun enrollmentModulePartitionFor(
        fetched: AndroidDataCollectionSetting,
        accepted: Set<CollectionModuleId>,
        declined: Set<CollectionModuleId>,
    ): EnrollmentModulePartition = enrollmentModulePartition(
        fetched = fetched,
        availableSensorModules = availableSensorModules(),
        accepted = accepted,
        declined = declined,
    )

    /**
     * Fetches the current [com.openlattice.chronicle.collection.AndroidDataCollectionSetting],
     * reconciles it against persisted state, persists the result, and dispatches side
     * effects. Returns true on success (including the no-op cases), false on a fetch error
     * so the caller (a Worker) can retry.
     */
    fun sync(): Boolean {
        val enrollment = EnrollmentSettings(appContext)
        if (enrollment.getParticipationStatus() != ParticipationStatus.ENROLLED) {
            Log.i(TAG, "Not enrolled; collection-loop sync is a no-op")
            return true
        }
        val pendingAcksReported = retryPendingCollectionAcks()
        val server = primaryServer() ?: run {
            Log.w(TAG, "Active enrollment has no eligible server; retrying collection-loop sync")
            return false
        }
        val studyId = runCatching { UUID.fromString(server.studyId) }.getOrNull() ?: return pendingAcksReported

        val fetched = try {
            UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
                .getDataCollectionSettings(studyId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch data collection settings; will retry", e)
            return false
        }
        try {
            requireSupportedCollectionPolicies(fetched)
        } catch (error: IllegalArgumentException) {
            ResearchPersistenceGate.stop {
                MinimalPlayArtifactState.markPolicyIncompatible(appContext)
            }
            Log.e(TAG, "Study settings use an unsupported collection policy; collection is now stopped", error)
            return false
        }
        // Close the Play boundary before publishing any part of a new settings generation. It is
        // reopened only after gates and runtime settings for the same supported generation are
        // durable, so a partial sync can never continue under stale policy.
        ResearchPersistenceGate.stop {
            MinimalPlayArtifactState.markPolicyIncompatible(appContext)
        }

        // Hardware-gate the resolved settings: a study-enabled sensor this device lacks is dropped
        // so it never collects, never prompts for consent, and never sits AWAITING_DECISION.
        val resolved = resolveCollectableSettings(fetched)
        val store = CollectionLoopStore.of(appContext)
        val previous = store.loadAll()
        val transitions = CollectionStateMachine.reconcile(previous, resolved, fetched.settingsVersion)
        try {
            // Persist the consent gate and its exact Health Connect scope under the same stop
            // barrier used by re-consent. A concurrent acceptance can therefore validate either
            // the complete old scope or the complete new scope, never a mixed transition.
            ResearchPersistenceGate.stop {
                store.save(transitions.map { it.newState })
                persistHealthConnectScope(fetched)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist the collection gate and approved Health Connect scope", e)
            return false
        }
        dispatch(transitions, suppressDecisionNotification = previous.isEmpty())
        if (!applyRuntimeSettings(studyId, fetched.settingsVersion, resolved)) {
            Log.w(TAG, "Failed to persist runtime settings; collection settings sync will retry")
            return false
        }
        // Push each pull module's collection interval into the schedule so the periodic workers
        // (expansion + battery) sample each at its own study-configured cadence, not every tick.
        applyPullIntervals(resolved)
        ResearchPersistenceGate.stop {
            MinimalPlayArtifactState.markPolicyCompatible(appContext)
        }
        updateUserIdentificationService()
        // Fetch + cache the study's payload-encryption setting (HIPAA-2028 W2) alongside the
        // data-collection settings. A study without e2ee has no Encryption setting (the endpoint
        // 404s/403s or returns absent), so this is best-effort: any failure leaves the upload
        // delegates on the plaintext path. It must NOT fail the sync (the data-collection
        // reconcile above already succeeded).
        syncEncryptionSetting(studyId, server.url, server.mobileSigningSecretOverride)
        return pendingAcksReported
    }

    /**
     * Applies participant decisions: ACCEPTs [accepted] and DECLINEs [declined], persists the
     * result, (de)activates the hardware-sensor service as needed, and reports the per-module
     * decision snapshot to the server tagged with [trigger]. Only modules whose state actually
     * changed are reported. Returns true on success; the local decision is persisted regardless,
     * and failed server reports are stored for the next collection-settings sync.
     */
    fun applyDecisions(
        accepted: Set<CollectionModuleId>,
        declined: Set<CollectionModuleId>,
        trigger: ConsentTrigger,
    ): Boolean = when (
        val outcome = applyDecisionsIf(accepted, declined, trigger) { true }
    ) {
        is DecisionApplicationOutcome.Applied -> outcome.reportsDelivered
        DecisionApplicationOutcome.PreconditionRejected -> false
    }

    /**
     * Opens Health Connect only if the exact scope the participant just reviewed is still current
     * at the persistence boundary. Reporting is retryable and cannot roll back local consent.
     */
    fun applyReviewedHealthConnectAcceptance(
        reviewedScope: Set<HealthConnectRecordType>,
        trigger: ConsentTrigger,
    ): Boolean = when (
        applyDecisionsIf(
            accepted = setOf(CollectionModuleId.HEALTH_CONNECT),
            declined = emptySet(),
            trigger = trigger,
        ) {
            val current = runCatching { HealthConnectScopeStore.of(appContext).read() }
                .getOrDefault(emptySet())
            healthConnectScopeMatchesReview(reviewedScope, current)
        }
    ) {
        is DecisionApplicationOutcome.Applied -> true
        DecisionApplicationOutcome.PreconditionRejected -> false
    }

    private fun applyDecisionsIf(
        accepted: Set<CollectionModuleId>,
        declined: Set<CollectionModuleId>,
        trigger: ConsentTrigger,
        reportChanges: Boolean = true,
        precondition: () -> Boolean,
    ): DecisionApplicationOutcome {
        val decisions = buildMap<CollectionModuleId, ParticipantDecision> {
            accepted.forEach { put(it, ParticipantDecision.ACCEPTED) }
            declined.forEach { put(it, ParticipantDecision.DECLINED) }
        }
        if (decisions.isEmpty()) return DecisionApplicationOutcome.Applied(reportsDelivered = true)
        val store = CollectionLoopStore.of(appContext)
        lateinit var result: DecisionResult
        var preconditionPassed = false
        ResearchPersistenceGate.stop {
            if (!precondition()) return@stop
            preconditionPassed = true
            result = CollectionStateMachine.decide(store.loadAll(), decisions, System.currentTimeMillis())
            store.save(result.newStates.values)
            // A participant turning a module off is a privacy decision, not a researcher-selected
            // retention policy. Pending local rows are discarded while every uploader is excluded.
            result.deactivated.forEach { moduleId ->
                applyDisposition(moduleId, CollectionDataDisposition.DISCARD_AND_STOP)
            }
        }
        if (!preconditionPassed) return DecisionApplicationOutcome.PreconditionRejected

        // Re-evaluate the shared sensor foreground service against the EFFECTIVE gate (which
        // folds in the global halt), not just this decision's activate/deactivate set. The
        // service runs while ANY per-sensor module collects; this covers the reversible-resume
        // case (accepting a newly-required module lifts the halt and makes an already-accepted
        // sensor collectable again, even though it isn't `activated`).
        updateSensorService()
        updateUserIdentificationService()
        if (!reportChanges) {
            return DecisionApplicationOutcome.Applied(reportsDelivered = true)
        }
        if (result.activated.isEmpty() && result.deactivated.isEmpty()) {
            return DecisionApplicationOutcome.Applied(reportsDelivered = true)
        }
        var allSucceeded = true
        (result.activated + result.deactivated)
            .groupBy { result.newStates.getValue(it).appliedVersion }
            .forEach { (settingsVersion, modules) ->
                val group = modules.toSet()
                if (!reportDecisions(
                        accepted = result.activated.intersect(group),
                        declined = result.deactivated.intersect(group),
                        trigger = trigger,
                        settingsVersion = settingsVersion,
                    ).allDelivered
                ) {
                    allSucceeded = false
                }
            }
        return DecisionApplicationOutcome.Applied(reportsDelivered = allSucceeded)
    }

    private sealed interface DecisionApplicationOutcome {
        data class Applied(val reportsDelivered: Boolean) : DecisionApplicationOutcome
        data object PreconditionRejected : DecisionApplicationOutcome
    }

    /**
     * The ordered per-module consent plan a participant works through at enrollment for
     * [fetched] — **required modules first, then optional** — computed WITHOUT persisting
     * anything. Used by the enrollment orientation wizard, which fetches the study's settings
     * over the public endpoint before any enrollment exists. Mirrors the needs-decision set
     * [sync] would produce from an empty baseline.
     */
    fun consentPlanFor(fetched: AndroidDataCollectionSetting): ConsentPlan {
        requireSupportedCollectionPolicies(fetched)
        val healthConnectSetting = fetched.modules[CollectionModuleId.HEALTH_CONNECT]
        val healthConnectRecordTypes = healthConnectSetting
            ?.takeIf { it.enabled }
            ?.healthConnectRecordTypes
            .orEmpty()
        require(healthConnectSetting?.enabled != true || healthConnectRecordTypes.isNotEmpty()) {
            "Enabled Health Connect collection requires at least one study-approved record type"
        }
        // Only walk the participant through sensors this device actually has — a study-enabled
        // sensor the hardware lacks (e.g. a Samsung-only sensor on a Pixel) must not get a step.
        val resolved = resolveCollectableSettings(fetched)
        val transitions = CollectionStateMachine.reconcile(emptyMap(), resolved, fetched.settingsVersion)
        // Present sensor steps in the canonical relevance order (SensorCollectionModules.
        // sensorDisplayOrder). Non-sensor modules get rank -1 and keep their relative order ahead
        // of the sensors via the stable sort.
        return ConsentPlan.fromTransitions(transitions, healthConnectRecordTypes)
    }

    /**
     * Seeds the collection-loop store from an ALREADY-FETCHED [fetched] setting (the one the
     * enrollment wizard was shown for) and applies the participant's per-module decisions:
     * [accepted] go straight to ACTIVE, [declined] to DECLINED. Called right after a
     * consent-first enrollment so the initial set is settled from the very setting consent was
     * shown for — no second settings fetch (which could fail and strand the device awaiting a
     * decision). Reports the decision snapshot (trigger = ENROLLMENT). The local state is
     * persisted regardless, and failed server reports are stored for retry.
     */
    fun seedAndApplyDecisions(
        studyId: UUID,
        fetched: AndroidDataCollectionSetting,
        accepted: Set<CollectionModuleId>,
        declined: Set<CollectionModuleId>,
        unavailable: Set<CollectionModuleId>,
    ): Boolean {
        try {
            requireSupportedCollectionPolicies(fetched)
        } catch (error: IllegalArgumentException) {
            ResearchPersistenceGate.stop {
                MinimalPlayArtifactState.markPolicyIncompatible(appContext)
            }
            Log.e(TAG, "Unsupported enrollment collection policy", error)
            return false
        }
        ResearchPersistenceGate.stop {
            MinimalPlayArtifactState.markPolicyIncompatible(appContext)
        }
        val partition = try {
            validateEnrollmentModulePartition(fetched, accepted, declined, unavailable)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Invalid enrollment module partition", error)
            return false
        }
        // Seed initial state from the same hardware-gated view the wizard was shown, so a
        // device-absent sensor isn't seeded into AWAITING_DECISION behind the participant's back.
        val resolved = resolveCollectableSettings(fetched).filterKeys { it !in partition.unavailable }
        val store = CollectionLoopStore.of(appContext)
        val transitions = CollectionStateMachine.reconcile(emptyMap(), resolved, fetched.settingsVersion)
        // Close every module gate before installing any newly accepted runtime scope. This also
        // makes same-study credential refreshes fail closed if the process stops midway.
        try {
            ResearchPersistenceGate.stop {
                store.save(transitions.map { it.newState })
                persistHealthConnectScope(fetched)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist the initial Health Connect scope", e)
            return false
        }
        if (!applyRuntimeSettings(studyId, fetched.settingsVersion, resolved)) {
            Log.e(TAG, "Failed to persist initial runtime settings; collection gates remain closed")
            return false
        }
        applyPullIntervals(resolved)
        val localOutcome = applyDecisionsIf(
            accepted = partition.accepted,
            declined = partition.declined,
            trigger = ConsentTrigger.ENROLLMENT,
            reportChanges = false,
        ) { true }
        if (localOutcome == DecisionApplicationOutcome.PreconditionRejected) return false

        // The issued row is enabled but intentionally not setup-complete yet, so use the exact
        // configured destination rather than getEnabledServer(). Network failure is acceptable
        // only after the same three-way partition is durable in the retry queue.
        val enrollmentServers = allServers().filter { it.enabled }
        val reportOutcome = reportDecisions(
            accepted = partition.accepted,
            declined = partition.declined,
            unavailable = partition.unavailable,
            trigger = ConsentTrigger.ENROLLMENT,
            settingsVersion = partition.settingsVersion,
            servers = enrollmentServers,
            provisionalEnrollmentServer = enrollmentServers.singleOrNull(),
        )
        if (!reportOutcome.allDelivered) {
            Log.w(TAG, "Initial collection decision report was queued for retry")
        }
        if (!reportOutcome.allDelivered && !reportOutcome.retryDurable) return false
        ResearchPersistenceGate.stop {
            MinimalPlayArtifactState.markPolicyCompatible(appContext)
        }
        updateUserIdentificationService()
        // Local consent and runtime settings are authoritative for setup completion. A transient
        // acknowledgment delivery failure is already durable in CollectionAckRetryQueue.
        return true
    }

    private fun persistHealthConnectScope(fetched: AndroidDataCollectionSetting) {
        val setting = fetched.modules[CollectionModuleId.HEALTH_CONNECT]
        val approved = setting
            ?.takeIf { it.enabled }
            ?.healthConnectRecordTypes
            .orEmpty()
        HealthConnectScopeStore.of(appContext).replace(approved)
    }

    /** Modules currently awaiting acknowledgment (PENDING_ACK) — what the ack screen shows. */
    fun pendingAcknowledgmentModules(): Set<CollectionModuleId> =
        pendingAcknowledgmentSnapshot().modules

    /**
     * Modules currently awaiting acknowledgment plus a stable fingerprint of that exact prompt.
     * The fingerprint includes the applied settings version so a later re-enable of the same
     * module set is treated as a new review prompt rather than a previously-snoozed one.
     */
    fun pendingAcknowledgmentSnapshot(): PendingAcknowledgmentSnapshot {
        val pending = CollectionLoopStore.of(appContext).loadAll()
            .filterValues { it.phase == CollectionModulePhase.AWAITING_DECISION }
        val fingerprint = pending.entries
            .sortedBy { it.key.id }
            .joinToString("|") { (moduleId, state) -> "${moduleId.id}:${state.appliedVersion}" }
        return PendingAcknowledgmentSnapshot(pending.keys, fingerprint)
    }

    // ----- side effects -----

    private fun dispatch(transitions: List<ModuleTransition>, suppressDecisionNotification: Boolean) {
        // Notify for modules that NEWLY need a participant decision on THIS sync — both an
        // optional NEEDS_DECISION and a mandatory NEWLY_REQUIRED_NEEDS_CONSENT. A module
        // already awaiting a decision from a prior sync (STILL_AWAITING_DECISION) is
        // deliberately excluded: several syncs fire in quick succession around enrollment, and
        // re-posting would (a) re-notify the initial baseline on the 2nd sync — once persisted,
        // `previous` is no longer empty so the baseline guard stops suppressing — and (b) nag on
        // every poll for a module the participant is already being prompted about in-app.
        // (Phase 3 splits NEWLY_REQUIRED_NEEDS_CONSENT into its own high-priority blocking
        // surface; for now it shares the "action needed" notification.)
        // Each per-sensor module participates here exactly like a usage module: a sensor a
        // researcher adds mid-study surfaces the same "a data collection option was added" review.
        val needsDecision = transitions.filter {
            it.type == ModuleTransitionType.NEEDS_DECISION ||
                it.type == ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT
        }.map { it.moduleId }.toSet()

        // The "turned off" notice fires only for modules that were actually COLLECTING (a
        // disposition is set only on an ACTIVE → disabled transition). A module disabled
        // before it ever collected (awaiting/declined) settles silently.
        val disabledWhileActive = transitions.filter {
            it.type == ModuleTransitionType.FORCIBLY_DISABLED && it.disposition != null
        }

        transitions.forEach { t ->
            if (t.type == ModuleTransitionType.FORCIBLY_DISABLED) {
                t.disposition?.let { applyDisposition(t.moduleId, it) }
            }
        }
        // Start/stop the one shared sensor foreground service against the post-reconcile gate:
        // it runs while ANY per-sensor module collects and stops when none do — so enabling or
        // disabling individual sensors (or accepting/declining them) toggles the service.
        updateSensorService()

        if (needsDecision.isNotEmpty() && !suppressDecisionNotification) {
            // A NEWLY_REQUIRED_NEEDS_CONSENT in this batch means the study made a module
            // required that the participant hasn't accepted → ALL collection is now paused
            // (the global gate halt), so the notice says so.
            val mandatory = transitions.any { it.type == ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT }
            postAckNeededNotification(needsDecision, mandatory)
        }
        if (disabledWhileActive.isNotEmpty()) postDisabledNotification(disabledWhileActive)

        // Required↔optional flips on an ALREADY-ACCEPTED module are informational (it keeps
        // collecting; only its lock state in Data Sharing changes). Tell the participant —
        // required→optional means "you may turn it off"; optional→required means "it can no
        // longer be turned off" (per-module consent design §5; the mid-study requirement).
        // These can't occur on the empty baseline (they need a prior ACCEPTED state).
        val nowRequired = transitions.any { it.type == ModuleTransitionType.NOW_REQUIRED_INFORM }
        val nowOptional = transitions.any { it.type == ModuleTransitionType.NOW_OPTIONAL_INFORM }
        if (nowRequired || nowOptional) postInformationalNotice(nowRequired, nowOptional)
    }

    /**
     * Applies a disable disposition to the module's pending on-device queue.
     * FLUSH_THEN_STOP enqueues an immediate upload (no data loss); DISCARD_AND_STOP drops
     * the module's pending queue; HOLD_PENDING retains it (the queue's existing retention +
     * the gate keep it bounded — a held module no longer collects).
     *
     * Dedicated queues are cleared independently. Sensor rows carry their sensor type and are
     * deleted per type. Usage, in-app activity, and lifecycle rows still share an untagged
     * `dataQueue`; there the privacy-correct fallback is to clear the whole shared queue. That may
     * discard sibling pending rows, but it never uploads data after the participant was promised
     * DISCARD_AND_STOP. A future tagged queue can make that deletion selective.
     */
    private fun applyDisposition(moduleId: CollectionModuleId, disposition: CollectionDataDisposition) {
        // The pure policy (which queue, whether DISCARD is honorable, flush vs retain) is decided
        // by planDisposition (JVM-tested); this method only performs the Android side effects.
        when (val action = planDisposition(moduleId, disposition)) {
            DispositionAction.Flush -> {
                Log.i(TAG, "Disposition FLUSH_THEN_STOP for '${moduleId.id}': triggering upload")
                triggerImmediateUpload(appContext)
            }
            is DispositionAction.ClearDedicated -> {
                UploadQueueSingleFlight.withExclusiveMutation {
                    val db = ChronicleDb.getInstance(appContext)
                    when (action.queue) {
                    DispositionQueue.BATTERY_SAMPLES -> {
                        Log.i(TAG, "DISCARD_AND_STOP for '${moduleId.id}': dropping battery_samples")
                        db.batterySampleDao().deleteAll()
                    }
                    DispositionQueue.USER_QUEUE -> {
                        Log.i(TAG, "DISCARD_AND_STOP for '${moduleId.id}': dropping userQueue")
                        db.userQueueEntryData().deleteAll()
                    }
                    DispositionQueue.SHARED_DATA_QUEUE -> {
                        Log.i(TAG, "DISCARD_AND_STOP for '${moduleId.id}': dropping untagged shared dataQueue")
                        db.queueEntryData().deleteAll()
                    }
                    DispositionQueue.INTERACTION_SAMPLES,
                    DispositionQueue.AUDIO_ACTIVITY_SAMPLES,
                    DispositionQueue.AUDIO_CONTENT_SAMPLES,
                    DispositionQueue.NOTIFICATION_ACTIVITY_SAMPLES,
                    DispositionQueue.SLEEP_SAMPLES,
                    DispositionQueue.ACTIVITY_RECOGNITION_SAMPLES,
                    DispositionQueue.HEALTH_METRIC_SAMPLES -> {
                        // These legacy tables must still be erased when upgrading a device that
                        // once ran a research-capable build. Use schema-level deletion so the
                        // minimal Play graph never references (and therefore never retains) the
                        // restricted Room DAO implementations.
                        db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM `${restrictedQueueTable(action.queue)}`",
                        )
                    }
                    DispositionQueue.CONNECTIVITY_STATE_SAMPLES -> db.connectivityStateSampleDao().deleteAll()
                    DispositionQueue.APP_NETWORK_USAGE_SAMPLES -> db.appNetworkUsageSampleDao().deleteAll()
                    DispositionQueue.DEVICE_SETTINGS_SAMPLES -> db.deviceSettingsSampleDao().deleteAll()
                        DispositionQueue.NONE -> Unit
                    }
                }
            }
            is DispositionAction.ClearSensor -> {
                UploadQueueSingleFlight.withExclusiveMutation {
                    Log.i(TAG, "DISCARD_AND_STOP for '${moduleId.id}': dropping only ${action.sensorType} rows")
                    ChronicleDb.getInstance(appContext).sensorSampleDao().deleteBySensorType(action.sensorType)
                }
            }
            DispositionAction.NoDedicatedQueue ->
                Log.i(TAG, "DISCARD_AND_STOP: no dedicated queue for '${moduleId.id}'")
            DispositionAction.Retain ->
                Log.i(TAG, "Disposition HOLD_PENDING for '${moduleId.id}': retaining queue (gate stops collection)")
        }
    }

    private fun reportDecisions(
        accepted: Set<CollectionModuleId>,
        declined: Set<CollectionModuleId>,
        unavailable: Set<CollectionModuleId> = emptySet(),
        trigger: ConsentTrigger,
        settingsVersion: Int,
        servers: List<UploadServerEntity> = enabledServers(),
        provisionalEnrollmentServer: UploadServerEntity? = null,
    ): DecisionReportOutcome {
        val operation = {
            val admittedServers = if (provisionalEnrollmentServer == null) enabledServers() else servers
            val acknowledgedAt = OffsetDateTime.now()
            val failedReports = mutableListOf<PendingCollectionAckRecord>()
            val succeeded = reportCollectionAckToServers(
                servers = admittedServers,
                accepted = accepted,
                declined = declined,
                unavailable = unavailable,
                trigger = trigger,
                acknowledgedAt = acknowledgedAt,
                settingsVersion = settingsVersion,
                report = { server, studyId, acknowledgment ->
                    UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride).reportCollectionAck(
                        studyId,
                        server.participantId,
                        server.sourceDeviceId,
                        server.apiKey,
                        acknowledgment,
                    )
                },
                onFailure = { server, error ->
                    // The local decision is already persisted; report partial failure so callers can
                    // surface it without blocking local consent state.
                    Log.w(TAG, "Failed to report collection decision to '${server.name}'", error)
                    failedReports += PendingCollectionAckRecord.from(
                        server = server,
                        accepted = accepted,
                        declined = declined,
                        unavailable = unavailable,
                        trigger = trigger,
                        acknowledgedAt = acknowledgedAt,
                        settingsVersion = settingsVersion,
                    )
                },
            )
            // A delivery is durable only if it succeeded or every failed destination was captured
            // before the lifecycle lease is released. A withdrawal cannot return and then have a
            // failed request resurrect its enrollment-bound retry record.
            var retryDurable = succeeded
            if (failedReports.isNotEmpty()) {
                try {
                    CollectionAckRetryQueue.of(appContext).enqueue(failedReports)
                    retryDurable = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist pending collection decision report", e)
                }
            }
            DecisionReportOutcome(allDelivered = succeeded, retryDurable = retryDurable)
        }
        return try {
            val admitted = if (provisionalEnrollmentServer == null) {
                ResearchPersistenceGate.runIfActive(appContext, operation)
            } else {
                ResearchPersistenceGate.runIfExpectedEnrollment(
                    appContext,
                    provisionalEnrollmentServer,
                    operation,
                )
            }
            admitted ?: DecisionReportOutcome(allDelivered = false, retryDurable = false)
        } catch (e: Exception) {
            Log.e(TAG, "Collection decision report admission failed", e)
            DecisionReportOutcome(allDelivered = false, retryDurable = false)
        }
    }

    private data class DecisionReportOutcome(
        val allDelivered: Boolean,
        val retryDurable: Boolean,
    )

    private fun primaryServer(): UploadServerEntity? = try {
        exactActiveEnrollmentServer(appContext, ChronicleDb.getInstance(appContext))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve the active enrollment server", e)
        null
    }

    private fun enabledServers(): List<UploadServerEntity> =
        listOfNotNull(primaryServer())

    private fun allServers(): List<UploadServerEntity> = try {
        listOfNotNull(ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer())
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve the configured enrollment server", e)
        emptyList()
    }

    private fun retryPendingCollectionAcks(): Boolean {
        val queue = try {
            CollectionAckRetryQueue.of(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open pending collection decision store", e)
            return false
        }
        return try {
            ResearchPersistenceGate.runIfActive(appContext) {
                // Load under the enrollment read lease. Withdrawal clears this durable queue
                // under the write side of the same barrier, so a predecessor record can never
                // remain only in memory and cross a completed withdrawal/re-enrollment boundary.
                val pending = queue.load()
                if (pending.isEmpty()) return@runIfActive true
                val result = CollectionLoopCoordinator.retryPendingCollectionAcks(
                    pending = pending,
                    servers = allServers(),
                    report = { server, studyId, acknowledgment ->
                        UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
                            .reportCollectionAck(
                                studyId,
                                server.participantId,
                                server.sourceDeviceId,
                                server.apiKey,
                                acknowledgment,
                            )
                    },
                    onFailure = { server, error ->
                        Log.w(TAG, "Failed to retry collection decision report to '${server.name}'", error)
                    },
                    onDiscard = { _, reason ->
                        Log.w(TAG, "Dropped pending collection decision with reason $reason")
                    },
                )
                queue.removeByStableKeys(result.removedStableKeys)
                result.allAttemptedSucceeded
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update pending collection decision store", e)
            false
        }
    }

    /**
     * Best-effort fetch of the study's [com.openlattice.chronicle.study.StudyEncryptionSetting]
     * (public key only) over the public settings endpoint, caching it in
     * [EncryptionSettingStore] for the upload delegates. Tolerates 404/403/absent (a study
     * without e2ee) by leaving the cache untouched — the delegates fall back to plaintext. Never
     * throws into the sync.
     */
    private fun syncEncryptionSetting(
        studyId: UUID,
        serverUrl: String,
        mobileSigningSecretOverride: String?
    ) {
        try {
            val setting = UploadWorker.getChronicleStudyApi(serverUrl, mobileSigningSecretOverride)
                .getStudyEncryptionSetting(studyId)
            EncryptionSettingStore.of(appContext).put(studyId, setting)
            Log.i(TAG, "Cached encryption setting (enabled=${setting.enabled})")
        } catch (e: Exception) {
            // No Encryption setting / no access ⇒ this study does not use e2ee; stay on plaintext.
            Log.i(TAG, "No encryption setting available; staying on plaintext upload", e)
        }
    }

    /** Whether any per-sensor module currently collects (server-enabled AND acknowledged). */
    private fun anySensorCollects(store: CollectionLoopStore): Boolean =
        SensorCollectionModules.sensorModuleIds.any { store.collects(it) }

    /**
     * Starts the one shared sensor foreground service if any per-sensor module collects, and
     * stops it otherwise. Reads the freshly-persisted gate state.
     */
    private fun updateSensorService() {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            SensorSettings(appContext).clear()
            return
        }
        if (anySensorCollects(CollectionLoopStore.of(appContext))) {
            if (!DistributionRestrictedRuntime.tryStartHardwareSensors(appContext)) {
                Log.i(TAG, "Sensor foreground service start deferred; collection settings sync remains applied")
            }
        } else {
            DistributionRestrictedRuntime.stopHardwareSensors(appContext)
        }
    }

    /** Keeps unlock monitoring aligned with the current accepted module state and local opt-in. */
    private fun updateUserIdentificationService() {
        if (userIdentificationMayRun(appContext)) {
            DeviceUnlockMonitoringService.startAuthorizedService(appContext)
        } else {
            DeviceUnlockMonitoringService.stopService(appContext)
        }
    }

    /**
     * Stops the shared sensor service when a participant begins withdrawal.
     * Withdrawal is an unconditional privacy boundary, so it must not consult
     * the persisted per-sensor gates before stopping collection.
     */
    fun stopSensorServiceForWithdrawal() {
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            DistributionRestrictedRuntime.stopHardwareSensors(appContext)
        }
    }

    /** Persists the exact study generation consumed by sensor and accessibility collectors. */
    private fun applyRuntimeSettings(
        studyId: UUID,
        settingsVersion: Int,
        resolved: Map<CollectionModuleId, ResolvedModuleSetting>,
    ): Boolean = applyRuntimeSettingsBeforeGate(
        resolved = resolved,
        persistSensors = {
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
                SensorSettings(appContext).applyResolvedSensors(it)
            } else {
                SensorSettings(appContext).clear()
                true
            }
        },
        persistInteraction = { enabled, policy ->
            InteractionPolicySettings(appContext).save(studyId, settingsVersion, enabled, policy)
        },
    )

    /**
     * Writes each interval-gated pull module's resolved `collectionCadence.intervalSeconds` into the
     * [ExpansionPullSchedule], so the periodic collection workers sample each module at its own
     * study-configured cadence rather than on every fixed worker tick. Modules absent from the
     * resolved set keep their previously-stored interval (or the default until first sync).
     */
    private fun applyPullIntervals(resolved: Map<CollectionModuleId, ResolvedModuleSetting>) {
        val schedule = ExpansionPullSchedule(appContext)
        ExpansionPullSchedule.INTERVAL_GATED_MODULES.forEach { moduleId ->
            resolved[moduleId]?.let { schedule.setIntervalSeconds(moduleId, it.setting.collectionCadence.intervalSeconds) }
        }
    }

    private fun postAckNeededNotification(modules: Set<CollectionModuleId>, mandatory: Boolean) {
        Utils.createNotificationChannel(appContext)
        // Deep-link to the Data Sharing tab, where each pending module is reviewed and decided
        // individually — NOT the retired all-at-once acknowledgment screen.
        val intent = Intent(appContext, com.openlattice.chronicle.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(com.openlattice.chronicle.MainActivity.EXTRA_SELECT_TAB, R.id.nav_data_sharing)
        val pending = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = if (mandatory) {
            appContext.getString(R.string.notif_required_title) to appContext.getString(R.string.notif_required_text)
        } else {
            appContext.getString(R.string.notif_action_needed) to appContext.getString(R.string.notif_option_added)
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        notifySafely(NOTIFICATION_ID_ACK, notification)
    }

    private fun postInformationalNotice(nowRequired: Boolean, nowOptional: Boolean) {
        Utils.createNotificationChannel(appContext)
        val intent = Intent(appContext, com.openlattice.chronicle.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(com.openlattice.chronicle.MainActivity.EXTRA_SELECT_TAB, R.id.nav_data_sharing)
        val pending = PendingIntent.getActivity(
            appContext, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = appContext.getString(
            when {
                nowRequired && nowOptional -> R.string.notif_changed_both
                nowRequired -> R.string.notif_changed_required
                else -> R.string.notif_changed_optional
            },
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(appContext.getString(R.string.notif_collection_updated))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        notifySafely(NOTIFICATION_ID_INFORM, notification)
    }

    private fun postDisabledNotification(disabled: List<ModuleTransition>) {
        Utils.createNotificationChannel(appContext)
        // Approved copy 2026-06-04 — docs/COLLECTION-LOOP-CLOSURE-DESIGN.md Appendix B
        // ("Disabled" notification). Fires only for disable transitions (see dispatch).
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(appContext.getString(R.string.notif_collection_updated))
            .setContentText(appContext.getString(R.string.notif_option_off))
            .setAutoCancel(true)
            .build()
        notifySafely(NOTIFICATION_ID_SCOPE, notification)
    }

    private fun notifySafely(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(appContext).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — the scope change is still applied; the user
            // will see pending modules in-app on next open.
            Log.w(TAG, "Notification suppressed (permission not granted)", e)
        }
    }
}

internal fun healthConnectScopeMatchesReview(
    reviewed: Set<HealthConnectRecordType>,
    current: Set<HealthConnectRecordType>,
): Boolean = reviewed.isNotEmpty() && reviewed == current

data class PendingAcknowledgmentSnapshot(
    val modules: Set<CollectionModuleId>,
    val fingerprint: String,
)

/**
 * The ordered per-module consent plan for the enrollment orientation wizard: the study's
 * [required] modules (shown first; declining one blocks enrollment) followed by its [optional]
 * modules (declining one is mistap-confirmed, then proceeds as not-collected). Either list may
 * be empty; both empty means the study enables no consent-gated modules and the wizard is
 * skipped entirely (enroll directly).
 */
data class ConsentPlan(
    val required: List<CollectionModuleId>,
    val optional: List<CollectionModuleId>,
    val healthConnectRecordTypes: Set<HealthConnectRecordType> = emptySet(),
) {
    init {
        require(
            CollectionModuleId.HEALTH_CONNECT !in (required + optional) ||
                healthConnectRecordTypes.isNotEmpty(),
        ) { "Enabled Health Connect consent requires at least one study-approved record type" }
    }

    /**
     * Required first, then optional — the exact screen order the wizard steps through. Both lists
     * already carry the canonical sensor-relevance order (established in [consentPlanFor]).
     */
    val orderedModules: List<CollectionModuleId> get() = required + optional
    val isEmpty: Boolean get() = required.isEmpty() && optional.isEmpty()

    companion object {
        fun fromTransitions(
            transitions: List<ModuleTransition>,
            healthConnectRecordTypes: Set<HealthConnectRecordType> = emptySet(),
        ): ConsentPlan {
            fun List<ModuleTransition>.idsFor(type: ModuleTransitionType): List<CollectionModuleId> =
                filter { it.type == type }
                    .map { it.moduleId }
                    .sortedBy { SensorCollectionModules.sensorDisplayOrder.indexOf(it) }

            return ConsentPlan(
                required = transitions.idsFor(ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT),
                optional = transitions.idsFor(ModuleTransitionType.NEEDS_DECISION),
                healthConnectRecordTypes = healthConnectRecordTypes,
            )
        }
    }
}

/** The on-device queue a module's pending data lives in, for disable-disposition purposes. */
internal enum class DispositionQueue {
    BATTERY_SAMPLES,
    USER_QUEUE,
    SHARED_DATA_QUEUE,
    INTERACTION_SAMPLES,
    AUDIO_ACTIVITY_SAMPLES,
    AUDIO_CONTENT_SAMPLES,
    NOTIFICATION_ACTIVITY_SAMPLES,
    SLEEP_SAMPLES,
    ACTIVITY_RECOGNITION_SAMPLES,
    HEALTH_METRIC_SAMPLES,
    CONNECTIVITY_STATE_SAMPLES,
    APP_NETWORK_USAGE_SAMPLES,
    DEVICE_SETTINGS_SAMPLES,
    NONE,
}

internal fun restrictedQueueTable(queue: DispositionQueue): String = when (queue) {
    DispositionQueue.INTERACTION_SAMPLES -> "interaction_samples"
    DispositionQueue.AUDIO_ACTIVITY_SAMPLES -> "audio_activity_samples"
    DispositionQueue.AUDIO_CONTENT_SAMPLES -> "audio_content_samples"
    DispositionQueue.NOTIFICATION_ACTIVITY_SAMPLES -> "notification_activity_samples"
    DispositionQueue.SLEEP_SAMPLES -> "sleep_samples"
    DispositionQueue.ACTIVITY_RECOGNITION_SAMPLES -> "activity_recognition_samples"
    DispositionQueue.HEALTH_METRIC_SAMPLES -> "health_metric_samples"
    else -> error("Queue is not a restricted legacy table: $queue")
}

/**
 * The action a disable disposition resolves to, independent of any Android side effect. This is the
 * pure policy the coordinator's [CollectionLoopCoordinator] glue then executes.
 */
internal sealed interface DispositionAction {
    /** FLUSH_THEN_STOP: ship the module's pending rows (immediate upload), then stop — no data loss. */
    object Flush : DispositionAction

    /** DISCARD_AND_STOP for a module with a dedicated queue: clear exactly that queue. */
    data class ClearDedicated(val queue: DispositionQueue) : DispositionAction

    /** DISCARD_AND_STOP for one tagged hardware-sensor module. */
    data class ClearSensor(val sensorType: String) : DispositionAction

    /** DISCARD_AND_STOP for a module with no dedicated queue: nothing to clear. */
    object NoDedicatedQueue : DispositionAction

    /** HOLD_PENDING: retain the queue; the gate stops further collection (queue stays bounded). */
    object Retain : DispositionAction
}

/** The dedicated on-device queue for [moduleId], or a shared/none marker. */
internal fun dedicatedQueueFor(moduleId: CollectionModuleId): DispositionQueue = when {
    moduleId == CollectionModuleId.BATTERY_TELEMETRY -> DispositionQueue.BATTERY_SAMPLES
    moduleId == CollectionModuleId.USER_IDENTIFICATION -> DispositionQueue.USER_QUEUE
    moduleId == CollectionModuleId.INTERACTION_EVENTS -> DispositionQueue.INTERACTION_SAMPLES
    moduleId == CollectionModuleId.AUDIO_ACTIVITY -> DispositionQueue.AUDIO_ACTIVITY_SAMPLES
    moduleId == CollectionModuleId.AUDIO_CONTENT -> DispositionQueue.AUDIO_CONTENT_SAMPLES
    moduleId == CollectionModuleId.NOTIFICATION_ACTIVITY -> DispositionQueue.NOTIFICATION_ACTIVITY_SAMPLES
    moduleId == CollectionModuleId.SLEEP -> DispositionQueue.SLEEP_SAMPLES
    moduleId == CollectionModuleId.ACTIVITY_RECOGNITION -> DispositionQueue.ACTIVITY_RECOGNITION_SAMPLES
    moduleId == CollectionModuleId.HEALTH_CONNECT -> DispositionQueue.HEALTH_METRIC_SAMPLES
    moduleId == CollectionModuleId.CONNECTIVITY_STATE -> DispositionQueue.CONNECTIVITY_STATE_SAMPLES
    moduleId == CollectionModuleId.APP_NETWORK_USAGE -> DispositionQueue.APP_NETWORK_USAGE_SAMPLES
    moduleId == CollectionModuleId.DEVICE_SETTINGS -> DispositionQueue.DEVICE_SETTINGS_SAMPLES
    // usage + in-app activity + lifecycle share dataQueue with no per-module tag.
    moduleId == CollectionModuleId.USAGE_EVENTS ||
        moduleId == CollectionModuleId.IN_APP_ACTIVITY_CLASS ||
        moduleId == CollectionModuleId.DEVICE_LIFECYCLE -> DispositionQueue.SHARED_DATA_QUEUE
    else -> DispositionQueue.NONE
}

/**
 * Pure decision for a disable disposition: maps (module, disposition) to the [DispositionAction] the
 * coordinator executes. Extracted from [CollectionLoopCoordinator.applyDisposition] so the full
 * disposition matrix is JVM-testable without Android (`ChronicleDb`, `Context`, the upload worker).
 */
internal fun planDisposition(
    moduleId: CollectionModuleId,
    disposition: CollectionDataDisposition,
): DispositionAction = when (disposition) {
    CollectionDataDisposition.FLUSH_THEN_STOP -> DispositionAction.Flush
    CollectionDataDisposition.HOLD_PENDING -> DispositionAction.Retain
    CollectionDataDisposition.DISCARD_AND_STOP ->
        SensorCollectionModules.sensorTypeOf(moduleId)?.let {
            DispositionAction.ClearSensor(it.name)
        } ?: when (val queue = dedicatedQueueFor(moduleId)) {
            DispositionQueue.NONE -> DispositionAction.NoDedicatedQueue
            else -> DispositionAction.ClearDedicated(queue)
        }
}
