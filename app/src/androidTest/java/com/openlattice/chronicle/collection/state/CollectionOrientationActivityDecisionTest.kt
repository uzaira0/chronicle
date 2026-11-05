package com.openlattice.chronicle.collection.state

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionOrientationActivityDecisionTest {

    @Test
    fun wizardSaysPositionRequirementAndDataTypeOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val plan = ConsentPlan(
            required = listOf(CollectionModuleId.USAGE_EVENTS),
            optional = listOf(CollectionModuleId.BATTERY_TELEMETRY),
        )

        ActivityScenario.launch<CollectionOrientationActivity>(
            CollectionOrientationActivity.intent(context, plan)
        ).use {
            onView(withId(R.id.orientationStep)).check(matches(withText("1 of 2 · Required")))
            onView(withId(R.id.orientationTitle)).check(matches(withText("App use")))
            onView(withId(R.id.orientationRequirementDetail))
                .check(matches(isDisplayed()))
                .check(matches(withText("You must allow this to take part.")))

            onView(withId(R.id.orientationAccept)).perform(click())

            onView(withId(R.id.orientationStep)).check(matches(withText("2 of 2 · Optional")))
            onView(withId(R.id.orientationTitle)).check(matches(withText("Battery Telemetry")))
            onView(withId(R.id.orientationRequirementDetail))
                .check(matches(withText("You can skip this and still take part.")))
        }
    }

    @Test
    fun amazonPolicyDoesNotOfferHealthConnectConsent() {
        assertFalse(
            DistributionModulePolicy.supports(DistributionChannel.AMAZON, CollectionModuleId.HEALTH_CONNECT),
        )
        assertTrue(CollectionConsentCopy.template(ApplicationProvider.getApplicationContext(), CollectionModuleId.HEALTH_CONNECT)
            .whatItCollects.isEmpty())
    }
}
