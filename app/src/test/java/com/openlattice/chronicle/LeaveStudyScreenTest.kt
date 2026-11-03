package com.openlattice.chronicle

import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.insertOwnedDay
import com.openlattice.chronicle.ui.awaitDashboardUi
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/** The study server screen of an enrolled pilot phone: its upload history and Leave study. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LeaveStudyScreenTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var serverId = 0L

    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        serverId = onPersistenceWorker { ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!!.id }
    }

    @After fun close() {
        onPersistenceWorker { ResearchPersistenceGate.resetAfterLocalStoreRecovery() }
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
    }

    private fun screen(block: (ActivityController<ServerEnrollmentActivity>) -> Unit) {
        val controller = Robolectric.buildActivity(ServerEnrollmentActivity::class.java,
            Intent(context, ServerEnrollmentActivity::class.java).putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, serverId)).setup()
        try { block(controller) } finally { controller.pause().stop().destroy() }
    }

    private fun ActivityController<ServerEnrollmentActivity>.leaveStudy() {
        get().findViewById<View>(R.id.serverDeleteButton).performClick()
        awaitDashboardUi { ShadowDialog.getLatestDialog()?.isShowing == true }
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
    }

    @Test fun theUploadHistoryListsTodaysCounts() {
        val today = LocalDate.now()
        onPersistenceWorker {
            val db = ChronicleDb.getInstance(context)
            db.uploadStatsDao().insertOwnedDay(db.uploadServerDao().getById(serverId)!!, today.toString())
            db.uploadStatsDao().incrementUsageCount(serverId, today.toString(), 12)
        }
        screen { controller ->
            val line = context.getString(R.string.server_upload_stat_line, uploadStatDay(today), 12, 0, 0, 0, 0, 0)
            val expected = context.getString(R.string.server_upload_history, line)
            val stats = controller.get().findViewById<TextView>(R.id.statsText)
            awaitDashboardUi { stats.text.toString() == expected }
        }
    }

    @Test fun confirmingLeaveStudyStartsTheWithdrawalAndClosesTheScreen() {
        screen { controller ->
            controller.leaveStudy()
            awaitDashboardUi { controller.get().isFinishing }
            assertEquals(WithdrawalState.PENDING, onPersistenceWorker { WithdrawalStateStore(context).state() })
        }
    }

    /** The request cannot be saved (storage full): the participant is told it failed and can press Leave study again. */
    @Test fun aLeaveStudyThatCannotBeSavedSaysSoAndCanBeRetried() {
        val healthy = checkNotNull(EnrollmentTestStores.prefs)
        EnrollmentTestStores.prefs = object : SharedPreferences by healthy {
            // Every chained call returns this editor, so the final commit is the refused one.
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by healthy.edit() {
                override fun putString(key: String, value: String?) = this
                override fun putStringSet(key: String, values: MutableSet<String>?) = this
                override fun putBoolean(key: String, value: Boolean) = this
                override fun putLong(key: String, value: Long) = this
                override fun putInt(key: String, value: Int) = this
                override fun remove(key: String) = this
                override fun clear() = this
                override fun commit() = false
                override fun apply() = Unit
            }
        }
        screen { controller ->
            controller.leaveStudy()
            val button = controller.get().findViewById<View>(R.id.serverDeleteButton)
            awaitDashboardUi { ShadowToast.getTextOfLatestToast() == context.getString(R.string.server_delete_failed) }
            awaitDashboardUi { button.isEnabled }
            assertTrue(!controller.get().isFinishing)
        }
        EnrollmentTestStores.prefs = healthy
        assertEquals(WithdrawalState.NONE, onPersistenceWorker { WithdrawalStateStore(context).state() })
    }
}
