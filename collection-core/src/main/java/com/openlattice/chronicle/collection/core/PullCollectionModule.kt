package com.openlattice.chronicle.collection.core

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId

/** Shared lifecycle and atomic diagnostics for modules that persist each pull immediately. */
public abstract class PullCollectionModule(
    final override val id: CollectionModuleId,
    private val clock: CollectionClock,
    private val queueDepth: () -> Int,
) : DataCollectionModule {
    final override val privacyClass get() = id.privacyClass

    private data class SampleState(val lastRunEpochMs: Long?, val result: ModuleResult)

    @Volatile
    private var state = SampleState(null, ModuleResult.Skipped("not yet run"))

    public open fun sample(): ModuleResult {
        val now = clock.nowEpochMs()
        val result = runSample(now)
        state = SampleState(now, result)
        return result
    }

    protected abstract fun runSample(now: Long): ModuleResult

    final override fun status(): CollectionModuleStatus = state.result.collectionStatus

    final override fun diagnostics(): CollectionModuleDiagnostics {
        val snapshot = state
        return CollectionModuleDiagnostics(
            moduleId = id,
            privacyClass = privacyClass,
            lastRunEpochMs = snapshot.lastRunEpochMs,
            lastResult = snapshot.result.label,
            itemsCollected = (snapshot.result as? ModuleResult.Ok)?.items ?: 0,
            queueDepth = runCatching(queueDepth).getOrDefault(-1),
            lastError = (snapshot.result as? ModuleResult.Failed)?.redactedMessage,
            redactedParticipantRef = null,
        )
    }

    final override fun poll(context: Context, window: CollectionWindow): ModuleResult = sample()
    final override fun start(context: Context): ModuleResult = ModuleResult.Skipped("${id.id} is pull-style")
    final override fun stop(context: Context): ModuleResult = ModuleResult.Skipped("${id.id} is pull-style")
    final override fun flush(context: Context): ModuleResult = ModuleResult.Skipped("${id.id} buffers nothing")
}
