package com.openlattice.chronicle.collection.state

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.isExpectedProvisionalEnrollmentServer
import com.openlattice.chronicle.services.upload.recordForExpectedOwner
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity

/**
 * Final persistence boundary shared by every research-data writer in the app process.
 *
 * Existing module gates decide whether a study requested a data type and whether the participant
 * accepted it. This boundary additionally proves that the one enrollment is still active and no
 * withdrawal has begun, immediately around the database mutation. Withdrawal closes the same
 * barrier under its write lock, eliminating the gate-check/insert race in framework callbacks.
 */
object ResearchPersistenceGate {
    private val admittedOrigins = ThreadLocal<Set<CollectionModuleId>>()
    private const val TAG = "ResearchPersistenceGate"
    private val barrier = ResearchPersistenceBarrier()
    private val collectingOwner = ThreadLocal<UploadServerEntity?>()

    /** Reads external providers without a lease, but binds their final writes to this enrollment. */
    internal fun <T> collectForCurrentOwner(context: Context, operation: () -> T): T? {
        val owner = captureOwner(context) ?: return null
        val previous = collectingOwner.get()
        collectingOwner.set(previous ?: owner)
        try {
            return operation()
        } finally {
            if (previous == null) collectingOwner.remove() else collectingOwner.set(previous)
        }
    }

