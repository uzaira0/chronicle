package com.openlattice.chronicle.release

import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsPersistence
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadIssueBucket
import com.openlattice.chronicle.services.upload.ProcessExit
import com.openlattice.chronicle.services.upload.recordProcessExits
import java.util.Properties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CrashDataSafetyContractTest {
    @Test fun everyEmittedProcessExitCategoryIsDeclaredAndDisclosed() {
        val persistence = object : LocalUploadDiagnosticsPersistence {
            var buckets: List<LocalUploadIssueBucket> = emptyList()
            override fun load() = buckets
            override fun save(buckets: List<LocalUploadIssueBucket>) { this.buckets = buckets }
        }
        val store = LocalUploadDiagnosticsStore(persistence)
        val now = System.currentTimeMillis()
        val reasons = ApplicationExitInfo::class.java.fields.filter { it.name.startsWith("REASON_") }
        val exits = reasons.mapIndexed { index, field -> ProcessExit(field.getInt(null), now - index) }
        recordProcessExits(store, exits, watermarkMillis = 0)
        val emitted = store.pending()
        assertFalse("control: the collector must emit runtime diagnostics", emitted.isEmpty())
        val properties = Properties().apply { load(disclosureResource("privacy.properties").reader()) }
        val categories = properties.getProperty("collected_categories").split(',').toSet()
        val dataSafety = disclosureResource("data-safety.md")
        emitted.forEach { bucket ->
            assertTrue("${bucket.issue} requires the Crash logs declaration", "crash_logs" in categories)
            assertTrue("${bucket.issue} requires collected/shared Crash logs copy",
                dataSafety.lineSequence().any { "App info and performance > Crash logs" in it && "Collected and shared" in it })
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val disclosures = listOf(context.getString(R.string.disclosure_function_upload_telemetry),
            context.getString(R.string.platform_privacy_policy_full), disclosureResource("listing.md"))
        disclosures.forEach { disclosure ->
            listOf("crash", "not responding", "count", "time").forEach { term ->
                assertTrue("upload diagnostics disclosure must describe $term", term in disclosure.lowercase())
            }
        }
    }

    private fun disclosureResource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader().use { it.readText() }
}
