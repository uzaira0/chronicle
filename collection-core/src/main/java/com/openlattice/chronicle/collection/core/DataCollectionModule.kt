package com.openlattice.chronicle.collection.core

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass

/**
 * The contract every Chronicle data collection module implements (design §1C.1).
 *
 * A module owns one collection responsibility (usage events, device lifecycle, hardware
 * sensors, etc.). It declares a stable [id] and the [privacyClass] of its output, and
 * exposes a uniform lifecycle (`start`/`stop`/`poll`/`flush`) plus introspection
 * (`status`/`diagnostics`).
 *
 * Design rules implemented by every conforming module:
 *  - **Declares identity.** [id] and [privacyClass] are required (design §1A.6, guardrail #1).
 *    [privacyClass] must equal [id]`.privacyClass`.
 *  - **No `Context` in singletons.** A module must not hold an Android [Context] in an
 *    `object`/singleton field; the [Context] is passed per call (design §1C, refactor
 *    plan §6.1 guardrail 2). Modules are therefore plain classes, constructed per use.
 *  - **Disabled is a no-op, not an exception.** A disabled module returns
 *    [ModuleResult.Skipped] for every operation and writes nothing — see
 *    [DisabledCollectionModule].
 *  - **Explicit results.** Every operation returns a [ModuleResult]; a persistent
 *    failure surfaces as [ModuleResult.Failed], never a swallowed exception.
 *
 * Phase 3 is purely additive: this interface is introduced with no callsite switched.
 * Existing collection code keeps running unchanged; Phases 4–8 wire modules in.
 *
 */
public interface DataCollectionModule {

    /** Stable identifier of this module (design §1A.1). Must reference [CollectionModuleId]. */
    public val id: CollectionModuleId

    /**
     * Privacy classification of this module's output (design §1A.4). Must equal
     * [id]`.privacyClass`; conforming implementations enforce this in their `init`.
     */
    public val privacyClass: CollectionPrivacyClass

    /** Current lifecycle status of the module. */
    public fun status(): CollectionModuleStatus

    /** Operational telemetry for the module — redaction-safe (design §1B.3). */
    public fun diagnostics(): CollectionModuleDiagnostics

    /**
     * Starts a push-style module (a sensor runtime or foreground service).
     * No-op for pull modules — they return [ModuleResult.Skipped].
     */
    public fun start(context: Context): ModuleResult

    /** Stops a push-style module. No-op for pull modules. */
    public fun stop(context: Context): ModuleResult

    /**
     * Runs one pull-style collection pass over [window] (usage/lifecycle modules).
     * No-op for push modules — they return [ModuleResult.Skipped].
     */
    public fun poll(context: Context, window: CollectionWindow): ModuleResult

    /** Flushes any buffered data the module holds to persistence. */
    public fun flush(context: Context): ModuleResult
}
