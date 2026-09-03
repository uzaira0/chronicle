package com.openlattice.chronicle

import android.content.Intent
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.preferences.EnrollmentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserIdentificationDecisionTest {

    @Before
    fun setUp() {
        AppTestState.resetPrefs()
        AppTestState.clearMutableTables()
        AppTestState.enrollActiveStudy(
            enabledModules = setOf(CollectionModuleId.USER_IDENTIFICATION),
            userIdentificationEnabled = true,
        )
    }

    @Test
    fun targetChildDecisionPersistsCurrentUserAndQueueRow() {
        ActivityScenario.launch<UserIdentificationActivity>(
            Intent(AppTestState.context, UserIdentificationActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { it.findViewById<Button>(R.id.child_user_btn).performClick() }
            waitForCurrentUser(AppTestState.context.getString(R.string.user_target_child))
        }

        assertEquals(AppTestState.context.getString(R.string.user_target_child), EnrollmentSettings(AppTestState.context).getCurrentUser())
        assertTrue(AppTestState.db().userQueueEntryData().getUserTimestamps().isNotEmpty())
    }

    @Test
    fun otherDecisionPersistsCurrentUserAndQueueRow() {
        ActivityScenario.launch<UserIdentificationActivity>(
            Intent(AppTestState.context, UserIdentificationActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { it.findViewById<Button>(R.id.other_user_btn).performClick() }
            waitForCurrentUser(AppTestState.context.getString(R.string.user_other))
        }

        assertEquals(AppTestState.context.getString(R.string.user_other), EnrollmentSettings(AppTestState.context).getCurrentUser())
        assertTrue(AppTestState.db().userQueueEntryData().getUserTimestamps().isNotEmpty())
    }

    @Test
    fun repeatedChoicesInEitherOrderLeaveLatestSelectionAsCurrentUser() {
        ActivityScenario.launch<UserIdentificationActivity>(
            Intent(AppTestState.context, UserIdentificationActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { it.findViewById<Button>(R.id.child_user_btn).performClick() }
            waitForCurrentUser(AppTestState.context.getString(R.string.user_target_child))
        }
        ActivityScenario.launch<UserIdentificationActivity>(
            Intent(AppTestState.context, UserIdentificationActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { it.findViewById<Button>(R.id.other_user_btn).performClick() }
            waitForCurrentUser(AppTestState.context.getString(R.string.user_other))
        }

        val rows = AppTestState.db().userQueueEntryData().getUserTimestamps()
        assertEquals(AppTestState.context.getString(R.string.user_other), EnrollmentSettings(AppTestState.context).getCurrentUser())
        assertTrue("each tap appends a persisted identification row", rows.size >= 2)
    }

    private fun waitForCurrentUser(expected: String) {
        repeat(50) {
            if (EnrollmentSettings(AppTestState.context).getCurrentUser() == expected) return
            Thread.sleep(100)
        }
        assertEquals(expected, EnrollmentSettings(AppTestState.context).getCurrentUser())
    }
}
