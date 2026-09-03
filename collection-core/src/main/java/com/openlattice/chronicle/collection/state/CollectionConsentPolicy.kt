package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionModuleSetting

/**
 * Stable snapshot of the collection policy a participant accepted for one module.
 *
 * Enablement and requiredness are persisted separately on [CollectionModuleState], and
 * disable disposition applies only after collection has stopped. Supported policy fields are
 * included here; the Android boundary rejects legacy values it cannot enforce before a participant
 * can consent. Sets are sorted so the snapshot remains stable across processes and JSON ordering
 * differences.
 */
public fun CollectionModuleSetting.consentPolicySnapshot(): String = buildString {
    append("collection=").append(collectionCadence.intervalSeconds).append(':')
        .append(collectionCadence.jitterSeconds)
    append("|upload=").append(uploadCadence.intervalSeconds).append(':')
        .append(uploadCadence.jitterSeconds)
    append("|battery=").append(batteryPolicy.minLevelPercent).append(':')
        .append(batteryPolicy.stopBelowCriticalPercent).append(':')
        .append(batteryPolicy.degradeInPowerSave)
    append("|network=").append(networkPolicy.requireUnmetered).append(':')
        .append(networkPolicy.requireConnected)
    append("|sensor=")
    sensorPolicy?.let { policy ->
        append(policy.sensors.map { it.name }.sorted().joinToString(","))
        append(':').append(policy.samplingRateHz)
        append(':').append(policy.dutyCycleActiveSeconds)
        append(':').append(policy.dutyCyclePeriodSeconds)
    } ?: append("null")
    append("|interaction=")
    interactionPolicy?.let { policy ->
        append(policy.gridRows).append(':').append(policy.gridCols).append(':')
        append(policy.captureClicks).append(':').append(policy.captureScrolls).append(':')
        append(policy.captureExactPosition).append(':').append(policy.captureElementPosition)
    } ?: append("null")
    append("|health=")
    append(healthConnectRecordTypes.map { it.id }.sorted().joinToString(","))
}
