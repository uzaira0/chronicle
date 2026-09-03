package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.SensorSampleDao
import com.openlattice.chronicle.storage.SensorSampleDeliveryEntity
import com.openlattice.chronicle.storage.SensorSampleDeadLetterEntity
import com.openlattice.chronicle.storage.SensorSampleTypeCount
import com.openlattice.chronicle.storage.StorageQueue
import com.openlattice.chronicle.storage.UploadStatsDao
import com.openlattice.chronicle.storage.UploadStatsEntity

/**
 * In-memory fakes of the Room DAOs for JVM unit tests of the Phase 3 sinks.
 *
 * The DAOs are interfaces, so these fakes implement them directly — no mocking
 * framework is needed (and none is on the test classpath). Each fake reproduces the
 * behaviour the sink depends on, including the conflict semantics that differ between
 * tables (`dataQueue` composite PK throws on duplicates; `sensor_samples` ignores them).
 */

/**
 * Fake [StorageQueue]. Mirrors `dataQueue`'s composite primary key `(writeTimestamp, id)`
 * — `insertEntries`/`insertEntry` throw on a duplicate key, matching the real DAO which
 * declares no `OnConflictStrategy`. [failNextInsert] forces the next insert to throw, to
 * exercise the sink's failure path.
 */
class FakeStorageQueue : StorageQueue {
    val rows = LinkedHashMap<Pair<Long, Long>, QueueEntry>()
    var failNextInsert = false

    override fun insertEntries(entries: List<QueueEntry>) {
        if (failNextInsert) {
            failNextInsert = false
            throw RuntimeException("simulated dataQueue write failure")
        }
        for (entry in entries) {
            val key = entry.writeTimestamp to entry.id
            if (rows.containsKey(key)) {
                throw IllegalStateException("duplicate primary key (writeTimestamp, id)=$key")
            }
            rows[key] = entry
        }
    }

    override fun insertEntry(entry: QueueEntry) = insertEntries(listOf(entry))

    override fun getNextEntries(size: Int): List<QueueEntry> =
        rows.values.sortedBy { it.writeTimestamp }.take(size)

    override fun getSize(): Int = rows.size

    override fun deleteEntry(entry: QueueEntry) {
        rows.remove(entry.writeTimestamp to entry.id)
    }

    override fun deleteEntries(entries: List<QueueEntry>) {
        entries.forEach { deleteEntry(it) }
    }

    override fun getEntriesAfter(cursor: Long, limit: Int): List<QueueEntry> =
        rows.values.filter { it.writeTimestamp > cursor }.sortedBy { it.writeTimestamp }.take(limit)

    override fun getEntriesAfter(cursorTimestamp: Long, cursorId: Long, limit: Int): List<QueueEntry> =
        rows.values
            .filter { it.writeTimestamp > cursorTimestamp || (it.writeTimestamp == cursorTimestamp && it.id > cursorId) }
            .sortedWith(compareBy({ it.writeTimestamp }, { it.id }))
            .take(limit)

    override fun deleteEntriesBefore(maxTimestamp: Long) {
        rows.entries.removeIf { it.value.writeTimestamp <= maxTimestamp }
    }

    override fun deleteEntriesBeforeOrAt(maxTimestamp: Long, maxId: Long) {
        rows.entries.removeIf {
            it.value.writeTimestamp < maxTimestamp ||
                (it.value.writeTimestamp == maxTimestamp && it.value.id <= maxId)
        }
    }

    override fun deleteAll() {
        rows.clear()
    }
}

/**
 * Fake [SensorSampleDao]. Mirrors `sensor_samples` with `OnConflictStrategy.IGNORE` —
 * `insertAll` silently de-duplicates by `id` and never throws on a duplicate.
 * [failNextInsert] forces the next insert to throw, to exercise the sink's failure path.
 */
