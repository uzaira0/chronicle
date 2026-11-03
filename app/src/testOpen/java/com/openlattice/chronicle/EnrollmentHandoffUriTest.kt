package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputEditText
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.ui.awaitDashboardUi
import java.io.File
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.w3c.dom.Element

/** Consumes the public enrollment page's actual JSON output, or its checked-in producer snapshot. */
@RunWith(RobolectricTestRunner::class)
class EnrollmentHandoffUriTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixtures = JSONArray(System.getProperty(javaClass.name)?.let { File(it).readText() }
        ?: checkNotNull(javaClass.getResource("/enrollment-handoff-fixture.json")).readText())
        .let { list -> List(list.length()) { list.getJSONObject(it) } }
        .also { require(it.isNotEmpty()) { "The web producer must supply handoff cases" } }

    @Test fun webHandoffsParseWithTheirExactDestinationCredentialsAndPrivateRoot() {
        for (fixture in fixtures) {
            val expected = fixture.getJSONObject("expected")
            val intent = Intent.parseUri(fixture.getString("handoff"), Intent.URI_INTENT_SCHEME)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("com.bcm.chronicle", intent.`package`)
            assertEquals("https://play.google.com/store/apps/details?id=com.bcm.chronicle",
                intent.getStringExtra("browser_fallback_url"))
            assertEquals("accessCode=${expected.getString("accessCode")}", intent.data?.fragment)
            val link = parseEnrollmentLink(intent.data.toString(), expected.getString("accessCode")) as EnrollmentLink.Prefill
            assertEquals(expected.getString("studyId"), link.studyId.toString())
            assertEquals(expected.getString("participantId"), link.participantId)
            assertEquals(expected.getString("serverUrl"), link.serverUrl)
            if (expected.isNull("serverCa")) assertNull(link.serverCa)
            else assertArrayEquals(Base64.getUrlDecoder().decode(expected.getString("serverCa")), link.serverCa?.encoded)
        }
    }

    @Test fun theMergedEnrollmentActivityAcceptsBrowserHandoffs() {
        val declarations = mergedMainManifest().getElementsByTagName("activity")
        val activity = (0 until declarations.length).map { declarations.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NS, "name") == Enrollment::class.java.name }
        assertEquals("true", activity.getAttributeNS(ANDROID_NS, "exported"))
        fun Element.attributes(tag: String, attribute: String = "name") = getElementsByTagName(tag).let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, attribute) }
        }
        val filters = activity.getElementsByTagName("intent-filter")
        val filter = (0 until filters.length).map { filters.item(it) as Element }
            .single { "chronicle" in it.attributes("data", "scheme") }
        assertEquals(listOf("enroll"), filter.attributes("data", "host"))
        assertTrue(Intent.ACTION_VIEW in filter.attributes("action"))
        assertTrue(filter.attributes("category").containsAll(setOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE)))
    }

    @Test fun everyWebHandoffOpensTheWizardAndRequestsTheBoundDisclosure() {
        for (fixture in fixtures) open(fixture)
    }

    private fun open(fixture: JSONObject) {
        val expected = fixture.getJSONObject("expected")
        val accessCode = expected.getString("accessCode")
        val db = EnrollmentTestStores.install(context, enrolled = false)
        val base = EnrollmentTestStores.enrollmentPreview()
        val preview = base.copy(manifest = base.manifest.copy(studyId = UUID.fromString(expected.getString("studyId")),
            participantId = expected.getString("participantId"), serverOrigin = expected.getString("serverUrl")))
        EnrollmentTestStores.expectedApiRequest = EnrollmentTestStores.ApiRequest(expected.getString("serverUrl"))
        var previews = 0
        try { EnrollmentTestStores.installApi(studyApi { method, args ->
            check(method == "getEnrollmentPreview") { method }
            assertEquals(listOf(preview.manifest.studyId, preview.manifest.participantId, accessCode), args!!.toList())
            previews++
            preview
        }).use {
            val parsed = Intent.parseUri(fixture.getString("handoff"), Intent.URI_INTENT_SCHEME)
            val launch = Intent(parsed).setClass(context, Enrollment::class.java).setPackage(null)
            ActivityScenario.launch<Enrollment>(launch).use { scenario ->
                scenario.onActivity { activity ->
                    awaitDashboardUi { ViewModelProvider(activity)[Enrollment.WizardState::class.java].accessCode == accessCode }
                    assertNull("the credential leaves the retained Intent", activity.intent.data?.fragment)
                    fun text(id: Int) = activity.findViewById<TextInputEditText>(id).text.toString()
                    assertEquals(listOf(expected.getString("studyId"), expected.getString("participantId"), expected.getString("serverUrl")),
                        listOf(text(R.id.studyIdText), text(R.id.participantIdText), text(R.id.serverUrlText)))
                    assertTrue(activity.findViewById<View>(R.id.button).performClick())
                    awaitDashboardUi { shadowOf(activity).peekNextStartedActivityForResult() != null }
                    assertEquals(StudyDisclosureActivity::class.java.name,
                        shadowOf(activity).nextStartedActivityForResult.intent.component?.className)
                    assertEquals(1, previews)
                }
            }
        } } finally { db.close() }
    }
}
