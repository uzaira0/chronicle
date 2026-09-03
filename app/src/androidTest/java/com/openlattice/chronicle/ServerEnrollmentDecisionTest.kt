package com.openlattice.chronicle

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Public Play builds must expose enrollment only through a study-issued invitation link. */
@RunWith(AndroidJUnit4::class)
class ServerEnrollmentDecisionTest {

    @Before
    fun setUp() {
        AppTestState.resetPrefs()
        AppTestState.clearMutableTables()
    }

    @Test
    fun playBuildRejectsTheLegacyManualServerEditorWithoutChangingTheEnrollmentSlot() {
        assertEquals("PLAY", BuildConfig.DISTRIBUTION_CHANNEL)

        ActivityScenario.launch<ServerEnrollmentActivity>(
            Intent(AppTestState.context, ServerEnrollmentActivity::class.java),
        ).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }

        assertEquals(0, AppTestState.db().uploadServerDao().count())
    }
}