    private fun collectionOwnerIsCurrent(context: Context): Boolean {
        val expected = collectingOwner.get() ?: return true
        val current = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer() ?: return false
        return current.id == expected.id && current.createdAt == expected.createdAt &&
            current.studyId == expected.studyId && current.participantId == expected.participantId &&
            current.sourceDeviceId == expected.sourceDeviceId
    }
    internal data class PrivacyOperation(val kind: String, val identity: String = "", val ownerKey: String? = null)
    private val failedOperations: MutableSet<PrivacyOperation> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<PrivacyOperation, Boolean>())
    private val untaggedFailure = PrivacyOperation("local-store")
    // A failed operation whose durable outcome is later reached by another path (e.g. erasure replay).
    private val failureResolvers = java.util.concurrent.ConcurrentHashMap<PrivacyOperation, () -> Boolean>()
    private val persistenceFailureClosed: Boolean get() = failedOperations.any { it.ownerKey == null || it.ownerKey == authorization.ownerKey }
    internal fun currentOwnerKey(): String? = authorization.ownerKey
    internal fun canStopOnCurrentThread(): Boolean = runCatching { barrier.checkCanStop() }.isSuccess
    private val operationStack = ThreadLocal<PrivacyOperation?>()
    private val attributedFailure = ThreadLocal<Throwable?>()
    private val stopDepth = ThreadLocal.withInitial { 0 }

    /** Nonblocking failure boundary; called from MAIN as well as worker error handlers. */
    fun closeForPersistenceFailure() {
        failedOperations.add(untaggedFailure)
    }

    internal fun isStorageFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        it is android.database.SQLException || it is java.io.IOException ||
            it is com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException ||
            it is com.openlattice.chronicle.preferences.SecurePreferencesUnavailableException
    }

    /** Verified local-store recovery also resets admission before the next snapshot is published. */
    fun resetAfterLocalStoreRecovery() {
        failedOperations.clear()
        failureResolvers.clear()
        initializationEpoch.incrementAndGet()
        initializationRetry?.cancel(false)
        initializationRetry = null
        initializationQueued.set(false)
        initializationAttempt = 0
    }

    /** Sink guard: write failures propagate so the sink returns Failed and its batch is retried. */
    fun guard(context: Context): CollectionPersistenceGuard {
        val appContext = context.applicationContext
        return sinkGuard(appContext, null)
    }

    /** Final combined active-enrollment + module-consent check for a fixed-module sink. */
    fun guard(context: Context, moduleId: CollectionModuleId): CollectionPersistenceGuard {
        val appContext = context.applicationContext
        return sinkGuard(appContext, moduleId)
    }

    private fun sinkGuard(context: Context, moduleId: CollectionModuleId?): CollectionPersistenceGuard =
        object : CollectionPersistenceGuard {
            override fun persist(persist: () -> Unit): Boolean =
                persistResult(persist) == CollectionPersistenceResult.PERSISTED

            override fun persistResult(persist: () -> Unit): CollectionPersistenceResult =
                persistGuarded(context, moduleId, persist)

            override fun capture(): CollectionPersistenceGuard = captureObservation(context, moduleId)
        }

    private data class AuthorizationSnapshot(
        val owner: UploadServerEntity? = null,
        val ownerKey: String? = null,
        val generations: Map<CollectionModuleId, Long> = emptyMap(),
        val floors: Map<CollectionModuleId, Long> = emptyMap(),
        val accepted: Set<CollectionModuleId> = emptySet(),
        val active: Boolean = false,
        val totalGeneration: Long = 0,
        val enrolled: Boolean = false,
    )
    @Volatile private var authorization = AuthorizationSnapshot()
    @Volatile private var authorizationContext: Context? = null
    private val replayWorker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "research-authorization").apply { isDaemon = true }
    }
    private val replayQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Process start/unlock entry: all authoritative reads and replay run on this worker. */
    private val initializationQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var initializationAttempt = 0
    private val initializationEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var initializationRetry: java.util.concurrent.ScheduledFuture<*>? = null
    private val onAuthorizationWorker = ThreadLocal.withInitial { false }

    internal fun executeAsync(operation: () -> Unit) {
        replayWorker.execute { onAuthorizationWorker.set(true); operation() }
    }

    /** Local retry has no network constraint and needs no admitted callback. */
    fun initializeAsync(context: Context) {
        authorizationContext = context.applicationContext
        if (!initializationQueued.compareAndSet(false, true)) return
        val epoch = initializationEpoch.get()
        executeAsync { retryInitialization(context.applicationContext, epoch) }
    }

    /**
     * Off-main callers woken in a cold process (e.g. an alarm) wait, bounded, for the first
     * initialization attempt to publish authorization before capturing an owner. Returns at once
     * when an owner is already published; never waits on the authorization worker itself.
     */
    internal fun awaitAuthorization(context: Context, timeoutMs: Long) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "awaitAuthorization blocks" }
        if (authorization.owner != null || onAuthorizationWorker.get() == true) return
        initializeAsync(context)
        // FIFO single worker: this marker runs after the queued initialization attempt.
        runCatching { replayWorker.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .onFailure { Log.w(TAG, "Enrollment admission not published before the reminder deadline", it) }
    }

    private fun retryInitialization(context: Context, epoch: Long) {
        if (epoch != initializationEpoch.get()) return
        try {
            initialize(context)
            if (epoch == initializationEpoch.get()) {
                initializationAttempt = 0
                initializationQueued.set(false)
                initializationRetry = null
            }
        } catch (error: Exception) {
            if (epoch != initializationEpoch.get()) return
            Log.e(TAG, "Research authorization initialization will retry locally", error)
            val delay = (250L shl initializationAttempt.coerceAtMost(7)).coerceAtMost(30_000L)
            initializationAttempt++
            initializationRetry = replayWorker.schedule({ onAuthorizationWorker.set(true); retryInitialization(context, epoch) },
                delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    internal fun initialize(context: Context) {
        authorizationContext = context.applicationContext
        stop {
            CollectionLoopCoordinator(context).replayPendingErasures()
        }
    }

    private fun publishAuthorization(context: Context) {
        val fence = ResearchErasureFence(context)
        val owner = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()
        val enrolled = owner != null && isSameEnrollment(context, owner) && EnrollmentSettings(context).isEnrolledOrThrow()
        val active = enrolled &&
            EnrollmentSettings(context).getParticipationStatus() == ParticipationStatus.ENROLLED &&
            WithdrawalStateStore(context).stateOrThrow() == WithdrawalState.NONE &&
            MinimalPlayArtifactState.isReadyOrThrow(context) && !fence.hasPendingErasures()
        if (active) fence.rememberLegacyCheckpointOwner(owner)
        val states = CollectionLoopStore.of(context).loadAll()
        val halted = states.values.any { it.serverEnabled && it.requiredApplied && it.decision == ParticipantDecision.DECLINED }
        val generations = CollectionModuleId.values().associateWith(fence::generation)
        authorization = AuthorizationSnapshot(owner, owner?.let(ResearchErasureFence::enrollmentKey),
            generations, CollectionModuleId.values().associateWith(fence::floor),
            if (active && !halted) states.filterValues { it.serverEnabled && it.decision == ParticipantDecision.ACCEPTED }.keys.toSet()
            else emptySet(), active, generations.values.sum(), enrolled)
    }

    private fun enqueueErasureReplay(context: Context) {
        if (!replayQueued.compareAndSet(false, true)) return
        executeAsync {
            try { CollectionLoopCoordinator(context).replayPendingErasures() }
            catch (error: Exception) { Log.e(TAG, "Research erasure replay will retry", error) }
            finally { replayQueued.set(false) }
        }
    }

    /** Immutable capture; callbacks read only the published snapshot, never Room or the gate. */
    fun captureObservation(context: Context, moduleId: CollectionModuleId?): CollectionPersistenceGuard {
        authorizationContext = context.applicationContext
        val snapshot = authorization
        return ObservationToken(context.applicationContext, moduleId, snapshot.ownerKey,
            if (moduleId == null) snapshot.totalGeneration else snapshot.generations[moduleId] ?: -1,
            !persistenceFailureClosed && snapshot.active && (moduleId == null || moduleId in snapshot.accepted))
    }

    /**
     * [captureObservation] for a non-choice module (QUESTIONNAIRE). Such a module never gets a
     * consent decision, so it is never in [AuthorizationSnapshot.accepted]; the captured
     * enrollment's authenticated manifest is its authority instead. Owner, generation and
     * erasure-fence checks are the same token checks every other observation uses.
     */
    fun captureStudyEnabledObservation(context: Context, moduleId: CollectionModuleId): CollectionPersistenceGuard {
        require(moduleId !in CollectionStateMachine.ACK_GATED_MODULES) { "${moduleId.id} is consent-gated" }
        authorizationContext = context.applicationContext
        val snapshot = authorization
        val owner = snapshot.owner
        val studyEnabled = owner != null && runCatching {
            com.openlattice.chronicle.preferences.configuredStudyModuleEnabled(
                owner, java.util.UUID.fromString(owner.studyId), owner.participantId, moduleId)
        }.getOrDefault(false)
        return ObservationToken(context.applicationContext, moduleId, snapshot.ownerKey,
            snapshot.generations[moduleId] ?: -1, !persistenceFailureClosed && snapshot.active && studyEnabled)
    }

    private class ObservationToken(
        val context: Context,
        val module: CollectionModuleId?,
        val ownerKey: String?,
        val generation: Long,
        val accepted: Boolean,
    ) : CollectionPersistenceGuard {
        override fun capture(): CollectionPersistenceGuard = this
        override fun isCurrent(): Boolean {
            val current = authorization
            return accepted && ownerKey != null && current.ownerKey == ownerKey &&
                generation == (if (module == null) current.totalGeneration else current.generations[module])
        }
        override fun validate(): Boolean {
            if (!isCurrent()) return false
            val owner = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer() ?: return false
            val fence = ResearchErasureFence(context)
            return ResearchErasureFence.enrollmentKey(owner) == ownerKey && isSameEnrollment(context, owner) &&
                generation == (if (module == null) CollectionModuleId.values().sumOf(fence::generation) else fence.generation(module))
        }
        override fun persist(persist: () -> Unit): Boolean = persistResult(persist) == CollectionPersistenceResult.PERSISTED
        override fun persistResult(persist: () -> Unit): CollectionPersistenceResult = barrier.withReadLease {
            if (!validate()) CollectionPersistenceResult.REFUSED
            else if (module == CollectionModuleId.USAGE_EVENTS) persistGuarded(context, module, persist)
            else {
                val previous = admittedOrigins.get()
                admittedOrigins.set(previous.orEmpty() + module?.let(::setOf).orEmpty())
                try { persistGuarded(context, null, persist) }
                finally { admittedOrigins.set(previous) }
            }
        }
    }

    /** Optional fields are projected again inside insertion's read lease, using capture epochs. */
    fun usageSink(context: Context): com.openlattice.chronicle.collection.sink.UsageEventSink = withReadLease {
        val usage = captureObservation(context, CollectionModuleId.USAGE_EVENTS)
        val activity = captureObservation(context, CollectionModuleId.IN_APP_ACTIVITY_CLASS)
        val user = captureObservation(context, CollectionModuleId.USER_IDENTIFICATION)
        com.openlattice.chronicle.collection.sink.UsageEventSink(
            ChronicleDb.getInstance(context).queueEntryData(), persistenceGuard = usage,
            prepareEntries = { entries ->
                val fence = ResearchErasureFence(context)
                val allowActivity = activity.validate()
                val allowUser = user.validate()
                entries.map { entry ->
                    val samples = com.openlattice.chronicle.serialization.JsonSerializer.deserializeQueueEntry(entry.data).map { sample ->
                        if (sample is com.openlattice.chronicle.models.ExtractedUsageEvent) {
                            val time = sample.timestamp.toInstant().toEpochMilli()
                            sample.copy(
                                activityClass = sample.activityClass.takeIf { allowActivity && time >= fence.floor(CollectionModuleId.IN_APP_ACTIVITY_CLASS) },
                                user = sample.user.takeIf { allowUser && time >= fence.floor(CollectionModuleId.USER_IDENTIFICATION) }.orEmpty(),
                            )
                        } else sample
                    }
                    com.openlattice.chronicle.storage.QueueEntry(entry.writeTimestamp, entry.id, com.openlattice.chronicle.serialization.JsonSerializer.serializeQueueEntry(samples))
                }
            },
        )
    }

    fun collectsNow(context: Context, module: CollectionModuleId): Boolean {
        authorizationContext = context.applicationContext
        val current = authorization
        return !persistenceFailureClosed && current.active && module in current.accepted
    }

    /** Lifecycle reads use the immutable publication; authoritative reconciliation is queued. */
    internal fun isEnrolledSnapshot(studyId: java.util.UUID, participantId: String): Boolean {
        val snapshot = authorization
        return snapshot.enrolled && snapshot.owner?.studyId == studyId.toString() &&
            snapshot.owner.participantId == participantId
    }

    /** A DE registration already accepted these RAM entries; HOLD does not revoke its epoch. */
    internal fun guardForRetainedRegistration(context: Context, module: CollectionModuleId, stamp: String?): CollectionPersistenceGuard {
        val current = authorization
        val generation = current.generations[module] ?: -1L
        val matches = current.ownerKey != null && stamp == "${current.ownerKey}:$generation"
        return ObservationToken(context.applicationContext, module, current.ownerKey, generation, matches)
    }

    internal fun guardForLegacyRetainedRegistration(context: Context, module: CollectionModuleId,
                                                     originalOwner: String?, writtenAt: Long?): CollectionPersistenceGuard {
        return object : CollectionPersistenceGuard {
            private fun resolved(): CollectionPersistenceGuard? {
                val current = authorization
                val owner = current.owner ?: return null
                val identity = "${owner.id}:${owner.createdAt}:${owner.studyId}:${owner.participantId}:${owner.sourceDeviceId}"
                if (originalOwner == null || writtenAt == null) return null
                if (identity != originalOwner || (owner.enrollmentIssuedAtEpochMillis ?: 0L) > writtenAt ||
                    (current.floors[module] ?: Long.MAX_VALUE) > writtenAt) {
                    return object : CollectionPersistenceGuard {
                        override fun persist(persist: () -> Unit): Boolean = false
                        override fun isCurrent(): Boolean = false
                    }
                }
                return ObservationToken(context.applicationContext, module, current.ownerKey,
                    current.generations[module] ?: -1L, true)
            }
            override fun isCurrent(): Boolean = resolved()?.isCurrent() ?: true
            override fun validate(): Boolean = resolved()?.validate()
                ?: throw IllegalStateException("Legacy registration ownership requires verification")
            override fun persist(persist: () -> Unit): Boolean = resolved()?.persist(persist) ?: false
            override fun persistResult(persist: () -> Unit): CollectionPersistenceResult =
                resolved()?.persistResult(persist) ?: CollectionPersistenceResult.STORAGE_UNAVAILABLE
        }
    }

    fun guardForRegistration(context: Context, module: CollectionModuleId, stamp: String?): CollectionPersistenceGuard {
        if (stamp == null || observationScope(context, module)?.first != stamp) {
            return object : CollectionPersistenceGuard {
                override fun persist(persist: () -> Unit): Boolean = false
                override fun isCurrent(): Boolean = false
            }
        }
        return captureObservation(context, module)
    }

    fun applyParticipationStatus(context: Context, expected: UploadServerEntity, responseGeneration: Long,
                                 status: ParticipationStatus): Boolean {
        var applied = false
        stop {
            val fence = ResearchErasureFence(context)
            if (!isSameActiveOwner(context, expected) || fence.settingsGeneration() != responseGeneration) return@stop
            val settings = EnrollmentSettings(context)
            val changed = settings.getParticipationStatus() != status
            settings.setParticipationStatus(status)
            if (changed) fence.settingsChanged()
            applied = true
        }
        return applied
    }

    /** Shared source scope; floors survive erasure even after a checkpoint is removed. */
    fun observationScope(context: Context, module: CollectionModuleId): Pair<String, Long>? {
        authorizationContext = context.applicationContext
        val snapshot = authorization
        val key = snapshot.ownerKey ?: return null
        if (!snapshot.active || persistenceFailureClosed) return null
        return "$key:${snapshot.generations[module] ?: return null}" to (snapshot.floors[module] ?: 0L)
    }

    /** Bind a direct-boot insertion to the same enrollment captured before the drain. */
    fun guardForExpectedOwner(
        context: Context,
        expectedOwner: String,
        ownerNow: () -> String?,
    ): CollectionPersistenceGuard {
        val appContext = context.applicationContext
        return expectedOwnerGuard(expectedOwner, ownerNow,
            allowed = { isActiveEnrollmentOrThrow(appContext) && StorageAdmission.allowed(appContext) })
    }

    internal fun expectedOwnerGuard(
        expectedOwner: String,
        ownerNow: () -> String?,
        allowed: () -> Boolean,
    ): CollectionPersistenceGuard = CollectionPersistenceGuard { persist ->
        barrier.persistIf(allowed = { allowed() && ownerNow() == expectedOwner }, persist = persist)
    }

    /**
     * Direct-writer convenience that does not invoke [persist] when either gate is closed. Direct
     * writers run in receivers and callbacks with no retry, so a failed write is caught (never
     * crashes the app) and its [records] are counted as LOCAL_WRITE_FAILED.
     */
    fun persistIfCollecting(
        context: Context,
        moduleId: CollectionModuleId,
        records: Int = 1,
        expectedOwner: UploadServerEntity? = captureOwner(context),
        onRefused: (Int) -> Unit = {},
        persist: () -> Unit,
    ): Boolean = persistCounted(context, moduleId, records, expectedOwner, onRefused, persist)

    fun persistIfActive(context: Context, records: Int = 1,
                        expectedOwner: UploadServerEntity? = captureOwner(context), persist: () -> Unit): Boolean =
        persistCounted(context, null, records, expectedOwner, {}, persist)

    private fun persistCounted(
        context: Context,
        moduleId: CollectionModuleId?,
        records: Int,
        expectedOwner: UploadServerEntity?,
        onRefused: (Int) -> Unit,
        persist: () -> Unit,
    ): Boolean {
        val owner = expectedOwner ?: return false
        val appContext = context.applicationContext
        var persisted = false
        try {
            barrier.persistIf(
                allowed = {
                    isSameActiveOwner(appContext, owner) && StorageAdmission.allowed(appContext) &&
                        (moduleId == null || moduleId in admittedOrigins.get().orEmpty() || CollectionLoopStore.of(appContext).collects(moduleId))
                },
                persist = {
                    try {
                        persist()
                        persisted = true
                    } catch (error: Exception) {
                        Log.e(TAG, "Research write failed; counting $records record(s) as lost", error)
                        if (records > 0) runCatching {
                            LocalUploadDiagnosticsStore.of(appContext).recordOperational(
                                LocalUploadModuleFamily.LOCAL_STORE,
                                LocalOperationalIssue.LOCAL_WRITE_FAILED, records,
                            )
                        }.onFailure { Log.e(TAG, "Unable to record lost research write", it) }
                    }
                },
                onRefused = {
                    if (records > 0 && isSameActiveOwner(appContext, owner)) {
                        runCatching { onRefused(records) }
                            .onFailure { Log.e(TAG, "Unable to record refused research write", it) }
                    }
                },
            )
        } catch (error: Exception) {
            Log.e(TAG, "Research write admission failed", error)
            recordForExpectedOwner(appContext, owner, LocalUploadModuleFamily.LOCAL_STORE,
                LocalOperationalIssue.LOCAL_WRITE_FAILED, records)
        }
        return persisted
    }

    /** See [ResearchPersistenceBarrier.withReadLease]; the direct-boot drain holds its buffer lock inside it. */
    fun <T> withReadLease(block: () -> T): T = barrier.withReadLease(block)

    /** Captured before a non-replayable sample is submitted; never substitutes a later server. */
    fun captureOwner(context: Context): UploadServerEntity? {
        authorizationContext = context.applicationContext
        return authorization.let { it.owner.takeIf { _ -> it.active && !persistenceFailureClosed } }
    }

    private fun isSameActiveOwner(context: Context, expected: UploadServerEntity): Boolean {
        return isActiveEnrollmentOrThrow(context) && isSameEnrollment(context, expected)
    }

    private fun isSameEnrollment(context: Context, expected: UploadServerEntity): Boolean {
        val settings = EnrollmentSettings(context)
        if (settings.getStudyId().toString() != expected.studyId ||
            settings.getParticipantId() != expected.participantId) return false
        val current = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()
            ?: return false
        return current.id == expected.id && current.createdAt == expected.createdAt &&
            current.enrollmentIssuedAtEpochMillis == expected.enrollmentIssuedAtEpochMillis && current.studyId == expected.studyId && current.participantId == expected.participantId &&
            current.sourceDeviceId == expected.sourceDeviceId
    }

    fun <T : Any> runIfExpectedOwner(context: Context, expectedOwner: UploadServerEntity,
                                     operation: () -> T): T? {
        val appContext = context.applicationContext
        if (ResearchErasureFence(appContext).hasPendingErasures()) enqueueErasureReplay(appContext)
        var result: T? = null
        val admitted = barrier.persistIf(
            allowed = { isSameActiveOwner(appContext, expectedOwner) },
            persist = { result = operation() },
        )
        return if (admitted) checkNotNull(result) else null
    }

    private fun persistGuarded(
        context: Context,
        moduleId: CollectionModuleId?,
        persist: () -> Unit,
    ): CollectionPersistenceResult = barrier.withReadLease {
        val appContext = context.applicationContext
        if (!(isActiveEnrollmentOrThrow(appContext, ignoreStorageLatch = true) &&
            (moduleId == null || moduleId in admittedOrigins.get().orEmpty() || CollectionLoopStore.of(appContext).collects(moduleId)))) {
            CollectionPersistenceResult.REFUSED
        } else if (persistenceFailureClosed || !StorageAdmission.allowed(appContext)) {
            CollectionPersistenceResult.STORAGE_UNAVAILABLE
        } else {
            persist()
            CollectionPersistenceResult.PERSISTED
        }
    }

    private fun isActiveEnrollmentOrThrow(context: Context, ignoreStorageLatch: Boolean = false): Boolean {
        if (ResearchErasureFence(context).hasPendingErasures()) return false
        if ((!ignoreStorageLatch && persistenceFailureClosed) || !collectionOwnerIsCurrent(context)) return false
        val settings = EnrollmentSettings(context)
        return settings.getParticipationStatus() == ParticipationStatus.ENROLLED &&
            settings.isEnrolledOrThrow() &&
            WithdrawalStateStore(context).stateOrThrow() == WithdrawalState.NONE &&
            MinimalPlayArtifactState.isReadyOrThrow(context)
    }

    /**
     * Runs one complete outbound research-data operation under the same read lease as a local
     * write. A withdrawal or policy stop takes the write side, so once that stop returns no
     * previously admitted network operation can still submit data. Exceptions propagate to the
     * worker so retry/failure behavior is preserved.
     */
    fun <T : Any> runIfActive(context: Context, operation: () -> T): T? {
        val appContext = context.applicationContext
        if (ResearchErasureFence(appContext).hasPendingErasures()) enqueueErasureReplay(appContext)
        var result: T? = null
        val admitted = barrier.persistIf(
            allowed = { isActiveEnrollmentOrThrow(appContext) },
            persist = { result = operation() },
        )
        return if (admitted) checkNotNull(result) else null
    }

    /**
     * Initial enrollment acknowledgment lease. Setup is not complete yet, so [runIfActive]
     * intentionally rejects it; this narrower lease instead binds the exact issued row owner,
     * immutable enrollment identity, canonical origin, and credential while sharing withdrawal's
     * read/write barrier. Once withdrawal or row ownership changes, it admits neither HTTP nor a
     * retry-queue mutation.
     */
    fun <T : Any> runIfExpectedEnrollment(
        context: Context,
        expected: UploadServerEntity,
        operation: () -> T,
    ): T? {
        val appContext = context.applicationContext
        if (ResearchErasureFence(appContext).hasPendingErasures()) enqueueErasureReplay(appContext)
        var result: T? = null
        val admitted = barrier.persistIf(
            allowed = {
                !persistenceFailureClosed && !ResearchErasureFence(appContext).hasPendingErasures() &&
                EnrollmentSettings(appContext).getParticipationStatus() == ParticipationStatus.ENROLLED &&
                    WithdrawalStateStore(appContext).state() == WithdrawalState.NONE &&
                    isExpectedProvisionalEnrollmentServer(
                        ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer(),
                        expected,
                    )
            },
            persist = { result = operation() },
        )
        return if (admitted) checkNotNull(result) else null
    }

    /** Fail-closed one-active-study predicate shared by callbacks and participant controls. */
    fun isActiveEnrollment(context: Context): Boolean = try {
        val appContext = context.applicationContext
        val settings = EnrollmentSettings(appContext)
        !ResearchErasureFence(appContext).hasPendingErasures() && !persistenceFailureClosed && collectionOwnerIsCurrent(appContext) && settings.getParticipationStatus() == ParticipationStatus.ENROLLED &&
            settings.isEnrolled() &&
            WithdrawalStateStore(appContext).state() == WithdrawalState.NONE &&
            MinimalPlayArtifactState.isReady(appContext)
    } catch (error: Exception) {
        Log.e(TAG, "Active-enrollment check failed", error)
        false
    }

    /** Every mutation and publication uses the worker, including lifecycle callers on MAIN. */
    fun stop(stopAction: () -> Unit) = stop(null, stopAction = stopAction)

    /** [resolved] reports, from durable state, that a failed [operation]'s requested outcome now holds. */
    internal fun stop(operation: PrivacyOperation?, completed: () -> Boolean = { true },
                      resolved: (() -> Boolean)? = null, stopAction: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            executeAsync {
                try { stop(operation, completed, resolved, stopAction) }
                catch (error: Exception) { Log.e(TAG, "Queued research mutation failed", error) }
            }
            return
        }
        barrier.checkCanStop()
        if (onAuthorizationWorker.get() != true) {
            try {
                replayWorker.submit {
                    onAuthorizationWorker.set(true)
                    stop(operation, completed, resolved, stopAction)
                }.get()
            } catch (error: java.util.concurrent.ExecutionException) {
                throw error.cause ?: error
            }
            return
        }
        barrier.stop {
            if ((stopDepth.get() ?: 0) == 0) attributedFailure.remove()
            val previous = operationStack.get()
            val tagged = operation?.takeIf { it.kind in setOf("withdrawal", "participant-decision", "module-discard", "erasure-replay", "settings-apply") }
            val effective = tagged ?: previous
            operationStack.set(effective)
            stopDepth.set((stopDepth.get() ?: 0) + 1)
            try {
                stopAction()
                if (stopDepth.get() == 1) authorizationContext?.let(::publishAuthorization)
                if (completed()) {
                    if (tagged != null) { failedOperations.remove(tagged); failureResolvers.remove(tagged) }
                    if (stopDepth.get() == 1) failedOperations.remove(untaggedFailure)
                }
                if (stopDepth.get() == 1) failureResolvers.entries.removeIf { (failed, check) ->
                    runCatching(check).getOrDefault(false).also { if (it) failedOperations.remove(failed) }
                }
            } catch (error: Exception) {
                if (isStorageFailure(error) && attributedFailure.get() !== error) {
                    failedOperations.add(effective ?: untaggedFailure)
                    if (effective != null && effective == tagged && resolved != null) failureResolvers[effective] = resolved
                    attributedFailure.set(error)
                    authorization = authorization.copy(accepted = emptySet(), active = false)
                }
                throw error
            } finally {
                stopDepth.set((stopDepth.get() ?: 1) - 1)
                operationStack.set(previous)
            }
        }
    }

    internal fun <T> enrollmentMutation(context: Context, operation: () -> T): T {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "Result-bearing enrollment mutations require a background caller"
        }
        authorizationContext = context.applicationContext
        var result: T? = null
        stop { result = operation() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    internal fun enrollmentUpdate(context: Context, operation: () -> Unit) {
        authorizationContext = context.applicationContext
        stop(stopAction = operation)
    }

    fun setServerEnabled(context: Context, serverId: Long, enabled: Boolean): Int {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            executeAsync { setServerEnabled(context.applicationContext, serverId, enabled) }
            return 0
        }
        authorizationContext = context.applicationContext
        var changed = 0
        stop(null, completed = { changed == 1 }) {
            changed = ChronicleDb.getInstance(context).uploadServerDao().setEnabled(serverId, enabled)
        }
        CollectionLoopCoordinator(context).refreshCollectorAdmission()
        return changed
    }
}
