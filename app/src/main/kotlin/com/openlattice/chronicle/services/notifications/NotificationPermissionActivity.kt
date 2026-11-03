@file:Suppress("DEPRECATION")

package com.openlattice.chronicle.services.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openlattice.chronicle.Enrollment
import com.openlattice.chronicle.R
import com.openlattice.chronicle.doMainActivity
import com.openlattice.chronicle.utils.DeviceSettingsNavigator
import com.openlattice.chronicle.utils.Utils

class NotificationPermissionActivity : AppCompatActivity() {
    private lateinit var openSettingsBtn: Button
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted && hasNotificationPermission(this)) {
            Log.i(Enrollment::class.java.name, "Notifications enabled!")
            doMainActivity(this, intent)
            finish()
        } else {
            updateViewForUserAbort()
            Log.e(Enrollment::class.java.name, "Unable to send notifications!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_notification)
        Utils.createNotificationChannel(this)
        if (hasNotificationPermission(this)) {
            doMainActivity(this, intent)
            finish()
        }

        openSettingsBtn = findViewById(R.id.open_notification_settings_btn)
        openSettingsBtn.setOnClickListener {
            requestPostNotificationsPermissions()
        }
    }

    @Deprecated("Remains for legacy android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            if (!hasNotificationPermission(applicationContext)) {
                updateViewForUserAbort()
            } else {
                doMainActivity(this, intent)
                finish()
            }
        }

    }

    private fun requestPostNotificationsPermissions() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPostNotificationsRuntimePermission(this)
        ) {
            requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val packageName = applicationContext.packageName
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivityForResult(DeviceSettingsNavigator.resolvedIntent(this, intent), 1)
        }

        //These permissions is actually for NotificationListenerService
        //Which we will need to hook to receive notifications to settings changes.
        //This might not be as important to hook at the moment, since we can directly
        //check notification settings changes every time uploadWorker runs.
        //Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    }

    private fun updateViewForUserAbort() {
        val permissionsText = findViewById<TextView>(R.id.notificationPermissionsText)
        permissionsText.text = getString(R.string.notification_permissions_required)
        openSettingsBtn.setOnClickListener {
            val settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
            startActivityForResult(DeviceSettingsNavigator.resolvedIntent(this, settingsIntent), 1)
        }
    }
}

fun hasPostNotificationsRuntimePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

/** Runtime permission, app-level switch, and [channelId] not muted: the notification will actually show. */
fun hasNotificationPermission(context: Context, channelId: String = CHANNEL_ID): Boolean =
    hasPostNotificationsRuntimePermission(context) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        channelDelivers(context, channelId)

/** The channel exists, is not muted, and its group (Android 9+) is not blocked. */
private fun channelDelivers(context: Context, channelId: String): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    val channel = manager.getNotificationChannel(channelId) ?: return false
    if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || channel.group == null) return true
    return manager.getNotificationChannelGroup(channel.group)?.isBlocked != true
}

/**
 * [channelId] still pops on screen. A channel the participant set to Silent, or with "Pop on
 * screen" off, only lands in the shade, so a prompt shown after unlock goes unseen.
 */
fun notificationPopsUp(context: Context, channelId: String): Boolean {
    val importance = context.getSystemService(NotificationManager::class.java)
        ?.getNotificationChannel(channelId)?.importance ?: return true
    return importance >= NotificationManager.IMPORTANCE_HIGH
}

/**
 * The settings page that fixes what blocks [channelId]: the app page while the runtime permission
 * or the app-level switch is off (a channel page cannot be changed then), else the channel page.
 */
fun notificationSettingsIntent(context: Context, channelId: String): Intent {
    val appLevelOn = hasPostNotificationsRuntimePermission(context) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    return if (appLevelOn) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}
