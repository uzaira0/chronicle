package com.openlattice.chronicle.collection.state

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.hardware.SensorManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.EnrolledWork
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.device.AndroidAppNetworkUsageSource
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.settings.CollectionSettingsResolver
import com.openlattice.chronicle.collection.settings.ResolvedModuleSetting
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.generated.CollectionConsentContracts
import com.openlattice.chronicle.preferences.AppliedOperationalModules
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.sensors.SensorTypeMapping
import com.openlattice.chronicle.services.enrollment.endIfEnrollmentCancelled
import com.openlattice.chronicle.services.notifications.CHANNEL_ID
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.postNotificationIfPermitted
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.exactActiveEnrollmentServer
import com.openlattice.chronicle.services.upload.quarantineSourcesForTable
import com.openlattice.chronicle.services.upload.recordPolicyErasureCountInTransaction
import com.openlattice.chronicle.services.upload.recordPolicyErasureInTransaction
import com.openlattice.chronicle.services.upload.resolveServerForIdentity
import com.openlattice.chronicle.services.upload.serverErrorCode
import com.openlattice.chronicle.services.upload.triggerImmediateUpload
import com.openlattice.chronicle.services.upload.uploadHttpStatus
import com.openlattice.chronicle.services.usage.UsageModuleCollectionDelegate
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.eraseSensorType
import com.openlattice.chronicle.storage.nextDecisionSeq
import java.net.HttpURLConnection
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
/**
 * Retires the locked-boot collection permission by writing an EMPTY snapshot, so
 * `LockedBootReceiver` starts nothing before first unlock. Writing empty (rather than clearing the
 * file) keeps a fresh `writtenAt` stamp, so the result is an explicit "nothing may collect" rather
 * than a never-written state.
 *
 * @return true when the snapshot was durably retired.
 */
internal fun clearDirectBootCollection(snapshot: DirectBootSensorSnapshot): Boolean =
    snapshot.write(emptyMap())

/** The study enables modules this artifact does not collect; the participant needs a different study setup, not a new link. */
internal class UnsupportedStudyModulesException(message: String) : IllegalArgumentException(message)

internal enum class SettingsApplicationOutcome { APPLIED, UNAPPLIABLE, RETRY }

