package com.openlattice.chronicle

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.preferences.EnrollmentSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * After an OEM process kill the gate has not yet published its snapshot, so a MAIN-thread
 * isEnrolled() says false. MainActivity must route from the authoritative off-main read and open
 * the dashboard, not the enrollment screen.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityColdStartRoutingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun enrolledParticipantBeforeHydrationLandsOnDashboard() {
        TestStores.install(context, enrolled = true)
        // Cold process: no authorization snapshot published yet.
        ResearchPersistenceGate::class.java.getDeclaredField("authorization").apply {
            isAccessible = true
            set(ResearchPersistenceGate, type.getDeclaredConstructor().apply { isAccessible = true }.newInstance())
        }
        assertFalse("precondition: MAIN snapshot not hydrated", EnrollmentSettings(context).isEnrolled())

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
        assertFalse("must not start Enrollment", started.any { it.component?.className == Enrollment::class.java.name })
        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null)
    }

    private fun settle() {
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun destroyedDuringEnrollmentReadDoesNotLaunchEnrollment() {
        TestStores.install(context, enrolled = true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        controller.destroy()
        settle()
        val started = generateSequence { shadowOf(controller.get()).nextStartedActivity }.toList()
        assertFalse(started.any { it.component?.className == Enrollment::class.java.name })
    }

    @Test fun readFinishingAfterStopWaitsForStartThenShowsDashboard() {
        TestStores.install(context, enrolled = true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        controller.pause().saveInstanceState(Bundle()).stop()
        settle()
        controller.start().resume()
        settle()
        assertTrue(controller.get().supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null)
    }

    @Test fun recreationWithBundleButNoFragmentSelectsDefaultTab() {
        TestStores.install(context, enrolled = true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup(Bundle()).get()
        settle()
        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null)
    }
}
