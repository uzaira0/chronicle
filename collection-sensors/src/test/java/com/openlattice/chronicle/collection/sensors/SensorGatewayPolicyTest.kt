package com.openlattice.chronicle.collection.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorGatewayPolicyTest {
    @Test
    fun `duty cycled collection disables batching until flush completion is owned`() {
        assertEquals(0, dutyCycledReportLatencyUs(5_000_000, 200_000, 100, false))
    }

    @Test
    fun `batch latency is disabled when no fifo capacity is reserved`() {
        assertEquals(0, dutyCycledReportLatencyUs(5_000_000, 200_000, 0, true))
    }

    @Test
    fun `batch latency uses guaranteed reserved fifo capacity`() {
        assertEquals(2_000_000, dutyCycledReportLatencyUs(5_000_000, 200_000, 10, true))
    }

    @Test
    fun `requested latency is retained when guaranteed reserved capacity is sufficient`() {
        assertEquals(5_000_000, dutyCycledReportLatencyUs(5_000_000, 200_000, 100, true))
    }

    @Test
    fun `six point two five hertz raw cadence converges on requested five hertz`() {
        assertRetainedRateNearFiveHertz(rawPeriodNanos = 160_000_000L)
    }

    @Test
    fun `fifteen hertz raw cadence converges on requested five hertz`() {
        assertRetainedRateNearFiveHertz(rawPeriodNanos = 66_666_667L)
    }

    @Test
    fun `long callback gap retains once and advances past the gap without a burst`() {
        val period = 200L
        val first = continuousThrottleDecision(0L, period, null)
        assertTrue(first.retain)
        assertEquals(200L, first.nextDeadlineNanos)

        val afterGap = continuousThrottleDecision(5_050L, period, first.nextDeadlineNanos)
        assertTrue(afterGap.retain)
        assertEquals(5_200L, afterGap.nextDeadlineNanos)

        val beforeNextPhase = continuousThrottleDecision(5_100L, period, afterGap.nextDeadlineNanos)
        assertFalse("no catch-up sample is retained before the next phase", beforeNextPhase.retain)
        assertEquals(5_200L, beforeNextPhase.nextDeadlineNanos)
    }

    @Test
    fun `fresh registration resets throttle phase and retains its first callback`() {
        val previous = continuousThrottleDecision(1_000L, 200L, null)
        assertFalse(continuousThrottleDecision(1_050L, 200L, previous.nextDeadlineNanos).retain)

        val afterReset = continuousThrottleDecision(1_050L, 200L, null)
        assertTrue(afterReset.retain)
        assertEquals(1_250L, afterReset.nextDeadlineNanos)
    }

    private fun assertRetainedRateNearFiveHertz(rawPeriodNanos: Long) {
        val requestedPeriodNanos = 200_000_000L
        val durationNanos = 120_000_000_000L
        var timestampNanos = 0L
        var nextDeadlineNanos: Long? = null
        var retained = 0L
        while (timestampNanos <= durationNanos) {
            val decision = continuousThrottleDecision(
                eventTimestampNanos = timestampNanos,
                minPeriodNanos = requestedPeriodNanos,
                nextDeadlineNanos = nextDeadlineNanos,
            )
            if (decision.retain) retained++
            nextDeadlineNanos = decision.nextDeadlineNanos
            timestampNanos += rawPeriodNanos
        }

        val retainedHz = retained * 1_000_000_000.0 / durationNanos
        assertTrue("retainedHz=$retainedHz", retainedHz in 4.95..5.05)
    }
}
