package com.openlattice.chronicle.services.sensors

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.lifecycle.INTERACTION_LOW_MEMORY
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sensor service's memory-pressure callback keeps the time Android delivered it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [EnrollmentTestStores.LifecycleModules::class])
class HardwareSensorTrimMemoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() = onPersistenceWorker {
        AuditStores.install(app, enrolled = true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(app)
    }

    @After fun tearDown() = onPersistenceWorker {
        ChronicleDb.getInstance(app).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    @Test fun delayedLifecycleExecutorKeepsTheLowMemoryReceiptTime() {
        val service = Robolectric.buildService(HardwareSensorService::class.java).get()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        DeviceLifecycleEventRecorder.recordObserved(app) {
            blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)); emptyList()
        }
        assertTrue(blocked.await(10, TimeUnit.SECONDS))
        val receipt = try {
            val receivedAt = System.currentTimeMillis()
            service.onTrimMemory(RUNNING_LOW)
            (receivedAt..System.currentTimeMillis()).also { Thread.sleep(5) }
        } finally { release.countDown() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (ChronicleDb.getInstance(app).queueEntryData().getSize() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        val events = ChronicleDb.getInstance(app).queueEntryData().getNextEntries(Int.MAX_VALUE)
            .flatMap { JsonSerializer.deserializeQueueEntry(it.data) }.filterIsInstance<ExtractedUsageEvent>()
            .filter { it.interactionType == INTERACTION_LOW_MEMORY }
        assertEquals(1, events.size)
        val at = events.single().timestamp.toInstant().toEpochMilli()
        assertTrue("$at must fall within receipt $receipt", at in receipt)
    }

    private companion object {
        /** ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW: the device is low on memory while this process runs. */
        const val RUNNING_LOW = 10
    }
}
