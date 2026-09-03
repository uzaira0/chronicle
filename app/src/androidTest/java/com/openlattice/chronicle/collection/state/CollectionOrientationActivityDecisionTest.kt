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
import org.hamcrest.CoreMatchers.containsString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionOrientationActivityDecisionTest {

    @Test
    fun wizardShowsRequiredAndOptionalLabelsFromSourceStudyPlan() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val plan = ConsentPlan(
            required = listOf(CollectionModuleId.USAGE_EVENTS),
            optional = listOf(CollectionModuleId.BATTERY_TELEMETRY),
        )

        ActivityScenario.launch<CollectionOrientationActivity>(
            CollectionOrientationActivity.intent(context, plan)
        ).use {
            onView(withId(R.id.orientationStep))
                .check(matches(withText(containsString("Required by source study"))))
            onView(withId(R.id.orientationBadge))
                .check(matches(isDisplayed()))
                .check(matches(withText("Required by source study")))
            onView(withId(R.id.orientationRequirementDetail))
                .check(matches(withText(containsString("source study marks this data type as required"))))
            onView(withId(R.id.orientationRequirementSummaryHeader))
                .check(matches(withText("Source study step plan: 1 required step, 1 optional step")))
            onView(withId(R.id.orientationRequirementSummary))
                .check(matches(withText(containsString("Step 1: [Required by source study] App Usage Events"))))
            onView(withId(R.id.orientationRequirementSummary))
                .check(matches(withText(containsString("Step 2: [Optional for source study] Battery Telemetry"))))

            onView(withId(R.id.orientationAccept)).perform(click())

            onView(withId(R.id.orientationStep))
                .check(matches(withText(containsString("Optional for source study"))))
            onView(withId(R.id.orientationBadge))
                .check(matches(isDisplayed()))
                .check(matches(withText("Optional for source study")))
            onView(withId(R.id.orientationRequirementDetail))
                .check(matches(withText(containsString("source study marks this data type as optional"))))
            onView(withId(R.id.orientationRequirementSummary))
                .check(matches(withText(containsString("Step 2: [Optional for source study] Battery Telemetry (current)"))))
        }
    }

    @Test
    fun playPolicyDoesNotOfferHealthConnectConsent() {
        assertFalse(
            DistributionModulePolicy.supports(DistributionChannel.PLAY, CollectionModuleId.HEALTH_CONNECT),
        )
        assertTrue(CollectionConsentCopy.template(CollectionModuleId.HEALTH_CONNECT).whatItCollects.isEmpty())
    }
}
