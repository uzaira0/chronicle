package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.core.ObservationScope
import com.openlattice.chronicle.EnrolledWork
import com.openlattice.chronicle.services.notifications.restoreReminderAlarms
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate.OwnerAdmission
import android.content.Context
import android.database.SQLException
import android.os.Looper
import android.util.Log
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.HardStop
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.SecurePreferencesUnavailableException
import com.openlattice.chronicle.preferences.AppliedOperationalModules
import com.openlattice.chronicle.preferences.configuredStudyModuleEnabled
import com.openlattice.chronicle.security.CallDeadline
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.notifications.REMINDER_SCHEDULE_LOCK
import com.openlattice.chronicle.services.notifications.cancelReminderByRequestCode
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.isExpectedProvisionalEnrollmentServer
import com.openlattice.chronicle.services.upload.recordForExpectedOwner
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LifecycleStateEntity
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.QUEUE_CAPTURE_VERSION_KEY
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Final persistence boundary shared by every research-data writer in the app process.
 *
 * Existing module gates decide whether a study requested a data type and whether the participant
 * accepted it. This boundary additionally proves that the one enrollment is still active and no
 * withdrawal has begun, immediately around the database mutation. Withdrawal closes the same
 * barrier under its write lock, eliminating the gate-check/insert race in framework callbacks.
 */
