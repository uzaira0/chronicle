package com.openlattice.chronicle.collection.core

/**
 * Lifecycle status of a [DataCollectionModule] (design §1C.1).
 *
 *  - [DISABLED] — the module is turned off by settings; every call returns
 *                 [ModuleResult.Skipped] and the module writes nothing.
 *  - [IDLE]     — the module is enabled but not currently running work.
 *  - [ACTIVE]   — the module is enabled and actively collecting.
 *  - [DEGRADED] — the module is running with reduced capability (e.g. power-save).
 *  - [FAILED]   — the module's last operation failed persistently.
 *
 */
public enum class CollectionModuleStatus {
    DISABLED,
    IDLE,
    ACTIVE,
    DEGRADED,
    FAILED,
}
