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
        sourceDeviceId = "d", lastUploadTime = "2026-10-01T18:00:00Z",
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
}
