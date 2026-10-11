package com.openlattice.chronicle.collection.device

import androidx.health.connect.client.HealthConnectFeatures

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
