package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import java.time.OffsetDateTime
import okhttp3.RequestBody
import okio.Buffer

internal fun deviceSettingsUploadRows(count: Int = 2) = List(count) {
    AndroidDeviceSettingsEvent("row-$it", OffsetDateTime.parse("2026-10-01T00:00:00Z"), "UTC")
}

internal fun uploadedBodyBytes(value: Any?): ByteArray =
    Buffer().also { (value as RequestBody).writeTo(it) }.readByteArray()

internal inline fun <reified T> decodedUploadBody(value: Any?): T =
    checkNotNull(JsonSerializer.fromJson<T>(uploadedBodyBytes(value).toString(Charsets.UTF_8)))
