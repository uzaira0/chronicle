package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the disable-disposition matrix (collection loop closure design §5.5, plan Group C) as pure
 * JVM logic — no Android, no device. Each case asserts the [DispositionAction] the coordinator will
 * execute for a (module, disposition) pair, so the policy is verified deterministically instead of
 * being driven by hand on hardware.
 */
class CollectionDispositionPlanTest {

    // C1 — FLUSH_THEN_STOP ships pending rows then stops, for every module (default disposition).
    @Test
    fun flushThenStopAlwaysFlushes() {
        CollectionModuleId.entries.forEach { module ->
            assertEquals(
                "FLUSH_THEN_STOP for ${module.id} must flush",
                DispositionAction.Flush,
                planDisposition(module, CollectionDataDisposition.FLUSH_THEN_STOP),
            )
        }
    }

    // C6 — HOLD_PENDING retains the queue (gate stops collection), for every module.
    @Test
    fun holdPendingAlwaysRetains() {
        CollectionModuleId.entries.forEach { module ->
            assertEquals(
                "HOLD_PENDING for ${module.id} must retain",
                DispositionAction.Retain,
                planDisposition(module, CollectionDataDisposition.HOLD_PENDING),
            )
        }
    }

    // C2 — DISCARD_AND_STOP on battery_telemetry clears its dedicated battery_samples queue.
    @Test
    fun discardBatteryClearsBatterySamples() {
        assertEquals(
            DispositionAction.ClearDedicated(DispositionQueue.BATTERY_SAMPLES),
            planDisposition(CollectionModuleId.BATTERY_TELEMETRY, CollectionDataDisposition.DISCARD_AND_STOP),
        )
    }

    // C3 — DISCARD_AND_STOP on a per-sensor module clears only that sensor's tagged rows.
    @Test
    fun discardSensorModuleClearsOnlyItsSensorSamples() {
        assertEquals(
            DispositionAction.ClearSensor("accelerometer"),
            planDisposition(CollectionModuleId.SENSOR_ACCELEROMETER, CollectionDataDisposition.DISCARD_AND_STOP),
        )
        assertEquals(
            DispositionAction.ClearSensor("light"),
            planDisposition(CollectionModuleId.SENSOR_LIGHT, CollectionDataDisposition.DISCARD_AND_STOP),
        )
    }

    // C4 — DISCARD_AND_STOP on user_identification clears its dedicated userQueue.
    @Test
    fun discardUserIdentificationClearsUserQueue() {
        assertEquals(
            DispositionAction.ClearDedicated(DispositionQueue.USER_QUEUE),
            planDisposition(CollectionModuleId.USER_IDENTIFICATION, CollectionDataDisposition.DISCARD_AND_STOP),
        )
    }

    // C5 — shared rows are untagged, so privacy wins: discard the whole shared queue.
    @Test
    fun discardOnSharedQueueModulesClearsTheSharedQueue() {
        assertEquals(
            DispositionAction.ClearDedicated(DispositionQueue.SHARED_DATA_QUEUE),
            planDisposition(CollectionModuleId.USAGE_EVENTS, CollectionDataDisposition.DISCARD_AND_STOP),
        )
        assertEquals(
            DispositionAction.ClearDedicated(DispositionQueue.SHARED_DATA_QUEUE),
            planDisposition(CollectionModuleId.DEVICE_LIFECYCLE, CollectionDataDisposition.DISCARD_AND_STOP),
        )
        assertEquals(
            DispositionAction.ClearDedicated(DispositionQueue.SHARED_DATA_QUEUE),
            planDisposition(CollectionModuleId.IN_APP_ACTIVITY_CLASS, CollectionDataDisposition.DISCARD_AND_STOP),
        )
    }

    @Test
    fun everyDedicatedExpansionModuleClearsItsOwnQueue() {
        val expected = mapOf(
            CollectionModuleId.INTERACTION_EVENTS to DispositionQueue.INTERACTION_SAMPLES,
            CollectionModuleId.AUDIO_ACTIVITY to DispositionQueue.AUDIO_ACTIVITY_SAMPLES,
            CollectionModuleId.AUDIO_CONTENT to DispositionQueue.AUDIO_CONTENT_SAMPLES,
            CollectionModuleId.NOTIFICATION_ACTIVITY to DispositionQueue.NOTIFICATION_ACTIVITY_SAMPLES,
            CollectionModuleId.SLEEP to DispositionQueue.SLEEP_SAMPLES,
            CollectionModuleId.ACTIVITY_RECOGNITION to DispositionQueue.ACTIVITY_RECOGNITION_SAMPLES,
            CollectionModuleId.HEALTH_CONNECT to DispositionQueue.HEALTH_METRIC_SAMPLES,
            CollectionModuleId.CONNECTIVITY_STATE to DispositionQueue.CONNECTIVITY_STATE_SAMPLES,
            CollectionModuleId.APP_NETWORK_USAGE to DispositionQueue.APP_NETWORK_USAGE_SAMPLES,
            CollectionModuleId.DEVICE_SETTINGS to DispositionQueue.DEVICE_SETTINGS_SAMPLES,
        )
        expected.forEach { (module, queue) ->
            assertEquals(
                "DISCARD_AND_STOP for ${module.id}",
                DispositionAction.ClearDedicated(queue),
                planDisposition(module, CollectionDataDisposition.DISCARD_AND_STOP),
            )
        }
    }

    @Test
    fun restrictedQueuesUseSchemaLevelLegacyCleanupWithoutRoomDaoAccess() {
        val expected = mapOf(
            DispositionQueue.INTERACTION_SAMPLES to "interaction_samples",
            DispositionQueue.AUDIO_ACTIVITY_SAMPLES to "audio_activity_samples",
            DispositionQueue.AUDIO_CONTENT_SAMPLES to "audio_content_samples",
            DispositionQueue.NOTIFICATION_ACTIVITY_SAMPLES to "notification_activity_samples",
            DispositionQueue.SLEEP_SAMPLES to "sleep_samples",
            DispositionQueue.ACTIVITY_RECOGNITION_SAMPLES to "activity_recognition_samples",
            DispositionQueue.HEALTH_METRIC_SAMPLES to "health_metric_samples",
        )
        expected.forEach { (queue, table) ->
            assertEquals(table, restrictedQueueTable(queue))
        }
    }

    // DISCARD_AND_STOP on a module with no dedicated queue is a no-op clear.
    @Test
    fun discardOnModuleWithoutDedicatedQueueIsNoop() {
        assertEquals(
            DispositionAction.NoDedicatedQueue,
            planDisposition(CollectionModuleId.UPLOAD_TELEMETRY, CollectionDataDisposition.DISCARD_AND_STOP),
        )
    }
}
