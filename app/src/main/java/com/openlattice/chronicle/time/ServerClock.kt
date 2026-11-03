package com.openlattice.chronicle.time

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.openlattice.chronicle.storage.checkLocalStoreWrite
import java.time.Instant

/**
 * The phone's one mapping between the server's clock and its own wall clock:
 * every phone-side boundary that comes from the server goes through [toPhone] or [toServer].
 *
 * Measured from the status response's `serverTime` (the database's `clock_timestamp()`, millisecond precision),
 * anchored on [SystemClock.elapsedRealtime] at the request midpoint. The HTTP `Date` header was the alternative;
 * its one-second resolution would put every window boundary up to a second off, and it is the proxy's clock, not
 * the database's. Because the anchor is the monotonic clock, a later manual wall-clock change is tracked without
 * a new measurement. A reboot resets elapsed time, so the offset last measured stands until the next status poll
 * (boot, `TIME_SET` and the periodic monitor all poll). A time zone change moves no epoch instant and needs nothing.
 * Before the first measurement the clocks are taken as equal.
 */
class ServerClock internal constructor(
    private val prefs: SharedPreferences,
    private val wall: () -> Long,
    private val elapsed: () -> Long,
    private val boot: () -> Int,
) {
    /** A server instant on this phone's wall clock, in epoch milliseconds. */
    fun toPhone(server: Instant): Long = server.toEpochMilli() - offsetMillis()

    /** A phone wall-clock time as the server's instant. */
    fun toServer(phoneWallMillis: Long): Instant = Instant.ofEpochMilli(phoneWallMillis + offsetMillis())

    /** [sentElapsed] and [receivedElapsed] bracket the request that returned [serverTime]. */
    fun measure(serverTime: Instant, sentElapsed: Long, receivedElapsed: Long) {
        val anchor = sentElapsed + (receivedElapsed - sentElapsed) / 2
        val wallAtAnchor = wall() - (elapsed() - anchor)
        checkLocalStoreWrite(prefs.edit()
            .putLong(SERVER_AT_ANCHOR, serverTime.toEpochMilli())
            .putLong(ELAPSED_ANCHOR, anchor)
            .putInt(BOOT, boot())
            .putLong(OFFSET, serverTime.toEpochMilli() - wallAtAnchor)
            .commit()) { "Unable to persist the server clock" }
    }

    private fun offsetMillis(): Long {
        if (!prefs.contains(OFFSET)) return 0
        val anchor = prefs.getLong(ELAPSED_ANCHOR, 0)
        val now = elapsed()
        if (prefs.getInt(BOOT, Int.MIN_VALUE) != boot() || now < anchor) return prefs.getLong(OFFSET, 0)
        return prefs.getLong(SERVER_AT_ANCHOR, 0) + (now - anchor) - wall()
    }

    companion object {
        private const val PREFS = "server_clock"
        private const val SERVER_AT_ANCHOR = "server_at_anchor"
        private const val ELAPSED_ANCHOR = "elapsed_anchor"
        private const val BOOT = "boot_count"
        private const val OFFSET = "offset"

        fun of(context: Context): ServerClock {
            val app = context.applicationContext
            return ServerClock(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), System::currentTimeMillis,
                SystemClock::elapsedRealtime) { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1) }
        }
    }
}
