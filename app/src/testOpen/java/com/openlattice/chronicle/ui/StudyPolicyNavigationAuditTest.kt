package com.openlattice.chronicle.ui

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.StudyDisclosureActivity
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.serialization.ChronicleJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StudyPolicyNavigationAuditTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    @Test fun settingsOpensPackagedPlatformPolicyAndMissingOwnerResourceIsExplicit() {
        val build = listOf(java.io.File("build.gradle"), java.io.File("app/build.gradle"))
            .first { it.exists() && it.readText().contains("ApprovedPlatformPolicy") }.readText()
        assertTrue(build.contains("platform-policy-approved.sha256"))
        assertTrue(build.contains("(Release|Dogfood)"))
        TestStores.install(context,true)
        val controller=Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
        val host=controller.get();val fragment=SettingsHomeFragment()
        host.supportFragmentManager.beginTransaction().replace(android.R.id.content,fragment).commitNow()
        controller.start().visible()
        try {
            assertTrue(fragment.requireView().findViewById<View>(R.id.privacyPolicyButton).performClick())
            val intent=shadowOf(host).nextStartedActivity
            assertEquals("com.openlattice.chronicle.PlatformPolicyActivity",intent?.component?.className)
            @Suppress("UNCHECKED_CAST") val type=Class.forName(intent!!.component!!.className) as Class<out android.app.Activity>
            val policy=Robolectric.buildActivity(type,intent).setup()
            try {
                val text=policy.get().findViewById<android.widget.TextView>(android.R.id.text1).text.toString()
                assertTrue(text.contains("unavailable",ignoreCase=true));assertFalse(text.contains("Health Connect"))
                val source=Class.forName("com.openlattice.chronicle.PlatformPolicySource")
                val instance=source.getDeclaredField("INSTANCE").get(null)
                val path=source.getDeclaredMethod("pathFor",String::class.java)
                for(channel in listOf("PLAY","AMAZON","RESEARCH","OPEN"))assertEquals("platform-policy/${channel.lowercase()}.txt",path.invoke(instance,channel))
            } finally{policy.pause().stop().destroy()}
        } finally{controller.stop().destroy()}
    }
    @Test fun safeStudyWithdrawalLinkAndEffectiveDateSurviveRetentionAndRemainReachable() = runBlocking {
        TestStores.install(context,true)
        val preview=ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java).fromJson(javaClass.getResource("/enrollment-preview.json")!!.readText())!!
        val intent=StudyDisclosureActivity.intent(context,preview)
        assertEquals(preview.manifest.participantPolicy.withdrawalUrl,intent.getStringExtra("study_withdrawal_url"))
        assertTrue(intent.getStringExtra("study_disclosure_body")!!.contains("Policy effective date"))
        val disclosure=Robolectric.buildActivity(StudyDisclosureActivity::class.java,intent).setup()
        val buttonId=context.resources.getIdentifier("studyWithdrawalButton","id",context.packageName)
        assertTrue(disclosure.get().findViewById<View>(buttonId).performClick())
        assertEquals(preview.manifest.participantPolicy.withdrawalUrl,shadowOf(disclosure.get()).nextStartedActivity.dataString)
        disclosure.pause().stop().destroy()
        val snapshot=DashboardDataRepository.load(context)
        val summary=snapshot.servers.single()
        assertEquals(preview.manifest.participantPolicy.withdrawalUrl,summary.javaClass.getDeclaredMethod("getWithdrawalUrl").invoke(summary))
        assertEquals(preview.manifest.participantPolicy.effectiveAt.toString(),summary.javaClass.getDeclaredMethod("getPolicyEffectiveAt").invoke(summary))
        val controller=Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
        val host=controller.get();val fragment=SettingsHomeFragment()
        host.supportFragmentManager.beginTransaction().replace(android.R.id.content,fragment).commitNow();controller.start().resume().visible()
        repeat(50){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20)}
        val settingsId=context.resources.getIdentifier("studyWithdrawalSettingsButton","id",context.packageName)
        assertEquals(View.VISIBLE,fragment.requireView().findViewById<View>(settingsId).visibility)
        fragment.requireView().findViewById<View>(settingsId).performClick()
        assertEquals(preview.manifest.participantPolicy.withdrawalUrl,shadowOf(host).nextStartedActivity.dataString)
        controller.pause().stop().destroy()
        assertTrue(runCatching{preview.manifest.participantPolicy.copy(withdrawalUrl="http://unsafe.example/withdraw")}.isFailure)
        com.openlattice.chronicle.storage.ChronicleDb.getInstance(context).openHelper.writableDatabase.execSQL("UPDATE upload_servers SET studyDisclosureJson=NULL")
        val absent=DashboardDataRepository.load(context).servers.single()
        assertNull(absent.javaClass.getDeclaredMethod("getWithdrawalUrl").invoke(absent))
        Unit
    }
}
