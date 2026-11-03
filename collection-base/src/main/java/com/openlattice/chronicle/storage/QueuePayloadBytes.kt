package com.openlattice.chronicle.storage

import okio.utf8Size

// Room TEXT and BLOB columns have no schema length constraint. Numeric columns are fixed width.
private fun encodedBytes(vararg values: String?): Long = values.sumOf { it?.utf8Size() ?: 0L }

internal fun SensorSampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, sensorType, timestamp, timezone, valuesJson)

internal fun BatterySampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, chargingState, plugType, health, producerVersion)

internal fun InteractionSampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, eventType, elementRole, foregroundPackage, positionSource, episodeId)

internal fun AudioActivitySampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, eventType, audioPackage, contentType, playbackState, outputRoute, ringerMode)

internal fun AudioContentSampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, audioPackage, title, artist, album)

internal fun NotificationActivitySampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, eventType, packageName, category)

internal fun SleepSampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, eventType, segmentStatus)

internal fun ActivityRecognitionSampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, activityType, transitionType)

internal fun HealthMetricSampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, metricType, unit, sourcePackage)

internal fun ConnectivityStateSampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, eventType, transport)

internal fun AppNetworkUsageSampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, packageName, networkType)

internal fun DeviceSettingsSampleEntry.persistedPayloadBytes(): Long =
    encodedBytes(id, timestamp, timezone, thermalStatus, ringerMode)

internal fun UploadDiagnosticEntity.persistedPayloadBytes(): Long =
    encodedBytes(id, studyId, participantId, randomDeviceId, enrollmentEpoch, day, moduleFamily, issueCode, firstOccurredAt,
        lastOccurredAt, errorType, deliveryState.name)
