package com.openlattice.chronicle

import android.content.Context
import com.openlattice.chronicle.services.lifecycle.persistLifecycleEntries
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.recordForExpectedOwner
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.lifecycle.RoomLifecycleDedupeStore
import com.openlattice.chronicle.collection.sink.LifecycleEventSink
import com.openlattice.chronicle.collection.lifecycle.DeviceLifecycleCollectionModule
import com.openlattice.chronicle.collection.lifecycle.DeviceLifecycleModuleHolder
import android.content.SharedPreferences
import androidx.room.Room
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.SecurePreferencesUnavailableException
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.nextQueueWriteTimestamp
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.createUnreadableChronicleDb
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplication

/** Robolectric replacements for the native stores; enrollment and Room operations stay real. */
internal object EnrollmentTestStores {
    var db: ChronicleDb? = null
    /** When set, opening the database throws it; [failedOpenThread] records where that open ran. */
    var openFailure: LocalStoreRecoveryRequiredException? = null
    @Volatile var failedOpenThread: Thread? = null
    val failedPreferenceOpenThread = AtomicReference<Thread?>()
    var prefs: SharedPreferences? = null
    var api: ChronicleStudyApi? = null
    const val TEST_SERVER_URL = "https://localhost"
    data class ApiRequest(val url: String)
    var expectedApiRequest: ApiRequest? = null
    val apiRequests = CopyOnWriteArrayList<ApiRequest>()

    fun assertApiRequests(expected: ApiRequest, count: Int? = null) {
        val countMatches = count == null || apiRequests.size == count
        if (apiRequests.isEmpty() || apiRequests.any { it != expected } || !countMatches) {
            throw AssertionError("Study API calls must use the expected destination and signing override; calls=${apiRequests.size}, expected=$count")
        }
    }

    fun enrollmentPreview(
        enabled: Set<CollectionModuleId> = setOf(CollectionModuleId.UPLOAD_TELEMETRY),
        issuedAt: OffsetDateTime = OffsetDateTime.now(),
    ) = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(EnrollmentTestStores::class.java.getResource("/enrollment-preview.json")!!.readText())!!.let {
                it.copy(manifest = it.manifest.copy(serverOrigin = TEST_SERVER_URL, issuedAt = issuedAt, expiresAt = issuedAt.plusHours(1),
                    collectionSettings = it.manifest.collectionSettings.copy(
                        modules = CollectionModuleId.values().associateWith { module ->
                            CollectionModuleSetting(
                                enabled = module in enabled)
                        },
                    )))
            }

    fun installApi(api: ChronicleStudyApi): AutoCloseable {
        val previous = this.api
        this.api = api
        return AutoCloseable { this.api = previous }
    }

