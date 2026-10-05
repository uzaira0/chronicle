package com.openlattice.chronicle.collection.audio

import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.squareup.moshi.Types
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V-30: an offline backlog with long media metadata must not form a request the server refuses (413). */
class AudioUploadBatchSizeTest {
    private fun event(index: Int, metadataChars: Int) = AndroidAudioContentEvent(
        id = "event-$index",
        timestamp = OffsetDateTime.parse("2026-10-01T19:00:00Z"),
        timezone = "UTC",
        audioPackage = "com.example.player",
        title = "t".repeat(metadataChars),
        artist = "a".repeat(metadataChars),
        album = "b".repeat(metadataChars),
    )

    private val listType = Types.newParameterizedType(List::class.java, AndroidAudioContentEvent::class.java)

    @Test fun backlogBatchStaysUnderTheRequestLimit() {
        val backlog = List(5000) { event(it, 1024) } // ~3 KiB each, ~15 MiB together

        val fitting = leadingEventsWithin(backlog)

        assertTrue(fitting in 1 until backlog.size)
        assertTrue(JsonSerializer.serializeToBytes(backlog.take(fitting), listType).size <= AUDIO_UPLOAD_MAX_JSON_BYTES)
        assertEquals(10, leadingEventsWithin(List(10) { event(it, 16) }))
        assertEquals(1, leadingEventsWithin(listOf(event(0, 64), event(1, 64)), maxBytes = 1))
    }
}
