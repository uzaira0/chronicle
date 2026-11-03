package com.openlattice.chronicle.collection.device

import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import java.lang.reflect.Proxy
import org.robolectric.util.ReflectionHelpers

internal fun healthConnectTestClient(
    permissions: () -> Set<String>,
    features: () -> HealthConnectFeatures = { healthConnectTestFeatures() },
    read: (ReadRecordsRequest<*>) -> List<Record> = { emptyList() },
): HealthConnectClient {
    val controller = Proxy.newProxyInstance(PermissionController::class.java.classLoader,
        arrayOf(PermissionController::class.java)) { _, _, _ -> permissions() }
    return Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader,
        arrayOf(HealthConnectClient::class.java)) { _, method, args ->
        when (method.name) {
            "getFeatures" -> features()
            "getPermissionController" -> controller
            "readRecords" -> ReflectionHelpers.callConstructor(ReadRecordsResponse::class.java,
                ReflectionHelpers.ClassParameter.from(List::class.java, read(args!![0] as ReadRecordsRequest<*>)),
                ReflectionHelpers.ClassParameter.from(String::class.java, null))
            else -> error("Unexpected provider method ${method.name}")
        }
    } as HealthConnectClient
}

internal fun healthConnectTestFeatures(backgroundAvailable: Boolean = true, skinAvailable: Boolean = false): HealthConnectFeatures =
    object : HealthConnectFeatures {
        override fun getFeatureStatus(feature: Int): Int {
            val backgroundRead = backgroundAvailable &&
                feature == HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            val skinTemperature = skinAvailable && feature == HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE
            return if (backgroundRead || skinTemperature)
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE else HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
        }
    }
