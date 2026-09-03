package com.openlattice.chronicle.collection.upload

/**
 * In-memory fakes for JVM unit tests of [UploadTelemetryCollectionModule].
 *
 * [UploadStateProbe] and [UploadWorkProbe] are interfaces, so these fakes implement them
 * directly — no mocking framework is needed (and none is on the test classpath).
 *
 */

/**
 * In-memory [UploadStateProbe]. Holds a mutable list of [UploadServerTelemetry] plus the
 * three depth/count values. [failServers] / [failUsageDepth] / [failSensorDepth] /
 * [failStatsRows] force the respective read to throw, to exercise the module's
 * contained-failure path.
 */
class FakeUploadStateProbe(
    var servers: MutableList<UploadServerTelemetry> = mutableListOf(),
    var usageDepth: Int = 0,
    var sensorDepth: Int = 0,
    var statsRows: Int = 0,
) : UploadStateProbe {

    var failServers = false
    var failUsageDepth = false
    var failSensorDepth = false
    var failStatsRows = false

    override fun servers(): List<UploadServerTelemetry> {
        if (failServers) throw RuntimeException("simulated upload_servers read failure")
        return servers.toList()
    }

    override fun usageQueueDepth(): Int {
        if (failUsageDepth) throw RuntimeException("simulated dataQueue read failure")
        return usageDepth
    }

    override fun sensorQueueDepth(): Int {
        if (failSensorDepth) throw RuntimeException("simulated sensor_samples read failure")
        return sensorDepth
    }

    override fun uploadStatsRowCount(): Int {
        if (failStatsRows) throw RuntimeException("simulated upload_stats read failure")
        return statsRows
    }
}

/** In-memory [UploadWorkProbe]. Both work statuses are settable and default to `null`. */
class FakeUploadWorkProbe(
    var periodic: CombinedUploadWorkStatus? = null,
    var immediate: CombinedUploadWorkStatus? = null,
) : UploadWorkProbe {
    override fun periodicUploadStatus(): CombinedUploadWorkStatus? = periodic
    override fun immediateUploadStatus(): CombinedUploadWorkStatus? = immediate
}
