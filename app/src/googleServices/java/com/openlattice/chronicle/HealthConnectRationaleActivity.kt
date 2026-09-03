package com.openlattice.chronicle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The research/open Health Connect permissions rationale screen. Health Connect requires any app
 * that requests `android.permission.health.READ_*` to provide a screen explaining why it wants the
 * data and that the access is read-only. It is shown when the participant taps "See app's data
 * policy" inside Health Connect in the research/open distributions.
 *
 * Declared in the manifest for both entry points:
 *  - pre-Android-14 (Health Connect as an installable app): the
 *    `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` intent.
 *  - Android 14+ (Health Connect in the platform): the `ViewPermissionUsageActivity` alias handling
 *    `android.intent.action.VIEW_PERMISSION_USAGE` with the `HEALTH_PERMISSIONS` category.
 *
 * The policy body is bundled so it remains available offline and matches the platform policy
 * supplied for store review. The public URL is also offered for updates and external review.
 */
public class HealthConnectRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        content.addView(TextView(this).apply {
            textSize = 24f
            text = getString(R.string.platform_privacy_policy_title)
        })
        content.addView(TextView(this).apply {
            textSize = 16f
            gravity = Gravity.START
            text = getString(R.string.platform_privacy_policy_full)
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad / 2)
        })
        content.addView(Button(this).apply {
            text = getString(R.string.open_public_privacy_policy)
            setOnClickListener {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(getString(R.string.platform_privacy_policy_url)),
                    ),
                )
            }
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }
}
