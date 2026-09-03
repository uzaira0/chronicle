package com.openlattice.chronicle.serialization

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.api.EnrollmentResponse
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.models.ExtractUsageStat
import com.openlattice.chronicle.models.ExtractedActivities
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.IOSDevice
import com.openlattice.chronicle.sources.SourceDevice
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.Types
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.lang.reflect.Type
import java.lang.reflect.ParameterizedType
import java.time.OffsetDateTime
import java.util.UUID

/** Canonical network and persistence JSON boundary for the Android app. */
object ChronicleJson {
    val moshi: Moshi = Moshi.Builder()
        .add(PlatformValueJsonAdapters())
        .add(NameStableEnumJsonAdapterFactory)
        .add(EnrollmentResponse::class.java, EnrollmentResponseJsonAdapter())
        .add(AndroidDataCollectionSettingJsonAdapterFactory)
        .add(ChronicleDataJsonAdapterFactory)
        .add(FullQualifiedNameMapJsonAdapterFactory)
        .add(
            PolymorphicJsonAdapterFactory.of(SourceDevice::class.java, "@class")
                .withSubtype(AndroidDevice::class.java, AndroidDevice::class.java.name)
                .withSubtype(IOSDevice::class.java, IOSDevice::class.java.name)
        )
        .add(
            PolymorphicJsonAdapterFactory.of(ChronicleSample::class.java, "@class")
                .withSubtype(ExtractedUsageEvent::class.java, ExtractedUsageEvent::class.java.name)
                .withSubtype(ExtractUsageStat::class.java, ExtractUsageStat::class.java.name)
                .withSubtype(ExtractedActivities::class.java, ExtractedActivities::class.java.name)
                .withSubtype(ChronicleUsageEvent::class.java, ChronicleUsageEvent::class.java.name)
        )
        .addLast(KotlinJsonAdapterFactory())
        .build()

    fun <T> adapter(type: Type): JsonAdapter<T> = moshi.adapter<T>(type).serializeNulls()
}

/**
 * Moshi's reflective enum adapter calls `Class.getField(Enum.name())` to inspect optional
 * `@Json` annotations. R8 is allowed to rename those public fields while preserving
 * [Enum.name], so minified Play builds otherwise crash before a request is sent. Chronicle's
 * special wire-id enums have explicit adapters registered ahead of this factory; every other
 * wire enum intentionally uses its stable enum name and therefore needs no reflective field
 * lookup.
 */
private object NameStableEnumJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: MutableSet<out Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        if (annotations.isNotEmpty()) return null
        val rawType = Types.getRawType(type)
        if (!rawType.isEnum) return null

        @Suppress("UNCHECKED_CAST")
        val constants = rawType.enumConstants as Array<out Enum<*>>
        val constantsByName = constants.associateBy(Enum<*>::name)
        return object : JsonAdapter<Enum<*>>() {
            override fun fromJson(reader: JsonReader): Enum<*> {
                val name = reader.nextString()
                return constantsByName[name]
                    ?: throw JsonDataException("Unknown ${rawType.name} value '$name' at ${reader.path}")
            }

            override fun toJson(writer: JsonWriter, value: Enum<*>?) {
                if (value == null) {
                    writer.nullValue()
                } else {
                    writer.value(value.name)
                }
            }
        }.nullSafe()
    }
}

private class PlatformValueJsonAdapters {
    @ToJson
    fun offsetDateTimeToJson(value: OffsetDateTime): String = value.toString()

    @FromJson
    fun offsetDateTimeFromJson(value: String): OffsetDateTime = OffsetDateTime.parse(value)

    @ToJson
    fun uuidToJson(value: UUID): String = value.toString()

    @FromJson
    fun uuidFromJson(value: String): UUID = UUID.fromString(value)

    @ToJson
    fun fullQualifiedNameToJson(value: FullQualifiedName): String = value.toString()

    @FromJson
    fun fullQualifiedNameFromJson(value: String): FullQualifiedName = FullQualifiedName(value)

