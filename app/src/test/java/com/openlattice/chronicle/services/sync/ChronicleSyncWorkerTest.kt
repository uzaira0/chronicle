package com.openlattice.chronicle.services.sync

import android.app.Application
import androidx.work.ListenableWorker.Result
import com.openlattice.chronicle.services.upload.retryOnStorageFailure
import java.io.IOException
import java.io.UncheckedIOException
import javax.net.ssl.SSLException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChronicleSyncWorkerTest {
    @Test fun mergeSyncResultsPreservesFailureAndRetry() {
        assertEquals(Result.failure(), mergeSyncResults(Result.success(), Result.failure()))
        assertEquals(Result.retry(), mergeSyncResults(Result.retry(), Result.success()))
        assertEquals(Result.success(), mergeSyncResults(Result.success(), Result.success()))
    }

    @Test fun storageFailureRequestsRetry() {
        assertEquals(Result.retry(), retryOnStorageFailure { throw IOException("Storage temporarily unavailable") })
    }

    /** The API adapter rethrows a dropped connection unchecked; the run still retries. */
    @Test fun aConnectionResetRethrownUncheckedRetries() {
        assertEquals(Result.retry(), retryOnStorageFailure { throw UncheckedIOException(SSLException("Connection reset")) })
    }

    @Test fun cancellationAndProgrammingErrorsKeepTheirMeaning() {
        assertThrows(CancellationException::class.java) { retryOnStorageFailure { throw CancellationException("Stopped") } }
        assertThrows(IllegalArgumentException::class.java) {
            retryOnStorageFailure { throw IllegalArgumentException("Invalid configuration") }
        }
        assertThrows(AssertionError::class.java) { retryOnStorageFailure { throw AssertionError("Invariant violated") } }
    }
}
