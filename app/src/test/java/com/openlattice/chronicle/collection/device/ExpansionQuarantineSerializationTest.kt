package com.openlattice.chronicle.collection.device

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpansionQuarantineSerializationTest {
    private data class Malformed(val value: Double, val coordinate: Float, val optional: Double?)

    @Test
    fun quarantineWriterPreservesNonFiniteValuesAsStrings() {
        val source = sequenceOf(File("app/src/main/java"), File("src/main/java"))
            .map { File(it, "com/openlattice/chronicle/collection/device/ExpansionUploadWorker.kt") }
            .first(File::isFile).readText()
        assertTrue(source.contains("serializeMalformedRow(entry as Any)"))
        val encoded = serializeMalformedRow(Malformed(Double.POSITIVE_INFINITY, Float.NaN,
            Double.NEGATIVE_INFINITY))
            .toString(Charsets.UTF_8)
        assertTrue(encoded.contains("\"value\":\"Infinity\""))
        assertTrue(encoded.contains("\"coordinate\":\"NaN\""))
        assertTrue(encoded.contains("\"optional\":\"-Infinity\""))
    }
}
