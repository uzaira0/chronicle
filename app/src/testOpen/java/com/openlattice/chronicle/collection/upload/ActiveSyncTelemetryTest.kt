package com.openlattice.chronicle.collection.upload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModules
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_IMMEDIATE_WORK_NAME
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_WORK_NAME
import com.openlattice.chronicle.services.sync.ChronicleSyncStrategy
import com.openlattice.chronicle.services.sync.ChronicleSyncWorker
import com.openlattice.chronicle.services.sync.SyncRuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import androidx.work.Configuration

@RunWith(RobolectricTestRunner::class)
class ActiveSyncTelemetryTest {
    @get:org.junit.Rule val persistence = BackgroundPersistenceRule()

    @Test fun coordinatedSchedulerReportsItsActiveWorkNamesAndEnqueuedState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TestStores.install(context, enrolled = true)
        SyncRuntimeConfig.save(context, ChronicleSyncStrategy.COORDINATED_COLLECT_THEN_UPLOAD)
        if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().build())
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(CHRONICLE_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(ChronicleSyncWorker::class.java, 15, TimeUnit.MINUTES)
                .setInitialDelay(1, TimeUnit.DAYS).build()).result.get()
        work.enqueueUniqueWork(CHRONICLE_SYNC_IMMEDIATE_WORK_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(ChronicleSyncWorker::class.java)
                .setInitialDelay(1, TimeUnit.DAYS).build()).result.get()
        @Suppress("UNCHECKED_CAST")
        val suppliers = CollectionModules::class.java.getDeclaredField("moduleSuppliers")
            .apply { isAccessible = true }.get(CollectionModules) as Map<CollectionModuleId, (Context) -> DataCollectionModule>
        UploadTelemetryModuleHolder::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(UploadTelemetryModuleHolder, null)
        val telemetry = suppliers.getValue(CollectionModuleId.UPLOAD_TELEMETRY)(context) as UploadTelemetryCollectionModule
        val snapshot = telemetry.snapshot()
        assertEquals(CHRONICLE_SYNC_WORK_NAME, snapshot.periodicWork?.workName)
        assertEquals(CHRONICLE_SYNC_IMMEDIATE_WORK_NAME, snapshot.immediateWork?.workName)
        assertEquals(WorkInfo.State.ENQUEUED.name, snapshot.periodicWork?.state)
        assertEquals(WorkInfo.State.ENQUEUED.name, snapshot.immediateWork?.state)
    }
}
