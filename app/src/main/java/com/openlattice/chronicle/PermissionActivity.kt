package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.openlattice.chronicle.utils.UsageAccess

class PermissionActivity : AppCompatActivity() {

    private lateinit var openSettingsBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)
        if (hasUsageSettingPermission(this)) {
            doMainActivity(this, intent)
            finish()
        }

        openSettingsBtn = findViewById(R.id.open_settings_btn)
        openSettingsBtn.setOnClickListener{
            handleGetUsageAccessSettings()
        }
    }

    private fun handleGetUsageAccessSettings() {
        getUsageAccessSettings()
    }

    private fun getUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            val permissionsText = findViewById<TextView>(R.id.permissionsText)
            permissionsText.text = getString(R.string.manual_usage_settings_permission)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && hasUsageSettingPermission(this)) {
            doMainActivity(this, intent)
            finish()
        }
    }
}

fun doMainActivity(context: Context, intent: Intent) {
    val newActivityIntent = Intent(context, MainActivity::class.java).apply {
        action = intent.action
        data = intent.data
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

    context.startActivity(newActivityIntent)
}

fun hasUsageSettingPermission(context: Context): Boolean {
    return UsageAccess.isGranted(context)
}


@RequiresApi(Build.VERSION_CODES.M)
fun hasIgnoreBatteryOptimization(context: Context): Boolean {
    return try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager;
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)

    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