object ResearchPersistenceGate {
    private val capturedObservation = ThreadLocal<ObservationToken?>()
    private val admittedOrigins = ThreadLocal<Set<CollectionModuleId>>()
    private const val TAG = "ResearchPersistenceGate"
    @Volatile private var barrier = ResearchPersistenceBarrier()
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
        return isSameEnrollment(context, expected)
    }
    /** [cancelsAdmittedUploads]: the participant revoked permission, so in-flight uploads are cut off. */
    internal enum class PrivacyOperationKind(val cancelsAdmittedUploads: Boolean = false) {
        LOCAL_STORE, WITHDRAWAL(true), PARTICIPANT_DECISION(true), MODULE_DISCARD(true), ERASURE_REPLAY, SETTINGS_APPLY
    }
    internal data class PrivacyOperation(val kind: PrivacyOperationKind, val identity: String = "", val ownerKey: String? = null)
    private val failedOperations: MutableSet<PrivacyOperation> = Collections.newSetFromMap(
        ConcurrentHashMap<PrivacyOperation, Boolean>())
    private val untaggedFailure = PrivacyOperation(PrivacyOperationKind.LOCAL_STORE)
    // A failed operation whose durable outcome is later reached by another path (e.g. erasure replay).
    private val failureResolvers = ConcurrentHashMap<PrivacyOperation, () -> Boolean>()
    private val persistenceFailureClosed: Boolean get() = failedOperations.any { it.ownerKey == null || it.ownerKey == authorization.ownerKey }
    internal fun currentOwnerKey(): String? = authorization.ownerKey
    internal fun currentSettingsVersion(): Int? = authorization.settingsVersion
    internal fun canStopOnCurrentThread(): Boolean = runCatching { barrier.checkCanStop() }.isSuccess
    private val operationStack = ThreadLocal<PrivacyOperation?>()
    private val attributedFailure = ThreadLocal<Throwable?>()
    private val stopDepth = ThreadLocal.withInitial { 0 }

    /** Nonblocking failure boundary; called from MAIN as well as worker error handlers. */
    fun closeForPersistenceFailure() {
        failedOperations.add(untaggedFailure)
    }

    internal fun isStorageFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        it is SQLException || it is IOException ||
            it is LocalStoreRecoveryRequiredException ||
            it is SecurePreferencesUnavailableException
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

            override fun <T> outsideCaptureWindows(rows: List<T>, capturedAt: (T) -> Long?): List<T> =
                capturedOutsideWindows(context, rows, capturedAt)
        }

    /**
     * The one write-time capture-window check. Callers are inside a persist, so they hold the read lease, which
     * excludes the stop that records windows and drops what is queued inside them ([applyPublishedWindows]): a row
     * captured before a window was applied and written after it never persists, one captured outside every window does.
     */
    private fun <T> capturedOutsideWindows(context: Context, rows: List<T>, capturedAt: (T) -> Long?): List<T> {
        val windows = ResearchErasureFence(context).captureWindows()
        if (windows.isEmpty()) return rows
        return rows.filterNot { row -> capturedAt(row)?.let { at -> windows.any { at in it } } == true }
    }

    @Volatile private var authorization = AuthorizationSnapshot()
    @Volatile private var authorizationContext: Context? = null
    private val replayWorker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "research-authorization").apply { isDaemon = true }
    }
    private val replayQueued = AtomicBoolean(false)

    /** Process start/unlock entry: all authoritative reads and replay run on this worker. */
    private val initializationQueued = AtomicBoolean(false)
    private var initializationAttempt = 0
    private val initializationEpoch = AtomicLong()
    @Volatile private var initializationRetry: ScheduledFuture<*>? = null
    private val onAuthorizationWorker = ThreadLocal.withInitial { false }

    internal fun executeAsync(operation: () -> Unit) {
        replayWorker.execute { onAuthorizationWorker.set(true); operation() }
    }

    internal fun isOnAuthorizationWorker(): Boolean = onAuthorizationWorker.get() == true

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
     *
     * Returns false when authorization is still unsettled at the deadline (the attempt is queued,
     * running or retrying): the published snapshot cannot yet say whether an enrollment owns this
     * process, so the caller must keep its work for [retryInitialization], not drop it.
     */
    internal fun awaitAuthorization(context: Context, timeoutMs: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "awaitAuthorization blocks" }
        if (authorization.owner != null || isOnAuthorizationWorker()) return true
        initializeAsync(context)
        // FIFO single worker: this marker runs after the queued initialization attempt.
        val drained = runCatching { replayWorker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
            .onFailure { Log.w(TAG, "Enrollment admission not published before the reminder deadline", it) }
            .isSuccess
        return authorization.owner != null || (drained && !initializationQueued.get())
    }

    private fun retryInitialization(context: Context, epoch: Long) {
        if (epoch != initializationEpoch.get()) return
        try {
            initialize(context)
            if (EnrollmentSettings(context).isEnrolledOrThrow()) {
                // Process start (also after Android revoked exact alarms, which kills the process and
                // cancels its alarms): re-arm the cached reminders locally, before any network refresh.
                restoreReminderAlarms(context)
                EnrolledWork.reconcile(context)
            }
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
                delay, TimeUnit.MILLISECONDS)
        }
    }

    internal fun initialize(context: Context, persistenceBarrier: ResearchPersistenceBarrier? = null) {
        if (persistenceBarrier != null) barrier = persistenceBarrier
        authorizationContext = context.applicationContext
        stop {
            CollectionLoopCoordinator(context).replayPendingErasures()
        }
    }

    private fun publishAuthorization(context: Context) {
        authorization = loadAuthorizationSnapshot(context) { isSameEnrollment(context, it) }
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
            !persistenceFailureClosed && snapshot.active && (moduleId == null || moduleId in snapshot.accepted), snapshot.settingsVersion)
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
            configuredStudyModuleEnabled(owner, UUID.fromString(owner.studyId), owner.participantId, moduleId,
                appliedOperationalModules = AppliedOperationalModules.load(context, owner))
        }.getOrDefault(false)
        return ObservationToken(context.applicationContext, moduleId, snapshot.ownerKey,
            snapshot.generations[moduleId] ?: -1, !persistenceFailureClosed && snapshot.active && studyEnabled, snapshot.settingsVersion)
    }

    private class ObservationToken(
        val context: Context,
        val module: CollectionModuleId?,
        val ownerKey: String?,
        val generation: Long,
        val accepted: Boolean,
        val settingsVersion: Int?,
    ) : CollectionPersistenceGuard {
        override fun capture(): CollectionPersistenceGuard = this
        override fun isCurrent(): Boolean {
            val current = authorization
            return accepted && (module == null || DistributionModulePolicy.supports(module)) &&
                ownerKey != null && current.ownerKey == ownerKey &&
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
        override fun <T> outsideCaptureWindows(rows: List<T>, capturedAt: (T) -> Long?): List<T> =
            capturedOutsideWindows(context, rows, capturedAt)
        override fun persistResult(persist: () -> Unit): CollectionPersistenceResult = barrier.withReadLease {
            val previousObservation = capturedObservation.get()
            capturedObservation.set(this)
            try {
                if (!validate()) CollectionPersistenceResult.REFUSED
                else if (module == CollectionModuleId.USAGE_EVENTS) persistGuarded(context, module, persist)
                else {
                    val previous = admittedOrigins.get()
                    admittedOrigins.set(previous.orEmpty() + module?.let(::setOf).orEmpty())
                    try { persistGuarded(context, null, persist) }
                    finally { admittedOrigins.set(previous) }
                }
            } finally { capturedObservation.set(previousObservation) }
        }
    }

    /**
     * Optional fields are projected inside insertion's read lease by the decision that held when each
     * event was captured: a field is kept only where its module's scope admits the event time (floor,
     * pauses, and the module's own stopped intervals). Turning a field off therefore never strips it
     * from unread history captured while it was on, and never attaches it to history captured after.
     */
    fun usageSink(context: Context): UsageEventSink = withReadLease {
        val usage = captureObservation(context, CollectionModuleId.USAGE_EVENTS)
        UsageEventSink(
            ChronicleDb.getInstance(context).queueEntryData(), persistenceGuard = usage,
            prepareEntries = { entries ->
                val scope = observationScope(context, CollectionModuleId.USAGE_EVENTS)
                val activityScope = observationScope(context, CollectionModuleId.IN_APP_ACTIVITY_CLASS)
                val userScope = observationScope(context, CollectionModuleId.USER_IDENTIFICATION)
                entries.mapNotNull { entry ->
                    val samples = JsonSerializer.deserializeQueueEntry(entry.data).filterNot { sample ->
                        sample is ExtractedUsageEvent && scope?.suspends(sample.timestamp.toInstant().toEpochMilli()) != false
                    }.map { sample ->
                        if (sample is ExtractedUsageEvent) {
                            val time = sample.timestamp.toInstant().toEpochMilli()
                            sample.copy(
                                activityClass = sample.activityClass.takeIf { activityScope?.admits(time) == true },
                                user = sample.user.takeIf { userScope?.admits(time) == true }.orEmpty(),
                            )
                        } else sample
                    }
                    if (samples.isEmpty()) null else QueueEntry(entry.writeTimestamp, entry.id,
                        JsonSerializer.serializeQueueEntry(samples))
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
    internal fun isEnrolledSnapshot(studyId: UUID, participantId: String): Boolean {
        val snapshot = authorization
        return snapshot.enrolled && snapshot.owner?.studyId == studyId.toString() &&
            snapshot.owner.participantId == participantId
    }

    /** A DE registration already accepted these RAM entries; HOLD does not revoke its epoch. */
    internal fun guardForRetainedRegistration(context: Context, module: CollectionModuleId, stamp: String?,
                                             settingsVersion: Int? = null): CollectionPersistenceGuard {
        val current = authorization
        val generation = current.generations[module] ?: -1L
        val matches = current.ownerKey != null && stamp == "${current.ownerKey}:$generation"
        return ObservationToken(context.applicationContext, module, current.ownerKey, generation, matches, settingsVersion)
    }

    fun guardForRegistration(context: Context, module: CollectionModuleId, stamp: String?): CollectionPersistenceGuard {
        if (stamp == null || observationScope(context, module)?.key != stamp) {
            return object : CollectionPersistenceGuard {
                override fun persist(persist: () -> Unit): Boolean = false
                override fun isCurrent(): Boolean = false
            }
        }
        return captureObservation(context, module)
    }

    /**
     * Applies a status poll response (pollParticipationStatus is the one caller). [windows] are the server's capture
     * windows, which a status never comes without: queued rows captured inside one are dropped here, under the stop,
     * so no upload can send them afterwards. [hardStop] is the latest Mark not enrolled or withdrawal: rows captured
     * before it are dropped the same way, and one this enrollment has not seen also clears what has no capture time
     * ([ResearchErasureFence.recordCaptureWindows]). False when the generation or the owner changed since the poll was sent:
     * nothing was applied.
     */
    fun applyParticipationStatus(context: Context, expected: UploadServerEntity, responseGeneration: Long,
                                 status: ParticipationStatus, windows: List<CaptureWindow>, hardStop: HardStop? = null): Boolean {
        var applied = false
        var wasHardStopped = false
        // Flush samples accepted before a stop while their enrollment is still active.
        if (status != ParticipationStatus.ENROLLED) SensorSampleWriter.flushRuntimes()
        stop {
            val fence = ResearchErasureFence(context)
            // Any status: a paused device must still accept the server's ENROLLED to resume.
            if (!isSameEnrolledOwner(context, expected) || fence.settingsGeneration() != responseGeneration) return@stop
            wasHardStopped = CollectionHardStop.applies(context)
            val settings = EnrollmentSettings(context)
            val previous = settings.getParticipationStatus()
            val changed = previous != status
            applyPublishedWindows(context, fence, windows, hardStop)
            settings.setParticipationStatus(status)
            if (status != ParticipationStatus.ENROLLED) {
                synchronized(REMINDER_SCHEDULE_LOCK) {
                    settings.getMobileReminderRequestCodes().forEach {
                        cancelReminderByRequestCode(context, it)
                    }
                }
            }
            if (changed) fence.settingsChanged()
            CollectionLoopCoordinator(context).updateSensorService()
            // The monitor's one owner on a status change: a status it does not survive stops it, and ENROLLED or PAUSED after
            // one starts it again, or marks it deferred until Chronicle is opened (Android 12+ refuses a background start).
            if (participationRetainsServices(status) != participationRetainsServices(previous)) {
                DeviceUnlockMonitoringService.reconcile(context)
            }
            if (changed && status == ParticipationStatus.ENROLLED) {
                EnrolledWork.refreshSensorAvailability(context)
            }
            applied = true
        }
        // A re-enrollment after Mark not enrolled schedules what the hard stop cancelled.
        if (applied) CollectionHardStop.resumeWorkIfLifted(context, wasHardStopped)
        return applied
    }

    /** Stores the windows alone, before the last usage poll of an enrollment that is stopping. */
    internal fun applyCaptureWindows(context: Context, expected: UploadServerEntity, responseGeneration: Long,
                                     windows: List<CaptureWindow>, hardStop: HardStop?): Boolean {
        var applied = false
        stop {
            val fence = ResearchErasureFence(context)
            if (!isSameEnrolledOwner(context, expected) || fence.settingsGeneration() != responseGeneration) return@stop
            applyPublishedWindows(context, fence, windows, hardStop)
            applied = true
        }
        return applied
    }

    /** Under the stop: records what the poll published, then drops every queued row captured inside a window. */
    private fun applyPublishedWindows(context: Context, fence: ResearchErasureFence, windows: List<CaptureWindow>,
                                      hardStop: HardStop?) {
        val db = ChronicleDb.getInstance(context)
        fence.recordCaptureWindows(windows, hardStop) { CollectionHardStop.clearQueues(db, CollectionHardStop.UNTIMED_QUEUES) }
        dropQueuedCapturesInside(db, fence.captureWindows())
    }

    /** Shared source scope; floors survive erasure even after a checkpoint is removed. */
    fun observationScope(context: Context, module: CollectionModuleId): ObservationScope? {
        authorizationContext = context.applicationContext
        val snapshot = authorization
        val key = snapshot.ownerKey ?: return null
        if (!snapshot.active || persistenceFailureClosed) return null
        return ObservationScope("$key:${snapshot.generations[module] ?: return null}", snapshot.floors[module] ?: 0L,
            snapshot.suspended[module].orEmpty())
    }

    /**
     * Direct-writer convenience that does not invoke [persist] when either gate is closed. Direct
     * writers run in receivers and callbacks with no retry, so a failed write is caught (never
     * crashes the app) and its request records are counted as LOCAL_WRITE_FAILED.
     */
    internal fun persistIfCollecting(
        context: Context,
        request: ResearchWriteRequest,
        persist: () -> Unit,
    ): Boolean = persistCounted(context, request, persist)

    fun persistIfActive(context: Context, records: Int = 1,
                        expectedOwner: UploadServerEntity? = captureOwner(context), persist: () -> Unit): Boolean =
        persistCounted(context, ResearchWriteRequest(
            moduleId = null, records = records, expectedOwner = expectedOwner, onRefused = {}), persist)

    private fun persistCounted(
        context: Context,
        request: ResearchWriteRequest,
        persist: () -> Unit,
    ): Boolean {
        val owner = request.expectedOwner ?: return false
        val appContext = context.applicationContext
        var persisted = false
        try {
            barrier.persistIf(
                allowed = {
                    isSameActiveOwner(appContext, owner) && StorageAdmission.allowed(appContext) &&
                        (request.moduleId == null || request.moduleId in admittedOrigins.get().orEmpty() ||
                            CollectionLoopStore.of(appContext).collects(request.moduleId))
                },
                persist = {
                    persistWithSettingsEvidence(appContext, persist)
                    persisted = true
                },
                onRefused = {
                    if (request.records > 0 && isSameActiveOwner(appContext, owner)) {
                        runCatching { request.onRefused(request.records) }
                            .onFailure { Log.e(TAG, "Unable to record refused research write", it) }
                    }
                },
            )
        } catch (error: Exception) {
            // The outer transaction must roll back before its caller records the loss.
            if (runCatching { ChronicleDb.getInstance(appContext).inTransaction() }.getOrDefault(false)) throw error
            Log.e(TAG, "Research write admission failed", error)
            recordForExpectedOwner(appContext, owner, LocalUploadModuleFamily.LOCAL_STORE,
                LocalOperationalIssue.LOCAL_WRITE_FAILED, request.records)
        }
        return persisted
    }

    private fun persistWithSettingsEvidence(context: Context, persist: () -> Unit,
        version: Int? = capturedObservation.get().let { if (it == null) authorization.settingsVersion else it.settingsVersion },
    ) {
        val db = ChronicleDb.getInstance(context)
        db.runInTransaction {
            val dao = db.lifecycleStateDao()
            val key = QUEUE_CAPTURE_VERSION_KEY
            val previous = dao.get(key)
            dao.upsert(LifecycleStateEntity(key,
                (version ?: CollectionUploadEvidence.UNKNOWN_SETTINGS_VERSION).toString(), 0))
            try { persist() }
            finally {
                if (previous == null) dao.delete(key)
                else dao.upsert(previous)
            }
        }
    }

    /** See [ResearchPersistenceBarrier.withReadLease]; the direct-boot drain holds its buffer lock inside it. */
    fun <T> withReadLease(block: () -> T): T {
        lateinit var cancelCalls: () -> Unit
        return CallDeadline.cancellable(CallDeadline.INTERACTIVE_TIMEOUT_MS, { cancelCalls = it }) {
            barrier.withReadLease(cancelCalls, block)
        }
    }

    internal sealed interface OwnerAdmission {
        data class Admitted(val owner: UploadServerEntity) : OwnerAdmission
        data class Stopped(val owner: UploadServerEntity, val reason: ParticipationStatus) : OwnerAdmission
        enum class Refused : OwnerAdmission { NO_OWNER, ENROLLMENT_UNAVAILABLE, PERSISTENCE_CLOSED }
    }

    private enum class OwnerVerification { SNAPSHOT, CURRENT }
    private enum class EnrollmentAdmissionRequest { ACTIVE, ANY_PARTICIPATION_STATUS, STORAGE_RECOVERY }

    private fun ownerAdmission(context: Context, verification: OwnerVerification): OwnerAdmission {
        authorizationContext = context.applicationContext
        val snapshot = authorization
        if (snapshot.owner == null) return OwnerAdmission.Refused.NO_OWNER
        if (persistenceFailureClosed) return OwnerAdmission.Refused.PERSISTENCE_CLOSED
        if (verification == OwnerVerification.CURRENT && !isSameEnrolledOwner(context, snapshot.owner)) {
            return OwnerAdmission.Refused.ENROLLMENT_UNAVAILABLE
        }
        return snapshot.admission
    }

    /** Keeps both the admission decision and any outbound observation inside the stop barrier. */
    internal fun <T> withOwnerAdmission(context: Context, operation: (OwnerAdmission) -> T): T = withReadLease {
        operation(ownerAdmission(context, OwnerVerification.CURRENT))
    }

    /** Captured before a non-replayable sample is submitted; callbacks only read the publication. */
    fun captureOwner(context: Context): UploadServerEntity? =
        (ownerAdmission(context, OwnerVerification.SNAPSHOT) as? OwnerAdmission.Admitted)?.owner

    private fun isSameEnrollment(context: Context, expected: UploadServerEntity): Boolean {
        val settings = EnrollmentSettings(context)
        if (settings.getStudyId().toString() != expected.studyId ||
            settings.getParticipantId() != expected.participantId) return false
        val current = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()
            ?: return false
        return current.id == expected.id && current.createdAt == expected.createdAt &&
            current.enrollmentIssuedAtEpochMillis == expected.enrollmentIssuedAtEpochMillis &&
            current.enrollmentIdentity() == expected.enrollmentIdentity()
    }

    private fun isSameActiveOwner(context: Context, expected: UploadServerEntity): Boolean {
        return isActiveEnrollmentOrThrow(context) && isSameEnrollment(context, expected)
    }

    internal fun isSameEnrolledOwner(context: Context, expected: UploadServerEntity): Boolean {
        return isActiveEnrollmentOrThrow(context, request = EnrollmentAdmissionRequest.ANY_PARTICIPATION_STATUS) &&
            isSameEnrollment(context, expected)
    }


    fun <T : Any> runIfExpectedOwner(context: Context, expectedOwner: UploadServerEntity,
                                     operation: () -> T): T? {
        return runOperation(context, { isSameActiveOwner(it, expectedOwner) }, operation)
    }

    private fun <T : Any> runOperation(context: Context, allowed: (Context) -> Boolean, operation: () -> T): T? {
        val appContext = context.applicationContext
        if (ResearchErasureFence(appContext).hasPendingErasures()) enqueueErasureReplay(appContext)
        return withReadLease {
            if (!allowed(appContext)) null else operation()
        }
    }

    private fun persistGuarded(
        context: Context,
        moduleId: CollectionModuleId?,
        persist: () -> Unit,
    ): CollectionPersistenceResult = barrier.withReadLease {
        val appContext = context.applicationContext
        val moduleAdmitted = moduleId == null || moduleId in admittedOrigins.get().orEmpty() ||
            CollectionLoopStore.of(appContext).collects(moduleId)
        if (!isActiveEnrollmentOrThrow(appContext, request = EnrollmentAdmissionRequest.STORAGE_RECOVERY) || !moduleAdmitted) {
            CollectionPersistenceResult.REFUSED
        } else if (persistenceFailureClosed || !StorageAdmission.allowed(appContext)) {
            CollectionPersistenceResult.STORAGE_UNAVAILABLE
        } else {
            persistWithSettingsEvidence(appContext, persist)
            CollectionPersistenceResult.PERSISTED
        }
    }

    private fun isActiveEnrollmentOrThrow(
        context: Context,
        request: EnrollmentAdmissionRequest = EnrollmentAdmissionRequest.ACTIVE,
    ): Boolean {
        if (ResearchErasureFence(context).hasPendingErasures()) return false
        if ((request != EnrollmentAdmissionRequest.STORAGE_RECOVERY && persistenceFailureClosed) || !collectionOwnerIsCurrent(context)) return false
        val settings = EnrollmentSettings(context)
        val participationAllowed = request == EnrollmentAdmissionRequest.ANY_PARTICIPATION_STATUS ||
            settings.getParticipationStatus() == ParticipationStatus.ENROLLED
        return participationAllowed &&
            settings.isEnrolledOrThrow() &&
            WithdrawalStateStore(context).stateOrThrow() == WithdrawalState.NONE
    }

    /**
     * Runs one complete outbound research-data operation under the same read lease as a local
     * write. A withdrawal or policy stop takes the write side, so once that stop returns no
     * previously admitted network operation can still submit data. Exceptions propagate to the
     * worker so retry/failure behavior is preserved.
     */
    fun <T : Any> runIfActive(context: Context, operation: () -> T): T? {
        return runOperation(context, { isActiveEnrollmentOrThrow(it) }, operation)
    }

    /**
     * Drain authorized queues after pause/completion. The durable hard-stop mark (withdrawal, Mark not enrolled,
     * a standing required decline) refuses every upload, whatever is still queued.
     */
    fun <T : Any> runIfEnrolled(context: Context, operation: () -> T): T? =
        runOperation(context, {
            isActiveEnrollmentOrThrow(it, request = EnrollmentAdmissionRequest.ANY_PARTICIPATION_STATUS) &&
                EnrollmentSettings(it).getParticipationStatus() in setOf(ParticipationStatus.ENROLLED,
                    ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED) &&
                !CollectionHardStop.applies(it)
        }, operation)

    /** Consent reports remain available to resolve a collection halt. */
    internal fun <T : Any> runIfEnrollmentConfigured(context: Context, operation: () -> T): T? =
        runOperation(context, { isActiveEnrollmentOrThrow(it, request = EnrollmentAdmissionRequest.ANY_PARTICIPATION_STATUS) }, operation)

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
        return runOperation(context, { appContext ->
            !persistenceFailureClosed && !ResearchErasureFence(appContext).hasPendingErasures() &&
                EnrollmentSettings(appContext).getParticipationStatus() == ParticipationStatus.ENROLLED &&
                WithdrawalStateStore(appContext).state() == WithdrawalState.NONE &&
                isExpectedProvisionalEnrollmentServer(
                    ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer(), expected,
                )
        }, operation)
    }

    /** Enrolled on this device and not withdrawn, whatever the server-side participation status. */
    fun isEnrolledIgnoringStatus(context: Context): Boolean = try {
        isActiveEnrollmentOrThrow(context.applicationContext, request = EnrollmentAdmissionRequest.ANY_PARTICIPATION_STATUS)
    } catch (error: Exception) {
        Log.e(TAG, "Enrollment check failed", error)
        false
    }

    /** Fail-closed one-active-study predicate shared by callbacks and participant controls. */
    fun isActiveEnrollment(context: Context): Boolean = try {
        isActiveEnrollmentOrThrow(context.applicationContext)
    } catch (error: Exception) {
        Log.e(TAG, "Active-enrollment check failed", error)
        false
    }

    /** Every mutation and publication uses the worker, including lifecycle callers on MAIN. */
    fun stop(stopAction: () -> Unit) = stop(null, stopAction = stopAction)

    /** [resolved] reports, from durable state, that a failed [operation]'s requested outcome now holds. */
    internal fun stop(operation: PrivacyOperation?, completed: () -> Boolean = { true },
                      resolved: (() -> Boolean)? = null, stopAction: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
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
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
            return
        }
        barrier.stop(operation?.kind?.cancelsAdmittedUploads == true) {
            if ((stopDepth.get() ?: 0) == 0) attributedFailure.remove()
            val previous = operationStack.get()
            val tagged = operation?.takeIf { it.kind != PrivacyOperationKind.LOCAL_STORE }
            val effective = tagged ?: previous
            operationStack.set(effective)
            stopDepth.set((stopDepth.get() ?: 0) + 1)
            try {
                stopAction()
                if (stopDepth.get() == 1) authorizationContext?.let {
                    // After every mutation, under the write side: a hard stop clears what is queued before any
                    // upload can be admitted again, including after a process death between mark and clear.
                    CollectionHardStop.enforce(it)
                    publishAuthorization(it)
                }
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
                    authorization = authorization.copy(accepted = emptySet(), admission = OwnerAdmission.Refused.PERSISTENCE_CLOSED)
                }
                throw error
            } finally {
                stopDepth.set((stopDepth.get() ?: 1) - 1)
                operationStack.set(previous)
            }
        }
    }

    internal fun <T> enrollmentMutation(context: Context, operation: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) {
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
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

private data class AuthorizationSnapshot(
    val owner: UploadServerEntity? = null,
    val ownerKey: String? = null,
    val generations: Map<CollectionModuleId, Long> = emptyMap(),
    val floors: Map<CollectionModuleId, Long> = emptyMap(),
    val suspended: Map<CollectionModuleId, List<LongRange>> = emptyMap(),
    val accepted: Set<CollectionModuleId> = emptySet(),
    val admission: OwnerAdmission = OwnerAdmission.Refused.NO_OWNER,
    val totalGeneration: Long = 0,
    val enrolled: Boolean = false,
    val settingsVersion: Int? = null,
) {
    val active: Boolean get() = admission is OwnerAdmission.Admitted
}

/** The modules whose capture the fence counts as running; the settings apply records the same set. */
internal fun collectingModules(states: Collection<CollectionModuleState>): Set<CollectionModuleId> =
    states.filter { it.collectsWhenEnrolled && DistributionModulePolicy.supports(it.moduleId) }.mapTo(linkedSetOf()) { it.moduleId }

private fun loadAuthorizationSnapshot(context: Context, matchesEnrollment: (UploadServerEntity) -> Boolean): AuthorizationSnapshot {
    val fence = ResearchErasureFence(context)
    val owner = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()
    val enrolled = owner != null && matchesEnrollment(owner) && EnrollmentSettings(context).isEnrolledOrThrow()
    val admission = decideOwnerAdmission(owner, EnrollmentSettings(context).getParticipationStatus(),
        enrolled && WithdrawalStateStore(context).stateOrThrow() == WithdrawalState.NONE && !fence.hasPendingErasures())
    val active = when (admission) {
        is OwnerAdmission.Admitted -> {
            true
        }
        else -> false
    }
    val states = CollectionLoopStore.of(context).loadAll()
    val halted = CollectionLoopStore.of(context).haltedByDeclinedRequired()
    val accepted = collectingModules(states.values)
    fence.reconcileHaltSuspension(halted)
    fence.reconcileModuleCollection(accepted)
    val generations = CollectionModuleId.values().associateWith(fence::generation)
    return AuthorizationSnapshot(
        owner = owner,
        ownerKey = owner?.let(ResearchErasureFence::enrollmentKey),
        generations = generations,
        floors = CollectionModuleId.values().associateWith(fence::floor),
        suspended = CollectionModuleId.values().associateWith(fence::suspendedIntervals),
        accepted = if (active && !halted) accepted else emptySet(),
        admission = admission,
        totalGeneration = generations.values.sum(),
        enrolled = enrolled,
        settingsVersion = states.values.maxOfOrNull { it.appliedVersion }?.takeIf { it > 0 },
    )
}

/**
 * A pause keeps the foreground services (sensors, device monitoring) running for a background resume,
 * which Android 12+ could not start again
 * (https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start);
 * only a terminal status releases them.
 */
internal fun participationRetainsServices(status: ParticipationStatus?): Boolean =
    status == ParticipationStatus.ENROLLED || status == ParticipationStatus.PAUSED

private fun decideOwnerAdmission(owner: UploadServerEntity?, status: ParticipationStatus, available: Boolean): OwnerAdmission =
    when {
        owner == null -> OwnerAdmission.Refused.NO_OWNER
        !available -> OwnerAdmission.Refused.ENROLLMENT_UNAVAILABLE
        status == ParticipationStatus.ENROLLED -> OwnerAdmission.Admitted(owner)
        status == ParticipationStatus.PAUSED || status == ParticipationStatus.COLLECTION_COMPLETED -> OwnerAdmission.Stopped(owner, status)
        else -> OwnerAdmission.Refused.ENROLLMENT_UNAVAILABLE
    }
