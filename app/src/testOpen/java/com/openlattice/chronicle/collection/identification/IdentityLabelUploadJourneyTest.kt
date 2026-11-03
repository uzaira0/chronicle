package com.openlattice.chronicle.collection.identification

import android.app.AppOpsManager
import android.app.Application
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.UserIdentificationActivity
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.receivers.lifecycle.UnlockDeviceReceiver
import com.openlattice.chronicle.services.notifications.IDENTIFY_USER_CHANNEL_ID
import com.openlattice.chronicle.services.upload.UploadWorkerDelegate
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.services.upload.uploadedBodyBytes
import com.openlattice.chronicle.services.usage.UsageModuleCollectionDelegate
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.ui.awaitDashboardUi
import com.openlattice.chronicle.utils.Utils
import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The shared-phone prompt an unlock posts is tapped and answered through the real
 * Activity, labels the usage that follows it in the bytes the phone uploads. With CHRONICLE_ANDROID_IDENTITY_DIR set,
 * the uploaded bodies are written there for the backend ingestion/export half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IdentityLabelUploadJourneyTest {
    private companion object {
        /** Read by the server-side AndroidReplayTest; every other file in the directory is an uploaded body. */
        const val EXPECTED_TIMES = "expected-times.json"
    }

    @get:Rule val workManagerRule = WorkManagerRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val uploaded = mutableListOf<ByteArray>()
    /** The instant each package was fed to the usage poller: the consumer checks the export against these, not the bytes. */
    private val fed = linkedMapOf<String, Long>()
    private val unlockReceiver = UnlockDeviceReceiver()

    @Before fun setUp() {
        onPersistenceWorker {
            AuditStores.install(app, enrolled = true)
            ResearchPersistenceGate.initialize(app)
        }
        app.registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_EXPORTED)
        Utils.createNotificationChannel(app)
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            app.applicationInfo.uid, app.packageName, AppOpsManager.MODE_ALLOWED)
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    }

    @After fun tearDown() = onPersistenceWorker {
        app.unregisterReceiver(unlockReceiver)
        ChronicleDb.getInstance(app).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    /** An unlock posts the prompt; tapping it opens the real Activity, where [button] is pressed (null: ignored). */
    private fun unlockAndAnswer(button: Int?) {
        notifications.cancelAll()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        awaitDashboardUi { shadowOf(notifications).allNotifications.any { it.channelId == IDENTIFY_USER_CHANNEL_ID } }
        val prompt = shadowOf(notifications).allNotifications.single { it.channelId == IDENTIFY_USER_CHANNEL_ID }
        if (button == null) return
        val tapped = shadowOf(prompt.contentIntent).savedIntent
        val controller = Robolectric.buildActivity(UserIdentificationActivity::class.java, tapped).setup()
        try {
            controller.get().findViewById<Button>(button).performClick()
            awaitDashboardUi { controller.get().isFinishing }
        } finally { controller.pause().stop().destroy() }
    }

    /** One foreground app at the current instant, strictly after whatever came before. */
    private fun use(app: String): Long {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val at = System.currentTimeMillis()
        shadowOf(this.app.getSystemService(UsageStatsManager::class.java)).addEvent(ShadowUsageStatsManager.EventBuilder
            .buildEvent().setPackage(app).setClass("$app.Main").setEventType(UsageEvents.Event.ACTIVITY_RESUMED)
            .setTimeStamp(at).build())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        fed[app] = at
        return at
    }

    /** Polls usage and uploads it through the production paths; returns the uploaded usage rows by package. */
    private fun pollAndUpload(): Map<String, ChronicleUsageEvent> {
        onPersistenceWorker { UsageModuleCollectionDelegate(app).execute() }
        val sent = mutableListOf<ChronicleUsageEvent>()
        EnrollmentTestStores.installApi(studyApi { method, args ->
            check(method == "uploadAndroidUsageEventData") { method }
            uploaded += uploadedBodyBytes(args!![3])
            val rows = decodedUploadBody<ChronicleData>(args[3]).filterIsInstance<ChronicleUsageEvent>()
            sent += rows
            UploadReceipt(rows.mapNotNullTo(linkedSetOf()) { it.clientEventId }, emptySet(), 1)
        }).use { assertEquals(0, onPersistenceWorker { UploadWorkerDelegate(app, ChronicleDb.getInstance(app)).execute() }) }
        return sent.filter { it.appPackageName.startsWith("example.") && it.interactionType == "Activity Resumed" }
            .associateBy { it.appPackageName }
    }

    @Test fun eachAnswerLabelsTheUsageAfterItInTheUploadedBytes() {
        unlockAndAnswer(R.id.participant_user_btn)
        use("example.participant")
        unlockAndAnswer(R.id.someone_else_user_btn)
        use("example.other")
        unlockAndAnswer(R.id.participant_user_btn)
        use("example.back")
        unlockAndAnswer(null)
        use("example.unanswered")

        val rows = pollAndUpload()

        val expectedLabels = mapOf("example.participant" to "Study participant", "example.other" to "Someone else",
            "example.back" to "Study participant", "example.unanswered" to "")
        assertEquals(expectedLabels, rows.mapValues { it.value.user })
        // in_app_activity_class is accepted: the platform poller keeps each event's own Activity class.
        assertEquals(expectedLabels.mapValues { "${it.key}.Main" }, rows.mapValues { it.value.activityClass })
        val owner = ChronicleDb.getInstance(app).uploadServerDao().getConfiguredServer()!!
        rows.values.forEach {
            assertEquals(owner.studyId, it.studyId.toString())
            assertEquals(owner.participantId, it.participantId)
        }
        assertEquals("each event keeps the instant it happened", fed, rows.mapValues { it.value.timestamp.toInstant().toEpochMilli() })
        System.getenv("CHRONICLE_ANDROID_IDENTITY_DIR")?.let(::File)?.let { dir ->
            dir.mkdirs()
            uploaded.forEachIndexed { index, body -> File(dir, "usage-$index.json").writeBytes(body) }
            File(dir, EXPECTED_TIMES).writeText(JSONObject(fed.mapValues { Instant.ofEpochMilli(it.value).toString() }).toString())
        }
    }
}
