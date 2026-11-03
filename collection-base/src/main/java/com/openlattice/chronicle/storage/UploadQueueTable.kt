package com.openlattice.chronicle.storage

/** Physical upload queues and the local archives. [uploaded] queues each feed one upload stream. */
enum class UploadQueueTable(val tableName: String, val growableColumns: List<String>, val uploaded: Boolean = true) {
    USAGE("dataQueue", listOf("data")),
    SENSOR("sensor_samples", listOf("id", "sensorType", "timestamp", "timezone", "valuesJson")),
    BATTERY("battery_samples", listOf("id", "timestamp", "timezone", "chargingState", "plugType", "health", "producerVersion")),
    INTERACTION("interaction_samples", listOf("id", "timestamp", "timezone", "eventType",
        "elementRole", "foregroundPackage", "positionSource", "episodeId")),
    AUDIO_ACTIVITY("audio_activity_samples", listOf("id", "timestamp", "timezone",
        "eventType", "audioPackage", "contentType", "playbackState", "outputRoute", "ringerMode")),
    AUDIO_CONTENT("audio_content_samples", listOf("id", "timestamp", "timezone",
        "audioPackage", "title", "artist", "album")),
    NOTIFICATION_ACTIVITY("notification_activity_samples", listOf("id",
        "timestamp", "timezone", "eventType", "packageName", "category")),
    SLEEP("sleep_samples", listOf("id", "timestamp", "timezone", "eventType", "segmentStatus")),
    ACTIVITY_RECOGNITION("activity_recognition_samples", listOf("id", "timestamp",
        "timezone", "activityType", "transitionType")),
    HEALTH_CONNECT("health_metric_samples", listOf("id", "timestamp", "timezone",
        "metricType", "unit", "sourcePackage")),
    CONNECTIVITY_STATE("connectivity_state_samples", listOf("id", "timestamp",
        "timezone", "eventType", "transport")),
    APP_NETWORK_USAGE("app_network_usage_samples", listOf("id", "timestamp",
        "timezone", "packageName", "networkType")),
    DEVICE_SETTINGS("device_settings_samples", listOf("id", "timestamp", "timezone",
        "thermalStatus", "ringerMode")),
    DIAGNOSTICS("upload_diagnostics", listOf("id", "studyId", "participantId", "randomDeviceId", "enrollmentEpoch", "day",
        "moduleFamily", "issueCode", "firstOccurredAt", "lastOccurredAt", "errorType", "deliveryState"), uploaded = false),
    QUARANTINE("local_data_quarantine", listOf("id", "sourceTable", "sourceId", "studyId", "participantId", "randomDeviceId",
        "rawData", "reason", "createdAt", "appVersion"), uploaded = false),
}

// 1 MiB leaves row/column headroom below AOSP config_cursorWindowSize (2048 KiB) and the HTTP body budget.
const val MAX_PERSISTED_QUEUE_PAYLOAD_BYTES = 1024 * 1024
