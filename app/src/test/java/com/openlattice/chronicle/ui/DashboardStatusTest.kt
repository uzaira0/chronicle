package com.openlattice.chronicle.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.insertOwnedDay
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** What the participant's dashboard says about collection and uploads on an enrolled pilot phone. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DashboardStatusTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
    }

    @After fun close() {
        StorageAdmission.clear(context)
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        onPersistenceWorker { ResearchPersistenceGate.resetAfterLocalStoreRecovery() }
    }

    private fun snapshot() = onPersistenceWorker { runBlocking { DashboardDataRepository.load(context) } }

    /** The phone runs low on storage: the dashboard says collection is paused for storage, and stops once space returns. */
    @Test fun lowStorageShowsCollectionPausedUntilSpaceReturns() {
        val paused = context.getString(R.string.collection_paused_storage)

        onPersistenceWorker { StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES - 1) }
        assertEquals(paused, snapshot().collection.message)

        onPersistenceWorker { StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES) }
        assertNotEquals(paused, snapshot().collection.message)
    }

    /**
     * The participant taps Upload now: the upload is queued and confirmed, the button rests so repeated taps do not
     * stack uploads, and comes back after five seconds; Details opens and closes the upload history.
     */
    @Test fun uploadNowQueuesOneUploadAndRestsTheButton() {
        val fragment = UploadsFragment()
        withHostedFragment(fragment) {
            val view = fragment.requireView()
            val details = view.findViewById<View>(R.id.uploadsDetails)
            val shown = details.visibility
            view.findViewById<View>(R.id.uploadsDetailsButton).performClick()
            assertNotEquals(shown, details.visibility)
            view.findViewById<View>(R.id.uploadsDetailsButton).performClick()
            assertEquals(shown, details.visibility)

            val button = view.findViewById<View>(R.id.uploadNowButton)
            button.performClick()
            assertFalse(button.isEnabled)
            awaitDashboardUi { ShadowToast.getTextOfLatestToast() == context.getString(R.string.upload_queued) }
            assertFalse(button.isEnabled)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(button.isEnabled)
        }
    }

    /** The Uploads history shows what this phone sent and failed to send today. */
    @Test fun uploadHistoryShowsTodaysCounts() {
        val today = LocalDate.now().toString()
        onPersistenceWorker {
            val db = ChronicleDb.getInstance(context)
            val server = db.uploadServerDao().getConfiguredServer()!!
            db.uploadStatsDao().insertOwnedDay(server, today)
            db.uploadStatsDao().incrementUsageCount(server.id, today, 3)
            db.uploadStatsDao().incrementBatteryCount(server.id, today, 1)
            db.uploadStatsDao().incrementUsageFailureCount(server.id, today, 2)
        }

        val shown = if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            context.getString(R.string.upload_stats_daily_research, today, 3, 0, 1, 2, 0, 0)
        } else context.getString(R.string.upload_stats_daily, today, 3, 1, 2, 0)
        assertEquals(listOf(shown), snapshot().servers.single().history)
    }
}
