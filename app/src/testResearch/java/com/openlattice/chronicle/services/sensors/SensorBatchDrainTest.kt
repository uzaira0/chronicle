package com.openlattice.chronicle.services.sensors

import com.openlattice.chronicle.storage.SensorSampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorBatchDrainTest {
    private data class Destination(
        val id: Long,
        val generation: Long = 0,
        val legacyCursor: String? = null,
    )

    private class Harness(
        samples: List<SensorSampleEntry>,
        val configuredDestinations: MutableList<Destination>,
    ) {
        val rows = linkedMapOf<String, SensorSampleEntry>().apply {
            samples.forEach { put(it.id, it) }
        }
        val receipts = mutableSetOf<Triple<String, Long, Long>>()
        val quarantinedIds = mutableListOf<String>()
        val deliveredBatches = mutableListOf<Pair<Long, List<String>>>()
        val deletedBatches = mutableListOf<List<String>>()

        fun drain(
            enabled: List<Destination>,
            deliver: (Destination, List<SensorSampleEntry>) -> Boolean = { destination, batch ->
                deliveredBatches += destination.id to batch.map { it.id }
                true
            },
            batchSize: Int = SENSOR_UPLOAD_BATCH_SIZE,
            mapEntry: (SensorSampleEntry) -> String = { it.id },
        ): SensorBatchDrainResult = drainSensorBatches(
            enabledDestinations = enabled,
            loadOldest = { limit ->
                rows.values.sortedWith(compareBy<SensorSampleEntry> { it.timestamp }.thenBy { it.id })
                    .take(limit)
            },
            isAcknowledged = { destination, ids ->
                ids.all { id -> Triple(id, destination.id, destination.generation) in receipts }
            },
            mapEntry = mapEntry,
            deliver = { destination, batch, _ -> deliver(destination, batch) },
            acknowledgeAndDelete = { destinations, ids ->
                destinations.forEach { destination ->
                    ids.forEach { id -> receipts += Triple(id, destination.id, destination.generation) }
                }
                val deletable = ids.filter { id ->
                    configuredDestinations.all { destination ->
                        Triple(id, destination.id, destination.generation) in receipts
                    }
                }
                deletedBatches += deletable
                deletable.forEach(rows::remove)
                deletable.size
            },
            quarantineMalformed = { malformed ->
                val ids = malformed.map { it.first.id }
                quarantinedIds += ids
                ids.forEach(rows::remove)
            },
            batchSize = batchSize,
        )
    }

    @Test
    fun `acceptance proof requires exact plaintext sample or encrypted envelope count`() {
        assertTrue(isCompleteSensorUploadAcceptance(encrypted = false, submittedSampleCount = 500, acceptedCount = 500))
        assertFalse(isCompleteSensorUploadAcceptance(encrypted = false, submittedSampleCount = 500, acceptedCount = 499))
        assertTrue(isCompleteSensorUploadAcceptance(encrypted = true, submittedSampleCount = 500, acceptedCount = 1))
        assertFalse(isCompleteSensorUploadAcceptance(encrypted = true, submittedSampleCount = 500, acceptedCount = 500))
        assertFalse(isCompleteSensorUploadAcceptance(encrypted = true, submittedSampleCount = 500, acceptedCount = 0))
    }

    @Test
    fun `backdated and equal timestamp samples are all drained by immutable ID batches`() {
        val destination = Destination(id = 1, legacyCursor = "2099-01-01T00:00:00Z")
        val samples = buildList {
            add(sample("backdated", "2000-01-01T00:00:00Z"))
            repeat(501) { index -> add(sample("equal-$index", "2026-07-14T12:00:00Z")) }
        }
        val harness = Harness(samples, mutableListOf(destination))

        val result = harness.drain(listOf(destination))

        assertEquals(0, result.failedDestinationCount)
        assertTrue(harness.rows.isEmpty())
        assertEquals(listOf(500, 2), harness.deliveredBatches.map { it.second.size })
        assertEquals(samples.map { it.id }.toSet(), harness.deliveredBatches.flatMap { it.second }.toSet())
    }

    @Test
    fun `partial two destination failure records no receipts and retry resends to both`() {
        val first = Destination(1)
        val second = Destination(2)
        val harness = Harness(listOf(sample("one"), sample("two")), mutableListOf(first, second))

        val firstResult = harness.drain(listOf(first, second), deliver = { destination, batch ->
            harness.deliveredBatches += destination.id to batch.map { it.id }
            destination != second
        })

        assertEquals(1, firstResult.failedDestinationCount)
        assertEquals(setOf(1L, 2L), harness.deliveredBatches.map { it.first }.toSet())
        assertTrue(harness.receipts.isEmpty())
        assertEquals(setOf("one", "two"), harness.rows.keys)

        val secondResult = harness.drain(listOf(first, second))

        assertEquals(0, secondResult.failedDestinationCount)
        assertEquals(2, harness.deliveredBatches.count { it.first == first.id })
        assertEquals(2, harness.deliveredBatches.count { it.first == second.id })
        assertTrue(harness.rows.isEmpty())
    }

    @Test
    fun `destination with null legacy cursor is attempted`() {
        val nullCursor = Destination(id = 7, legacyCursor = null)
        val harness = Harness(listOf(sample("one")), mutableListOf(nullCursor))

        harness.drain(listOf(nullCursor))

        assertEquals(listOf(7L), harness.deliveredBatches.map { it.first })
        assertTrue(harness.rows.isEmpty())
    }

    @Test
    fun `concurrent later insert is never covered by an earlier exact ID delete`() {
        val destination = Destination(1)
        val harness = Harness(
            listOf(sample("first", "2026-07-14T12:00:00Z")),
            mutableListOf(destination),
        )
        var inserted = false

        harness.drain(listOf(destination), deliver = { current, batch ->
            harness.deliveredBatches += current.id to batch.map { it.id }
            if (!inserted) {
                inserted = true
                harness.rows["late"] = sample("late", "2000-01-01T00:00:00Z")
            }
            true
        })

        assertEquals(listOf("first"), harness.deletedBatches.first())
        assertFalse("late" in harness.deletedBatches.first())
        assertEquals(listOf(listOf("first"), listOf("late")), harness.deliveredBatches.map { it.second })
        assertTrue(harness.rows.isEmpty())
    }

    @Test
    fun `disabled destination holds rows without repeatedly uploading acknowledged enabled destination`() {
        val active = Destination(1)
        val paused = Destination(2)
        val harness = Harness(listOf(sample("one")), mutableListOf(active, paused))

        val first = harness.drain(listOf(active))
        val second = harness.drain(listOf(active))

        assertTrue(first.heldForUnacknowledgedDestination)
        assertTrue(second.heldForUnacknowledgedDestination)
        assertEquals(1, harness.deliveredBatches.count { it.first == active.id })
        assertEquals(setOf("one"), harness.rows.keys)

        val resumed = harness.drain(listOf(active, paused))

        assertEquals(0, resumed.failedDestinationCount)
        assertEquals(1, harness.deliveredBatches.count { it.first == active.id })
        assertEquals(1, harness.deliveredBatches.count { it.first == paused.id })
        assertTrue(harness.rows.isEmpty())
    }

    @Test
    fun `new enrollment generation invalidates an old destination receipt`() {
        val original = Destination(1, generation = 0)
        val harness = Harness(listOf(sample("one")), mutableListOf(original, Destination(2)))
        harness.drain(listOf(original))
        assertEquals(1, harness.deliveredBatches.size)

        val reenrolled = original.copy(generation = 1)
        harness.configuredDestinations[0] = reenrolled
        harness.drain(listOf(reenrolled))

        assertEquals(2, harness.deliveredBatches.size)
        assertEquals(1L, harness.deliveredBatches.last().first)
        assertTrue(harness.rows.isNotEmpty())
    }

    @Test
    fun `zero enabled destinations retains without loading or deleting`() {
        var loaded = false
        var acknowledged = false

        val result = drainSensorBatches(
            enabledDestinations = emptyList<Long>(),
            loadOldest = {
                loaded = true
                listOf(sample("one"))
            },
            isAcknowledged = { _, _ -> false },
            mapEntry = { it.id },
            deliver = { _, _, _ -> true },
            acknowledgeAndDelete = { _, _ ->
                acknowledged = true
                1
            },
            quarantineMalformed = {},
        )

        assertEquals(0, result.failedDestinationCount)
        assertFalse(loaded)
        assertFalse(acknowledged)
    }

    @Test
    fun `malformed-only batch is counted and removed only after acknowledgement`() {
        val destination = Destination(1)
        val harness = Harness(listOf(sample("bad")), mutableListOf(destination))

        val result = harness.drain(listOf(destination), mapEntry = { error("malformed") })

        assertEquals(1, result.malformedSampleCount)
        assertTrue(harness.deliveredBatches.isEmpty())
        assertEquals(listOf("bad"), harness.quarantinedIds)
        assertTrue(harness.receipts.isEmpty())
        assertTrue(harness.rows.isEmpty())
    }

    private fun sample(
        id: String,
        timestamp: String = "2026-07-14T12:00:00Z",
    ) = SensorSampleEntry(
        id = id,
        sensorType = "accelerometer",
        timestamp = timestamp,
        timezone = "UTC",
        x = 1f,
        y = 2f,
        z = 3f,
        w = null,
        accuracy = 3,
    )
}
