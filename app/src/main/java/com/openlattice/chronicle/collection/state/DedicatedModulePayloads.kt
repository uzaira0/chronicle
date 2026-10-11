package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.crypto.EncryptedPayloadType

internal val dedicatedModulePayloads = mapOf(
    CollectionModuleId.INTERACTION_EVENTS to EncryptedPayloadType.INTERACTION,
    CollectionModuleId.AUDIO_ACTIVITY to EncryptedPayloadType.AUDIO_ACTIVITY,
    CollectionModuleId.AUDIO_CONTENT to EncryptedPayloadType.AUDIO_CONTENT,
    CollectionModuleId.NOTIFICATION_ACTIVITY to EncryptedPayloadType.NOTIFICATION_ACTIVITY,
    CollectionModuleId.BATTERY_TELEMETRY to EncryptedPayloadType.BATTERY,
    CollectionModuleId.SLEEP to EncryptedPayloadType.SLEEP,
    CollectionModuleId.ACTIVITY_RECOGNITION to EncryptedPayloadType.ACTIVITY_RECOGNITION,
    CollectionModuleId.HEALTH_CONNECT to EncryptedPayloadType.HEALTH_CONNECT,
    CollectionModuleId.CONNECTIVITY_STATE to EncryptedPayloadType.CONNECTIVITY_STATE,
    CollectionModuleId.APP_NETWORK_USAGE to EncryptedPayloadType.APP_NETWORK_USAGE,
    CollectionModuleId.DEVICE_SETTINGS to EncryptedPayloadType.DEVICE_SETTINGS,
)
