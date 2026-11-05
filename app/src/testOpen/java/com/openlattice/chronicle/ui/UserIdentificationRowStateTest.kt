package com.openlattice.chronicle.ui

import android.Manifest
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionConsentCopy
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.IDENTIFY_USER
import com.openlattice.chronicle.utils.Utils
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Data Sharing's user-identification row says what Settings' Identify device user switch and the unlock prompt
 * actually do: "collecting" only while the switch is on and the prompt can be shown, otherwise what to turn on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UserIdentificationRowStateTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun switchOnAndPromptsAllowedReadsCollecting() =
        assertRowStatus(identify = true, notifications = true, app.getString(R.string.ds_status_on))

    @Test fun switchOffInSettingsReadsOffWithWhereToTurnItOn() =
        assertRowStatus(identify = false, notifications = true, app.getString(R.string.cap_identify_off))

    @Test fun notificationsBlockedReadsPaused() =
        assertRowStatus(identify = true, notifications = false, app.getString(R.string.cap_identify_paused))

    private fun assertRowStatus(identify: Boolean, notifications: Boolean, status: String) {
        AuditStores.install(app, enrolled = true)
        // The channels Chronicle creates before its first prompt (reminder worker or first post).
        Utils.createNotificationChannel(app)
        onPersistenceWorker { EncryptedPrefsHelper.getEncryptedPrefs(app).edit().putBoolean(IDENTIFY_USER, identify).commit() }
        if (!notifications) shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val label = CollectionConsentCopy.label(app, CollectionModuleId.USER_IDENTIFICATION)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val host = controller.get()
            awaitDashboardUi(attempts = 1000) { host.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null }
            host.selectTab(R.id.nav_data_sharing)
            host.supportFragmentManager.executePendingTransactions()
            var row: String? = null
            awaitDashboardUi(attempts = 1000) {
                row = texts(host.findViewById(R.id.mainFragmentContainer)).firstOrNull { it.startsWith("$label\n") }
                row != null
            }
            assertEquals("$label\n$status", row)
        } finally { controller.pause().stop().destroy() }
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