    // These three enums cross the wire as their Jackson @JsonValue ids, which Moshi's default
    // enum adapter does not honor — it emits constant names ("USAGE_EVENTS"), which the server
    // rejects with 400. That broke every collection-ack upload in v49, so the consent trail was
    // never recorded. Decode also accepts the constant-name form so retry records and settings
    // persisted by v49 stay readable after upgrade and queued acks deliver instead of dropping.

    @ToJson
    fun collectionModuleIdToJson(value: CollectionModuleId): String = value.id

    @FromJson
    fun collectionModuleIdFromJson(value: String): CollectionModuleId =
        CollectionModuleId.fromIdOrNull(value)
            ?: runCatching { CollectionModuleId.valueOf(value) }.getOrNull()
            ?: throw JsonDataException("Unknown collection module id: $value")

    @ToJson
    fun collectionDataDispositionToJson(value: CollectionDataDisposition): String = value.id

    @FromJson
    fun collectionDataDispositionFromJson(value: String): CollectionDataDisposition =
        CollectionDataDisposition.fromIdOrNull(value)
            ?: runCatching { CollectionDataDisposition.valueOf(value) }.getOrNull()
            ?: throw JsonDataException("Unknown collection data disposition: $value")

    @ToJson
    fun healthConnectRecordTypeToJson(value: HealthConnectRecordType): String = value.id

    @FromJson
    fun healthConnectRecordTypeFromJson(value: String): HealthConnectRecordType =
        runCatching { HealthConnectRecordType.fromId(value) }.getOrNull()
            ?: runCatching { HealthConnectRecordType.valueOf(value) }.getOrNull()
            ?: throw JsonDataException("Unknown Health Connect record type: $value")

    @ToJson
    fun encryptedPayloadTypeToJson(value: EncryptedPayloadType): String = value.id

    @FromJson
    fun encryptedPayloadTypeFromJson(value: String): EncryptedPayloadType =
        EncryptedPayloadType.fromIdOrNull(value)
            ?: runCatching { EncryptedPayloadType.valueOf(value) }.getOrNull()
            ?: throw JsonDataException("Unknown encrypted payload type: $value")
}

private class EnrollmentResponseJsonAdapter : JsonAdapter<EnrollmentResponse>() {
    override fun fromJson(reader: JsonReader): EnrollmentResponse {
        if (reader.peek() == JsonReader.Token.STRING) {
            return EnrollmentResponse(UUID.fromString(reader.nextString()))
        }
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            throw JsonDataException("Expected UUID string or enrollment object at ${reader.path}")
        }

        var chronicleId: UUID? = null
        var enrollmentId: UUID? = null
        var apiKey: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "chronicleId" -> chronicleId = readRequiredUuid(reader, "chronicleId")
                "enrollmentId" -> enrollmentId = readNullableUuid(reader, "enrollmentId")
                "apiKey" -> apiKey = readNullableString(reader, "apiKey")
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return EnrollmentResponse(
            chronicleId = chronicleId
                ?: throw JsonDataException("EnrollmentResponse missing chronicleId at ${reader.path}"),
            enrollmentId = enrollmentId,
            apiKey = apiKey,
        )
    }

    override fun toJson(writer: JsonWriter, value: EnrollmentResponse?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("chronicleId").value(value.chronicleId.toString())
        writer.name("enrollmentId")
        value.enrollmentId?.let { writer.value(it.toString()) } ?: writer.nullValue()
        writer.name("apiKey")
        value.apiKey?.let(writer::value) ?: writer.nullValue()
        writer.endObject()
    }

    private fun readRequiredUuid(reader: JsonReader, field: String): UUID =
        UUID.fromString(readRequiredString(reader, field))

    private fun readNullableUuid(reader: JsonReader, field: String): UUID? =
        readNullableString(reader, field)?.let(UUID::fromString)

    private fun readNullableString(reader: JsonReader, field: String): String? =
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.nextNull<Unit>()
            null
        } else {
            readRequiredString(reader, field)
        }

    private fun readRequiredString(reader: JsonReader, field: String): String {
        if (reader.peek() != JsonReader.Token.STRING) {
            throw JsonDataException("$field must be a string or null at ${reader.path}")
        }
        return reader.nextString()
    }
}

