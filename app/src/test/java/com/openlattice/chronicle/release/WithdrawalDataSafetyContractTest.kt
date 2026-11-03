package com.openlattice.chronicle.release

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.ServerEnrollmentActivity
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WithdrawalDataSafetyContractTest {
    @Test fun declaredDeletionCapabilityMatchesTheShippedStudyServerControl() {
        val properties = Properties().apply { load(resource("privacy.properties").reader()) }
        assumeTrue(properties.getProperty("play_variant").removeSuffix("Release") == BuildConfig.FLAVOR)
        val context = ApplicationProvider.getApplicationContext<Context>()
        AuditStores.install(context, enrolled = true)
        val db = ChronicleDb.getInstance(context)
        val server = db.uploadServerDao().getConfiguredServer()!!
        val controller = Robolectric.buildActivity(ServerEnrollmentActivity::class.java,
            Intent(context, ServerEnrollmentActivity::class.java).putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, server.id)).setup()
        try {
            val activity = controller.get()
            repeat(50) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            val button = activity.findViewById<View>(R.id.serverDeleteButton)
            val reachable = !activity.isFinishing && button?.isShown == true && button.isEnabled
            assertEquals(reachable, properties.getProperty("deletion_request_supported").toBooleanStrict())
            if (reachable) {
                assertTrue(button.performClick())
                repeat(50) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                assertTrue("Withdrawal confirmation is reachable", ShadowDialog.getLatestDialog()?.isShowing == true)
                assertTrue(resource("data-safety.md").contains(context.getString(R.string.server_delete_confirm_action)))
                assertTrue(resource("listing.md").contains(context.getString(R.string.server_delete_confirm_action)))
            }
        } finally {
            controller.pause().stop().destroy()
            EnrollmentTestStores.db = null
            EnrollmentTestStores.prefs = null
            db.close()
        }
    }

    private fun resource(name: String): String = checkNotNull(javaClass.getResourceAsStream("/$name"))
        .bufferedReader().use { it.readText() }
}
