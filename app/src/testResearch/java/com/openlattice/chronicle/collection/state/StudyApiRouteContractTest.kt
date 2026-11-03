package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.audio.AudioUploadWorkerDelegate
import com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate
import com.openlattice.chronicle.collection.interaction.InteractionUploadWorkerDelegate
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.services.enrollment.EnrollmentMonitoringWorker
import com.openlattice.chronicle.services.notifications.NotificationsWorker
import com.openlattice.chronicle.services.sensors.SensorSettingsRefreshWorker
import com.openlattice.chronicle.services.sensors.SensorUploadWorkerDelegate
import com.openlattice.chronicle.services.upload.RestrictedApiShadow
import com.openlattice.chronicle.services.upload.RestrictedUploadApiFactory
import com.openlattice.chronicle.services.upload.UploadExecutor
import com.openlattice.chronicle.services.usage.UsageCollectionDelegateBase
import com.openlattice.chronicle.storage.AudioContentSampleEntry
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.audioContentSampleDao
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config

/** Each path acquires its production API factory; only the HTTP transport is replaced. */
@Config(shadows = [RestrictedApiShadow::class])
class StudyApiRouteContractTest : ResearchErasureTestFixture() {
    @Before fun routeToStudyServer() {
        db.uploadServerDao().update(owner.copy(url = "https://route.study.local", apiKey = "route-test-credential"))
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        RestrictedApiShadow.transport = { url ->
            EnrollmentTestStores.apiRequests += EnrollmentTestStores.ApiRequest(url)
            Proxy.newProxyInstance(RestrictedChronicleStudyApi::class.java.classLoader,
                arrayOf(RestrictedChronicleStudyApi::class.java)) { _, _, _ -> throw RouteProbe() } as RestrictedChronicleStudyApi
        }
    }

    private class RouteProbe : RuntimeException()

    private fun assertRoute(count: Int? = null, action: () -> Unit) {
        EnrollmentTestStores.apiRequests.clear()
        withStudyApi { throw RouteProbe() }.use {
            try { action() } catch (_: RouteProbe) { /* Direct delegates propagate transport failures. */ }
        }
        EnrollmentTestStores.assertApiRequests(EnrollmentTestStores.ApiRequest(owner.url), count)
    }

    @Test fun usageUploadAndMetadataUseTheStudyRoute() {
        db.queueEntryData().insertEntry(row())
        assertRoute { UploadExecutor(context, db).uploadForServer(owner, { false }) { throw it } }
        // Usage collection only queues locally; it builds no study API (an unused client once was).
        EnrollmentTestStores.apiRequests.clear()
        val collector = object : UsageCollectionDelegateBase(context) {
            override fun monitorUsage(): Boolean = true
        }
        assertTrue(collector.execute())
        assertEquals(emptyList<EnrollmentTestStores.ApiRequest>(), EnrollmentTestStores.apiRequests.toList())
    }

    @Test fun notificationsAndEnrollmentMonitoringUseTheStudyRoute() {
        assertRoute(1) { NotificationsWorker(context, auditWorkerParameters()).doWork() }
        assertRoute(1) { EnrollmentMonitoringWorker(context, auditWorkerParameters()).doWork() }
    }

    @Test fun acknowledgmentsUseTheStudyRoute() {
        enrollWithSensorAvailability(enabled = true)
        consent(CollectionModuleId.BATTERY_TELEMETRY, ParticipantDecision.DECLINED)
        assertRoute(1) {
            onPersistenceWorker {
                coordinator.applyDecisions(setOf(CollectionModuleId.BATTERY_TELEMETRY), emptySet(), ConsentTrigger.PARTICIPANT_TOGGLE)
            }
        }
    }

    @Test fun settingsUseTheStudyRoute() {
        val methods = mutableListOf<String>()
        val settings = EnrollmentTestStores.enrollmentPreview().manifest.collectionSettings
        EnrollmentTestStores.apiRequests.clear()
        withStudyApi { method ->
            methods += method
            when (method) {
                "getDataCollectionSettings" -> settings
                else -> error("Unexpected settings call")
            }
        }.use { assertTrue(onPersistenceWorker { coordinator.sync() }) }
        assertEquals(listOf("getDataCollectionSettings"), methods)
        EnrollmentTestStores.assertApiRequests(EnrollmentTestStores.ApiRequest(owner.url), 1)
    }

    @Test fun sensorSettingsUseTheStudyRoute() {
        enrollWithSensorAvailability(enabled = true)
        SensorSettings(context).applyResolvedSensors(mapOf(
            AndroidSensorType.accelerometer to AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer)),
        ))
        assertRoute { SensorSettingsRefreshWorker(context, auditWorkerParameters()).doWork() }
    }

    @Test fun sensorUploadsUseTheStudyRoute() {
        db.sensorSampleDao().insertAll(listOf(SensorSampleEntry(UUID(0, 1).toString(), AndroidSensorType.accelerometer.name,
            "2026-10-01T00:00:00Z", "UTC", 1f, 2f, 3f, null, null)))
        assertRoute { SensorUploadWorkerDelegate(context, db).execute() }
    }

    @Test fun restrictedUploadRouteCheckRejectsTheWrongDestination() {
        db.sensorSampleDao().insertAll(listOf(SensorSampleEntry(UUID(0, 1).toString(), AndroidSensorType.accelerometer.name,
            "2026-10-01T00:00:00Z", "UTC", 1f, 2f, 3f, null, null)))
        assertThrows(AssertionError::class.java) {
            assertRoute {
                SensorUploadWorkerDelegate(context, db, restrictedApiFor = { target ->
                    RestrictedUploadApiFactory.get("https://wrong.invalid")
                }).execute()
            }
        }
    }

    @Test fun audioAndExpansionUploadsUseTheStudyRoute() {
        db.audioContentSampleDao().insertAll(listOf(AudioContentSampleEntry("route-audio", "2026-10-01T00:00:00Z", "UTC",
            "example.player", "title", "artist", "album", null, null)))
        assertRoute { AudioUploadWorkerDelegate(context, db).execute() }
        db.connectivityStateSampleDao().insertAll(listOf(ConnectivityStateSampleEntry("route-connectivity",
            "2026-10-01T00:00:00Z", "UTC", "SNAPSHOT", "WIFI", true, false, true)))
        assertRoute { ExpansionUploadWorkerDelegate(context, db).execute() }
    }

    @Test fun interactionUploadsUseTheStudyRoute() {
        queuedInteraction(erase = false, expectedRows = 1)
        assertRoute { InteractionUploadWorkerDelegate(context, db).execute() }
    }
}
