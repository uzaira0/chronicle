package com.openlattice.chronicle.collection.core

import com.openlattice.chronicle.collection.CollectionModuleId

private const val TAG = "CollectionModuleRegistry"

/**
 * Registry mapping a [CollectionModuleId] to its [DataCollectionModule] (design §1C.3).
 *
 * The registry is the single lookup point for modules. It enforces the "module must be
 * registered" guardrail (design §1A.3, §1C.3):
 *  - **Reserved IDs are rejected.** Registering a module whose [CollectionModuleId.active]
 *    is `false` (`time_use_diary`, `questionnaire`, `app_inventory`) throws — reserved
 *    IDs freeze the namespace but have no implementation.
 *  - **Identity must match.** A module whose `id` differs from the key it is registered
 *    under is rejected.
 *  - **Duplicate registration is rejected.** Registering the same id twice throws,
 *    rather than silently overwriting.
 *  - **Unknown/unregistered lookup is explicit.** [require] throws for an unregistered
 *    id; [find] returns `null`. There is no silent miss.
 *
 * This is a plain class, not an `object` — it holds no Android `Context` and is
 * constructed where module wiring happens (Phases 4–8). Phase 3 introduces it with no
 * callsite switched.
 *
 * Not thread-safe for concurrent registration; register modules during single-threaded
 * bootstrap, then read.
 *
 */
public class CollectionModuleRegistry(
    private val log: CollectionLog = CollectionLog.LOGCAT,
) {
    private val modules = LinkedHashMap<CollectionModuleId, DataCollectionModule>()

    /** The ids of every currently registered module, in registration order. */
    public val registeredIds: Set<CollectionModuleId> get() = modules.keys.toSet()

    /** Number of registered modules. */
    public val size: Int get() = modules.size

    /**
     * Registers [module] under its own [DataCollectionModule.id].
     *
     * @throws IllegalArgumentException if the module's id is reserved/inactive, or the
     *   id is already registered.
     */
    public fun register(module: DataCollectionModule) {
        val moduleId = module.id
        require(moduleId.active) {
            "Cannot register module for reserved/inactive id '${moduleId.id}'; " +
                "reserved IDs freeze the namespace and have no implementation (design §1A.3)."
        }
        require(module.privacyClass == moduleId.privacyClass) {
            "Module '${moduleId.id}' privacyClass ${module.privacyClass} must match " +
                "id privacy class ${moduleId.privacyClass}."
        }
        require(!modules.containsKey(moduleId)) {
            "A module for id '${moduleId.id}' is already registered; duplicate registration is rejected."
        }
        modules[moduleId] = module
        log.info(TAG, "Registered collection module '${moduleId.id}'")
    }

    /** Registers every module in [toRegister]. */
    public fun registerAll(toRegister: Iterable<DataCollectionModule>) {
        toRegister.forEach(::register)
    }

    /** Whether a module is registered for [id]. */
    public fun isRegistered(id: CollectionModuleId): Boolean = modules.containsKey(id)

    /**
     * Returns the module registered for [id], or `null` if none is — an explicit miss,
     * never a silent default.
     */
    public fun find(id: CollectionModuleId): DataCollectionModule? = modules[id]

    /**
     * Returns the module registered for [id].
     *
     * @throws IllegalArgumentException if no module is registered for [id] (an
     *   unregistered or reserved id).
     */
    public fun require(id: CollectionModuleId): DataCollectionModule =
        modules[id] ?: throw IllegalArgumentException(
            "No collection module registered for id '${id.id}'."
        )

    /** Every registered module, in registration order. */
    public fun all(): List<DataCollectionModule> = modules.values.toList()
}
