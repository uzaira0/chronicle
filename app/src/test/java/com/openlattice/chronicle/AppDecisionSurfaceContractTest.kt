package com.openlattice.chronicle

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.ui.DataSharingFragment
import com.openlattice.chronicle.ui.OverviewFragment
import com.openlattice.chronicle.ui.SettingsHomeFragment
import com.openlattice.chronicle.ui.UploadsFragment
import com.openlattice.chronicle.ui.awaitDashboardUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/** Contracts against merged resources and running screens; domain policies have their own suites. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppDecisionSurfaceContractTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun installStores() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
    }

    @Test fun mergedManifestGrantsHealthAccessOnlyToHealthArtifacts() {
        val declarations = mergedMainManifest().getElementsByTagName("uses-permission")
        val permissions = (0 until declarations.length).map {
            (declarations.item(it) as Element).getAttributeNS(ANDROID_NS, "name")
        }
        assertTrue("the application manifest must be loaded", "android.permission.INTERNET" in permissions)
        assertEquals(BuildConfig.HAS_HEALTH_CONNECT, permissions.any { it.startsWith("android.permission.health.") })
    }

    @Test fun decisionLayoutsInflateWithTheirParticipantControls() = withLayout { activity ->
        val layouts = mapOf(
            R.layout.activity_enrollment to listOf(R.id.button, R.id.doneButton),
            R.layout.activity_main to listOf(R.id.mainBottomNav, R.id.mainFragmentContainer),
            R.layout.fragment_overview to listOf(R.id.overviewScroll),
            R.layout.fragment_uploads to listOf(R.id.uploadsScroll, R.id.uploadNowButton),
            R.layout.fragment_data_sharing to listOf(R.id.dataSharingScroll, R.id.sensorToggleList),
            R.layout.fragment_settings_home to listOf(R.id.settingsHomeScroll, R.id.identifyUserSwitch, R.id.deviceUserGroup),
            R.layout.activity_user_identification to listOf(R.id.participant_user_btn, R.id.someone_else_user_btn),
            R.layout.activity_server_enrollment to listOf(R.id.serverDeleteButton),
            R.layout.activity_collection_orientation to listOf(R.id.orientationAccept, R.id.orientationDecline),
        )
        layouts.forEach { (layout, controls) ->
            activity.setContentView(layout)
            controls.forEach { id ->
                assertNotNull("${activity.resources.getResourceEntryName(layout)}: ${activity.resources.getResourceEntryName(id)}",
                    activity.findViewById<View>(id))
            }
        }
    }

    @Test fun navigationSelectsEachOfTheFourDashboardPagesWithoutRequestingUsageAccess() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            awaitDashboardUi { activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null }
            val pages = mapOf(
                R.id.nav_overview to OverviewFragment::class.java,
                R.id.nav_uploads to UploadsFragment::class.java,
                R.id.nav_data_sharing to DataSharingFragment::class.java,
                R.id.nav_settings to SettingsHomeFragment::class.java,
            )
            val nav = activity.findViewById<BottomNavigationView>(R.id.mainBottomNav)
            assertEquals(pages.keys, (0 until nav.menu.size()).map { nav.menu.getItem(it).itemId }.toSet())
            pages.forEach { (id, page) ->
                activity.selectTab(id)
                activity.supportFragmentManager.executePendingTransactions()
                assertTrue(page.isInstance(activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)))
            }
            assertFalse(generateSequence { shadowOf(activity).nextStartedActivity }.any {
                it.component?.className == PermissionActivity::class.java.name
            })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun missingInvitationOriginRemainsBlankAndShowsTheValidationError() = withLayout { activity ->
        activity.setContentView(R.layout.activity_enrollment)
        val form = EnrollmentForm(activity, onSubmit = {}, onDone = {})
        activity.findViewById<EditText>(R.id.studyIdText).setText("11111111-1111-1111-1111-111111111111")
        activity.findViewById<EditText>(R.id.participantIdText).setText("participant")
        assertTrue(activity.findViewById<EditText>(R.id.serverUrlText).text.isNullOrBlank())
        assertEquals(null, form.enrollmentInvitation("invitation"))
        assertEquals(EnrollmentLink.Invalid(R.string.enrollment_missing_server_url, EnrollmentField.SERVER),
            parseEnrollmentLink("chronicle://enroll?participantId=participant", null))
        assertEquals(activity.getString(R.string.enrollment_missing_server_url),
            activity.findViewById<TextView>(R.id.statusMessage).text.toString())
    }

    @Test fun orientationActionsStayOutsideTheScrollableBodyAndAllowWrappedLabels() = withLayout { activity ->
        activity.setContentView(R.layout.activity_collection_orientation)
        val scroll = activity.findViewById<ScrollView>(R.id.orientationScrollView)
        assertFalse(scroll.clipToPadding)
        assertNotNull(activity.findViewById<View>(R.id.orientationRequirementSummary))
        val actions = activity.findViewById<ViewGroup>(R.id.orientationActions)
        listOf(R.id.orientationAccept, R.id.orientationDecline).forEach { id ->
            val button = activity.findViewById<Button>(id)
            assertEquals(actions, button.parent)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, button.layoutParams.height)
            assertTrue(button.minimumHeight >= (56 * activity.resources.displayMetrics.density).toInt())
            assertEquals(Int.MAX_VALUE, button.maxLines)
        }
    }

    @Test fun sharedInsetsConsumeSystemBarsWithoutAccumulatingPadding() = withLayout { activity ->
        activity.setContentView(R.layout.activity_user_identification)
        SystemBarInsets.onActivityPostCreated(activity, null)
        val content = activity.findViewById<View>(android.R.id.content)
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(8, 24, 8, 32)).build()
        repeat(2) {
            assertTrue(ViewCompat.dispatchApplyWindowInsets(content, insets).isConsumed)
            assertEquals(listOf(8, 24, 8, 32), listOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom))
        }
    }

    @Test fun resolvedThemeColorsUseTheLocalAccessiblePalette() {
        mapOf(
            R.color.eq_bg to "#F4F3FA", R.color.eq_surface to "#FFFFFF", R.color.eq_ink to "#1C1B22",
            R.color.eq_muted to "#66626F", R.color.eq_primary to "#6D49FE", R.color.eq_focus to "#5433D6",
        ).forEach { (id, color) -> assertEquals(Color.parseColor(color), ContextCompat.getColor(context, id)) }
        assertEquals(ContextCompat.getColor(context, R.color.eq_bg), ContextCompat.getColor(context, R.color.chronicle_bg))
        assertEquals(ContextCompat.getColor(context, R.color.eq_ink), ContextCompat.getColor(context, R.color.chronicle_text_primary))
    }

    private fun withLayout(action: (AppCompatActivity) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        try { action(controller.get()) } finally { controller.pause().stop().destroy() }
    }
}
