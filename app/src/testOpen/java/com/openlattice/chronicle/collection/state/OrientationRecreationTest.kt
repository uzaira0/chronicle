package com.openlattice.chronicle.collection.state

import android.content.Context
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.layout.TestStores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Rotating mid-wizard keeps the current step and the decisions already made. */
@RunWith(RobolectricTestRunner::class)
class OrientationRecreationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun stepAndDecisionsSurviveRecreation() {
        TestStores.install(context, enrolled = false)
        val plan = ConsentPlan(
            required = listOf(CollectionModuleId.USAGE_EVENTS),
            optional = listOf(CollectionModuleId.BATTERY_TELEMETRY),
        )
        ActivityScenario.launch<CollectionOrientationActivity>(CollectionOrientationActivity.intent(context, plan)).use { scenario ->
            scenario.onActivity { it.findViewById<MaterialButton>(R.id.orientationAccept).performClick() }
            scenario.recreate()
            scenario.onActivity { activity ->
                val progress = ViewModelProvider(activity)[CollectionOrientationActivity.Progress::class.java]
                assertEquals(1, progress.current)
                assertEquals(setOf(CollectionModuleId.USAGE_EVENTS), progress.accepted.toSet())
                assertTrue(activity.findViewById<TextView>(R.id.orientationStep).text.startsWith("Step 2 of 2"))
            }
        }
    }
}
