package com.openlattice.chronicle.debug

import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.serialization.JsonSerializer
import java.net.URI

internal data class DebugDestinationAssertion(
    val expectedTotalServers: Int,
    val expectedEnabledServers: Int,
    val actualTotalServers: Int,
    val actualEnabledServers: Int,
    val distinctHosts: Int,
    val passed: Boolean,
)

internal object DebugAutomationStateFormatter {
    fun stateJson(
        operation: String,
        servers: List<UploadServerEntity>,
        queueDepths: Map<String, Int>,
        assertion: DebugDestinationAssertion? = null,
        triggered: Map<String, Boolean> = emptyMap(),
    ): String {
        val root: MutableMap<String, Any> = linkedMapOf(
            "operation" to operation,
            "serverCount" to servers.size,
            "enabledServerCount" to servers.count { it.enabled },
            "servers" to redactedServers(servers),
            "queueDepths" to queueDepths,
        )

        if (assertion != null) {
            root["assertion"] = linkedMapOf(
                "expectedTotalServers" to assertion.expectedTotalServers,
                "expectedEnabledServers" to assertion.expectedEnabledServers,
                "actualTotalServers" to assertion.actualTotalServers,
                "actualEnabledServers" to assertion.actualEnabledServers,
                "distinctHosts" to assertion.distinctHosts,
                "passed" to assertion.passed,
            )
        }

        if (triggered.isNotEmpty()) {
            root["triggered"] = triggered
        }

        return JsonSerializer.toJson(root)
    }

    private fun redactedServers(servers: List<UploadServerEntity>): List<Map<String, Any>> =
        servers.map { server ->
            linkedMapOf(
                "id" to server.id,
                "host" to hostOnly(server.url),
                "enabled" to server.enabled,
                "authMode" to server.authMode,
                "hasApiKey" to !server.apiKey.isNullOrBlank(),
                "hasMobileSigningSecretOverride" to !server.mobileSigningSecretOverride.isNullOrBlank(),
                "usageUploadSuccessCount" to server.usageUploadSuccessCount,
                "usageUploadFailureCount" to server.usageUploadFailureCount,
                "sensorUploadSuccessCount" to server.sensorUploadSuccessCount,
                "sensorUploadFailureCount" to server.sensorUploadFailureCount,
                "batteryUploadSuccessCount" to server.batteryUploadSuccessCount,
                "batteryUploadFailureCount" to server.batteryUploadFailureCount,
                "lastUploadedTimestamp" to server.lastUploadedTimestamp,
                "lastUploadedQueueId" to server.lastUploadedQueueId,
                "hasSensorCursor" to !server.lastUploadedSensorId.isNullOrBlank(),
            )
        }

    internal fun hostOnly(url: String): String =
        runCatching { URI(url).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "invalid"
}
