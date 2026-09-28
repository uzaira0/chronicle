package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAdmissionTest {
    @Test fun pauseIsOneEpisodeUntilStorageRecovers() {
        val low = LOCAL_STORAGE_RESERVE_BYTES - 1
        assertTrue(StorageAdmission.beginsPauseEpisode(false, low))
        assertFalse(StorageAdmission.beginsPauseEpisode(true, low))
        assertFalse(StorageAdmission.shouldPause(LOCAL_STORAGE_RESERVE_BYTES))
        assertTrue(StorageAdmission.beginsPauseEpisode(false, low))
    }
}
