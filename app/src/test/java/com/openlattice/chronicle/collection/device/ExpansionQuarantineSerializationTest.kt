package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.services.upload.DedicatedUploadBatch
import com.openlattice.chronicle.services.upload.DedicatedUploadSource
import com.openlattice.chronicle.services.upload.UsageUploadFixture
import com.openlattice.chronicle.storage.DeviceSettingsSampleEntry
import com.openlattice.chronicle.storage.serializeMalformedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpansionQuarantineSerializationTest : UsageUploadFixture() {
    private data class NonFinite(val value: Double, val coordinate: Float, val optional: Double?)

    @Test fun quarantineEncodingPreservesEveryNonFiniteValueAsAString() {
        val raw = serializeMalformedRow(NonFinite(Double.POSITIVE_INFINITY, Float.NaN, Double.NEGATIVE_INFINITY)).toString(Charsets.UTF_8)
        assertTrue(raw.contains("\"value\":\"Infinity\""))
        assertTrue(raw.contains("\"coordinate\":\"NaN\""))
        assertTrue(raw.contains("\"optional\":\"-Infinity\""))
    }

    @Test fun mappingFailurePreservesNonFiniteValuesAndUploadsTheRest() {
        val good = DeviceSettingsSampleEntry("good", "2026-09-01T00:00:00Z", "UTC", null, 1f,
            null, null, null, null, null, null, null, null)
        val bad = good.copy(id = "bad", fontScale = Float.POSITIVE_INFINITY)
        db.deviceSettingsSampleDao().insertAll(listOf(bad, good))
        var uploaded = 0
        val source = DedicatedUploadSource<DeviceSettingsSampleEntry, AndroidDeviceSettingsEvent>(
            table = UploadQueueTable.DEVICE_SETTINGS, elementType = AndroidDeviceSettingsEvent::class.java,
            getOldest = db.deviceSettingsSampleDao()::getOldest,
            idOf = { it.id }, deleteByIds = db.deviceSettingsSampleDao()::deleteByIds,
            toDto = { if (it.id == bad.id) error("invalid settings sample") else it.toAndroidDeviceSettingsEvent() },
            plainUpload = { _, _, _, _ -> uploaded++ },
        )
        assertEquals(0, DedicatedUploadBatch(context, db, server, source).execute())
        assertEquals(1, uploaded)
        val row = db.localDataQuarantineDao().forSource("device_settings_samples").single()
        assertTrue(row.rawData.toString(Charsets.UTF_8).contains("\"fontScale\":\"Infinity\""))
        assertEquals(0, db.deviceSettingsSampleDao().count())
    }
}
