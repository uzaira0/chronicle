package com.openlattice.chronicle.storage

import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePayloadBytesTest {
    private class Counter<T : Any>(val table: UploadQueueTable, val type: KClass<T>, val count: (T) -> Long) {
        fun verify() {
            val constructor = checkNotNull(type.primaryConstructor)
            val payload = "é".repeat(17)
            for (column in table.growableColumns) {
                val values = constructor.parameters.associateWith { parameter ->
                    when (val classifier = parameter.type.classifier) {
                        String::class -> if (parameter.name == column) payload else ""
                        Int::class -> 0
                        Long::class -> 0L
                        Float::class -> 0f
                        Double::class -> 0.0
                        Boolean::class -> false
                        // A TEXT enum column is stored by name; it has a fixed set of values, not a payload.
                        else -> (classifier as? KClass<*>)?.java?.enumConstants?.last()
                            ?: error("Unhandled entity field: $parameter")
                    }
                }
                val row = constructor.callBy(values)
                val expected = values.entries.filter { it.key.name in table.growableColumns }
                    .sumOf { (_, value) -> ((value as? Enum<*>)?.name ?: value as? String).orEmpty().toByteArray().size.toLong() }
                assertEquals("${table.tableName}.$column", expected, count(row))
            }
        }
    }

    @Test fun everyGrowableEntityColumnContributesToPayloadAdmission() {
        val counters = listOf(
            Counter(UploadQueueTable.SENSOR, SensorSampleEntry::class, SensorSampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.BATTERY, BatterySampleEntry::class, BatterySampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.INTERACTION, InteractionSampleEntry::class, InteractionSampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.AUDIO_ACTIVITY, AudioActivitySampleEntry::class, AudioActivitySampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.AUDIO_CONTENT, AudioContentSampleEntry::class, AudioContentSampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.NOTIFICATION_ACTIVITY, NotificationActivitySampleEntry::class,
                NotificationActivitySampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.SLEEP, SleepSampleEntry::class, SleepSampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.ACTIVITY_RECOGNITION, ActivityRecognitionSampleEntry::class,
                ActivityRecognitionSampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.HEALTH_CONNECT, HealthMetricSampleEntry::class, HealthMetricSampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.CONNECTIVITY_STATE, ConnectivityStateSampleEntry::class, ConnectivityStateSampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.APP_NETWORK_USAGE, AppNetworkUsageSampleEntry::class, AppNetworkUsageSampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.DEVICE_SETTINGS, DeviceSettingsSampleEntry::class, DeviceSettingsSampleEntry::persistedPayloadBytes),
            Counter(UploadQueueTable.DIAGNOSTICS, UploadDiagnosticEntity::class, UploadDiagnosticEntity::persistedPayloadBytes),
        )
        // Usage BLOBs split in StorageQueue; quarantine BLOBs use incremental chunk reads.
        assertEquals(UploadQueueTable.entries.toSet() - setOf(UploadQueueTable.USAGE, UploadQueueTable.QUARANTINE),
            counters.mapTo(mutableSetOf()) { it.table })
        counters.forEach { it.verify() }
    }
}
