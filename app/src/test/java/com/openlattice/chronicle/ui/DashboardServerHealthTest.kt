package com.openlattice.chronicle.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DashboardServerHealthTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = UploadServerEntity(
        name = "study", url = "https://example.org", studyId = "s", participantId = "p",
        sourceRandomDeviceId = "d", lastUploadTime = "2026-10-01T18:00:00Z",
    )

    @Test fun `offline device never reports a healthy server`() {
        assertEquals(
            context.getString(R.string.server_health_offline),
            DashboardDataRepository.loadServerHealth(context, listOf(server), online = false).message,
        )
    }

    @Test fun `online device with no upload failures reports healthy`() {
        assertEquals(
            context.getString(R.string.server_health_healthy),
            DashboardDataRepository.loadServerHealth(context, listOf(server), online = true).message,
        )
    }

    /** A server refusing every upload (a version contract mismatch) never recovers alone: the participant is sent to the study team. */
    @Test fun `uploads that keep failing say the data is kept and to contact the study team`() {
        fun health(failures: Int) = DashboardDataRepository.loadServerHealth(
            context, listOf(server.copy(consecutiveFailures = failures, lastUploadError = "HTTP_CLIENT_ERROR")), online = true).message
        assertEquals(context.getString(R.string.server_health_status, context.getString(R.string.health_status_degraded).lowercase()),
            health(1))
        assertEquals(context.getString(R.string.server_health_uploads_failing), health(11))
    }
}
