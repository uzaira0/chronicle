package com.openlattice.chronicle.serialization

import android.util.Log
import com.google.common.collect.HashMultimap
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.android.LegacyChronicleData
import com.squareup.moshi.adapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Types
import okio.Buffer
import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.io.IOException
import java.lang.reflect.Type
import java.nio.charset.StandardCharsets
import java.util.UUID

@OptIn(ExperimentalStdlibApi::class)
object JsonSerializer {
    private const val TAG = "JsonSerializer"
    private val sampleListType = Types.newParameterizedType(List::class.java, ChronicleSample::class.java)
    private val stringUuidMapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        UUID::class.java,
    )

    @JvmStatic
    fun serializeQueueEntry(queueData: List<ChronicleSample>): ByteArray =
        serializeToBytes(queueData, sampleListType)

    @JvmStatic
    fun deserializeLegacyQueueEntry(bytes: ByteArray): LegacyChronicleData {
        try {
            val root = JsonReader.of(Buffer().write(bytes)).readJsonValue() as? List<*>
                ?: throw IOException("Legacy queue entry must be a JSON array")
            val entries = root.map { rawEntry ->
                val fields = rawEntry as? Map<*, *>
                    ?: throw IOException("Legacy queue item must be a JSON object")
                HashMultimap.create<UUID, Any>().apply {
                    fields.forEach { (rawKey, rawValues) ->
                        val key = UUID.fromString(rawKey as? String
                            ?: throw IOException("Legacy queue property key must be a UUID string"))
                        val values = rawValues as? List<*> ?: listOf(rawValues)
                        values.filterNotNull().forEach { put(key, it) }
                    }
                }
            }
            return LegacyChronicleData(entries)
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Unable to deserialize legacy queue entry", e) }
            throw RuntimeException("Failed to deserialize legacy queue entry", e)
        }
    }

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

    @JvmStatic
    fun serializePropertyTypeIds(propertyTypeIds: Map<FullQualifiedName, UUID>): String =
        toJson(propertyTypeIds.mapKeys { it.key.toString() }, stringUuidMapType)

    @JvmStatic
    fun deserializePropertyTypeIds(json: String?): Map<FullQualifiedName, UUID> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            ChronicleJson.adapter<Map<String, UUID>>(stringUuidMapType).fromJson(json)
                ?.mapKeys { FullQualifiedName(it.key) }
                ?: emptyMap()
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Unable to deserialize property type ids. Using empty map.", e) }
            emptyMap()
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
