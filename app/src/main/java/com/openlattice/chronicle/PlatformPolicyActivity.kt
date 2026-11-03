package com.openlattice.chronicle

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PlatformPolicyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.platform_policy_title)
        val body = TextView(this).apply {
            id = android.R.id.text1
            val spacing = resources.getDimensionPixelSize(R.dimen.eq_space_5)
            setPadding(spacing, spacing, spacing, spacing)
            setTextAppearance(R.style.Chronicle_Body)
            setTextColor(ContextCompat.getColor(context, R.color.chronicle_text_primary))
            setTextIsSelectable(true)
            text = getString(R.string.platform_privacy_policy_full)
        }
        setContentView(ScrollView(this).apply { addView(body) })
    }
}
