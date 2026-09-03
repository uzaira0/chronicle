package com.openlattice.chronicle.collection.directboot

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.storage.SensorSampleEntry
import java.io.File
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
            ModuleResult.Ok(batch.size)
        }

        assertEquals(listOf("a", "b", "c"), persisted.map { it.id })
        assertEquals(sample("a"), persisted[0])
        assertEquals(3, result.persisted)
        assertEquals(0, result.corruptRecordsDropped)
        assertFalse(result.failed)
        assertTrue(buffer.isEmpty())

        // A second drain is a no-op.
        val again = buffer.drain { ModuleResult.Ok(it.size) }
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

        val failed = buffer.drain { ModuleResult.Failed(RuntimeException("db closed")) }
        assertTrue(failed.failed)
        assertFalse(buffer.isEmpty())

        val persisted = mutableListOf<SensorSampleEntry>()
        val retry = buffer.drain { batch ->
            persisted.addAll(batch)
            ModuleResult.Ok(batch.size)
        }
        assertEquals(listOf("a"), persisted.map { it.id })
        assertFalse(retry.failed)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `explicit enrollment reset removes live and interrupted drain records`() {
        val buffer = buffer()
        buffer.append(listOf(sample("old-study")))
        buffer.drain { ModuleResult.Failed(RuntimeException("interrupted")) }
        buffer.append(listOf(sample("newer-old-study")))
        assertFalse(buffer.isEmpty())

        assertTrue(buffer.clear())

        assertTrue(buffer.isEmpty())
        assertFalse(File(tmp.root, "buffer.bin").exists())
        assertFalse(File(tmp.root, "buffer.draining.bin").exists())
    }

    @Test
    fun `appends after a crashed drain are preserved and drain after the older records`() {
        val buffer = buffer()
        buffer.append(listOf(sample("old")))
        // Crash mid-drain: the rename happened but persistence never completed.
        buffer.drain { ModuleResult.Failed(RuntimeException("crash")) }
        buffer.append(listOf(sample("new")))

        val persisted = mutableListOf<SensorSampleEntry>()
        val result = buffer.drain { batch ->
            persisted.addAll(batch)
            ModuleResult.Ok(batch.size)
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
            ModuleResult.Ok(batch.size)
        }

        assertEquals(listOf("good"), persisted.map { it.id })
        assertEquals(1, result.corruptRecordsDropped)
        assertFalse(result.failed)
        assertTrue(buffer.isEmpty())
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
            ModuleResult.Ok(batch.size)
        }

        assertEquals(listOf(entry), persisted)
    }
}
