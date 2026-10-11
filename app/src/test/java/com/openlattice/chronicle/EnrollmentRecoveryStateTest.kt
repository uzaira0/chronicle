package com.openlattice.chronicle

import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class EnrollmentRecoveryStateTest {

    @Test
    fun processDeathStateUsesCanonicalRoundTrippableModuleIds() {
        val decisions = linkedSetOf(
            CollectionModuleId.HEALTH_CONNECT,
            CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.SLEEP,
        )

        val encoded = encodePendingEnrollmentModules(decisions)

        assertEquals(encoded.lineSequence().sorted().toList(), encoded.lineSequence().toList())
        assertEquals(decisions, decodePendingEnrollmentModules(encoded))
        assertEquals(emptySet<CollectionModuleId>(), decodePendingEnrollmentModules(""))
    }

    @Test
    fun unavailableHardwareUsesTheSameExactCanonicalReplayEncoding() {
        val unavailable = setOf(
            CollectionModuleId.SENSOR_GYROSCOPE,
            CollectionModuleId.SENSOR_LIGHT,
        )

        val encoded = encodePendingEnrollmentModules(unavailable)

        assertEquals(unavailable, decodePendingEnrollmentModules(encoded))
    }

    @Test
    fun corruptedOrUnknownProcessDeathStateFailsClosed() {
        assertNull(decodePendingEnrollmentModules(null))
        assertNull(decodePendingEnrollmentModules("unknown_module"))
        assertNull(decodePendingEnrollmentModules("usage_events\n"))
    }

    @Test fun verifiedRecoverySchedulesAFreshTaskBeforeTerminatingDaoHolders() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val alarms = org.robolectric.Shadows.shadowOf(checkNotNull(context.getSystemService(android.app.AlarmManager::class.java)))
        var terminated = false
        restartAfterVerifiedRecovery(context) {
            @Suppress("DEPRECATION")
            val intent = org.robolectric.Shadows.shadowOf(alarms.scheduledAlarms.single().operation).savedIntent
            assertEquals(MainActivity::class.java.name, intent.component!!.className)
            org.junit.Assert.assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
            terminated = true
        }
        org.junit.Assert.assertTrue(terminated)
    }
}
