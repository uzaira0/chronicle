package com.openlattice.chronicle.storage

import java.time.Duration
import java.time.OffsetDateTime

enum class ServerHealthStatus {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    DISABLED,
    UNKNOWN
}

private const val UNHEALTHY_FAILURE_THRESHOLD = 10

/**
 * Derives health from combined usage + sensor + battery consecutive failure count.
 * Thresholds: 0 = HEALTHY, 1-[UNHEALTHY_FAILURE_THRESHOLD] = DEGRADED, above = UNHEALTHY.
 * Transport failures never disable enrollment; they remain visible until a later success resets
 * the per-family consecutive-failure counter.
 */
fun UploadServerEntity.healthStatus(): ServerHealthStatus {
    if (!enabled) return ServerHealthStatus.DISABLED
    if (lastUploadTime == null && lastSensorUploadTime == null && lastBatteryUploadTime == null) {
        return ServerHealthStatus.UNKNOWN
    }

    val totalFailures = consecutiveFailures + sensorConsecutiveFailures + batteryConsecutiveFailures
    if (totalFailures == 0) return ServerHealthStatus.HEALTHY
    if (totalFailures < UNHEALTHY_FAILURE_THRESHOLD) return ServerHealthStatus.DEGRADED
    return ServerHealthStatus.UNHEALTHY
}

fun ServerHealthStatus.statusDot(): String = when (this) {
    ServerHealthStatus.HEALTHY -> "\u2B24" // green (will be colored)
    ServerHealthStatus.DEGRADED -> "\u26A0" // warning
    ServerHealthStatus.UNHEALTHY -> "\u274C" // red X
    ServerHealthStatus.DISABLED -> "\u23F8" // pause
    ServerHealthStatus.UNKNOWN -> "\u2B58" // circle outline
}
