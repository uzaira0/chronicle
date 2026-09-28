package com.openlattice.chronicle

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mikepenz.aboutlibraries.Libs

/**
 * Open-source notices for the libraries in this build, from the `aboutlibraries.json` the
 * AboutLibraries Gradle plugin generates per variant: each library with its licenses, then each
 * license text once.
 */
class LicensesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        val padding = (16 * resources.displayMetrics.density).toInt()
        setContentView(
            ScrollView(this).apply {
                addView(
                    TextView(this@LicensesActivity).apply {
                        setPadding(padding, padding, padding, padding)
                        setTextIsSelectable(true)
                        text = noticeText(Libs.Builder().withJson(json).build())
                    },
                )
            },
        )
    }

    private fun noticeText(libs: Libs): String = buildString {
        libs.libraries.sortedBy { it.name.lowercase() }.forEach { library ->
            append(library.name)
            library.artifactVersion?.let { append(' ').append(it) }
            // A POM license may carry only a URL (sqlcipher-android); list the named ones.
            val names = library.licenses.map { it.name }.filter { it.isNotBlank() }
            append('\n').append(names.joinToString().ifEmpty { "-" })
            append("\n\n")
        }
        libs.licenses.filter { !it.licenseContent.isNullOrBlank() }.sortedBy { it.name }.forEach { license ->
            append("—— ").append(license.name).append(" ——\n\n")
            append(license.licenseContent).append("\n\n")
        }
    }
}