class FakeSensorSampleDao : SensorSampleDao {
    val rows = LinkedHashMap<String, SensorSampleEntry>()
    val deliveries = LinkedHashSet<SensorSampleDeliveryEntity>()
    val deadLetters = LinkedHashMap<String, SensorSampleDeadLetterEntity>()
    val configuredServerGenerations = LinkedHashSet<Pair<Long, Long>>()
    val deleteOldestBeforeRequests = mutableListOf<Int>()
    val deleteOldestRequests = mutableListOf<Int>()
    val deleteOldestDeadLetterRequests = mutableListOf<Int>()
    var failNextInsert = false

    override fun insertAll(samples: List<SensorSampleEntry>) {
        if (failNextInsert) {
            failNextInsert = false
            throw RuntimeException("simulated sensor_samples write failure")
        }
        // OnConflictStrategy.IGNORE — keep the first row for a given id.
        for (sample in samples) {
            rows.putIfAbsent(sample.id, sample)
        }
    }

    override fun getOldest(limit: Int): List<SensorSampleEntry> =
        rows.values.sortedWith(compareBy({ it.timestamp }, { it.id })).take(limit)

    override fun deleteByIds(ids: List<String>) {
        ids.forEach { rows.remove(it) }
        deliveries.removeIf { it.sampleId in ids }
    }

    override fun count(): Int = rows.size

    override fun countBySensorType(): List<SensorSampleTypeCount> =
        rows.values
            .groupingBy { it.sensorType }
            .eachCount()
            .map { (sensorType, count) -> SensorSampleTypeCount(sensorType, count) }
            .sortedWith(compareByDescending<SensorSampleTypeCount> { it.count }.thenBy { it.sensorType })

    override fun deleteOldestBefore(cutoffTimestamp: String, limit: Int): Int {
        deleteOldestBeforeRequests += limit
        val removedIds = rows.values
            .filter { it.timestamp < cutoffTimestamp }
            .sortedWith(compareBy({ it.timestamp }, { it.id }))
            .take(limit)
            .map { it.id }
        deleteByIds(removedIds)
        return removedIds.size
    }

    override fun deleteOldest(limit: Int): Int {
        deleteOldestRequests += limit
        val removedIds = getOldest(limit).map { it.id }
        deleteByIds(removedIds)
        return removedIds.size
    }

    override fun countDeliveriesForServer(
        serverId: Long,
        serverGeneration: Long,
        sampleIds: List<String>,
    ): Int = deliveries.count {
        it.serverId == serverId &&
            it.serverGeneration == serverGeneration &&
            it.sampleId in sampleIds
    }

    override fun insertDeliveries(deliveries: List<SensorSampleDeliveryEntity>) {
        deliveries.forEach { delivery ->
            this.deliveries.removeIf {
                it.sampleId == delivery.sampleId && it.serverId == delivery.serverId
            }
            this.deliveries += delivery
        }
    }

    override fun deleteFullyDeliveredByIds(sampleIds: List<String>): Int {
        val deletable = sampleIds.filter { sampleId ->
            configuredServerGenerations.all { (serverId, generation) ->
                deliveries.any {
                    it.sampleId == sampleId &&
                        it.serverId == serverId &&
                        it.serverGeneration == generation
                }
            }
        }
        deleteByIds(deletable)
        return deletable.size
    }

    override fun insertDeadLetters(deadLetters: List<SensorSampleDeadLetterEntity>) {
        deadLetters.forEach { this.deadLetters[it.sampleId] = it }
    }

    override fun countDeadLetters(): Int = deadLetters.size

    override fun getOldestDeadLetters(limit: Int): List<SensorSampleDeadLetterEntity> =
        deadLetters.values.sortedWith(compareBy({ it.quarantinedAt }, { it.sampleId })).take(limit)

    override fun deleteDeadLettersByIds(sampleIds: List<String>): Int {
        val before = deadLetters.size
        sampleIds.forEach(deadLetters::remove)
        return before - deadLetters.size
    }

    override fun deleteOldestDeadLetters(limit: Int): Int {
        deleteOldestDeadLetterRequests += limit
        return deleteDeadLettersByIds(getOldestDeadLetters(limit).map { it.sampleId })
    }

    override fun deleteAll() {
        rows.clear()
        deliveries.clear()
        deadLetters.clear()
    }

