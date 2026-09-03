package com.openlattice.chronicle.services.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChronicleSyncStrategyTest {
    @Test
    fun parsesConfigValuesCaseAndSeparatorInsensitive() {
        assertEquals(
            ChronicleSyncStrategy.COORDINATED_COLLECT_THEN_UPLOAD,
            ChronicleSyncStrategy.fromConfigValue("coordinated-collect-then-upload")
        )
        assertEquals(
            ChronicleSyncStrategy.COORDINATED_UPLOAD_THEN_COLLECT,
            ChronicleSyncStrategy.fromConfigValue("COORDINATED_UPLOAD_THEN_COLLECT")
        )
    }

    @Test
    fun defaultsUnknownValuesToCoordinatedCollectThenUpload() {
        assertEquals(
            ChronicleSyncStrategy.COORDINATED_COLLECT_THEN_UPLOAD,
            ChronicleSyncStrategy.fromConfigValue(null)
        )
        assertEquals(
            ChronicleSyncStrategy.COORDINATED_COLLECT_THEN_UPLOAD,
            ChronicleSyncStrategy.fromConfigValue("not-a-real-strategy")
        )
    }

    @Test
    fun mergeSyncResultsPreservesFailureAndRetry() {
        assertTrue(
            mergeSyncResults(
                androidx.work.ListenableWorker.Result.success(),
                androidx.work.ListenableWorker.Result.failure()
            ) is androidx.work.ListenableWorker.Result.Failure
        )
        assertTrue(
            mergeSyncResults(
                androidx.work.ListenableWorker.Result.success(),
                androidx.work.ListenableWorker.Result.retry()
            ) is androidx.work.ListenableWorker.Result.Retry
        )
        assertTrue(
            mergeSyncResults(
                androidx.work.ListenableWorker.Result.success(),
                androidx.work.ListenableWorker.Result.success()
            ) is androidx.work.ListenableWorker.Result.Success
        )
    }
}
