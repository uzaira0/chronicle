package com.openlattice.chronicle.services.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import java.io.IOException
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationsWorkerTest {
    @get:Rule val backgroundPersistence = BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manifest = MobileReminderConfiguration(ParticipationStatus.ENROLLED, emptyList())
    private var readFailure: Exception? = null
    private var reads = 0
    private var posts = 0
    private lateinit var api: ChronicleStudyApi
    private lateinit var apiCache: MutableMap<String, ChronicleStudyApi>
    private lateinit var cacheKey: String
    private var previousApi: ChronicleStudyApi? = null

    @Before fun setUp() {
        AuditStores.install(context, enrolled = true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(context)
        api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
            when (method.name) {
                "getMobileReminderSchedule" -> {
                    reads++
                    readFailure?.let { throw it }
                    manifest
                }
                "getMobileReminderConfiguration" -> { posts++; manifest }
                else -> error(method.name)
            }
        } as ChronicleStudyApi
        val server = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!!
        cacheKey = Utils.normalizeTrustedServerUrl(server.url) + "|" +
            Utils.mobileSigningSecretFingerprint(server.mobileSigningSecretOverride)
        @Suppress("UNCHECKED_CAST")
        val cache = UploadWorker::class.java.getDeclaredField("studyApiCache").apply { isAccessible = true }
            .get(null) as MutableMap<String, ChronicleStudyApi>
        apiCache = cache
        previousApi = apiCache.put(cacheKey, api)
    }

    @After fun tearDown() {
        previousApi?.let { apiCache[cacheKey] = it } ?: apiCache.remove(cacheKey)
        ChronicleDb.getInstance(context).close()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, null)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    private fun fetch() = EnrollmentSettings(context).let {
        fetchReminderSchedule(api, it.getStudyId(), it.getParticipantId(), "device", "key")
    }

    private fun httpFailure(code: Int) = ChronicleCallException("GET", "url", "", code)

    @Test fun compatibilityErrorsFallBackToThePost() {
        for (code in listOf(404, 405, 500)) {
            readFailure = httpFailure(code)
            val postsBefore = posts
            assertSame(manifest, fetch())
            assertEquals(postsBefore + 1, posts)
        }
        assertEquals(posts, reads)
    }

    private fun assertRetriesWithoutPost(failure: Exception) {
        assumeTrue(BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS)
        readFailure = failure
        val readsBefore = reads
        val result = NotificationsWorker(context, auditWorkerParameters()).doWork()
        assertEquals(failure.toString(), 0, posts)
        assertEquals(failure.toString(), readsBefore + 1, reads)
        assertEquals(failure.toString(), ListenableWorker.Result.retry(), result)
    }

    @Test fun transientHttpErrorsRetryWithoutMintingCodes() {
        for (code in listOf(503, 502, 504)) assertRetriesWithoutPost(httpFailure(code))
    }

    @Test fun ioFailureRetriesWithoutMintingCodes() {
        assertRetriesWithoutPost(IOException("connection interrupted"))
    }

    @Test fun authAndRateLimitRefusalsDoNotFallBack() {
        for (code in listOf(401, 403, 429)) {
            val failure = httpFailure(code)
            readFailure = failure
            assertSame(failure, assertThrows(ChronicleCallException::class.java) { fetch() })
            assertEquals(0, posts)
        }
    }
}
