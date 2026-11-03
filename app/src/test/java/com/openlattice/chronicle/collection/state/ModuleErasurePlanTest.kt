package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each case asserts the [ModuleErasure] the coordinator runs when the participant declines a module,
 * so the local erase target is verified as pure JVM logic.
 */
class ModuleErasurePlanTest {

    // Declining battery_telemetry clears its dedicated battery_samples queue.
    @Test
    fun discardBatteryClearsBatterySamples() {
        assertEquals(
            ModuleErasure.ClearDedicated(ModuleQueue.BATTERY_SAMPLES),
            planModuleErasure(CollectionModuleId.BATTERY_TELEMETRY),
        )
    }

    // Declining a per-sensor module clears only that sensor's tagged rows.
    @Test
    fun discardSensorModuleClearsOnlyItsSensorSamples() {
        assertEquals(
            ModuleErasure.ClearSensor("accelerometer"),
            planModuleErasure(CollectionModuleId.SENSOR_ACCELEROMETER),
        )
        assertEquals(
            ModuleErasure.ClearSensor("light"),
            planModuleErasure(CollectionModuleId.SENSOR_LIGHT),
        )
    }

    // Declining user_identification clears its dedicated userQueue.
    @Test
    fun discardUserIdentificationClearsUserQueue() {
        assertEquals(
            ModuleErasure.ClearDedicated(ModuleQueue.USER_QUEUE),
            planModuleErasure(CollectionModuleId.USER_IDENTIFICATION),
        )
    }

    // shared rows are untagged, so privacy wins: discard the whole shared queue.
    @Test
    fun discardOnSharedQueueModulesClearsTheSharedQueue() {
        assertEquals(
            ModuleErasure.ClearDedicated(ModuleQueue.SHARED_DATA_QUEUE),
            planModuleErasure(CollectionModuleId.USAGE_EVENTS),
        )
        assertEquals(
            ModuleErasure.ClearDedicated(ModuleQueue.SHARED_DATA_QUEUE),
            planModuleErasure(CollectionModuleId.DEVICE_LIFECYCLE),
        )
        assertEquals(
            ModuleErasure.ClearDedicated(ModuleQueue.SHARED_DATA_QUEUE),
            planModuleErasure(CollectionModuleId.IN_APP_ACTIVITY_CLASS),
        )
    }

    @Test
    fun everyDedicatedExpansionModuleClearsItsOwnQueue() {
        val expected = mapOf(
            CollectionModuleId.INTERACTION_EVENTS to ModuleQueue.INTERACTION_SAMPLES,
            CollectionModuleId.AUDIO_ACTIVITY to ModuleQueue.AUDIO_ACTIVITY_SAMPLES,
            CollectionModuleId.AUDIO_CONTENT to ModuleQueue.AUDIO_CONTENT_SAMPLES,
            CollectionModuleId.NOTIFICATION_ACTIVITY to ModuleQueue.NOTIFICATION_ACTIVITY_SAMPLES,
            CollectionModuleId.SLEEP to ModuleQueue.SLEEP_SAMPLES,
            CollectionModuleId.ACTIVITY_RECOGNITION to ModuleQueue.ACTIVITY_RECOGNITION_SAMPLES,
            CollectionModuleId.HEALTH_CONNECT to ModuleQueue.HEALTH_METRIC_SAMPLES,
            CollectionModuleId.CONNECTIVITY_STATE to ModuleQueue.CONNECTIVITY_STATE_SAMPLES,
            CollectionModuleId.APP_NETWORK_USAGE to ModuleQueue.APP_NETWORK_USAGE_SAMPLES,
            CollectionModuleId.DEVICE_SETTINGS to ModuleQueue.DEVICE_SETTINGS_SAMPLES,
        )
        expected.forEach { (module, queue) ->
            assertEquals(
                "erasure for ${module.id}",
                ModuleErasure.ClearDedicated(queue),
                planModuleErasure(module),
            )
        }
    }

    @Test
    fun restrictedQueuesUseSchemaLevelCleanupWithoutRoomDaoAccess() {
        val expected = mapOf(
            ModuleQueue.INTERACTION_SAMPLES to "interaction_samples",
            ModuleQueue.AUDIO_ACTIVITY_SAMPLES to "audio_activity_samples",
            ModuleQueue.AUDIO_CONTENT_SAMPLES to "audio_content_samples",
            ModuleQueue.NOTIFICATION_ACTIVITY_SAMPLES to "notification_activity_samples",
            ModuleQueue.SLEEP_SAMPLES to "sleep_samples",
            ModuleQueue.ACTIVITY_RECOGNITION_SAMPLES to "activity_recognition_samples",
            ModuleQueue.HEALTH_METRIC_SAMPLES to "health_metric_samples",
        )
        expected.forEach { (queue, table) ->
            assertEquals(table, restrictedQueueTable(queue))
        }
    }

    // Declining a module with no dedicated queue is a no-op clear.
    @Test
    fun discardOnModuleWithoutDedicatedQueueIsNoop() {
        assertEquals(
            ModuleErasure.NoDedicatedQueue,
            planModuleErasure(CollectionModuleId.UPLOAD_TELEMETRY),
        )
    }
}
