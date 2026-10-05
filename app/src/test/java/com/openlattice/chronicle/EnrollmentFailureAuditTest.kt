package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.TextView
import android.widget.EditText
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.utils.Utils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class EnrollmentFailureAuditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Suppress("UNCHECKED_CAST")
    private fun installApi(action: (String) -> Any?) {
        val cache = UploadWorker::class.java.getDeclaredField("studyApiCache").apply { isAccessible = true }
            .get(null) as MutableMap<String, ChronicleStudyApi>
        cache["https://localhost|${Utils.mobileSigningSecretFingerprint(null)}"] = Proxy.newProxyInstance(
            ChronicleStudyApi::class.java.classLoader, arrayOf(ChronicleStudyApi::class.java),
        ) { _, method, _ -> action(method.name) } as ChronicleStudyApi
    }
    private fun installEmptyStores() {
        com.openlattice.chronicle.collection.state.ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val prefs = context.getSharedPreferences("enrollment-failure-audit", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        com.openlattice.chronicle.preferences.EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(com.openlattice.chronicle.preferences.EncryptedPrefsHelper, prefs)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.openlattice.chronicle.storage.ChronicleDb::class.java).allowMainThreadQueries().build()
        com.openlattice.chronicle.storage.ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
    }
    private fun await(condition: () -> Boolean) {
        repeat(100) { shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(20) }
        assertTrue("UI did not settle", condition())
    }
    @Test fun temporaryPreviewFailureRetainsInvitationAndRetryOpensDisclosure() {
        installEmptyStores()
        val preview = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(javaClass.getResource("/enrollment-preview.json")!!.readText())!!.let {
                it.copy(manifest = it.manifest.copy(serverOrigin = "https://localhost"))
            }
        var attempts = 0
        installApi { method -> if (method == "getEnrollmentPreview") {
            attempts++; if (attempts == 1) throw IOException("unique-private-transport-sentinel") else preview
        } else null }
        val code = "a".repeat(40)
        val link = Uri.parse("chronicle://enroll?studyId=${preview.manifest.studyId}&participantId=${preview.manifest.participantId}&serverUrl=https%3A%2F%2Flocalhost#accessCode=$code")
        val controller = Robolectric.buildActivity(Enrollment::class.java, Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW).setData(link)).setup()
        val activity = controller.get()
        try {
            val button = activity.findViewById<View>(R.id.button)
            await { button.isEnabled }
            button.performClick()
            val status = activity.findViewById<TextView>(R.id.statusMessage)
            await { attempts == 1 && button.isEnabled && status.visibility == View.VISIBLE }
            assertTrue(status.text.toString(), status.text.contains("connection", ignoreCase = true))
            assertFalse(status.text.contains("sentinel"))
            assertEquals(code, ViewModelProvider(activity)[Enrollment.WizardState::class.java].accessCode)
            button.performClick()
            await { shadowOf(activity).peekNextStartedActivityForResult() != null }
            assertEquals(StudyDisclosureActivity::class.java.name, shadowOf(activity).nextStartedActivityForResult.intent.component?.className)
            assertEquals(2, attempts)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun serverHealthExceptionNeverAppearsInParticipantCopy() {
        org.junit.Assume.assumeTrue(BuildConfig.DISTRIBUTION_CHANNEL == "RESEARCH")
        installEmptyStores()
        installApi { throw IOException("UNIQUE_PRIVATE_ENDPOINT_AND_EXCEPTION") }
        val controller = Robolectric.buildActivity(ServerEnrollmentActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.findViewById<EditText>(R.id.serverUrlText).setText("https://localhost")
            val button = activity.findViewById<View>(R.id.serverHealthButton)
            button.performClick()
            val status = activity.findViewById<TextView>(R.id.serverStatusMessage)
            await { status.visibility == View.VISIBLE && button.isEnabled }
            assertFalse(status.text.toString(), status.text.contains("UNIQUE_PRIVATE"))
            assertFalse(status.text.contains("IOException"))
            assertTrue(status.text.contains("try again", ignoreCase = true))
        } finally { controller.pause().stop().destroy() }
    }
}
