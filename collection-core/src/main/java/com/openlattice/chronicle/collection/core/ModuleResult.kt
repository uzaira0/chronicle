package com.openlattice.chronicle.collection.core

/**
 * Outcome of a single [DataCollectionModule] or [com.openlattice.chronicle.collection.sink.CollectionSink]
 * operation (design §1C.1).
 *
 * Every collection operation returns an explicit [ModuleResult] — a persistent failure
 * is **never** silently swallowed (design §1C.2). Callers branch on the sealed type;
 * there is no exception-as-control-flow path through the core.
 *
 *  - [Ok]      — the operation succeeded; [Ok.items] reports how many items it handled
 *                (`0` is a valid success, e.g. an idempotent empty write).
 *  - [Skipped] — the operation did no work intentionally: the module is disabled or
 *                not configured. A disabled module returns [Skipped] for every call.
 *  - [Retry]   — the operation failed transiently and should be retried later.
 *  - [Failed]  — the operation failed persistently; [Failed.error] carries the cause.
 *
 */
public sealed class ModuleResult {

    /** A short, redaction-safe label for diagnostics ([CollectionModuleDiagnostics.lastResult]). */
    public abstract val label: String

    /** Whether this result represents a successful operation. */
    public val isSuccess: Boolean get() = this is Ok

    /** Successful operation handling [items] items (`0` is a valid idempotent no-op). */
    public data class Ok(val items: Int = 0) : ModuleResult() {
        init {
            require(items >= 0) { "ModuleResult.Ok.items must be non-negative: $items" }
        }

        override val label: String get() = "OK"
    }

    /** No work done intentionally — module disabled or not configured. */
    public data class Skipped(val reason: String) : ModuleResult() {
        override val label: String get() = "SKIPPED"
    }

    /** Transient failure; the operation should be retried later. */
    public data class Retry(val reason: String) : ModuleResult() {
        override val label: String get() = "RETRY"
    }

    /**
     * Persistent failure. [error] is the underlying cause; [redactedMessage] is a
     * diagnostics-safe message (never a raw request body, key, or participant id).
     */
    public data class Failed(
        val error: Throwable,
        val redactedMessage: String = error.message ?: error.javaClass.simpleName,
    ) : ModuleResult() {
        override val label: String get() = "FAILED"
    }
}
