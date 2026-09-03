package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.HealthMetricType

/** One normalized Health Connect record ready for Chronicle persistence. */
public data class HealthMetricReading(
    public val metricType: HealthMetricType,
    public val value: Double,
    public val unit: String,
    public val startMillis: Long,
    public val endMillis: Long,
    public val sourcePackage: String?,
    /** Health Connect record identity used only to make Chronicle retries idempotent; never uploaded. */
    public val sourceRecordId: String? = null,
)

/** Distribution-neutral boundary for optional Health Connect support. */
public fun interface HealthMetricSource {
    public fun read(): List<HealthMetricReading>

    /** Called only after every returned reading has been durably queued. */
    public fun acknowledgeRead() {}

    /** Called when queuing fails so the same Health Connect window remains retryable. */
    public fun rejectRead() {}
}
