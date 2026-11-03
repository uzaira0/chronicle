package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.core.ObservationScope
import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HealthOverlapAuditTest {
    @Test fun lateRecordRemainsInsideOverlapAfterProcessRestartAndConsentFloor() {
        val hour = 3_600_000L
        var saved: Long? = 13 * hour
        val checkpoint = object : HealthMetricCheckpoint {
            override fun read() = saved
            override fun write(endMillis: Long) { saved = endMillis }
        }
        val ids = mutableSetOf<String>()
        repeat(2) {
            val restarted = HealthMetricReadCoordinator(checkpoint, consentScope = { ObservationScope("owner", 10 * hour) })
            val records = restarted.read(14 * hour + it) { start, end ->
                assertEquals(10 * hour, start)
                listOf(9 * hour to "before-consent", 12 * hour to "late").filter { it.first in start until end }
            }
            records.forEach { ids.add(it.second) }
            restarted.acknowledge()
        }
        assertEquals(setOf("late"), ids)
    }

    @Test fun acknowledgedProviderRecordIsNotQueuedAfterSourceRestart() {
        // OPEN excludes Health Connect; the production persistence gate refuses it there.
        assumeTrue(DistributionModulePolicy.supports(CollectionModuleId.HEALTH_CONNECT))
        val hour = 3_600_000L
        onPersistenceWorker {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val module = CollectionModuleId.HEALTH_CONNECT
            AuditStores.install(context, true,
                mapOf(module to CollectionModuleSetting(true)))
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            ResearchPersistenceGate.initialize(context)
            HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
            val now = System.currentTimeMillis()
            val record = StepsRecord(
                Instant.ofEpochMilli(now - 2 * hour), ZoneOffset.UTC,
                Instant.ofEpochMilli(now - hour), ZoneOffset.UTC, 100,
                Metadata.manualEntryWithId("late-record"))
            val client = healthConnectTestClient(permissions = {
                setOf(HealthPermission.getReadPermission(StepsRecord::class),
                    ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ)
            }) { request ->
                val start = request.timeRangeFilter.startTime!!
                val end = request.timeRangeFilter.endTime!!
                listOf(record).filter { it.startTime >= start && it.startTime < end }
            }
            fun source() = AndroidHealthMetricSource(context, clientProvider = { client }, nowMillis = { now })
            val scope = ResearchPersistenceGate.observationScope(context,module)!!
            val prefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
            prefs.edit().clear().putLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS),now - hour)
                .putString(healthCheckpointScopeKey(HealthConnectRecordType.STEPS),scope.key).commit()
            val first = source()
            assertEquals("late provider insertion is still read",1,first.read().size)
            first.acknowledgeRead()
            assertEquals(1,prefs.getStringSet(healthCheckpointSeenKey(HealthConnectRecordType.STEPS),emptySet())!!.size)
            val restarted = source()
            assertTrue("acknowledged overlap record is not queued twice after restart",restarted.read().isEmpty())
            restarted.acknowledgeRead()
        }
    }
}
