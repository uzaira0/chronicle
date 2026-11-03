package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.HealthMetricSampleDao
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationAdmissionHealthTest {
    @Test fun failedAdmissionRejectsTheReadWithoutAcknowledgingIt() {
        for (afterWrite in listOf(false, true)) {
            val failure = IllegalStateException("private transaction evidence")
            val guard = CollectionPersistenceGuard { write ->
                if (afterWrite) write()
                throw failure
            }
            val dao = Proxy.newProxyInstance(
                HealthMetricSampleDao::class.java.classLoader, arrayOf(HealthMetricSampleDao::class.java),
            ) { _, _, _ -> error("An empty read must not reach the DAO") } as HealthMetricSampleDao
            var acknowledgments = 0
            var rejections = 0
            val source = object : HealthMetricSource {
                override fun read(): List<HealthMetricReading> = emptyList()
                override fun acknowledgeRead() { acknowledgments++ }
                override fun rejectRead() { rejections++ }
            }
            val module = HealthMetricCollectionModule(HealthMetricSampleSink(dao, guard), source, { true },
                persistLease = { it() })
            val result = module.sample()
            assertTrue(result is ModuleResult.Failed)
            result as ModuleResult.Failed
            assertSame(failure, result.error)
            assertEquals("observation persistence failed: IllegalStateException", result.redactedMessage)
            assertEquals(0, acknowledgments)
            assertEquals(1, rejections)
        }
    }
}