internal fun requireSupportedCollectionPolicies(fetched: AndroidDataCollectionSetting) {
    val distribution = DistributionChannel.current()
    val requestedEnabledModules = fetched.effectiveEnabledModuleIds() +
        fetched.modules.filterValues { it.enabled }.keys
    val unsupportedModules = requestedEnabledModules
        .filterNot { DistributionModulePolicy.supports(distribution, it) }
    if (unsupportedModules.isNotEmpty()) {
        throw UnsupportedStudyModulesException(
            "Modules not available on this build (${distribution.name.lowercase()}): " +
                unsupportedModules.joinToString { it.id },
        )
    }
    if (distribution.minimalPublic) {
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
 * scope-change notifications, the hardware-sensor service, and module queue flush or erase).
 *
 * Two entry points:
 *  - [sync] — called by the settings-sync worker, piggybacked on uploads, and at
 *    enrollment. Reconciles server-driven changes.
 *  - [acknowledge] — called by the acknowledgment screen when the participant accepts
 *    one or more pending modules; activates them and reports the acknowledgment.
 */
private const val TAG = "CollectionLoopCoordinator"

/**
 * Only the server's ack-rejected 400 says this acknowledgment can never be accepted. Any other refusal (a proxy, a
 * contract or size mismatch, a server bug, a 422) keeps the participant's decision queued and retried.
 */
private fun isPermanentAckRejection(error: Exception): Boolean =
    uploadHttpStatus(error) == HttpURLConnection.HTTP_BAD_REQUEST &&
        serverErrorCode(error) == CollectionConsentContracts.COLLECTION_ACK_REJECTED_CODE
private data class ConsentNotificationCopy(val title: String, val text: String)

class CollectionLoopCoordinator(context: Context) {

    private val appContext = context.applicationContext

    companion object {

        private const val NOTIFICATION_ID_ACK = 47_001
        private const val NOTIFICATION_ID_SCOPE = 47_002
        private const val NOTIFICATION_ID_INFORM = 47_003

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

        @JvmStatic
        fun retryPendingCollectionAcks(
            pending: List<PendingCollectionAckRecord>,
            servers: List<UploadServerEntity>,
            provisionalEnrollmentServer: UploadServerEntity? = null,
            report: (server: UploadServerEntity, studyId: UUID, acknowledgment: CollectionAcknowledgment) -> Unit,
            onFailure: (server: UploadServerEntity, error: Exception) -> Unit = { _, _ -> },
            onDiscard: (
                record: PendingCollectionAckRecord,
                reason: PendingCollectionAckDiscardReason
            ) -> Unit = { _, _ -> },
            onRejected: (server: UploadServerEntity, error: Exception) -> Unit = { _, _ -> },
        ): CollectionAckRetryResult {
            if (pending.isEmpty()) return CollectionAckRetryResult(emptySet(), true)
            val removedStableKeys = linkedSetOf<String>()
            val deferredServerIds = mutableSetOf<Long>()
            var allAttemptedSucceeded = true

            pending.forEach { record ->
                val acknowledgment = record.toAcknowledgmentOrNull()
                val discardReason = record.discardReason(acknowledgment)
                if (discardReason != null) {
                    onDiscard(record, discardReason)
                    removedStableKeys += record.stableKey()
                    return@forEach
                }
                val validAcknowledgment = checkNotNull(acknowledgment)
                if (servers.isEmpty()) {
                    // No authoritative configured row can currently be read. Retain the record;
                    // absence of a mutable Room locator is not proof that the enrollment changed.
                    allAttemptedSucceeded = false
                    return@forEach
                }

                val server = servers.singleOrNull(record::isBoundTo)
                if (server == null) {
                    onDiscard(record, PendingCollectionAckDiscardReason.ENROLLMENT_IDENTITY_MISMATCH)
                    removedStableKeys += record.stableKey()
                    return@forEach
                }
                if (server.id in deferredServerIds) {
                    allAttemptedSucceeded = false
                    return@forEach
                }

                // discardReason refused a record whose studyId is not a UUID, and the bound server has that studyId.
                val studyId = UUID.fromString(server.studyId)
                val participantId = checkNotNull(record.participantId)
                val eligible = resolveServerForIdentity(server, studyId, participantId,
                    requireSetupComplete = server != provisionalEnrollmentServer).server
                if (eligible == null) {
                    // The exact enrollment still exists, but is not currently safe to contact
                    // (provisional/disabled/non-canonical/credential-incomplete). Retain it.
                    allAttemptedSucceeded = false
                    deferredServerIds += server.id
                    return@forEach
                }
                try {
                    report(eligible, studyId, validAcknowledgment)
                    removedStableKeys += record.stableKey()
                } catch (e: Exception) {
                    if (isPermanentAckRejection(e)) {
                        // E3: the server will never take this acknowledgment; later decisions must not wait behind it.
                        onRejected(eligible, e)
                        removedStableKeys += record.stableKey()
                    } else {
                        onFailure(eligible, e)
                        allAttemptedSucceeded = false
                        deferredServerIds += server.id
                    }
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

    /**
     * Resolves [fetched] and hardware-gates it: every sensor module whose hardware this device
     * lacks is dropped before the settings reach the state machine, so the enrollment walkthrough,
     * post-enrollment sync, and decision seeding are all hardware-aware from one place. Logs the
     * gated-out set for on-device visibility.
     */
    private fun resolveCollectableSettings(
        fetched: AndroidDataCollectionSetting,
    ): Pair<Map<CollectionModuleId, ResolvedModuleSetting>, Map<CollectionModuleId, ResolvedModuleSetting>> {
        val raw = CollectionSettingsResolver().resolveAll(generalized = fetched.copy(modules = fetched.effectiveModules()))
        val resolved = retainCollectableSensors(raw, availableSensorModules(appContext))
        val gatedOut = raw.keys.filter { SensorCollectionModules.isSensorModule(it) && it !in resolved }
        if (gatedOut.isNotEmpty()) {
            Log.i(TAG, "Hardware-gated out ${gatedOut.size} device-absent sensor module(s): ${gatedOut.map { it.id }}")
        }
        return raw to resolved
    }

    internal fun enrollmentModulePartitionFor(
        fetched: AndroidDataCollectionSetting,
        accepted: Set<CollectionModuleId>,
        declined: Set<CollectionModuleId>,
    ): EnrollmentModulePartition = enrollmentModulePartition(
        fetched = fetched,
        availableSensorModules = availableSensorModules(appContext),
        accepted = accepted,
        declined = declined,
    )

    /**
     * Fetches the current [com.openlattice.chronicle.collection.AndroidDataCollectionSetting],
     * reconciles it against persisted state, persists the result, and dispatches side
     * effects. Returns false for transient failures so the worker retries. A policy this build
     * cannot apply is recorded and ends this run; periodic polling can discover the next revision.
     */
    fun sync(): Boolean {
        replayPendingErasures()
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

        val responseGeneration = ResearchPersistenceGate.withReadLease {
            ResearchErasureFence(appContext).settingsGeneration()
        }
        val fetched = try {
            UploadWorker.getChronicleStudyApi(server.url)
                .getDataCollectionSettings(studyId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch data collection settings; will retry", e)
            return false
        }
        if (applyFetchedSettings(server, responseGeneration, fetched) == SettingsApplicationOutcome.RETRY) return false
        return pendingAcksReported
    }

    /** The response is committed against decisions read under this stop. */
    internal fun applyFetchedSettings(
        expected: UploadServerEntity,
        responseGeneration: Long,
        fetched: AndroidDataCollectionSetting,
    ): SettingsApplicationOutcome {
        var outcome = SettingsApplicationOutcome.RETRY
        try {
            ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(ResearchPersistenceGate.PrivacyOperationKind.SETTINGS_APPLY,
                ResearchErasureFence.enrollmentKey(expected), ResearchErasureFence.enrollmentKey(expected)),
                completed = { outcome == SettingsApplicationOutcome.APPLIED }) {
                val fence = ResearchErasureFence(appContext)
                if (!stillEnrolled(expected) || fence.settingsGeneration() != responseGeneration) return@stop
                try {
                    requireSupportedCollectionPolicies(fetched)
                } catch (error: IllegalArgumentException) {
                    Log.e(TAG, "Collection settings revision ${fetched.settingsVersion} is unsupported by this build", error)
                    LocalUploadDiagnosticsStore.of(appContext).recordFailure(LocalUploadModuleFamily.APP_RUNTIME, error)
                    outcome = SettingsApplicationOutcome.UNAPPLIABLE
                    return@stop
                }
                val (requested, resolved) = resolveCollectableSettings(fetched)
                val store = CollectionLoopStore.of(appContext)
                val previous = store.loadAll()
                val transitions = CollectionStateMachine.reconcile(previous, resolved, fetched.settingsVersion)
                val states = transitions.map { it.newState }
                val changed = transitions.any { it.newState != previous[it.moduleId] }
                if (changed) collectUnreadUsage()
                val changedAt = System.currentTimeMillis()
                if (changed) {
                    // Field projection and revision evidence share this phone-clock boundary, including unread
                    // history when the flush is unavailable and events after its exclusive end.
                    fence.reconcileModuleCollection(collectingModules(states), now = changedAt)
                    fence.settingsChanged()
                }
                ChronicleDb.getInstance(appContext).runInTransaction {
                    if (changed) UsageModuleCollectionDelegate.recordSettingsRevision(appContext,
                        ResearchErasureFence.enrollmentKey(expected),
                        previous.values.maxOfOrNull { it.appliedVersion }?.takeIf { it > 0 }, fetched.settingsVersion, changedAt)
                    store.save(states)
                }
                AppliedOperationalModules.save(appContext, expected, resolved.filter { (id, setting) ->
                    id !in CollectionStateMachine.ACK_GATED_MODULES && setting.enabled }.keys)
                persistHealthConnectScope(fetched)
                dispatch(transitions, previous)
                replayPendingErasures()
                if (!applyRuntimeSettings(UUID.fromString(expected.studyId), fetched, requested)) return@stop
                applyPullIntervals(resolved)
                // B9: applying a revision records no decision; the participant's own decisions are the only acks.
                updateUserIdentificationService()
                EnrolledWork.reconcile(appContext, refreshSettings = false)
                outcome = SettingsApplicationOutcome.APPLIED
            }
        } catch (error: Exception) {
            Log.e(TAG, "Collection settings response could not be applied", error)
        }
        return outcome
    }

    /**
     * One usage poll through the regular delegate, under the caller's stop: it collects only while usage collects (no
     * halt, withdrawal or pause) and Usage Access reads, and otherwise keeps its cursor. Its failure must not hold back
     * a revision, which can be a disable; the cursor and revision timeline retain the window for the next poll.
     * Do not take USAGE_POLL_LOCK inside the stop: its holder may be waiting for the barrier's read side.
     */
    private fun collectUnreadUsage() {
        try {
            UsageModuleCollectionDelegate(appContext).execute()
        } catch (error: Exception) {
            Log.e(TAG, "Unread usage could not be queued under the current settings revision", error)
        }
    }

    internal fun refreshCollectorAdmission() {
        updateSensorService()
        updateUserIdentificationService()
    }

    /** Retire grants and queues of modules this release excludes, through the replayable participant-decision erasure. */
    /** The enrollment a settings response was fetched for is still the configured, enrolled, unwithdrawn one. */
    private fun stillEnrolled(expected: UploadServerEntity): Boolean {
        val current = ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer() ?: return false
        return ResearchErasureFence.enrollmentKey(current) == ResearchErasureFence.enrollmentKey(expected) &&
            EnrollmentSettings(appContext).getParticipationStatus() == ParticipationStatus.ENROLLED &&
            WithdrawalStateStore(appContext).state() == WithdrawalState.NONE
    }

    private fun currentErasureOwner(): String? = ChronicleDb.getInstance(appContext)
        .uploadServerDao().getConfiguredServer()?.let(ResearchErasureFence::enrollmentKey)

    internal fun replayPendingErasures() = ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(
        ResearchPersistenceGate.PrivacyOperationKind.ERASURE_REPLAY, ownerKey = ResearchPersistenceGate.currentOwnerKey()), completed = {
        !ResearchErasureFence(appContext).hasPendingErasures() }) {
        val fence = ResearchErasureFence(appContext)
        fence.replayRecoveryErasure(appContext)
        fence.pending().forEach { module ->
            if (fence.pendingOwner(module) == currentErasureOwner()) {
                fence.pendingState(module)?.let { CollectionLoopStore.of(appContext).save(listOf(it)) }
            }
            eraseModuleQueue(module)
        }
    }

    internal fun prepareRecoveryErasures(ownerScope: String?) {
        val fence = ResearchErasureFence(appContext)
        if (fence.hasPendingErasures()) fence.markRecoveryErasure(ownerScope)
    }

    /** Recovery archives are indivisible: explicit pending erasure wins over preservation. */
    internal fun reconcileVerifiedRecoveryErasures(ownerScope: String?) = ResearchPersistenceGate.stop {
        val fence = ResearchErasureFence(appContext)
        prepareRecoveryErasures(ownerScope)
        fence.erase(CollectionModuleId.values().toSet(), durableIntent = false)
        UploadQueueSingleFlight.withExclusiveMutation {
            fence.pending().forEach { eraseModuleAuxiliaryState(appContext, it) }
        }
        fence.replayRecoveryErasure(appContext)
        fence.completeAfterVerifiedReset()
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

    /** A disclosure for an earlier policy or enrollment cannot authorize the current module. */
    fun applyReviewedModuleAcceptance(
        reviewed: CollectionModuleState,
        expectedOwner: UploadServerEntity,
        trigger: ConsentTrigger,
    ): Boolean = applyDecisionsIf(setOf(reviewed.moduleId), emptySet(), trigger) {
        reviewedModuleIsCurrent(appContext, reviewed, expectedOwner)
    } is DecisionApplicationOutcome.Applied

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
        var acks = emptyList<PendingCollectionAckRecord>()
        var preconditionPassed = false
        val wasHalted = store.haltedByDeclinedRequired()
        ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(ResearchPersistenceGate.PrivacyOperationKind.PARTICIPANT_DECISION,
            decisions.toSortedMap(compareBy { it.id }).toString(), ResearchPersistenceGate.currentOwnerKey()),
            completed = { preconditionPassed },
            resolved = {
                val saved = store.loadAll()
                !ResearchErasureFence(appContext).hasPendingErasures() && decisions.all { (module, decision) -> saved[module]?.decision == decision }
            }) {
            replayPendingErasures()
            if (!precondition()) return@stop
            preconditionPassed = true
            result = CollectionStateMachine.decide(store.loadAll(), decisions, System.currentTimeMillis())
            val fence = ResearchErasureFence(appContext)
            if (result.deactivated.isNotEmpty()) fence.erase(result.deactivated, states = result.newStates.values, ownerKey = currentErasureOwner())
            else fence.settingsChanged()
            DistributionRestrictedRuntime.retireDirectBootSensors(appContext,
                result.deactivated.mapNotNull { SensorCollectionModules.sensorTypeOf(it)?.name }.toSet())
            // Only the enrollment review admits history captured before the acceptance.
            fence.accepted(result.activated, initialReview = trigger == ConsentTrigger.ENROLLMENT)
            // The decision, its sequence number and its acknowledgment commit together, so a crash cannot leave the
            // server untold, and every row captured afterwards carries the new sequence (B7).
            val db = ChronicleDb.getInstance(appContext)
            db.runInTransaction {
                store.save(result.newStates.values)
                if (reportChanges) acks = decisionAcks(result, trigger).map { it.copy(decisionSeq = db.nextDecisionSeq()) }
                CollectionAckRetryQueue.of(appContext).enqueue(acks)
            }
            // A participant turning a module off is a privacy decision: its pending local rows are erased while every
            // uploader is excluded. A study disable flushes instead (C5).
            result.deactivated.forEach(::eraseModuleQueue)
        }
        if (!preconditionPassed) return DecisionApplicationOutcome.PreconditionRejected
        // Accepting the declined required module lifts the hard stop: schedule what it cancelled. The queue it
        // cleared stays gone.
        CollectionHardStop.resumeWorkIfLifted(appContext, wasHalted)

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
        return DecisionApplicationOutcome.Applied(reportsDelivered = acks.isNotEmpty() && retryPendingCollectionAcks())
    }

    /** One acknowledgment per enrolled server and settings version for the modules this decision changed. */
    private fun decisionAcks(result: DecisionResult, trigger: ConsentTrigger): List<PendingCollectionAckRecord> {
        val acknowledgedAt = OffsetDateTime.now()
        return (result.activated + result.deactivated)
            .groupBy { result.newStates.getValue(it).appliedVersion }
            .flatMap { (settingsVersion, modules) ->
                enabledServers().map { server ->
                    PendingCollectionAckRecord.from(server = server,
                        accepted = result.activated.intersect(modules.toSet()),
                        declined = result.deactivated.intersect(modules.toSet()),
                        trigger = trigger, acknowledgedAt = acknowledgedAt, settingsVersion = settingsVersion)
                }
            }
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
        val resolved = resolveCollectableSettings(fetched).second
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
        expectedEnrollment: UploadServerEntity? = null,
    ): Boolean {
        val expected = expectedEnrollment ?: ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer()
            ?: return false
        var applied = false
        ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(ResearchPersistenceGate.PrivacyOperationKind.SETTINGS_APPLY,
            ResearchErasureFence.enrollmentKey(expected), ResearchErasureFence.enrollmentKey(expected)), completed = { applied }) {
            if (!stillEnrolled(expected)) return@stop
            replayPendingErasures()
            applied = seedCurrentEnrollmentDecisions(studyId, fetched, accepted, declined, unavailable)
        }
        return applied
    }

    private fun seedCurrentEnrollmentDecisions(
        studyId: UUID,
        fetched: AndroidDataCollectionSetting,
        accepted: Set<CollectionModuleId>,
        declined: Set<CollectionModuleId>,
        unavailable: Set<CollectionModuleId>,
    ): Boolean {
        try {
            requireSupportedCollectionPolicies(fetched)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Unsupported enrollment collection policy", error)
            return false
        }
        val partition = try {
            validateEnrollmentModulePartition(fetched, accepted, declined, unavailable)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Invalid enrollment module partition", error)
            return false
        }
        // Seed initial state from the same hardware-gated view the wizard was shown, so a
        // device-absent sensor isn't seeded into AWAITING_DECISION behind the participant's back.
        val (requested, collectable) = resolveCollectableSettings(fetched)
        val resolved = collectable.filterKeys { it !in partition.unavailable }
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
        if (!applyRuntimeSettings(studyId, fetched, requested)) {
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
        val enrollmentServers = allServers(appContext).filter { it.enabled }
        val reportOutcome = reportEnrollmentDecisions(
            accepted = partition.accepted,
            declined = partition.declined,
            unavailable = partition.unavailable,
            settingsVersion = partition.settingsVersion,
            servers = enrollmentServers,
            provisionalEnrollmentServer = enrollmentServers.singleOrNull(),
        )
        if (!reportOutcome.allDelivered) {
            Log.w(TAG, "Initial collection decision report was queued for retry")
        }
        if (!reportOutcome.allDelivered && !reportOutcome.retryDurable) return false
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

    private fun dispatch(transitions: List<ModuleTransition>, previous: Map<CollectionModuleId, CollectionModuleState>) {
        val suppressDecisionNotification = previous.isEmpty()
        // Notify for modules that NEWLY need a participant decision on THIS sync — both an
        // optional NEEDS_DECISION and a mandatory NEWLY_REQUIRED_NEEDS_CONSENT. A module
        // already awaiting a decision from a prior sync (STILL_AWAITING_DECISION) is
        // deliberately excluded: several syncs fire in quick succession around enrollment, and
        // re-posting would (a) re-notify the initial baseline on the 2nd sync — once persisted,
        // `previous` is no longer empty so the baseline guard stops suppressing — and (b) nag on
        // every poll for a module the participant is already being prompted about in-app.
        // NEWLY_REQUIRED_NEEDS_CONSENT shares the "action needed" notification.
        // Each per-sensor module participates here exactly like a usage module: a sensor a
        // researcher adds mid-study surfaces the same "a data collection option was added" review.
        val needsDecision = transitions.filter {
            it.type == ModuleTransitionType.NEEDS_DECISION ||
                it.type == ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT
        }.map { it.moduleId }.toSet()

        // The "turned off" notice fires only for modules that were actually COLLECTING. A module disabled
        // before it ever collected (awaiting/declined) settles silently.
        val disabledWhileActive = transitions.filter {
            it.type == ModuleTransitionType.FORCIBLY_DISABLED && previous[it.moduleId]?.phase == CollectionModulePhase.ACTIVE
        }
        // C5: a study disable flushes what the module already captured.
        if (disabledWhileActive.isNotEmpty()) triggerImmediateUpload(appContext)
        // Start/stop the one shared sensor foreground service against the post-reconcile gate:
        // it runs while ANY per-sensor module collects and stops when none do — so enabling or
        // disabling individual sensors (or accepting/declining them) toggles the service.
        updateSensorService()

        if (needsDecision.isNotEmpty() && !suppressDecisionNotification) {
            // A NEWLY_REQUIRED_NEEDS_CONSENT in this batch means the study made a module
            // required that the participant hasn't accepted → ALL collection is now paused
            // (the global gate halt), so the notice says so.
            val mandatory = transitions.any { it.type == ModuleTransitionType.NEWLY_REQUIRED_NEEDS_CONSENT }
            postAckNeededNotification(mandatory)
        }
        if (disabledWhileActive.isNotEmpty()) postDisabledNotification()

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
     * Erases the module's pending on-device queue: the participant declined it, or an erasure is replayed.
     *
     * Dedicated queues are cleared independently. Sensor rows carry their sensor type and are
     * deleted per type. Usage, in-app activity, and lifecycle rows share `dataQueue`; the
     * shared-queue eraser retains sibling samples and redacts only the declined activity field.
     */
    private fun eraseModuleQueue(moduleId: CollectionModuleId) {
        ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(
            ResearchPersistenceGate.PrivacyOperationKind.MODULE_DISCARD, moduleId.id, currentErasureOwner())) {
            val fence = ResearchErasureFence(appContext)
            if (moduleId !in fence.pending()) fence.erase(setOf(moduleId), ownerKey = currentErasureOwner())
            UploadQueueSingleFlight.withExclusiveMutation {
                executeErasure(moduleId)
                eraseModuleAuxiliaryState(appContext, moduleId)
                fence.completed(moduleId)
            }
        }
    }

    private fun executeErasure(moduleId: CollectionModuleId) {
        // Which queue holds the module's rows is decided by planModuleErasure (JVM-tested); this method only
        // performs the Android side effects.
        when (val action = planModuleErasure(moduleId)) {
            is ModuleErasure.ClearDedicated -> clearDedicatedQueue(moduleId, action.queue)
            is ModuleErasure.ClearSensor -> clearSensorQueue(moduleId, action.sensorType)
            ModuleErasure.NoDedicatedQueue ->
                Log.i(TAG, "Erasure: no dedicated queue for '${moduleId.id}'")
        }
    }

    private fun clearDedicatedQueue(moduleId: CollectionModuleId, queue: ModuleQueue) = ResearchPersistenceGate.stop {
        UploadQueueSingleFlight.withExclusiveMutation {
            if (queue == ModuleQueue.APP_NETWORK_USAGE_SAMPLES) {
                AndroidAppNetworkUsageSource.clearCheckpoint(appContext)
            }
            val db = ChronicleDb.getInstance(appContext)
            db.runInTransaction {
                val table = moduleQueueTable(queue)
                if (table != null && queue != ModuleQueue.SHARED_DATA_QUEUE) {
                    recordPolicyErasureInTransaction(
                        db, table, moduleQueueFamily(queue),
                        LocalOperationalIssue.MODULE_POLICY_ERASED,
                    )
                }
                if (table != null && queue != ModuleQueue.SHARED_DATA_QUEUE &&
                    queue !in setOf(
                        ModuleQueue.INTERACTION_SAMPLES,
                        ModuleQueue.AUDIO_ACTIVITY_SAMPLES,
                        ModuleQueue.AUDIO_CONTENT_SAMPLES,
                        ModuleQueue.NOTIFICATION_ACTIVITY_SAMPLES,
                        ModuleQueue.SLEEP_SAMPLES,
                        ModuleQueue.ACTIVITY_RECOGNITION_SAMPLES,
                        ModuleQueue.HEALTH_METRIC_SAMPLES,
                    )) db.localDataQuarantineDao().eraseSources(quarantineSourcesForTable(table))
                when (queue) {
                    ModuleQueue.BATTERY_SAMPLES -> {
                        Log.i(TAG, "Erasure for '${moduleId.id}': dropping battery_samples")
                        db.batterySampleDao().deleteAll()
                    }
                    ModuleQueue.USER_QUEUE -> {
                        Log.i(TAG, "Erasure for '${moduleId.id}': dropping userQueue")
                        db.userQueueEntryData().deleteAll()
                    }
                    ModuleQueue.SHARED_DATA_QUEUE -> {
                        Log.i(TAG, "Erasure for '${moduleId.id}': erasing only known module samples")
                        eraseSharedQueueModule(db, moduleId)
                        eraseSharedQueueModuleQuarantine(db, moduleId)
                    }
                    ModuleQueue.INTERACTION_SAMPLES,
                    ModuleQueue.AUDIO_ACTIVITY_SAMPLES,
                    ModuleQueue.AUDIO_CONTENT_SAMPLES,
                    ModuleQueue.NOTIFICATION_ACTIVITY_SAMPLES,
                    ModuleQueue.SLEEP_SAMPLES,
                    ModuleQueue.ACTIVITY_RECOGNITION_SAMPLES,
                    ModuleQueue.HEALTH_METRIC_SAMPLES -> {
                        // Schema-level deletion: the amazon build has no collector for these
                        // tables, so its graph never references their Room DAO implementations.
                        eraseRestrictedQueueAndQuarantineInTransaction(db, queue)
                    }
                    ModuleQueue.CONNECTIVITY_STATE_SAMPLES -> db.connectivityStateSampleDao().deleteAll()
                    ModuleQueue.APP_NETWORK_USAGE_SAMPLES -> db.appNetworkUsageSampleDao().deleteAll()
                    ModuleQueue.DEVICE_SETTINGS_SAMPLES -> db.deviceSettingsSampleDao().deleteAll()
                    ModuleQueue.NONE -> Unit
                }
            }
        }
    }

    private fun clearSensorQueue(moduleId: CollectionModuleId, sensorType: String) = ResearchPersistenceGate.stop {
        UploadQueueSingleFlight.withExclusiveMutation {
            Log.i(TAG, "Erasure for '${moduleId.id}': dropping only $sensorType rows")
            val ramErased = SensorSampleWriter.discardSensorSamples(sensorType)
            val db = ChronicleDb.getInstance(appContext)
            db.runInTransaction {
                recordPolicyErasureCountInTransaction(db, ramErased.toLong(), LocalUploadModuleFamily.SENSOR,
                    LocalOperationalIssue.MODULE_POLICY_ERASED)
            }
            DistributionRestrictedRuntime.retireDirectBootSensors(appContext, setOf(sensorType))
            db.runInTransaction {
                recordPolicyErasureInTransaction(
                    db, "sensor_samples", LocalUploadModuleFamily.SENSOR,
                    LocalOperationalIssue.MODULE_POLICY_ERASED,
                    "sensorType = ?", arrayOf(sensorType),
                )
                recordPolicyErasureInTransaction(
                    db, "sensor_sample_dead_letters", LocalUploadModuleFamily.SENSOR,
                    LocalOperationalIssue.MODULE_POLICY_ERASED,
                    "sensorType = ?", arrayOf(sensorType),
                )
                db.eraseSensorType(sensorType)
            }
        }
    }

    /** The enrollment's first decision report; later decisions are acknowledged from [applyDecisionsIf]. */
    private fun reportEnrollmentDecisions(
        accepted: Set<CollectionModuleId>,
        declined: Set<CollectionModuleId>,
        unavailable: Set<CollectionModuleId>,
        settingsVersion: Int,
        servers: List<UploadServerEntity>,
        provisionalEnrollmentServer: UploadServerEntity?,
    ): DecisionReportOutcome {
        val operation = { UploadQueueSingleFlight.withExclusiveMutation {
            val admittedServers = if (provisionalEnrollmentServer == null) enabledServers() else servers
            val acknowledgedAt = OffsetDateTime.now()
            if (admittedServers.isEmpty()) return@withExclusiveMutation DecisionReportOutcome(false, false)
            val records = admittedServers.map { server ->
                    PendingCollectionAckRecord.from(server = server,
                        accepted = accepted,
                        declined = declined,
                        unavailable = unavailable,
                        trigger = ConsentTrigger.ENROLLMENT,
                        acknowledgedAt = acknowledgedAt,
                        settingsVersion = settingsVersion,
                    )
                }
            admittedServers.zip(records).forEach { (server, record) ->
                CollectionAckRetryQueue.enqueueEnrollmentOnce(appContext, server, record)
            }
            DecisionReportOutcome(allDelivered = retryPendingCollectionAcks(provisionalEnrollmentServer), retryDurable = true)
        } }
        return try {
            val admitted = if (provisionalEnrollmentServer == null) {
                ResearchPersistenceGate.runIfEnrollmentConfigured(appContext, operation)
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

    private fun retryPendingCollectionAcks(provisionalEnrollmentServer: UploadServerEntity? = null): Boolean {
        val queue = try {
            CollectionAckRetryQueue.of(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open pending collection decision store", e)
            return false
        }
        // A cancel this ack learns of ends the enrollment, which needs the stop, so it is handled after the lease.
        var failure: Triple<UploadServerEntity, Long, Exception>? = null
        return try {
            val operation = { UploadQueueSingleFlight.withExclusiveMutation {
                val generation = ResearchErasureFence(appContext).settingsGeneration()
                // Load under the enrollment read lease. Withdrawal clears this durable queue
                // under the write side of the same barrier, so a predecessor record can never
                // remain only in memory and cross a completed withdrawal/re-enrollment boundary.
                val pending = queue.load()
                if (pending.isEmpty()) return@withExclusiveMutation true
                val result = CollectionLoopCoordinator.retryPendingCollectionAcks(
                    pending = pending,
                    servers = allServers(appContext),
                    provisionalEnrollmentServer = provisionalEnrollmentServer,
                    report = { server, studyId, acknowledgment ->
                        UploadWorker.getChronicleStudyApi(server.url)
                            .reportCollectionAck(
                                studyId,
                                server.participantId,
                                server.sourceRandomDeviceId,
                                server.apiKey,
                                acknowledgment,
                            )
                        if (acknowledgment.trigger == ConsentTrigger.ENROLLMENT) {
                            CollectionAckRetryQueue.markEnrollmentDelivered(appContext, server)
                        }
                    },
                    onFailure = { server, error ->
                        Log.w(TAG, "Failed to retry collection decision report to '${server.name}'", error)
                        failure = Triple(server, generation, error)
                    },
                    onDiscard = { _, reason ->
                        Log.w(TAG, "Dropped pending collection decision with reason $reason")
                    },
                    onRejected = { server, error ->
                        Log.e(TAG, "Server '${server.name}' refused a collection decision; it is dropped", error)
                        LocalUploadDiagnosticsStore.of(appContext).recordFailure(LocalUploadModuleFamily.APP_RUNTIME, error)
                    },
                )
                queue.removeByStableKeys(result.removedStableKeys)
                result.allAttemptedSucceeded
            } }
            if (provisionalEnrollmentServer == null) {
                val delivered = ResearchPersistenceGate.runIfEnrollmentConfigured(appContext, operation) ?: false
                failure?.let { (server, generation, error) -> endIfEnrollmentCancelled(appContext, server, generation, error) }
                delivered
            } else {
                // The enrollment's own first report runs under its setup stop; a cancel it meets stays queued for the
                // next retry or status poll, which end the enrollment once its setup completes.
                ResearchPersistenceGate.runIfExpectedEnrollment(appContext, provisionalEnrollmentServer, operation) ?: false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update pending collection decision store", e)
            false
        }
    }

    /** Whether any per-sensor module currently collects (server-enabled AND acknowledged). */
    internal fun anySensorCollects(): Boolean =
        ResearchPersistenceGate.isActiveEnrollment(appContext) &&
            sensorServiceDesired()

    /** A pause closes the listeners, preserving the foreground service for background resume. */
    internal fun sensorServiceDesired(): Boolean = ResearchPersistenceGate.runIfEnrolled(appContext) {
        participationRetainsServices(EnrollmentSettings(appContext).getParticipationStatus()) &&
            SensorCollectionModules.sensorModuleIds.any { CollectionLoopStore.of(appContext).collects(it) }
    } == true

    /**
     * Reconciles the foreground lifetime; temporary collection gates belong to the runtime.
     */
    internal fun updateSensorService() {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            SensorSettings(appContext).clear()
            return
        }
        if (sensorServiceDesired()) {
            if (!DistributionRestrictedRuntime.tryStartHardwareSensors(appContext)) {
                Log.i(TAG, "Sensor foreground service start deferred; collection settings sync remains applied")
            }
        } else {
            // The direct-boot snapshot is the ONLY collection state LockedBootReceiver can read
            // before first unlock, and it is refreshed from HardwareSensorService — which is about
            // to stop. Retire it here or a reboot-while-locked resumes exactly the sensors whose
            // gate just closed.
            if (!clearDirectBootCollection(DirectBootSensorSnapshot(appContext))) {
                Log.e(TAG, "Failed to retire the direct-boot snapshot before stopping sensor collection")
            }
            DistributionRestrictedRuntime.stopHardwareSensors(appContext)
        }
    }

    /** Keeps unlock monitoring aligned with the current accepted module state and local opt-in. */
    private fun updateUserIdentificationService() {
        DeviceUnlockMonitoringService.reconcile(appContext)
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
        fetched: AndroidDataCollectionSetting,
        requested: Map<CollectionModuleId, ResolvedModuleSetting>,
    ): Boolean = applyRuntimeSettingsBeforeGate(
        resolved = requested,
        persistSensors = {
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
                SensorSettings(appContext).applyResolvedSensors(it)
            } else {
                SensorSettings(appContext).clear()
                true
            }
        },
        persistInteraction = { enabled, policy ->
            InteractionPolicySettings(appContext).save(studyId, fetched.settingsVersion, enabled, policy)
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

    private fun postAckNeededNotification(mandatory: Boolean) {
        // Deep-link to the Data Sharing tab, where each pending module is reviewed and decided
        // individually — NOT the retired all-at-once acknowledgment screen.
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_data_sharing)
        val pending = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val copy = if (mandatory) {
            ConsentNotificationCopy(title = appContext.getString(R.string.notif_required_title),
                text = appContext.getString(if (CollectionLoopStore.of(appContext).haltedByDeclinedRequired())
                    R.string.ds_status_paused_required else R.string.notif_required_text))
        } else {
            ConsentNotificationCopy(title = appContext.getString(R.string.notif_action_needed),
                text = appContext.getString(R.string.notif_option_added))
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        postNotificationIfPermitted(appContext, NOTIFICATION_ID_ACK, notification)
    }

    private fun postInformationalNotice(nowRequired: Boolean, nowOptional: Boolean) {
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_data_sharing)
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
        postNotificationIfPermitted(appContext, NOTIFICATION_ID_INFORM, notification)
    }

    private fun postDisabledNotification() {
        // Fires only for disable transitions (see dispatch).
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(appContext.getString(R.string.notif_collection_updated))
            .setContentText(appContext.getString(R.string.notif_option_off))
            .setAutoCancel(true)
            .build()
        // Without the permission the scope change still applies; pending modules show in-app on next open.
        postNotificationIfPermitted(appContext, NOTIFICATION_ID_SCOPE, notification)
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

/** The on-device queue a module's pending data lives in. */
internal enum class ModuleQueue {
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

internal fun restrictedQueueTable(queue: ModuleQueue): String = when (queue) {
    ModuleQueue.INTERACTION_SAMPLES -> "interaction_samples"
    ModuleQueue.AUDIO_ACTIVITY_SAMPLES -> "audio_activity_samples"
    ModuleQueue.AUDIO_CONTENT_SAMPLES -> "audio_content_samples"
    ModuleQueue.NOTIFICATION_ACTIVITY_SAMPLES -> "notification_activity_samples"
    ModuleQueue.SLEEP_SAMPLES -> "sleep_samples"
    ModuleQueue.ACTIVITY_RECOGNITION_SAMPLES -> "activity_recognition_samples"
    ModuleQueue.HEALTH_METRIC_SAMPLES -> "health_metric_samples"
    ModuleQueue.APP_NETWORK_USAGE_SAMPLES -> "app_network_usage_samples"
    else -> error("Queue is not a restricted legacy table: $queue")
}

/** Caller holds the queue mutation lock and a Room transaction. */
internal fun eraseRestrictedQueueAndQuarantineInTransaction(db: ChronicleDb, queue: ModuleQueue): Int {
    val table = restrictedQueueTable(queue)
    db.localDataQuarantineDao().eraseSources(quarantineSourcesForTable(table))
    return when (queue) {
        ModuleQueue.INTERACTION_SAMPLES -> db.restrictedArtifactErasureDao().deleteInteractions()
        ModuleQueue.AUDIO_ACTIVITY_SAMPLES -> db.restrictedArtifactErasureDao().deleteAudioActivity()
        ModuleQueue.AUDIO_CONTENT_SAMPLES -> db.restrictedArtifactErasureDao().deleteAudioContent()
        ModuleQueue.NOTIFICATION_ACTIVITY_SAMPLES -> db.restrictedArtifactErasureDao().deleteNotificationActivity()
        ModuleQueue.SLEEP_SAMPLES -> db.restrictedArtifactErasureDao().deleteSleep()
        ModuleQueue.ACTIVITY_RECOGNITION_SAMPLES -> db.restrictedArtifactErasureDao().deleteActivityRecognition()
        ModuleQueue.HEALTH_METRIC_SAMPLES -> db.restrictedArtifactErasureDao().deleteHealthMetrics()
        ModuleQueue.APP_NETWORK_USAGE_SAMPLES -> db.appNetworkUsageSampleDao().deleteAll()
        else -> error("Queue is not a restricted legacy table: $queue")
    }
}

internal fun moduleQueueTable(queue: ModuleQueue): String? = when (queue) {
    ModuleQueue.BATTERY_SAMPLES -> "battery_samples"
    ModuleQueue.USER_QUEUE -> "userQueue"
    ModuleQueue.SHARED_DATA_QUEUE -> "dataQueue"
    ModuleQueue.CONNECTIVITY_STATE_SAMPLES -> "connectivity_state_samples"
    ModuleQueue.DEVICE_SETTINGS_SAMPLES -> "device_settings_samples"
    ModuleQueue.NONE -> null
    else -> restrictedQueueTable(queue)
}

internal fun moduleQueueFamily(queue: ModuleQueue): LocalUploadModuleFamily = when (queue) {
    ModuleQueue.BATTERY_SAMPLES -> LocalUploadModuleFamily.BATTERY
    ModuleQueue.USER_QUEUE, ModuleQueue.SHARED_DATA_QUEUE -> LocalUploadModuleFamily.USAGE_LIFECYCLE
    ModuleQueue.INTERACTION_SAMPLES -> LocalUploadModuleFamily.INTERACTION
    ModuleQueue.AUDIO_ACTIVITY_SAMPLES -> LocalUploadModuleFamily.AUDIO_ACTIVITY
    ModuleQueue.AUDIO_CONTENT_SAMPLES -> LocalUploadModuleFamily.AUDIO_CONTENT
    ModuleQueue.NOTIFICATION_ACTIVITY_SAMPLES -> LocalUploadModuleFamily.NOTIFICATION
    ModuleQueue.SLEEP_SAMPLES -> LocalUploadModuleFamily.SLEEP
    ModuleQueue.ACTIVITY_RECOGNITION_SAMPLES -> LocalUploadModuleFamily.ACTIVITY_RECOGNITION
    ModuleQueue.HEALTH_METRIC_SAMPLES -> LocalUploadModuleFamily.HEALTH
    ModuleQueue.CONNECTIVITY_STATE_SAMPLES -> LocalUploadModuleFamily.CONNECTIVITY
    ModuleQueue.APP_NETWORK_USAGE_SAMPLES -> LocalUploadModuleFamily.APP_NETWORK
    ModuleQueue.DEVICE_SETTINGS_SAMPLES -> LocalUploadModuleFamily.DEVICE_SETTINGS
    ModuleQueue.NONE -> LocalUploadModuleFamily.LOCAL_STORE
}

/** Which rows a module erasure clears, independent of any Android side effect. */
internal sealed interface ModuleErasure {
    /** A module with a dedicated queue: clear exactly that queue. */
    data class ClearDedicated(val queue: ModuleQueue) : ModuleErasure

    /** One tagged hardware-sensor module. */
    data class ClearSensor(val sensorType: String) : ModuleErasure

    /** A module with no dedicated queue: nothing to clear. */
    object NoDedicatedQueue : ModuleErasure
}

/** The dedicated on-device queue for [moduleId], or a shared/none marker. */
internal fun dedicatedQueueFor(moduleId: CollectionModuleId): ModuleQueue = when {
    moduleId == CollectionModuleId.BATTERY_TELEMETRY -> ModuleQueue.BATTERY_SAMPLES
    moduleId == CollectionModuleId.USER_IDENTIFICATION -> ModuleQueue.USER_QUEUE
    moduleId == CollectionModuleId.INTERACTION_EVENTS -> ModuleQueue.INTERACTION_SAMPLES
    moduleId == CollectionModuleId.AUDIO_ACTIVITY -> ModuleQueue.AUDIO_ACTIVITY_SAMPLES
    moduleId == CollectionModuleId.AUDIO_CONTENT -> ModuleQueue.AUDIO_CONTENT_SAMPLES
    moduleId == CollectionModuleId.NOTIFICATION_ACTIVITY -> ModuleQueue.NOTIFICATION_ACTIVITY_SAMPLES
    moduleId == CollectionModuleId.SLEEP -> ModuleQueue.SLEEP_SAMPLES
    moduleId == CollectionModuleId.ACTIVITY_RECOGNITION -> ModuleQueue.ACTIVITY_RECOGNITION_SAMPLES
    moduleId == CollectionModuleId.HEALTH_CONNECT -> ModuleQueue.HEALTH_METRIC_SAMPLES
    moduleId == CollectionModuleId.CONNECTIVITY_STATE -> ModuleQueue.CONNECTIVITY_STATE_SAMPLES
    moduleId == CollectionModuleId.APP_NETWORK_USAGE -> ModuleQueue.APP_NETWORK_USAGE_SAMPLES
    moduleId == CollectionModuleId.DEVICE_SETTINGS -> ModuleQueue.DEVICE_SETTINGS_SAMPLES
    // usage + in-app activity + lifecycle share dataQueue with no per-module tag.
    moduleId == CollectionModuleId.USAGE_EVENTS ||
        moduleId == CollectionModuleId.IN_APP_ACTIVITY_CLASS ||
        moduleId == CollectionModuleId.DEVICE_LIFECYCLE -> ModuleQueue.SHARED_DATA_QUEUE
    else -> ModuleQueue.NONE
}

/** Pure plan for a module erasure, JVM-testable without Android (`ChronicleDb`, `Context`, the upload worker). */
internal fun planModuleErasure(moduleId: CollectionModuleId): ModuleErasure =
    SensorCollectionModules.sensorTypeOf(moduleId)?.let { ModuleErasure.ClearSensor(it.name) }
        ?: when (val queue = dedicatedQueueFor(moduleId)) {
            ModuleQueue.NONE -> ModuleErasure.NoDedicatedQueue
            else -> ModuleErasure.ClearDedicated(queue)
        }

private fun reviewedModuleIsCurrent(appContext: Context, reviewed: CollectionModuleState, expectedOwner: UploadServerEntity): Boolean {
    val currentOwner = ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer()
    return currentOwner != null && ResearchErasureFence.enrollmentKey(currentOwner) == ResearchErasureFence.enrollmentKey(expectedOwner) &&
        reviewed.serverEnabled && CollectionLoopStore.of(appContext).loadAll()[reviewed.moduleId] == reviewed
}

private fun availableSensorModules(appContext: Context): Set<CollectionModuleId> {
    val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    return SensorCollectionModules.sensorModuleIds.filterTo(mutableSetOf()) { moduleId ->
        val sensorType = SensorCollectionModules.sensorTypeOf(moduleId) ?: return@filterTo false
        sensorManager.getDefaultSensor(SensorTypeMapping.toAndroidType(sensorType)) != null
    }
}

@VisibleForTesting
internal fun allServers(appContext: Context): List<UploadServerEntity> = try {
    listOfNotNull(ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer())
} catch (error: SQLiteException) {
    unavailableConfiguredServers(error)
} catch (error: IllegalStateException) {
    unavailableConfiguredServers(error)
}

private fun unavailableConfiguredServers(error: Exception): List<UploadServerEntity> {
    Log.e(TAG, "Failed to resolve the configured enrollment server", error)
    return emptyList()
}
