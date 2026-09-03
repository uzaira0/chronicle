package com.openlattice.chronicle

import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
