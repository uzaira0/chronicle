package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.audio.AudioCaptureController
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.storage.audioActivitySampleDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureWindowTest : ResearchErasureTestFixture() {
    @Test fun audioSnapshotsUseTheSameCaptureWindowAdmission() {
        val capture = AudioCaptureController(context)
        onPersistenceWorker { capture.snapshot() }
        assertEquals(1, db.audioActivitySampleDao().count())

        val now = System.currentTimeMillis()
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyCaptureWindows(context, owner, fence.settingsGeneration(),
                listOf(CaptureWindow(utc(now - 60_000))), null)
        })
        onPersistenceWorker { capture.snapshot() }
        assertEquals(0, db.audioActivitySampleDao().count())
    }
}
