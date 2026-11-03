package com.openlattice.chronicle

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.descendants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.checkedSQLiteOpenHelperFactory
import com.openlattice.chronicle.ui.SettingsHomeFragment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Server editing exists only in Research; failures are driven through its actual controls. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ServerEnrollmentCrashBoundaryTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private var beforeQuery: (String) -> Unit = {}
    private val reads = AtomicInteger()

    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        EnrollmentTestStores.prefs = context.getSharedPreferences("server-crash-boundary", Context.MODE_PRIVATE).apply {
            edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
                .putString(PARTICIPANT_ID, "participant").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        }
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries()
            .openHelperFactory(checkedSQLiteOpenHelperFactory { sql ->
                if (sql.startsWith("SELECT") && "upload_servers" in sql) reads.incrementAndGet()
                beforeQuery(sql)
            }).build()
        EnrollmentTestStores.db = db
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = "11111111-1111-1111-1111-111111111111", participantId = "participant",
            sourceRandomDeviceId = "device", apiKey = "test-key"))
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
    }

    @After fun tearDown() {
        beforeQuery = {}
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    private fun editor() = Robolectric.buildActivity(ServerEnrollmentActivity::class.java,
        Intent(context, ServerEnrollmentActivity::class.java).putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID,
            db.uploadServerDao().getConfiguredServer()!!.id)).setup()

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("UI result must arrive", condition())
    }

    @Test fun failedServerToggleShowsRecoveryAndKeepsRow() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_server BEFORE UPDATE ON upload_servers BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        val controller = Robolectric.buildActivity(CrashTestHostActivity::class.java).setup()
        val host = controller.get()
        val fragment = SettingsHomeFragment()
        host.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        try {
            val label = context.getString(R.string.settings_pause_uploads)
            awaitUi { (fragment.requireView() as ViewGroup).descendants.filterIsInstance<MaterialButton>().any { it.text.toString() == label } }
            (fragment.requireView() as ViewGroup).descendants.filterIsInstance<MaterialButton>().single { it.text.toString() == label }.performClick()
            awaitUi { host.isFinishing }
            assertTrue(db.uploadServerDao().getConfiguredServer()!!.enabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun serverEditorReadFailuresBecomeUiErrors() {
        val id = db.uploadServerDao().getConfiguredServer()!!.id
        db.openHelper.writableDatabase.execSQL("DROP TABLE upload_servers")
        val controller = Robolectric.buildActivity(ServerEnrollmentActivity::class.java,
            Intent(context, ServerEnrollmentActivity::class.java).putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, id)).setup()
        val activity = controller.get()
        try {
            awaitUi { activity.findViewById<View>(R.id.serverStatusMessage).visibility == View.VISIBLE }
            val previous = reads.get()
            activity.findViewById<View>(R.id.serverDeleteButton).performClick()
            awaitUi { reads.get() > previous }
            assertFalse(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun serverDeleteReadFailureBecomesUiError() {
        val controller = editor()
        val activity = controller.get()
        try {
            awaitUi { activity.findViewById<TextView>(R.id.serverNameText).text.toString() == "study" }
            db.openHelper.writableDatabase.execSQL("DROP TABLE upload_servers")
            activity.findViewById<View>(R.id.serverDeleteButton).performClick()
            awaitUi { activity.findViewById<View>(R.id.serverStatusMessage).visibility == View.VISIBLE }
            assertFalse(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun destroyedServerEditorDiscardsQueuedDeleteDialog() {
        val controller = editor()
        val activity = controller.get()
        awaitUi { activity.findViewById<TextView>(R.id.serverNameText).text.toString() == "study" }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        beforeQuery = { sql ->
            if (sql.startsWith("SELECT") && "upload_servers" in sql) {
                reader.set(Thread.currentThread()); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            activity.findViewById<View>(R.id.serverDeleteButton).performClick()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.pause().stop().destroy()
            release.countDown()
            reader.get().join(TimeUnit.SECONDS.toMillis(5))
            assertFalse("destroyed editor's submitted read must finish", reader.get().isAlive)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(ShadowDialog.getLatestDialog())
        } finally {
            release.countDown()
            if (!activity.isDestroyed) controller.pause().stop().destroy()
        }
    }
}
