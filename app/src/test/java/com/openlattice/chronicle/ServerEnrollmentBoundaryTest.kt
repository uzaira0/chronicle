package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.ui.awaitDashboardUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [EnrollmentTestStores.Database::class, EnrollmentTestStores.Preferences::class])
class ServerEnrollmentBoundaryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb

    @Before fun setUp() {
        db = EnrollmentTestStores.install(context)
    }

    @After fun tearDown() { db.close() }

    /** Store builds (open and amazon) refuse the manual editor but keep Withdraw from study reachable. */
    @Test fun storeParticipantCanOnlyWithdrawFromAnExistingServer() {
        val add = Robolectric.buildActivity(ServerEnrollmentActivity::class.java).setup()
        assertTrue(add.get().isFinishing)
        add.destroy()

        val id = db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = "11111111-1111-1111-1111-111111111111",
            participantId = "p1", sourceRandomDeviceId = "d1", apiKey = "key"))
        val controller = Robolectric.buildActivity(ServerEnrollmentActivity::class.java,
            Intent(context, ServerEnrollmentActivity::class.java).putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, id)).setup()
        val activity = controller.get()
        try {
            awaitDashboardUi { activity.findViewById<EditText>(R.id.serverNameText).text.toString() == "study" }
            awaitDashboardUi { activity.findViewById<TextView>(R.id.statsText).text.isNotBlank() }
            assertFalse(activity.isFinishing)
            listOf(R.id.serverNameText, R.id.serverUrlText, R.id.serverStudyIdText,
                R.id.serverParticipantIdText).forEach { assertFalse(activity.findViewById<View>(it).isEnabled) }
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.serverDeleteButton).visibility)
        } finally { controller.destroy() }
    }

    @Test fun enrollmentReadFailureDoesNotDescribeAWithdrawalAttempt() {
        db.close()
        db = EnrollmentTestStores.installUnreadable(context)
        val controller = Robolectric.buildActivity(ServerEnrollmentActivity::class.java,
            Intent(context, ServerEnrollmentActivity::class.java).putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, 1L)).setup()
        try {
            val status = controller.get().findViewById<TextView>(R.id.serverStatusMessage)
            repeat(100) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (status.visibility != View.VISIBLE) Thread.sleep(20)
            }
            assertEquals(context.getString(R.string.server_load_failed), status.text.toString())
        } finally { controller.destroy() }
    }
}