    override fun deleteSamplesBySensorType(sensorType: String): Int {
        val ids = rows.values.filter { it.sensorType == sensorType }.map { it.id }
        deleteByIds(ids)
        return ids.size
    }

    override fun deleteDeadLettersBySensorType(sensorType: String): Int {
        val ids = deadLetters.values.filter { it.sensorType == sensorType }.map { it.sampleId }
        return deleteDeadLettersByIds(ids)
    }
}

/**
 * Fake [UploadStatsDao]. Mirrors `upload_stats` with `OnConflictStrategy.IGNORE` on
 * `insertDay` (a no-op when the `(serverId, date)` row exists) and additive UPDATE
 * counters. [failNextWrite] forces the next write to throw.
 */
class FakeUploadStatsDao : UploadStatsDao {
    val rows = LinkedHashMap<Pair<Long, String>, UploadStatsEntity>()
    var failNextWrite = false

    private fun checkFailure() {
        if (failNextWrite) {
            failNextWrite = false
            throw RuntimeException("simulated upload_stats write failure")
        }
    }

    override fun insertDay(stats: UploadStatsEntity) {
        checkFailure()
        rows.putIfAbsent(stats.serverId to stats.date, stats)
    }

    override fun incrementUsageCount(serverId: Long, date: String, count: Int) {
        checkFailure()
        val key = serverId to date
        rows[key]?.let { rows[key] = it.copy(usageEventsUploaded = it.usageEventsUploaded + count) }
    }

    override fun incrementSensorCount(serverId: Long, date: String, count: Int) {
        checkFailure()
        val key = serverId to date
        rows[key]?.let { rows[key] = it.copy(sensorSamplesUploaded = it.sensorSamplesUploaded + count) }
    }

    override fun incrementBatteryCount(serverId: Long, date: String, count: Int) {
        checkFailure()
        val key = serverId to date
        rows[key]?.let { rows[key] = it.copy(batterySamplesUploaded = it.batterySamplesUploaded + count) }
    }

    override fun incrementUsageFailureCount(serverId: Long, date: String, count: Int) {
        checkFailure()
        val key = serverId to date
        rows[key]?.let { rows[key] = it.copy(usageUploadFailures = it.usageUploadFailures + count) }
    }

    override fun incrementSensorFailureCount(serverId: Long, date: String, count: Int) {
        checkFailure()
        val key = serverId to date
        rows[key]?.let { rows[key] = it.copy(sensorUploadFailures = it.sensorUploadFailures + count) }
    }

    override fun incrementBatteryFailureCount(serverId: Long, date: String, count: Int) {
        checkFailure()
        val key = serverId to date
        rows[key]?.let { rows[key] = it.copy(batteryUploadFailures = it.batteryUploadFailures + count) }
    }

    override fun getRecentStats(serverId: Long, days: Int): List<UploadStatsEntity> =
        rows.values.filter { it.serverId == serverId }.sortedByDescending { it.date }.take(days)

    override fun rowCount(): Int = rows.size

    override fun usageUploadedOn(date: String): Int =
        rows.values.filter { it.date == date }.sumOf { it.usageEventsUploaded }

    override fun sensorUploadedOn(date: String): Int =
        rows.values.filter { it.date == date }.sumOf { it.sensorSamplesUploaded }

    override fun batteryUploadedOn(date: String): Int =
        rows.values.filter { it.date == date }.sumOf { it.batterySamplesUploaded }

    override fun usageFailuresOn(date: String): Int =
        rows.values.filter { it.date == date }.sumOf { it.usageUploadFailures }

    override fun sensorFailuresOn(date: String): Int =
        rows.values.filter { it.date == date }.sumOf { it.sensorUploadFailures }

    override fun batteryFailuresOn(date: String): Int =
        rows.values.filter { it.date == date }.sumOf { it.batteryUploadFailures }

    override fun deleteOlderThan(cutoffDate: String): Int {
        val before = rows.size
        rows.entries.removeIf { it.value.date < cutoffDate }
        return before - rows.size
    }
}
