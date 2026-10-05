package com.openlattice.chronicle.collection.battery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.state.eraseResearchSourceState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The upload sync and the battery worker share one interval clock, so a 15-min study gets 4 samples an hour, not 8. */
@RunWith(RobolectricTestRunner::class)
class BatterySampleGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun sampleInsideIntervalIsSkipped() {
        ExpansionPullSchedule(context).apply {
            setIntervalSeconds(CollectionModuleId.BATTERY_TELEMETRY, 900)
            markRan(CollectionModuleId.BATTERY_TELEMETRY, System.currentTimeMillis() - 60_000)
        }

        assertNull(collectBatterySampleIfDue(context))
    }

    @Test fun overlappingDueCallersTakeOnlyOneSample() {
        context.getSharedPreferences("expansion_pull_schedule", Context.MODE_PRIVATE).edit().clear().commit()
        ExpansionPullSchedule(context).setIntervalSeconds(CollectionModuleId.BATTERY_TELEMETRY, 3600)
        val sampleEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val sampleCount = AtomicInteger()
        val callers = Executors.newFixedThreadPool(2)
        try {
            val first = callers.submit<ModuleResult?> {
                collectBatterySampleIfDue(context) {
                    sampleCount.incrementAndGet()
                    sampleEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    ModuleResult.Ok(1)
                }
            }
            assertTrue(sampleEntered.await(5, TimeUnit.SECONDS))
            val secondStarted = CountDownLatch(1)
            val secondEnteredSample = CountDownLatch(1)
            val second = callers.submit<ModuleResult?> {
                secondStarted.countDown()
                collectBatterySampleIfDue(context) {
                    sampleCount.incrementAndGet()
                    secondEnteredSample.countDown()
                    ModuleResult.Ok(1)
                }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            // Returns while the first sample is still running: no lock is held across a sample,
            // which takes a persistence lease (holding one across it can deadlock with uploads).
            assertNull(second.get(2, TimeUnit.SECONDS))
            assertFalse(secondEnteredSample.await(0, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()

            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(1, sampleCount.get())
        } finally {
            releaseFirst.countDown()
            callers.shutdownNow()
        }
    }

    @Test fun failedSampleLeavesTheModuleDue() {
        context.getSharedPreferences("expansion_pull_schedule", Context.MODE_PRIVATE).edit().clear().commit()
        ExpansionPullSchedule(context).setIntervalSeconds(CollectionModuleId.BATTERY_TELEMETRY, 3600)

        collectBatterySampleIfDue(context) { ModuleResult.Failed(IllegalStateException("no battery")) }

        assertTrue(ExpansionPullSchedule(context).isDue(CollectionModuleId.BATTERY_TELEMETRY, System.currentTimeMillis()))
    }

    /** A process killed mid-sample must not count as a sample; its claim lapses after ten minutes. */
    @Test fun claimAbandonedMidSampleDoesNotSkipTheInterval() {
        val prefs = context.getSharedPreferences("expansion_pull_schedule", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val schedule = ExpansionPullSchedule(context)
        schedule.setIntervalSeconds(CollectionModuleId.BATTERY_TELEMETRY, 86_400)

        assertTrue(schedule.claimIfDue(CollectionModuleId.BATTERY_TELEMETRY) != null) // then the process dies

        assertTrue(schedule.isDue(CollectionModuleId.BATTERY_TELEMETRY, System.currentTimeMillis()))
        assertNull(collectBatterySampleIfDue(context) { ModuleResult.Ok(1) })
        prefs.edit().putLong("claim_${CollectionModuleId.BATTERY_TELEMETRY.id}",
            System.currentTimeMillis() - ExpansionPullSchedule.CLAIM_ABANDONED_MS - 1).commit()
        assertTrue(collectBatterySampleIfDue(context) { ModuleResult.Ok(1) } is ModuleResult.Ok)
        assertFalse(schedule.isDue(CollectionModuleId.BATTERY_TELEMETRY, System.currentTimeMillis()))
    }

    /** A sample whose claim was cleared (erasure) or superseded meanwhile must not write a last run. */
    @Test fun completionAfterTheClaimWasLostWritesNothing() {
        val prefs = context.getSharedPreferences("expansion_pull_schedule", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val schedule = ExpansionPullSchedule(context)
        val module = CollectionModuleId.BATTERY_TELEMETRY
        schedule.setIntervalSeconds(module, 3600)

        val cleared = checkNotNull(collectBatterySampleIfDue(context) {
            prefs.edit().clear().commit() // erasure mid-sample
            ModuleResult.Ok(1)
        })
        assertTrue(cleared is ModuleResult.Ok)
        assertTrue(schedule.isDue(module, System.currentTimeMillis()))

        val claimedMs = checkNotNull(schedule.claimIfDue(module))
        prefs.edit().putLong("claim_${module.id}", claimedMs + 1).commit() // a newer claimant
        schedule.completeClaim(module, claimedMs, succeeded = true)
        assertTrue(schedule.isDue(module, System.currentTimeMillis()))
        assertEquals(claimedMs + 1, prefs.getLong("claim_${module.id}", 0L))
    }

    /** Erasure clears the schedule under the claim monitor, so a completion it overlaps commits first and is cleared. */
    @Suppress("DEPRECATION") // Thread.id: ThreadMXBean still keys threads by it.
    @Test fun erasureWaitsForAClaimCompletionInProgress() {
        val prefs = context.getSharedPreferences("expansion_pull_schedule", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val schedule = ExpansionPullSchedule(context)
        val module = CollectionModuleId.BATTERY_TELEMETRY
        schedule.setIntervalSeconds(module, 3600)
        val claimedMs = checkNotNull(schedule.claimIfDue(module))
        val erasure = Thread { eraseResearchSourceState(context) }
        val threads = java.lang.management.ManagementFactory.getThreadMXBean()

        synchronized(ExpansionPullSchedule::class.java) { // completeClaim has validated its claim
            erasure.start()
            val deadline = System.currentTimeMillis() + 10_000
            while (erasure.isAlive && threads.getThreadInfo(erasure.id)?.lockOwnerId != Thread.currentThread().id &&
                System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertEquals(Thread.currentThread().id, threads.getThreadInfo(erasure.id)?.lockOwnerId)
            schedule.completeClaim(module, claimedMs, succeeded = true)
            assertFalse(schedule.isDue(module, System.currentTimeMillis()))
        }
        erasure.join(10_000)

        assertFalse(erasure.isAlive)
        assertTrue(schedule.isDue(module, System.currentTimeMillis()))
    }
}
