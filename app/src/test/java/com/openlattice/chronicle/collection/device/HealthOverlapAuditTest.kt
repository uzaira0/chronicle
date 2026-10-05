package com.openlattice.chronicle.collection.device

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28])
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
            val restarted = HealthMetricReadCoordinator(checkpoint, consentScope = { "owner" to 10 * hour })
            val records = restarted.read(14 * hour + it) { start, end ->
                assertEquals(10 * hour, start)
                listOf(9 * hour to "before-consent", 12 * hour to "late").filter { it.first in start until end }
            }
            records.forEach { ids.add(it.second) }
            restarted.acknowledge()
        }
        assertEquals(setOf("late"), ids)
        // The production source persists its deduplication state across a new source instance.
        com.openlattice.chronicle.collection.state.onPersistenceWorker {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val module = com.openlattice.chronicle.collection.CollectionModuleId.HEALTH_CONNECT
            com.openlattice.chronicle.audit.AuditStores.install(context, true,
                mapOf(module to com.openlattice.chronicle.collection.CollectionModuleSetting(true)))
            com.openlattice.chronicle.collection.state.ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            com.openlattice.chronicle.collection.state.ResearchPersistenceGate.initialize(context)
            HealthConnectScopeStore.of(context).replace(setOf(com.openlattice.chronicle.collection.HealthConnectRecordType.STEPS))
            val now = System.currentTimeMillis()
            val record = androidx.health.connect.client.records.StepsRecord(
                java.time.Instant.ofEpochMilli(now - 2 * hour), java.time.ZoneOffset.UTC,
                java.time.Instant.ofEpochMilli(now - hour), java.time.ZoneOffset.UTC, 100,
                androidx.health.connect.client.records.metadata.Metadata.manualEntryWithId("late-record"))
            val permissions = java.lang.reflect.Proxy.newProxyInstance(
                androidx.health.connect.client.PermissionController::class.java.classLoader,
                arrayOf(androidx.health.connect.client.PermissionController::class.java)) { _, _, _ ->
                setOf(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.StepsRecord::class))
            }
            val client = java.lang.reflect.Proxy.newProxyInstance(
                androidx.health.connect.client.HealthConnectClient::class.java.classLoader,
                arrayOf(androidx.health.connect.client.HealthConnectClient::class.java)) { _, method, args ->
                when (method.name) {
                    "getPermissionController" -> permissions
                    "readRecords" -> {
                        val request = args!![0] as androidx.health.connect.client.request.ReadRecordsRequest<*>
                        val start = request.timeRangeFilter.startTime!!
                        val end = request.timeRangeFilter.endTime!!
                        val records = listOf(record).filter { it.startTime >= start && it.startTime < end }
                        org.robolectric.util.ReflectionHelpers.callConstructor(
                            androidx.health.connect.client.response.ReadRecordsResponse::class.java,
                            org.robolectric.util.ReflectionHelpers.ClassParameter.from(List::class.java, records),
                            org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, null))
                    }
                    else -> error("Unexpected provider call ${method.name}")
                }
            } as androidx.health.connect.client.HealthConnectClient
            fun source() = AndroidHealthMetricSource(context).also { reader ->
                reader.javaClass.getDeclaredField("clientProvider").apply { isAccessible = true }
                    .set(reader, { client })
            }
            val scope = com.openlattice.chronicle.collection.state.ResearchPersistenceGate.observationScope(context,module)!!
            val prefs = context.getSharedPreferences("chronicle_health_connect", android.content.Context.MODE_PRIVATE)
            prefs.edit().clear().putLong("last_end_millis",now - hour).putString("consent_scope",scope.first).commit()
            val first = source()
            assertEquals("late provider insertion is still read",1,first.read().size)
            first.acknowledgeRead()
            assertEquals(1,prefs.getStringSet("seen_records",emptySet())!!.size)
            val restarted = source()
            assertTrue("acknowledged overlap record is not queued twice after restart",restarted.read().isEmpty())
            restarted.acknowledgeRead()
        }
    }
}
