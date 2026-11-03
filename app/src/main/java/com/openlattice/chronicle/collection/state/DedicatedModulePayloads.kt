package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.storage.UploadQueueTable

internal val dedicatedModuleTables = mapOf(
    CollectionModuleId.INTERACTION_EVENTS to UploadQueueTable.INTERACTION,
    CollectionModuleId.AUDIO_ACTIVITY to UploadQueueTable.AUDIO_ACTIVITY,
    CollectionModuleId.AUDIO_CONTENT to UploadQueueTable.AUDIO_CONTENT,
    CollectionModuleId.NOTIFICATION_ACTIVITY to UploadQueueTable.NOTIFICATION_ACTIVITY,
    CollectionModuleId.BATTERY_TELEMETRY to UploadQueueTable.BATTERY,
    CollectionModuleId.SLEEP to UploadQueueTable.SLEEP,
    CollectionModuleId.ACTIVITY_RECOGNITION to UploadQueueTable.ACTIVITY_RECOGNITION,
    CollectionModuleId.HEALTH_CONNECT to UploadQueueTable.HEALTH_CONNECT,
    CollectionModuleId.CONNECTIVITY_STATE to UploadQueueTable.CONNECTIVITY_STATE,
    CollectionModuleId.APP_NETWORK_USAGE to UploadQueueTable.APP_NETWORK_USAGE,
    CollectionModuleId.DEVICE_SETTINGS to UploadQueueTable.DEVICE_SETTINGS,
)