private object ChronicleDataJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: MutableSet<out Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        if (annotations.isNotEmpty() || Types.getRawType(type) != ChronicleData::class.java) return null
        val listType = Types.newParameterizedType(List::class.java, ChronicleSample::class.java)
        val delegate = moshi.adapter<List<ChronicleSample>>(listType)
        return object : JsonAdapter<ChronicleData>() {
            override fun fromJson(reader: JsonReader): ChronicleData =
                ChronicleData(delegate.fromJson(reader) ?: emptyList())

            override fun toJson(writer: JsonWriter, value: ChronicleData?) {
                delegate.toJson(writer, value?.toList())
            }
        }
    }
}

private object AndroidDataCollectionSettingJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: MutableSet<out Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        if (annotations.isNotEmpty() || Types.getRawType(type) != AndroidDataCollectionSetting::class.java) return null
        val moduleAdapter = moshi.adapter(CollectionModuleSetting::class.java)
        return object : JsonAdapter<AndroidDataCollectionSetting>() {
            override fun fromJson(reader: JsonReader): AndroidDataCollectionSetting {
                val modules = linkedMapOf<CollectionModuleId, CollectionModuleSetting>()
                var version = AndroidDataCollectionSetting.CURRENT_VERSION
                var settingsVersion = AndroidDataCollectionSetting.INITIAL_SETTINGS_VERSION
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "modules" -> {
                            if (reader.peek() == JsonReader.Token.NULL) {
                                reader.nextNull<Unit>()
                                continue
                            }
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val moduleId = CollectionModuleId.fromIdOrNull(reader.nextName())
                                if (moduleId == null) {
                                    reader.skipValue()
                                } else {
                                    moduleAdapter.fromJson(reader)?.let { modules[moduleId] = it }
                                }
                            }
                            reader.endObject()
                        }
                        "version" -> version = if (reader.peek() == JsonReader.Token.NULL) {
                            reader.nextNull<Unit>()
                            AndroidDataCollectionSetting.CURRENT_VERSION
                        } else {
                            reader.nextInt()
                        }
                        "settingsVersion" -> settingsVersion = if (reader.peek() == JsonReader.Token.NULL) {
                            reader.nextNull<Unit>()
                            AndroidDataCollectionSetting.INITIAL_SETTINGS_VERSION
                        } else {
                            reader.nextInt()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                return AndroidDataCollectionSetting(modules, version, settingsVersion)
            }

            override fun toJson(writer: JsonWriter, value: AndroidDataCollectionSetting?) {
                if (value == null) {
                    writer.nullValue()
                    return
                }
                writer.beginObject()
                writer.name("modules").beginObject()
                value.modules.forEach { (moduleId, setting) ->
                    writer.name(moduleId.id)
                    moduleAdapter.toJson(writer, setting)
                }
                writer.endObject()
                writer.name("version").value(value.version)
                writer.name("settingsVersion").value(value.settingsVersion)
                writer.endObject()
            }
        }
    }
}

/** Moshi's standard map adapter cannot encode Olingo keys as JSON object field names. */
private object FullQualifiedNameMapJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: MutableSet<out Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        if (annotations.isNotEmpty() || Types.getRawType(type) != Map::class.java) return null
        val keyAndValue = (type as? ParameterizedType)?.actualTypeArguments ?: return null
        if (keyAndValue[0] != FullQualifiedName::class.java) return null
        val valueAdapter = moshi.adapter<Any?>(keyAndValue[1])
        return object : JsonAdapter<Map<FullQualifiedName, Any?>>() {
            override fun fromJson(reader: JsonReader): Map<FullQualifiedName, Any?> {
                val result = linkedMapOf<FullQualifiedName, Any?>()
                reader.beginObject()
                while (reader.hasNext()) {
                    result[FullQualifiedName(reader.nextName())] = valueAdapter.fromJson(reader)
                }
                reader.endObject()
                return result
            }

            override fun toJson(writer: JsonWriter, value: Map<FullQualifiedName, Any?>?) {
                if (value == null) {
                    writer.nullValue()
                    return
                }
                writer.beginObject()
                value.forEach { (key, item) ->
                    writer.name(key.toString())
                    valueAdapter.toJson(writer, item)
                }
                writer.endObject()
            }
        }
    }
}
