package com.openlattice.chronicle.collection.state

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.SleepSegmentEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.activity.SleepActivityReceiver
import com.openlattice.chronicle.collection.device.AndroidAppNetworkUsageSource
import com.openlattice.chronicle.collection.device.AndroidHealthMetricSource
import com.openlattice.chronicle.collection.device.AppNetworkEnrollmentPersistenceTest
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.device.healthCheckpointEndKey
import com.openlattice.chronicle.collection.device.healthCheckpointScopeKey
import com.openlattice.chronicle.collection.device.healthConnectTestClient
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.storage.sleepSampleDao
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

/** Every historical source keeps its bookmark across a pause and drops only what was captured inside it. */
@Config(shadows = [AppNetworkEnrollmentPersistenceTest.StatsManager::class, AppNetworkEnrollmentPersistenceTest.EmptyStats::class])
class SuspendedIntervalExclusionTest : ResearchErasureTestFixture() {
    private val hour = TimeUnit.HOURS.toMillis(1)

    /** The server pauses at [pausedAt] and resumes at [resumedAt]; its status polls publish the window. */
    private fun suspendBetween(pausedAt: Long, resumedAt: Long) {
        for ((status, window) in listOf(ParticipationStatus.PAUSED to CaptureWindow(utc(pausedAt)),
            ParticipationStatus.ENROLLED to CaptureWindow(utc(pausedAt), utc(resumedAt)))) {
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), status,
                    listOf(window))
            })
        }
    }

    @Test fun healthConnectSkipsRecordsCapturedWhileSuspended() {
        val now = System.currentTimeMillis()
        val step = HealthConnectRecordType.STEPS
        HealthConnectScopeStore.of(context).replace(setOf(step))
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!
        context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE).edit().clear()
            .putLong(healthCheckpointEndKey(step), now - 4 * hour).putString(healthCheckpointScopeKey(step), scope.key).commit()
        suspendBetween(now - 3 * hour, now - 2 * hour)
        val records = listOf(now - 7 * hour / 2, now - 5 * hour / 2, now - 2 * hour, now - hour).mapIndexed { index, start ->
            StepsRecord(Instant.ofEpochMilli(start), ZoneOffset.UTC, Instant.ofEpochMilli(start + 1), ZoneOffset.UTC,
                10L + index, Metadata.manualEntryWithId("steps-$index"))
        }
        val client = healthConnectTestClient(permissions = {
            setOf(HealthPermission.getReadPermission(StepsRecord::class), ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ)
        }) { request ->
            records.filter { it.startTime >= request.timeRangeFilter.startTime!! && it.startTime < request.timeRangeFilter.endTime!! }
        }
        val source = AndroidHealthMetricSource(context, clientProvider = { client }, nowMillis = { now }, isForeground = { true })
        val read = onPersistenceWorker { source.read().also { source.acknowledgeRead() } }
        assertEquals(listOf("steps-0", "steps-2", "steps-3"), read.mapNotNull { it.sourceRecordId }.sorted())
    }

    @Test fun appNetworkUsageQueriesAroundTheSuspendedWindow() {
        AppNetworkEnrollmentPersistenceTest.StatsManager.windows.clear()
        val start = OffsetDateTime.parse(owner.createdAt).toInstant().toEpochMilli()
        val (pausedAt, resumedAt, end) = Triple(start + hour, start + 2 * hour, start + 3 * hour)
        suspendBetween(pausedAt, resumedAt)
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.APP_NETWORK_USAGE)!!
        context.getSharedPreferences("chronicle_app_network_usage", Context.MODE_PRIVATE).edit().clear()
            .putString("enrollment_epoch", scope.key).putLong("last_end_millis", start).putLong("pending_end_millis", end).commit()
        val source = AndroidAppNetworkUsageSource(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.NETWORK_STATS_SERVICE) ReflectionHelpers.newInstance(NetworkStatsManager::class.java)
                else super.getSystemService(name)
        }) { ResearchPersistenceGate.observationScope(context, CollectionModuleId.APP_NETWORK_USAGE) }
        onPersistenceWorker { source.read() }
        assertEquals(listOf(start to pausedAt, start to pausedAt, resumedAt to end, resumedAt to end),
            AppNetworkEnrollmentPersistenceTest.StatsManager.windows.sortedBy { it.first })
    }

    @Suppress("DEPRECATION") // Play services offers no replacement for building a test delivery.
    @Test fun sleepSegmentsThatStartWhileSuspendedAreDropped() {
        val now = System.currentTimeMillis()
        suspendBetween(now - 3 * hour, now - 2 * hour)
        val intent = Intent(SleepActivityReceiver.ACTION_SLEEP_ACTIVITY).setPackage(context.packageName)
            .putExtra("registration_module", CollectionModuleId.SLEEP.id)
            .putExtra("registration_scope", ResearchPersistenceGate.observationScope(context, CollectionModuleId.SLEEP)!!.key)
        SafeParcelableSerializer.serializeIterableToIntentExtra(listOf(
            SleepSegmentEvent(now - 5 * hour / 2, now - 2 * hour - 1, 0, 0, 0),
            SleepSegmentEvent(now - hour, now - hour / 2, 0, 0, 0),
        ), intent, "com.google.android.location.internal.EXTRA_SLEEP_SEGMENT_RESULT")
        val receiver = SleepActivityReceiver()
        context.registerReceiver(receiver, IntentFilter(intent.action), Context.RECEIVER_NOT_EXPORTED)
        try {
            context.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(shadowOf(receiver).originalPendingResult).getFuture().get(10, TimeUnit.SECONDS)
            assertEquals(listOf(now - hour), onPersistenceWorker {
                db.sleepSampleDao().getOldest(10).map { it.segmentStartMillis }
            })
        } finally { context.unregisterReceiver(receiver) }
    }

    @Test fun aClockSetBackDuringARequiredDeclineHaltStillExcludesWhatWasCapturedInIt() {
        val minute = TimeUnit.MINUTES.toMillis(1)
        val pausedAt = System.currentTimeMillis()
        fence.reconcileHaltSuspension(halted = true, now = pausedAt)
        // Five minutes in, the wall clock is corrected 20 minutes back; collection resumes ten minutes later.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(15))
        val resumedAt = pausedAt - 5 * minute
        fence.reconcileHaltSuspension(halted = false, now = resumedAt)
        val excluded = fence.suspendedIntervals()
        fun dropped(at: Long) = excluded.any { at in it }
        assertTrue("captured before the correction", dropped(pausedAt + 2 * minute))
        assertTrue("captured after the correction", dropped(resumedAt - 5 * minute))
        assertFalse("captured just before the pause", dropped(pausedAt - minute))
        assertFalse("captured at resume", dropped(resumedAt))
    }

    @Test fun aClockSetBackDuringAModuleStopStillExcludesWhatWasCapturedInIt() {
        val minute = TimeUnit.MINUTES.toMillis(1)
        val module = CollectionModuleId.IN_APP_ACTIVITY_CLASS
        val stoppedAt = System.currentTimeMillis()
        fence.reconcileModuleCollection(setOf(module), now = stoppedAt - hour)
        fence.reconcileModuleCollection(emptySet(), now = stoppedAt)
        // Five minutes in, the wall clock is corrected 20 minutes back; the module resumes ten minutes later.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(15))
        val resumedAt = stoppedAt - 5 * minute
        fence.reconcileModuleCollection(setOf(module), now = resumedAt)
        val excluded = fence.suspendedIntervals(module)
        fun dropped(at: Long) = excluded.any { at in it }
        assertTrue("captured before the correction", dropped(stoppedAt + 2 * minute))
        assertTrue("captured after the correction", dropped(resumedAt - 5 * minute))
        assertFalse("captured just before the stop", dropped(stoppedAt - minute))
        assertFalse("captured at resume", dropped(resumedAt))
    }
}
