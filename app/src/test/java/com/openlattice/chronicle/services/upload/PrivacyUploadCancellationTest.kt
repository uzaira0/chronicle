package com.openlattice.chronicle.services.upload

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.utils.Utils
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PrivacyUploadCancellationTest {
    @Before fun installEnrollment() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
    }

    @After fun closeEnrollment() {
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    @Test fun privacyMutationsAcquireWriteLockDuringTricklingCollectedCall() {
        for (kind in listOf(ResearchPersistenceGate.PrivacyOperationKind.WITHDRAWAL,
            ResearchPersistenceGate.PrivacyOperationKind.MODULE_DISCARD,
            ResearchPersistenceGate.PrivacyOperationKind.PARTICIPANT_DECISION)) {
            tricklingCall { call, pool ->
                val uploading = pool.submit { ResearchPersistenceGate.withReadLease { consume(call) } }
                RunningUpload(uploading) { pool.submit { ResearchPersistenceGate.stop(
                    ResearchPersistenceGate.PrivacyOperation(kind)) {} }.get(2, TimeUnit.SECONDS) }
            }
        }
    }

    @Test fun routineStopsLetAdmittedCollectedCallsFinish() {
        for (kind in listOf(null, ResearchPersistenceGate.PrivacyOperationKind.SETTINGS_APPLY,
            ResearchPersistenceGate.PrivacyOperationKind.ERASURE_REPLAY)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("x".repeat(8 * 1024)).throttleBody(1024, 30, TimeUnit.MILLISECONDS))
                val pool = Executors.newFixedThreadPool(2)
                val call = Utils.createRetrofitAdapter(server.url("/").toString()).callFactory()
                    .newCall(Request.Builder().url(server.url("/upload")).post(body()).build())
                try {
                    val uploading = pool.submit {
                        ResearchPersistenceGate.withReadLease { call.execute().use { assertNotNull(it.body?.string()) } }
                    }
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    val stopping = pool.submit { ResearchPersistenceGate.stop(kind?.let {
                        ResearchPersistenceGate.PrivacyOperation(it) }) {} }
                    uploading.get(3, TimeUnit.SECONDS)
                    stopping.get(3, TimeUnit.SECONDS)
                    assertFalse("Routine stop cancelled admitted data: $kind", call.isCanceled())
                } finally { call.cancel(); pool.shutdownNow() }
            }
        }
    }

    private data class RunningUpload(val future: Future<*>, val stop: () -> Unit)

    private fun tricklingCall(start: (Call, ExecutorService) -> RunningUpload) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024)).throttleBody(1024, 100, TimeUnit.MILLISECONDS))
            val pool = Executors.newFixedThreadPool(2)
            val call = Utils.createRetrofitAdapter(server.url("/").toString()).callFactory()
                .newCall(Request.Builder().url(server.url("/upload")).post(body()).build())
            try {
                val (uploading, stop) = start(call, pool)
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                stop()
                uploading.get(2, TimeUnit.SECONDS)
                assertTrue("The active collected call must be cancelled", call.isCanceled())
            } finally { call.cancel(); pool.shutdownNow() }
        }
    }

    private fun body() = checkNotNull(CollectedUploadBody.encode(deviceSettingsUploadRows(), AndroidDeviceSettingsEvent::class.java))

    private fun consume(call: Call) {
        try { call.execute().use { it.body?.string() } } catch (_: IOException) { }
    }
}
