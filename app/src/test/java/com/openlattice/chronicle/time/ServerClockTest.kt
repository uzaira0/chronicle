package com.openlattice.chronicle.time

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Random
import java.util.TimeZone

/**
 * Two clocks: the server's true time and the phone's wall clock, which is skewed, set by hand and rebooted.
 * Every assertion compares the phone's conversion with the instant the true timeline says it must be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ServerClockTest {
    /** [now] is the server's true time; the phone's wall clock is [now] + [skew]; elapsed counts from the last boot. */
    private class World(var now: Long, var skew: Long) {
        var bootedAt = now - 3_600_000
        var boots = 1
        fun wall() = now + skew
        fun elapsed() = now - bootedAt
        fun reboot() { boots++; bootedAt = now }
    }

    private val prefs by lazy {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("server-clock-test", Context.MODE_PRIVATE)
    }

    @Before fun clear() { check(prefs.edit().clear().commit()) }

    private fun clock(world: World) = ServerClock(prefs, world::wall, world::elapsed, world::boots)

    /** One status poll: the request leaves, the server stamps its clock after [upMillis], the reply takes [downMillis]. */
    private fun poll(world: World, upMillis: Long = 40, downMillis: Long = 40) {
        val sent = world.elapsed()
        world.now += upMillis
        val serverTime = Instant.ofEpochMilli(world.now)
        world.now += downMillis
        clock(world).measure(serverTime, sent, world.elapsed())
    }

    @Test fun everySkewMapsAServerInstantToThePhoneTimeOfTheSameMoment() {
        for (skew in listOf(1_000L, 60_000L, 3_600_000L).flatMap { listOf(it, -it) }) {
            clear()
            val world = World(Instant.parse("2026-10-28T12:00:00Z").toEpochMilli(), skew)
            poll(world)
            world.now += 600_000
            val pause = Instant.ofEpochMilli(world.now)
            assertEquals("skew $skew", world.wall(), clock(world).toPhone(pause))
            assertEquals("skew $skew", pause, clock(world).toServer(world.wall()))
        }
    }

    /** At +20 s skew, a row captured 10 s before the server's pause is before the converted window start. */
    @Test fun aRowTenSecondsBeforeThePauseStaysOutsideTheWindowUnderTwentySecondsOfSkew() {
        val world = World(Instant.parse("2026-10-28T12:00:00Z").toEpochMilli(), skew = 20_000)
        poll(world)
        val rowStamp = world.wall()
        world.now += 10_000
        val windowStart = clock(world).toPhone(Instant.ofEpochMilli(world.now))
        assertTrue(rowStamp < windowStart)
        assertEquals(10_000, windowStart - rowStamp)
    }

    @Test fun aManualClockChangeAfterTheMeasurementIsTrackedWithoutANewPoll() {
        val world = World(Instant.parse("2026-10-28T12:00:00Z").toEpochMilli(), skew = 5_000)
        poll(world)
        world.now += 60_000
        world.skew += 7_200_000
        val instant = Instant.ofEpochMilli(world.now + 30_000)
        assertEquals(world.wall() + 30_000, clock(world).toPhone(instant))
        world.skew -= 3 * 7_200_000
        assertEquals(world.wall() + 30_000, clock(world).toPhone(instant))
    }

    @Test fun aRebootKeepsTheLastMeasuredOffsetUntilTheNextPoll() {
        val world = World(Instant.parse("2026-10-28T12:00:00Z").toEpochMilli(), skew = -90_000)
        poll(world)
        world.now += 3_600_000
        world.reboot()
        world.now += 1_000
        val instant = Instant.ofEpochMilli(world.now)
        assertEquals(world.wall(), clock(world).toPhone(instant))
        // A reboot whose elapsed time has run past the old anchor is still a reboot.
        world.now += 10 * 3_600_000
        assertEquals(world.wall(), clock(world).toPhone(Instant.ofEpochMilli(world.now)))
    }

    @Test fun anOfflineRestartKeepsThePersistedOffset() {
        val world = World(Instant.parse("2026-10-28T12:00:00Z").toEpochMilli(), skew = 42_000)
        poll(world)
        world.now += 86_400_000
        val restarted = ServerClock(ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("server-clock-test", Context.MODE_PRIVATE), world::wall, world::elapsed, world::boots)
        assertEquals(world.wall(), restarted.toPhone(Instant.ofEpochMilli(world.now)))
    }

    @Test fun beforeTheFirstPollTheClocksAreTakenAsEqual() {
        val world = World(Instant.parse("2026-10-28T12:00:00Z").toEpochMilli(), skew = 3_000)
        assertEquals(world.now, clock(world).toPhone(Instant.ofEpochMilli(world.now)))
    }

    /** A time zone change, and America/Santiago's DST switch inside a window, move no epoch instant. */
    @Test fun timeZoneChangesAndSantiagoDaylightSavingLeaveWindowsWhereTheyAre() {
        val saved = TimeZone.getDefault()
        try {
            val santiago = ZoneId.of("America/Santiago")
            TimeZone.setDefault(TimeZone.getTimeZone(santiago))
            // America/Santiago leaves DST at 2026-04-05 00:00 local: clocks fall back to 2026-04-04 23:00 (-03:00 to -04:00).
            val start = OffsetDateTime.parse("2026-04-04T22:30:00-03:00").toInstant()
            val end = OffsetDateTime.parse("2026-04-04T23:30:00-04:00").toInstant()
            val world = World(start.toEpochMilli() - 600_000, skew = 15_000)
            poll(world)
            val phoneStart = clock(world).toPhone(start)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val phoneEnd = clock(world).toPhone(end)
            assertEquals("the window keeps its two hours across the fall-back", 7_200_000, phoneEnd - phoneStart)
            // A row the phone stamped in local time one second before the window start stays outside it.
            val row = OffsetDateTime.ofInstant(Instant.ofEpochMilli(phoneStart - 1_000), santiago)
            assertTrue(row.toInstant().toEpochMilli() !in phoneStart until phoneEnd)
            val inside = OffsetDateTime.ofInstant(Instant.ofEpochMilli(phoneStart + 3_600_000), santiago)
            assertTrue(inside.toInstant().toEpochMilli() in phoneStart until phoneEnd)
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    /** Generated polls: the conversion error never exceeds half the latency asymmetry, and the two directions invert. */
    @Test fun generatedPollsStayWithinHalfTheLatencyAsymmetry() {
        val random = Random(20261028)
        repeat(200) { run ->
            clear()
            val skew = (random.nextInt(2 * 86_400) - 86_400).toLong() * 1_000 + random.nextInt(1_000)
            val world = World(Instant.parse("2026-10-28T00:00:00Z").toEpochMilli() + random.nextInt(86_400_000), skew)
            val up = random.nextInt(2_000).toLong()
            val down = random.nextInt(2_000).toLong()
            poll(world, up, down)
            world.now += random.nextInt(3 * 86_400_000).toLong()
            val instant = Instant.ofEpochMilli(world.now + random.nextInt(3_600_000) - 1_800_000)
            val expected = instant.toEpochMilli() + world.skew
            val error = Math.abs(clock(world).toPhone(instant) - expected)
            assertTrue("run $run error $error up $up down $down", error <= Math.abs(up - down) / 2 + 1)
            assertEquals(instant, clock(world).toServer(clock(world).toPhone(instant)))
        }
    }
}
