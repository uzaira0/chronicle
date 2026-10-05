package com.openlattice.chronicle

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** The owner supplies the approved platform policy separately for each distribution. */
internal object PlatformPolicySource {
    fun pathFor(channel: String): String {
        require(channel in setOf("PLAY", "AMAZON", "RESEARCH", "OPEN"))
        return "platform-policy/${channel.lowercase(java.util.Locale.ROOT)}.txt"
    }
    fun read(context: android.content.Context): String? = runCatching {
        context.assets.open(pathFor(BuildConfig.DISTRIBUTION_CHANNEL)).bufferedReader().use { it.readText() }
            .takeIf { it.isNotBlank() }
    }.getOrNull()
}

class PlatformPolicyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.platform_policy_title)
        val body = TextView(this).apply {
            id = android.R.id.text1
            setPadding(24, 24, 24, 24)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.chronicle_text_primary))
            setTextIsSelectable(true)
            text = PlatformPolicySource.read(this@PlatformPolicyActivity)
                ?: getString(R.string.platform_policy_unavailable)
        }
        setContentView(ScrollView(this).apply { addView(body) })
    }
}
