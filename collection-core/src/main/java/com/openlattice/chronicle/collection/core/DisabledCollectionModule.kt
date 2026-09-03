package com.openlattice.chronicle.collection.core

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId

/**
 * No-op base for a disabled [DataCollectionModule] (design §1C.1, refactor plan §6.1 step 7).
 *
 * "A disabled module returns [ModuleResult.Skipped] for every call and writes nothing —
 * no-op, not an exception." This class is the canonical implementation of that rule:
 *  - [status] is always [CollectionModuleStatus.DISABLED];
 *  - `start`/`stop`/`poll`/`flush` always return [ModuleResult.Skipped];
 *  - [diagnostics] reports a clean, never-run, redaction-safe snapshot.
 *
 * The settings resolver returns a [DisabledCollectionModule] (directly or via a
 * subclass) whenever a module is disabled by settings, is privacy-sensitive and not
 * explicitly opted in, or has a malformed setting (design §1B.4). It holds no Android
 * [Context].
 *
 */
public open class DisabledCollectionModule(
    final override val id: CollectionModuleId,
    /** Human-readable, redaction-safe reason the module is disabled. */
    public val reason: String = "module disabled by settings",
) : DataCollectionModule {

    final override val privacyClass = id.privacyClass

    private val skipped = ModuleResult.Skipped(reason)

    override fun status(): CollectionModuleStatus = CollectionModuleStatus.DISABLED

    override fun diagnostics(): CollectionModuleDiagnostics = CollectionModuleDiagnostics(
        moduleId = id,
        privacyClass = privacyClass,
        lastRunEpochMs = null,
        lastResult = skipped.label,
        itemsCollected = 0,
        queueDepth = 0,
        lastError = null,
        redactedParticipantRef = null,
        notTracked = emptySet(),
    )

    override fun start(context: Context): ModuleResult = skipped

    override fun stop(context: Context): ModuleResult = skipped

    override fun poll(context: Context, window: CollectionWindow): ModuleResult = skipped

    override fun flush(context: Context): ModuleResult = skipped
}
