package com.openlattice.chronicle.serialization

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleSample
import com.squareup.moshi.adapter
import com.squareup.moshi.Types
import java.io.IOException
import java.lang.reflect.Type
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalStdlibApi::class)
object JsonSerializer {
    private val sampleListType = Types.newParameterizedType(List::class.java, ChronicleSample::class.java)

    @JvmStatic
    fun serializeQueueEntry(queueData: List<ChronicleSample>): ByteArray =
        serializeToBytes(queueData, sampleListType)

    @JvmStatic
    @Throws(IOException::class)
    fun deserializeQueueEntry(bytes: ByteArray): ChronicleData {
        return try {
            val samples = ChronicleJson.adapter<List<ChronicleSample>>(sampleListType)
                .fromJson(bytes.toString(StandardCharsets.UTF_8))
                ?: emptyList()
            ChronicleData(samples)
        } catch (e: Exception) {
            throw IOException("Failed to deserialize queue entry", e)
        }
    }

    

    

    inline fun <reified T> serializeToBytes(value: T): ByteArray =
        ChronicleJson.moshi.adapter<T>().serializeNulls().toJson(value)
            .toByteArray(StandardCharsets.UTF_8)

    fun serializeToBytes(value: Any?, type: Type): ByteArray =
        toJson(value, type).toByteArray(StandardCharsets.UTF_8)

    inline fun <reified T> toJson(value: T): String =
        ChronicleJson.moshi.adapter<T>().serializeNulls().toJson(value)

    fun toJson(value: Any?, type: Type): String = ChronicleJson.adapter<Any?>(type).toJson(value)

    inline fun <reified T> fromJson(json: String): T? =
        ChronicleJson.moshi.adapter<T>().serializeNulls().fromJson(json)
}
