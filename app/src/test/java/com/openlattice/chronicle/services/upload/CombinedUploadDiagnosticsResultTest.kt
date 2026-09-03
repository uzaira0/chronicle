package com.openlattice.chronicle.services.upload

import androidx.work.ListenableWorker
import com.openlattice.chronicle.collection.upload.COMBINED_UPLOAD_MAX_ATTEMPTS
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedUploadDiagnosticsResultTest {
    @Test
    fun diagnosticsRetryWithoutPermanentlyStoppingPeriodicWork() {
        assertEquals(
            ListenableWorker.Result.retry(),
            mergeDiagnosticUploadResult(0, ListenableWorker.Result.success(), diagnosticFailures = 1),
        )
        assertEquals(
            ListenableWorker.Result.success(),
            mergeDiagnosticUploadResult(
                COMBINED_UPLOAD_MAX_ATTEMPTS + 1,
                ListenableWorker.Result.success(),
                diagnosticFailures = 1,
                logPending = {},
            ),
        )
    }

    @Test
    fun diagnosticsDoNotMaskPrimaryFailureOrChangeAnAcknowledgedRun() {
        assertEquals(
            ListenableWorker.Result.failure(),
            mergeDiagnosticUploadResult(0, ListenableWorker.Result.failure(), diagnosticFailures = 1),
        )
        assertEquals(
            ListenableWorker.Result.success(),
            mergeDiagnosticUploadResult(0, ListenableWorker.Result.success(), diagnosticFailures = 0),
        )
    }
}
