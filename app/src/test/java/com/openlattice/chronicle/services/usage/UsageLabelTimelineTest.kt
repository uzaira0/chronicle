package com.openlattice.chronicle.services.usage

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.UserManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.sensors.UsageEventsChronicleSensor
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UserQueueEntry
import com.openlattice.chronicle.storage.currentUserOr
import com.openlattice.chronicle.storage.labelTimeline
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUsageStatsManager

/** Who used a shared phone: the label an event gets from the participant's selections. */
@RunWith(RobolectricTestRunner::class)
class UsageLabelTimelineTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val minute = TimeUnit.MINUTES.toMillis(1)
    private val t0 = System.currentTimeMillis() - 60 * minute
    private lateinit var db: ChronicleDb

    @Before fun setUp() {
        EnrollmentTestStores.prefs = app.getSharedPreferences("usage-label-test", Context.MODE_PRIVATE)
        db = Room.inMemoryDatabaseBuilder(app, ChronicleDb::class.java).allowMainThreadQueries().build()
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            app.applicationInfo.uid, app.packageName, AppOpsManager.MODE_ALLOWED)
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    }

    @After fun tearDown() { db.close(); EnrollmentTestStores.prefs = null }

    private fun usage(type: Int, at: Long, activity: String = "") =
        shadowOf(app.getSystemService(UsageStatsManager::class.java)).addEvent(ShadowUsageStatsManager.EventBuilder
            .buildEvent().setPackage("example.app").setClass(activity).setEventType(type).setTimeStamp(at).build())

    /** Labels of the ACTIVITY_RESUMED rows one poll over [from, to) produces, keyed by activity. */
    private fun labels(from: Long, to: Long) = UsageEventsChronicleSensor(app).poll(from, to, db.userQueueEntryData().labelTimeline())!!
        .filterIsInstance<ExtractedUsageEvent>().filter { it.eventType == UsageEvents.Event.ACTIVITY_RESUMED }
        .associate { it.activityClass to it.user }

    private fun select(at: Long, user: String) = db.userQueueEntryData().insertEntries(listOf(UserQueueEntry(at, user)))

    /**
     * The participant picks Child at phone time 10:00, the phone clock is set back four minutes, and they pick
     * Other at 09:56. The later decision wins on both clocks: it is the current selection, it labels the next event at
     * 10:01, and pruning after the poll keeps it.
     */
    @Test fun aSelectionAfterABackwardClockChangeWins() {
        select(t0 + 10 * minute, "Child")
        select(t0 + 6 * minute, "Someone else")
        assertEquals("Someone else", db.userQueueEntryData().currentUserOr("none"))
        usage(UsageEvents.Event.ACTIVITY_RESUMED, t0 + 11 * minute, "next")
        assertEquals(mapOf("next" to "Someone else"), labels(t0 + 6 * minute, t0 + 12 * minute))
        db.userQueueEntryData().deleteEntriesWithLowerTimestamp(t0 + 12 * minute)
        assertEquals("Someone else", db.userQueueEntryData().currentUserOr("none"))
        assertEquals(mapOf("next" to "Someone else"), labels(t0 + 6 * minute, t0 + 12 * minute))
    }

    /**
     * An answer labels its own session only. Screen off ends it and unlock starts the next one, whether or not the
     * reset was written (a full disk refuses that write); an event after the reset with no new answer is unlabelled,
     * also when the reset fell in an earlier poll.
     */
    @Test fun aSessionBoundaryEndsTheLastAnswer() {
        select(t0, "Child")
        usage(UsageEvents.Event.ACTIVITY_RESUMED, t0 + minute, "same-session")
        usage(UsageEvents.Event.SCREEN_NON_INTERACTIVE, t0 + 2 * minute)
        usage(UsageEvents.Event.ACTIVITY_RESUMED, t0 + 3 * minute, "screen-off")
        usage(UsageEvents.Event.KEYGUARD_HIDDEN, t0 + 4 * minute)
        usage(UsageEvents.Event.ACTIVITY_RESUMED, t0 + 5 * minute, "next-session")
        select(t0 + 6 * minute, "Someone else")
        usage(UsageEvents.Event.ACTIVITY_RESUMED, t0 + 7 * minute, "answered")
        assertEquals(mapOf("same-session" to "Child", "screen-off" to "", "next-session" to "", "answered" to "Someone else"),
            labels(t0, t0 + 8 * minute))
        assertEquals(mapOf("next-session" to ""), labels(t0 + 5 * minute - 1, t0 + 6 * minute))
    }

    /**
     * A phone updated from a release that wrote "Target child" / "Other": the next usage rows carry the current
     * values, while the selection rows already queued keep what they were captured with.
     */
    @Test fun aLegacySelectionLabelsNewRowsWithTheCurrentValue() {
        select(t0, "Target child")
        usage(UsageEvents.Event.ACTIVITY_RESUMED, t0 + minute, "after-child")
        select(t0 + 2 * minute, "Other")
        usage(UsageEvents.Event.ACTIVITY_RESUMED, t0 + 3 * minute, "after-other")
        assertEquals(mapOf("after-child" to "Study participant", "after-other" to "Someone else"),
            labels(t0, t0 + 4 * minute))
        assertEquals(listOf("Other", "Target child"), db.userQueueEntryData().getUserTimestamps().map { it.user })
    }
}
