package com.openlattice.chronicle

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.ui.awaitDashboardUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * After enrollment the dashboard asks one thing at a time: the notification question first, and the battery
 * question only after the participant has answered it. Two Chronicle dialogs are never on screen together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StartupDialogOrderTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun ActivityController<MainActivity>.openDialogs() =
        get().supportFragmentManager.fragments.filterIsInstance<DialogFragment>().filter { it.dialog?.isShowing == true }
            .mapNotNull { it.tag }

    @Test fun notificationsAreAskedFirstAndBatteryOnlyAfterTheAnswer() {
        AuditStores.install(app, enrolled = true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            awaitDashboardUi(attempts = 1000) { controller.openDialogs().isNotEmpty() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(NotificationRationaleDialog.TAG), controller.openDialogs())
            val rationale = controller.get().supportFragmentManager.findFragmentByTag(NotificationRationaleDialog.TAG) as DialogFragment
            (rationale.dialog as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            awaitDashboardUi(attempts = 1000) { controller.openDialogs() == listOf("batteryExemption") }
            assertEquals(listOf("batteryExemption"), controller.openDialogs())
        } finally { controller.pause().stop().destroy() }
    }
}
