package com.openlattice.chronicle.ui

import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.openlattice.chronicle.ANDROID_NS
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.capability.CollectionCapabilityResolver
import com.openlattice.chronicle.collection.capability.activeModulePermissionStatus
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.mergedMainManifest
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.UsageAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.w3c.dom.Element

/**
 * The weak ActiveModuleAccessStatusTest proved only the capability helper. This drives the real dashboard: an enrolled
 * Open phone whose accepted usage module lacks Usage Access lands on Data Sharing once, then shows the Overview "access
 * needed" card; tapping it opens Data Sharing, whose usage-access row explains the access and, after its disclosure,
 * opens Android's Usage Access screen. Once access is allowed there, the Overview card on return is the plain status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UsageAccessShortcutJourneyTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After fun tearDown() = onPersistenceWorker {
        ChronicleDb.getInstance(app).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    private fun usageAccess(mode: Int) = shadowOf(app.getSystemService(AppOpsManager::class.java))
        .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, app.applicationInfo.uid, app.packageName, mode)

    private fun ActivityController<MainActivity>.shown(): Fragment? =
        get().supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)

    private fun ActivityController<MainActivity>.overviewCard() =
        get().findViewById<TextView?>(R.id.overviewCollectionStatus)?.text?.toString().orEmpty()

    /** Waits for the Overview card to satisfy [expected]; a failure names the text it shows. */
    private fun ActivityController<MainActivity>.awaitCard(expected: (String) -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (shown() is OverviewFragment && expected(overviewCard())) return
            Thread.sleep(10)
        }
        val nav = get().findViewById<BottomNavigationView>(R.id.mainBottomNav)
        val access = onPersistenceWorker {
            activeModulePermissionStatus(CollectionLoopStore.of(app).loadAll().values.toList(),
                CollectionCapabilityResolver.snapshot(app))
        }
        fail("Overview card: \"${overviewCard()}\" on ${shown()?.javaClass?.simpleName}, " +
            "tab ${get().resources.getResourceEntryName(nav.selectedItemId)}, access $access")
    }

    private fun View.buttons(): List<Button> = when (this) {
        is Button -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).buttons() }
        else -> emptyList()
    }

    private fun View.texts(): List<String> = when (this) {
        is TextView -> listOf(text.toString())
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).texts() }
        else -> emptyList()
    }

    @Test fun theAccessNeededCardLeadsThroughDataSharingToUsageAccessAndClearsOnReturn() {
        AuditStores.install(app, enrolled = true)
        // Every permission the app declares is granted, so Usage Access is the only thing the accepted modules lack.
        val declared = mergedMainManifest().getElementsByTagName("uses-permission")
        repeat(declared.length) {
            shadowOf(app).grantPermissions((declared.item(it) as Element).getAttributeNS(ANDROID_NS, "name"))
        }
        usageAccess(AppOpsManager.MODE_IGNORED)
        assertFalse(UsageAccess.isGranted(app))
        val usageSettings = ComponentName("com.android.settings", "com.android.settings.UsageAccess")
        shadowOf(app.packageManager).addActivityIfNotPresent(usageSettings)
        shadowOf(app.packageManager).addIntentFilterForActivity(usageSettings,
            IntentFilter(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        val accessCard = app.getString(R.string.overview_collection_status_access, "").substringAfter("\n\n")

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            // An accepted module without its access sends the first launch to Data Sharing once; back on Overview,
            // the card says why and is the shortcut there.
            awaitDashboardUi { controller.shown() is DataSharingFragment }
            assertTrue(controller.get().findViewById<View>(R.id.nav_overview).performClick())
            controller.awaitCard { it.endsWith(accessCard) }
            assertTrue(controller.get().findViewById<TextView>(R.id.overviewCollectionStatus).isClickable)

            controller.get().findViewById<TextView>(R.id.overviewCollectionStatus).performClick()
            awaitDashboardUi { controller.shown() is DataSharingFragment }
            val dataSharing = controller.shown()!!.requireView()
            val reviewAccess = app.getString(R.string.ds_review_access)
            awaitDashboardUi { dataSharing.buttons().any { it.text.toString() == reviewAccess } }
            assertTrue(app.getString(R.string.ds_usage_access_needed) in dataSharing.texts())

            generateSequence { shadowOf(controller.get()).nextStartedActivity }.toList()
            dataSharing.buttons().single { it.text.toString() == reviewAccess }.performClick()
            awaitDashboardUi { ShadowDialog.getLatestDialog()?.isShowing == true }
            val disclosure = ShadowDialog.getLatestDialog() as AlertDialog
            disclosure.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val opened = shadowOf(controller.get()).nextStartedActivity
            assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, opened?.action)

            // Allowed in Settings; back on the dashboard, the Overview card is the plain status and no longer a shortcut.
            usageAccess(AppOpsManager.MODE_ALLOWED)
            assertTrue(UsageAccess.isGranted(app))
            assertTrue("worker thread sees the grant", onPersistenceWorker { UsageAccess.isGranted(app) })
            assertTrue("capability snapshot sees the grant",
                onPersistenceWorker { CollectionCapabilityResolver.snapshot(app).usageAccessGranted })
            controller.pause().stop().start().resume()
            // The participant taps the Overview tab.
            assertTrue(controller.get().findViewById<View>(R.id.nav_overview).performClick())
            assertTrue("the tap opens Overview at once", controller.shown() is OverviewFragment)
            controller.awaitCard { it.startsWith("Collection status\n") && !it.endsWith(accessCard) }
            assertEquals(false, controller.get().findViewById<TextView>(R.id.overviewCollectionStatus).isClickable)
        } finally { controller.pause().stop().destroy() }
    }
}
