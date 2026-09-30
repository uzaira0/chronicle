package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.serialization.ChronicleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Rotation mid-wizard must not lose the one-time invitation or the verified disclosure. */
@RunWith(RobolectricTestRunner::class)
class EnrollmentRecreationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val accessCode = "a".repeat(40)

    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun invitationAndVerifiedPreviewSurviveRecreation() {
        TestStores.install(context, enrolled = false)
        val link = Uri.parse(
            "chronicle://enroll?studyId=11111111-1111-1111-1111-111111111111" +
                "&participantId=participant&serverUrl=https%3A%2F%2Flocalhost#accessCode=$accessCode",
        )
        val preview = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(javaClass.getResource("/enrollment-preview.json")!!.readText())!!

        ActivityScenario.launch<Enrollment>(
            Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW).setData(link),
        ).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val wizard = ViewModelProvider(activity)[Enrollment.WizardState::class.java]
                assertEquals(accessCode, wizard.accessCode)
                // The credential is stripped from the retained Intent.
                assertEquals(null, activity.intent.data?.fragment)
                // Disclosure verified; the wizard is now open in another Activity.
                wizard.preview = preview
                wizard.fetched = preview.manifest.collectionSettings
                wizard.pendingAccessCode = accessCode
            }

            scenario.recreate()
            settle()

            scenario.onActivity { activity ->
                val wizard = ViewModelProvider(activity)[Enrollment.WizardState::class.java]
                assertEquals(accessCode, wizard.accessCode)
                assertEquals(accessCode, wizard.pendingAccessCode)
                assertNotNull(wizard.preview)
                assertFalse(activity.findViewById<MaterialButton>(R.id.button).isEnabled)
            }
        }
    }

    @Test fun credentialStrippedBeforeHandleIntentSurvivesRecreation() {
        TestStores.install(context, enrolled = false)
        val link = Uri.parse(
            "chronicle://enroll?studyId=11111111-1111-1111-1111-111111111111" +
                "&participantId=participant&serverUrl=https%3A%2F%2Flocalhost#accessCode=$accessCode",
        )
        ActivityScenario.launch<Enrollment>(
            Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW).setData(link),
        ).use { scenario ->
            // Retained synchronously, before the background recovery check reaches handleIntent.
            scenario.onActivity { activity ->
                assertEquals(accessCode, ViewModelProvider(activity)[Enrollment.WizardState::class.java].detachedAccessCode)
            }
            scenario.recreate()
            settle()
            scenario.onActivity { activity ->
                assertEquals(accessCode, ViewModelProvider(activity)[Enrollment.WizardState::class.java].accessCode)
            }
        }
    }
}
