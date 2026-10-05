package com.openlattice.chronicle.audit

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.services.notifications.NotificationPermissionActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class NotificationDenialAuditTest {
    @Test fun runtimeDenialShowsRecoveryAndFollowingTapOpensSettingsWithoutReprompt() {
        val context=ApplicationProvider.getApplicationContext<Context>();AuditStores.install(context,false)
        shadowOf(context as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val settings=android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
        shadowOf(context.packageManager).addResolveInfoForIntent(settings,android.content.pm.ResolveInfo().apply{activityInfo=android.content.pm.ActivityInfo().apply{packageName="android";name="Settings"}})
        val controller=Robolectric.buildActivity(NotificationPermissionActivity::class.java).setup();val activity=controller.get()
        try {
            val button=activity.findViewById<View>(R.id.open_notification_settings_btn)
            button.performClick()
            val requested=shadowOf(activity).lastRequestedPermission
            assertNotNull(requested)
            activity.activityResultRegistry.dispatchResult(requested.requestCode,false)
            assertEquals(activity.getString(R.string.notification_permissions_required),activity.findViewById<TextView>(R.id.notificationPermissionsText).text.toString())
            button.performClick()
            val intent=shadowOf(activity).nextStartedActivityForResult.intent
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,intent.action)
            assertEquals(context.packageName,intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertFalse(activity.isFinishing)
        } finally{controller.pause().stop().destroy()}
    }
}
