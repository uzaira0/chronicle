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
    fun `records from before ownership drain under the current enrollment after an upgrade`() {
        val plaintext = JsonSerializer.toJson(listOf(sample("legacy"))).toByteArray()
        val blob = cipher.encrypt(plaintext)
        DataOutputStream(FileOutputStream(File(tmp.root, "buffer.bin"))).use {
            it.writeInt(blob.size)
            it.write(blob)
        }
        val imported = mutableListOf<String>()
        buffer().drain { batch ->
            imported += batch.map { it.id }
            transferred(batch)
        }
        // 2026.9.27 and earlier cleared the buffer with the enrollment, so an ownerless record
        // can only belong to the enrollment that is draining it.
        assertEquals(listOf("legacy"), imported)
        assertTrue(File(tmp.root, "quarantine").listFiles().isNullOrEmpty())
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