    /**
     * [mainThreadQueries] false keeps Room's production rule (no read on the main thread), so an activity or receiver
     * test fails where the app would crash; the enrollment is then seeded off the main thread.
     */
    fun install(
        context: Context,
        enrolled: Boolean = false,
        manifestOverrides: Map<CollectionModuleId, CollectionModuleSetting> = emptyMap(),
        mainThreadQueries: Boolean = true,
    ): ChronicleDb {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        failedPreferenceOpenThread.set(null)
        val preferences = context.getSharedPreferences("enrollment-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        prefs = preferences
        // The process-wide storage decision is cached; a previous test's low-space answer must not leak in.
        StorageAdmission.clear(context)
        val database = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .apply { if (mainThreadQueries) allowMainThreadQueries() }.build()
        db = database
        api = null
        apiRequests.clear()
        expectedApiRequest = null
        if (enrolled) {
            if (mainThreadQueries) AuditStores.enroll(context, database, preferences, manifestOverrides)
            else CompletableFuture.runAsync { AuditStores.enroll(context, database, preferences, manifestOverrides) }
                .get(30, TimeUnit.SECONDS)
        }
        return database
    }

    fun installUnreadable(context: Context): ChronicleDb = createUnreadableChronicleDb(context).also { db = it }

    /** Keeps enrollment durable while the published gate has no owner, as before cold-start hydration. */
    fun leaveEnrollmentUnpublished(context: Context) = onPersistenceWorker {
        ResearchPersistenceGate.initialize(context)
        val dao = checkNotNull(db).uploadServerDao()
        val enrollment = checkNotNull(dao.getConfiguredServer())
        ResearchPersistenceGate.stop { dao.delete(enrollment.id) }
        dao.insert(enrollment)
        check(ResearchPersistenceGate.captureOwner(context) == null)
        check(dao.getConfiguredServer() == enrollment)
    }

    /** Owns a real lifecycle module per test database, while the process may outlive that database. */
    @Implements(value = DeviceLifecycleModuleHolder::class,
        isInAndroidSdk = false)
    class LifecycleModules {
        private var database: ChronicleDb? = null
        private var module: DeviceLifecycleCollectionModule? = null

        @Implementation @Synchronized fun get(context: Context): DeviceLifecycleCollectionModule {
            val current = ChronicleDb.getInstance(context)
            if (database !== current) {
                database = current
                module = DeviceLifecycleCollectionModule(
                    sink = LifecycleEventSink(current.queueEntryData(),
                        ResearchPersistenceGate.guard(context, CollectionModuleId.DEVICE_LIFECYCLE),
                        writeEntries = { persistLifecycleEntries(current, it) }),
                    dedupeStore = RoomLifecycleDedupeStore(current),
                    enrolled = {
                        EnrollmentSettings(context).getParticipationStatus() ==
                            ParticipationStatus.ENROLLED &&
                            CollectionGate.collects(context, CollectionModuleId.DEVICE_LIFECYCLE)
                    },
                    serializeQueueEntry = { JsonSerializer.serializeQueueEntry(it) },
                    nextWriteTimestamp = { current.nextQueueWriteTimestamp(it) },
                    captureLossRecorder = {
                        val owner = ResearchPersistenceGate.captureOwner(context)
                        fun(code: String, count: Int) = recordForExpectedOwner(context, owner,
                            LocalUploadModuleFamily.USAGE_LIFECYCLE,
                            LocalOperationalIssue.valueOf(code), count)
                    },
                )
            }
            return checkNotNull(module)
        }
    }

    /** Reset process-static stores before real startup, then finish startup before a test installs its enrollment. */
    @Implements(value = ChronicleApplication::class, isInAndroidSdk = false)
    class ApplicationStartup : ShadowApplication() {
        @RealObject private lateinit var application: ChronicleApplication

        @Implementation fun onCreate() {
            // Only Robolectric's own application is a process start; a test-built instance attached to it
            // (AndroidSweepReview4RegressionTest) delegates applicationContext to it and keeps the test's stores.
            val processStart = application.applicationContext === application
            if (processStart) install(application)
            Shadow.directlyOn<Void, ChronicleApplication>(application, ChronicleApplication::class.java, "onCreate")
            if (processStart) {
                val started = CountDownLatch(1)
                ResearchPersistenceGate.executeAsync { started.countDown() }
                check(started.await(30, TimeUnit.SECONDS)) { "process start initialization did not finish" }
            }
        }
    }

    @Implements(value = ChronicleDb.Companion::class, isInAndroidSdk = false)
    class Database {
        @Implementation fun getInstance(ignored: Context): ChronicleDb {
            openFailure?.let { failedOpenThread = Thread.currentThread(); throw it }
            return checkNotNull(db) { "Install a database before accessing test storage" }
        }
    }

    @Implements(value = EncryptedPrefsHelper::class, isInAndroidSdk = false)
    class Preferences {
        @Implementation fun getEncryptedPrefs(ignored: Context): SharedPreferences = prefs
            ?: run {
                failedPreferenceOpenThread.compareAndSet(null, Thread.currentThread())
                throw SecurePreferencesUnavailableException(IllegalStateException("Test preferences are unavailable"))
            }
    }

    @Implements(value = UploadWorker::class, isInAndroidSdk = false)
    class StudyApi {
        @Implementation fun getChronicleStudyApi(url: String): ChronicleStudyApi {
            val request = ApiRequest(url)
            apiRequests += request
            val target = api
            return Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
                arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
                val expected = expectedApiRequest ?: db?.uploadServerDao()?.getAll()?.singleOrNull()?.let {
                    ApiRequest(it.url)
                } ?: ApiRequest(TEST_SERVER_URL)
                val unsignedHealth = method.name == "health" && request.url == expected.url
                if (request != expected && !unsignedHealth) {
                    throw AssertionError("Unexpected study API destination")
                }
                try { method.invoke(checkNotNull(target) { "Install a study API before making HTTP requests" }, *(args ?: emptyArray())) }
                catch (error: InvocationTargetException) { throw error.targetException }
            } as ChronicleStudyApi
        }
    }
}
