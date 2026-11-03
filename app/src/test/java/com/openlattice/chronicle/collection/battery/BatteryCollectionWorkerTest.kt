package com.openlattice.chronicle.collection.battery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The periodic battery worker on an enrolled pilot phone with a 15-minute battery interval. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BatteryCollectionWorkerTest {
    @get:Rule val persistence = BackgroundPersistenceRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val schedule get() = ExpansionPullSchedule(context)

    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        context.getSharedPreferences("expansion_pull_schedule", Context.MODE_PRIVATE).edit().clear().commit()
        schedule.setIntervalSeconds(CollectionModuleId.BATTERY_TELEMETRY, 900)
    }

    @After fun close() {
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    private fun run() = onPersistenceWorker { BatteryCollectionWorker(context, auditWorkerParameters()).doWork() }

    /** Android has not reported the battery state yet: the run retries and the next run samples again. */
    @Test fun aBatteryStateNotYetReportedIsRetriedAndTheIntervalStaysDue() {
        assertEquals(ListenableWorker.Result.retry(), run())
        assertNotNull("the failed run released its interval claim", schedule.claimIfDue(CollectionModuleId.BATTERY_TELEMETRY))
    }

    /** A run inside the 15-minute interval takes no sample and succeeds. */
    @Test fun aRunInsideTheIntervalTakesNoSample() {
        schedule.markRan(CollectionModuleId.BATTERY_TELEMETRY, System.currentTimeMillis() - 60_000)
        assertEquals(ListenableWorker.Result.success(), run())
        assertNull(schedule.claimIfDue(CollectionModuleId.BATTERY_TELEMETRY))
    }
}
