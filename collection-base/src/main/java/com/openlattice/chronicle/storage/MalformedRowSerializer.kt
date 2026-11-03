package com.openlattice.chronicle.storage

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Type

// Reuse Moshi's adapter cache on the collection write path.
private val quarantineMoshi = Moshi.Builder().add(object : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (annotations.isNotEmpty()) return null
        return when (Types.getRawType(type)) {
            Double::class.javaObjectType, Double::class.javaPrimitiveType -> object : JsonAdapter<Double>() {
                override fun fromJson(reader: JsonReader): Double = reader.nextDouble()
                override fun toJson(writer: JsonWriter, value: Double?) {
                    if (value == null) writer.nullValue()
                    else if (value.isFinite()) writer.value(value)
                    else writer.value(value.toString())
                }
            }
            Float::class.javaObjectType, Float::class.javaPrimitiveType -> object : JsonAdapter<Float>() {
                override fun fromJson(reader: JsonReader): Float = reader.nextDouble().toFloat()
                override fun toJson(writer: JsonWriter, value: Float?) {
                    if (value == null) writer.nullValue()
                    else if (value.isFinite()) writer.value(value)
                    else writer.value(value.toString())
                }
            }
            else -> null
        }
    }
}).add(KotlinJsonAdapterFactory()).build()

/** Quarantine preserves values rejected by strict wire JSON, including SQLite infinities. */
fun serializeMalformedRow(row: Any): ByteArray =
    quarantineMoshi.adapter(row.javaClass).toJson(row).toByteArray(Charsets.UTF_8)

fun <T> deserializeMalformedRow(raw: ByteArray, type: Class<T>): T? =
    quarantineMoshi.adapter(type).fromJson(raw.toString(Charsets.UTF_8))
