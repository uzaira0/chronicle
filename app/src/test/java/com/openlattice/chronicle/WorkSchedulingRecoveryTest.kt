package com.openlattice.chronicle

import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_WORK_NAME
import com.openlattice.chronicle.ui.awaitDashboardUi
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** WorkManager's own database failed while the app started (storage briefly unavailable after an update). */
@RunWith(RobolectricTestRunner::class)
class WorkSchedulingRecoveryTest {
    @get:Rule val workManager = WorkManagerRule()
    private val app = ApplicationProvider.getApplicationContext<ChronicleApplication>()

    private fun syncState() = WorkManager.getInstance(app).getWorkInfosForUniqueWork(CHRONICLE_SYNC_WORK_NAME)
        .get(5, TimeUnit.SECONDS).singleOrNull()?.state

    /** The background retry reconciles WorkManager's interrupted startup, then schedules the periodic sync. */
    @Test fun aFailedWorkManagerStartIsReconciledAndTheSyncScheduledByTheRetry() {
        app.workManagerConfiguration.initializationExceptionHandler!!.accept(SQLiteException("disk I/O error"))
        assertTrue("collection reports scheduling as unavailable", WorkSchedulingStatus.unavailable)
        awaitDashboardUi { !WorkSchedulingStatus.unavailable }
        assertEquals(WorkInfo.State.ENQUEUED, syncState())
    }

    /** A later enqueue that succeeds does not hide that the startup reconciliation is still owed. */
    @Test fun aSuccessfulEnqueueDoesNotClearAnOwedStartupReconciliation() {
        app.workManagerConfiguration.initializationExceptionHandler!!.accept(SQLiteException("disk I/O error"))
        WorkSchedulingStatus.schedulingSucceeded()
        assertTrue(WorkSchedulingStatus.unavailable)
        awaitDashboardUi { !WorkSchedulingStatus.unavailable }
    }
}
