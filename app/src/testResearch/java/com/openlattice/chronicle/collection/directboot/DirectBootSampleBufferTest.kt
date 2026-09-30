package com.openlattice.chronicle.collection.directboot

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.storage.SensorSampleEntry
import java.io.File
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.util.UUID
import com.openlattice.chronicle.serialization.JsonSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM coverage for the direct-boot sample buffer: encrypt/append/drain round-trip,
 * crash-mid-drain recovery, corrupt-tail tolerance, persist-failure retention and the
 * size cap. Uses a JVM stand-in cipher (byte-reversal — enough to prove every record goes
 * through the cipher seam both ways) and a temp dir; no Android Keystore, no `Context`.
 */
class DirectBootSampleBufferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Proves ciphertext ≠ plaintext and decrypt(encrypt(x)) == x without the Keystore. */
    private val cipher = object : DirectBootRecordCipher {
        override fun encrypt(plaintext: ByteArray) = plaintext.reversedArray()
        override fun decrypt(blob: ByteArray) = blob.reversedArray()
    }

    private fun buffer(dir: File = tmp.root) = DirectBootSampleBuffer(dir, cipher, NoOpCollectionLog)
    private fun transferred(batch: List<SensorSampleEntry>) =
        DirectBootSampleBuffer.DrainTransfer(batch.mapTo(linkedSetOf()) { it.id })
    private val refused = DirectBootSampleBuffer.DrainTransfer(emptySet(), failed = true)

    private fun sample(id: String, sensorType: String = "accelerometer") = SensorSampleEntry(
        id = id,
        sensorType = sensorType,
        timestamp = "2026-07-15T18:18:09.282Z",
        timezone = "UTC",
        x = 0.1f,
        y = -9.8f,
        z = 0.02f,
        w = null,
        accuracy = 3,
    )

    @Test
    fun `append then drain round-trips entries in order and empties the buffer`() {
        val buffer = buffer()
        buffer.append(listOf(sample("a"), sample("b")))
        buffer.append(listOf(sample("c")))
        assertFalse(buffer.isEmpty())

        val persisted = mutableListOf<SensorSampleEntry>()
        val result = buffer.drain { batch ->
            persisted.addAll(batch)
            transferred(batch)
        }

        assertEquals(listOf("a", "b", "c"), persisted.map { it.id })
        assertEquals(sample("a"), persisted[0])
        assertEquals(3, result.persisted)
        assertEquals(0, result.corruptRecordsDropped)
        assertFalse(result.failed)
        assertTrue(buffer.isEmpty())

        // A second drain is a no-op.
        val again = buffer.drain { transferred(it) }
        assertEquals(0, again.persisted)
        assertFalse(again.failed)
    }

    @Test
    fun `records on disk are not plaintext`() {
        val buffer = buffer()
        buffer.append(listOf(sample("secret-id")))

        val raw = File(tmp.root, "buffer.bin").readBytes().toString(Charsets.ISO_8859_1)

        assertFalse(raw.contains("secret-id"))
        assertFalse(raw.contains("accelerometer"))
    }

    @Test
    fun `failed persist keeps the records for the next drain`() {
        val buffer = buffer()
        buffer.append(listOf(sample("a")))

        val failed = buffer.drain { refused }
        assertTrue(failed.failed)
        assertFalse(buffer.isEmpty())

        val persisted = mutableListOf<SensorSampleEntry>()
        val retry = buffer.drain { batch ->
            persisted.addAll(batch)
            transferred(batch)
        }
        assertEquals(listOf("a"), persisted.map { it.id })
        assertFalse(retry.failed)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `explicit enrollment reset removes live and interrupted drain records`() {
        val buffer = buffer()
        buffer.append(listOf(sample("old-study")))
        buffer.drain { refused }
        buffer.append(listOf(sample("newer-old-study")))
        assertFalse(buffer.isEmpty())

        assertTrue(buffer.clear())

        assertTrue(buffer.isEmpty())
        assertFalse(File(tmp.root, "buffer.bin").exists())
        assertFalse(File(tmp.root, "buffer.draining.bin").exists())
    }

    @Test
    fun `sensor discard erases only that sensor from both buffer files`() {
        val buffer = buffer()
        buffer.append(listOf(sample("a", "accelerometer"), sample("g", "gyroscope")))
        buffer.drain { refused }
        buffer.append(listOf(sample("b", "accelerometer")))

        assertEquals(2, buffer.eraseSensorType("accelerometer"))
        val drained = mutableListOf<String>()
        buffer.drain { batch ->
            drained += batch.map { it.id }
            transferred(batch)
        }
        assertEquals(listOf("g"), drained)
    }

    @Test
    fun `appends after a crashed drain are preserved and drain after the older records`() {
        val buffer = buffer()
        buffer.append(listOf(sample("old")))
        // Crash mid-drain: the rename happened but persistence never completed.
        buffer.drain { refused }
        buffer.append(listOf(sample("new")))

        val persisted = mutableListOf<SensorSampleEntry>()
        val result = buffer.drain { batch ->
            persisted.addAll(batch)
            transferred(batch)
        }

        assertEquals(listOf("old", "new"), persisted.map { it.id })
        assertEquals(2, result.persisted)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `corrupt tail drops only the tail`() {
        val buffer = buffer()
        buffer.append(listOf(sample("good")))
        // Simulate a crash mid-append: a dangling length prefix with a short body.
        File(tmp.root, "buffer.bin").appendBytes(byteArrayOf(0, 0, 1, 0, 42))

        val persisted = mutableListOf<SensorSampleEntry>()
        val result = buffer.drain { batch ->
            persisted.addAll(batch)
            transferred(batch)
        }

        assertEquals(listOf("good"), persisted.map { it.id })
        assertEquals(1, result.corruptRecordsDropped)
        assertFalse(result.failed)
        assertTrue(buffer.isEmpty())
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, 42),
            tmp.root.listFiles()!!.single { it.name.startsWith("corrupt-") }.readBytes())
    }

    @Test
    fun `sensor discard scrubs retained owner and whole file corrupt copies and erases undecodable bytes`() {
        val buffer = buffer()
        buffer.append(listOf(sample("erase"), sample("keep", "gyroscope")))
        val live = File(tmp.root, "buffer.bin")
        val quarantine = File(tmp.newFolder("quarantine"), "owner-existing.bin")
        live.copyTo(quarantine)
        val badBlob = cipher.encrypt("not JSON".toByteArray())
        DataOutputStream(FileOutputStream(live, true)).use {
            it.writeInt(badBlob.size)
            it.write(badBlob)
        }
        val tail = byteArrayOf(0, 0, 1, 0, 42)
        live.appendBytes(tail)
        val corrupt = File(tmp.root, "corrupt-existing.bin")
        live.copyTo(corrupt)

        assertEquals(1, buffer.eraseSensorType("accelerometer", "test-owner"))
        assertEquals(0, buffer.eraseSensorType("accelerometer", "test-owner"))
        listOf(live, quarantine, corrupt).forEachIndexed { index, retained ->
            val replayDir = tmp.newFolder("replay-$index")
            retained.copyTo(File(replayDir, "buffer.bin"))
            val imported = mutableListOf<String>()
            val result = buffer(replayDir).drain { batch ->
                imported += batch.map { it.id }
                transferred(batch)
            }
            assertEquals(listOf("keep"), imported)
            assertEquals(0, result.corruptRecordsDropped)
        }
    }

    @Test
    fun `sensor discard retains records belonging to another known owner`() {
        val other = DirectBootSampleBuffer(tmp.root, cipher, NoOpCollectionLog, ownerForAppend = { "other" })
        other.append(listOf(sample("other")))
        other.drain("test-owner") { transferred(it) }
        buffer().append(listOf(sample("current")))

        assertEquals(1, buffer().eraseSensorType("accelerometer", "test-owner"))
        val replayDir = tmp.newFolder("replay")
        File(tmp.root, "quarantine").listFiles()!!.single().copyTo(File(replayDir, "buffer.bin"))
        val imported = mutableListOf<String>()
        buffer(replayDir).drain("other") { batch ->
            imported += batch.map { it.id }
            transferred(batch)
        }
        assertEquals(listOf("other"), imported)
    }

    @Test
    fun `build 63 obfuscated batch and sample fields are preserved and reported without crashing`() {
        val losses = mutableListOf<Pair<String, Int>>()
        val buffer = DirectBootSampleBuffer(tmp.root, cipher, NoOpCollectionLog,
            reportLoss = { code, count, _ -> losses += code to count })
        val blobs = listOf(
            """{"a":"test-owner","b":[{"a":"old","b":"accelerometer","c":"2026-07-15T18:18:09.282Z","d":"UTC","e":0.1,"f":-9.8,"g":0.02,"h":null,"i":3,"j":null}]}""",
            """[{"a":"legacy","b":"accelerometer","c":"2026-07-15T18:18:09.282Z","d":"UTC","e":0.1,"f":-9.8,"g":0.02,"h":null,"i":3,"j":null}]""",
        ).map { cipher.encrypt(it.toByteArray()) }
        DataOutputStream(FileOutputStream(File(tmp.root, "buffer.bin"))).use { output ->
            blobs.forEach { output.writeInt(it.size); output.write(it) }
        }
        buffer.append(listOf(sample("good")))
        val imported = mutableListOf<String>()
        val result = buffer.drain { batch ->
            imported += batch.map { it.id }
            transferred(batch)
        }
        assertEquals(listOf("good"), imported)
        assertEquals(2, result.corruptRecordsDropped)
        assertFalse(result.failed)
        assertEquals(List(2) { "DIRECT_BOOT_CORRUPT_RECORD" to 1 }, losses)
        val copies = tmp.root.listFiles()!!.filter { it.name.startsWith("corrupt-") }
        blobs.forEach { blob -> assertTrue(copies.any { it.readBytes().contentEquals(blob) }) }
    }

    @Test
    fun `bad encrypted record does not discard the valid record after it`() {
        val buffer = buffer()
        buffer.append(listOf(sample("before")))
        DataOutputStream(FileOutputStream(File(tmp.root, "buffer.bin"), true)).use {
            it.writeInt(3)
            it.write(byteArrayOf(1, 2, 3))
        }
        buffer.append(listOf(sample("after")))

        val drained = mutableListOf<String>()
        val result = buffer.drain { batch ->
            drained += batch.map { it.id }
            transferred(batch)
        }

        assertEquals(listOf("before", "after"), drained)
        assertEquals(1, result.corruptRecordsDropped)
    }

    @Test
    fun `full buffer drops the batch without failing the runtime`() {
        val buffer = buffer()
        buffer.append(listOf(sample("kept")))
        // Inflate the live file past the cap; the next append must drop, not grow or fail.
        File(tmp.root, "buffer.bin").appendBytes(ByteArray(DirectBootSampleBuffer.MAX_BUFFER_BYTES.toInt()))

        val result = buffer.append(listOf(sample("dropped")))

        assertTrue(result is ModuleResult.Ok)
        assertEquals(0, (result as ModuleResult.Ok).items)
    }

    @Test
    fun `full buffer records exact capacity loss in device protected journal seam`() {
        val losses = mutableListOf<Pair<String, Int>>()
        val buffer = DirectBootSampleBuffer(tmp.root, cipher, NoOpCollectionLog,
            reportLoss = { code, count, _ -> losses += code to count })
        File(tmp.root, "buffer.bin").writeBytes(ByteArray(DirectBootSampleBuffer.MAX_BUFFER_BYTES.toInt()))

        buffer.append(listOf(sample("one"), sample("two")))

        assertEquals(listOf("DIRECT_BOOT_CAPACITY_DROPPED" to 2), losses)
    }

    @Test
    fun `admission pauses before the next maximum record could fill the buffer`() {
        val buffer = buffer()
        assertTrue(buffer.hasAdmissionCapacity())
        File(tmp.root, "buffer.bin").writeBytes(ByteArray(DirectBootSampleBuffer.MAX_RECORD_BYTES + 1))
        assertFalse(buffer.hasAdmissionCapacity())
    }

    @Test
    fun `empty append is an idempotent success`() {
        val result = buffer().append(emptyList())
        assertEquals(ModuleResult.Ok(0), result)
        assertTrue(buffer().isEmpty())
    }

    @Test
    fun `valuesJson survives the round-trip`() {
        val buffer = buffer()
        val entry = sample("multi").copy(w = 0.5f, valuesJson = "[1.0,2.0,3.0,4.0,5.0]")
        buffer.append(listOf(entry))

        val persisted = mutableListOf<SensorSampleEntry>()
        buffer.drain { batch ->
            persisted.addAll(batch)
            transferred(batch)
        }

        assertEquals(listOf(entry), persisted)
    }

    @Test
    fun `old enrollment records are quarantined while current records drain`() {
        val old = DirectBootSampleBuffer(tmp.root, cipher, NoOpCollectionLog,
            ownerForAppend = { "owner-A" })
        val current = DirectBootSampleBuffer(tmp.root, cipher, NoOpCollectionLog,
            ownerForAppend = { "owner-B" })
        old.append(listOf(sample("A")))
        current.append(listOf(sample("B")))
        val imported = mutableListOf<String>()

        val result = current.drain("owner-B") { batch ->
            imported += batch.map { it.id }
            transferred(batch)
        }

        assertFalse(result.failed)
        assertEquals(listOf("B"), imported)
        assertEquals(1, File(tmp.root, "quarantine").listFiles()?.size)
        assertTrue(current.isEmpty())
    }

    @Test
    fun `only transferred samples checkpoint while gate refused samples wait for later drain`() {
        val losses = mutableListOf<Pair<String, Int>>()
        val buffer = DirectBootSampleBuffer(tmp.root, cipher, NoOpCollectionLog,
            reportLoss = { code, count, _ -> losses += code to count })
        buffer.append(listOf(sample("one"), sample("two"), sample("three")))
        val first = buffer.drain { DirectBootSampleBuffer.DrainTransfer(setOf("one", "three")) }
        assertEquals(2, first.persisted)
        assertTrue(first.failed)
        assertFalse(buffer.isEmpty())
        buffer.append(listOf(sample("later")))
        val later = mutableListOf<String>()
        buffer.drain { batch ->
            later += batch.map { it.id }
            transferred(batch)
        }
        assertEquals(listOf("two", "later"), later)
        assertTrue(losses.isEmpty())
    }

    @Test
    fun `successful batch is not replayed after a later batch fails`() {
        val buffer = buffer()
        buffer.append((0..DirectBootSampleBuffer.DRAIN_BATCH).map { sample("id-$it") })
        var calls = 0
        val first = buffer.drain { batch ->
            calls++
            if (calls == 1) transferred(batch) else refused
        }
        assertTrue(first.failed)
        assertEquals(DirectBootSampleBuffer.DRAIN_BATCH, first.persisted)
        val replayed = mutableListOf<String>()
        buffer.drain { batch ->
            replayed += batch.map { it.id }
            transferred(batch)
        }
        assertEquals(listOf("id-${DirectBootSampleBuffer.DRAIN_BATCH}"), replayed)
    }

    @Test
    fun `only ownerless records captured at or after enrollment creation are adopted`() {
        val plaintext = JsonSerializer.toJson(listOf(
            sample("before").copy(timestamp = "2026-07-15T18:18:09.281Z"),
            sample("at"),
            sample("after").copy(timestamp = "2026-07-15T13:18:09.283-05:00"),
            sample("invalid").copy(timestamp = "invalid"),
        )).toByteArray()
        val blob = cipher.encrypt(plaintext)
        DataOutputStream(FileOutputStream(File(tmp.root, "buffer.bin"))).use {
            it.writeInt(blob.size)
            it.write(blob)
        }
        val imported = mutableListOf<String>()
        buffer().drain(enrollmentCreatedAt = "2026-07-15T18:18:09.282Z") { batch ->
            imported += batch.map { it.id }
            transferred(batch)
        }
        assertEquals(listOf("at", "after"), imported)
        val replayDir = tmp.newFolder("legacy-quarantine")
        File(tmp.root, "quarantine").listFiles()!!.single().copyTo(File(replayDir, "buffer.bin"))
        val quarantined = mutableListOf<String>()
        buffer(replayDir).drain(enrollmentCreatedAt = "2026-07-15T18:18:09.280Z") { batch ->
            quarantined += batch.map { it.id }
            transferred(batch)
        }
        assertEquals(listOf("before"), quarantined)
        assertEquals(1, File(replayDir, "quarantine").listFiles()?.size)
    }

    @Test
    fun `ownerless records without a provable enrollment creation time are quarantined`() {
        val blob = cipher.encrypt(JsonSerializer.toJson(listOf(sample("legacy"))).toByteArray())
        DataOutputStream(FileOutputStream(File(tmp.root, "buffer.bin"))).use {
            it.writeInt(blob.size)
            it.write(blob)
        }
        assertEquals(0, buffer().drain { transferred(it) }.persisted)
        assertEquals(1, File(tmp.root, "quarantine").listFiles()?.size)
    }

    @Test
    fun `corruption incident id is a UUID`() {
        val ids = mutableListOf<String>()
        val buffer = DirectBootSampleBuffer(tmp.root, cipher, NoOpCollectionLog,
            reportLoss = { code, _, id -> if (code == "DIRECT_BOOT_CORRUPT_RECORD") ids += id })
        buffer.append(listOf(sample("good")))
        File(tmp.root, "buffer.bin").appendBytes(byteArrayOf(0, 0, 1, 0, 42))
        buffer.drain { transferred(it) }
        assertEquals(1, ids.size)
        assertEquals(ids.single(), UUID.fromString(ids.single()).toString())
    }
}
