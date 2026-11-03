package com.openlattice.chronicle.collection.state

import java.util.concurrent.TimeUnit
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.device.healthCheckpointScopeKey
import com.openlattice.chronicle.collection.device.healthCheckpointEndKey
import com.openlattice.chronicle.collection.device.healthConnectTestClient
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.HealthConnectRecordType
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.permission.HealthPermission
import com.openlattice.chronicle.audit.auditWorkerParameters
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ListenableWorker
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.device.AndroidHealthMetricSource
import com.openlattice.chronicle.preferences.MOBILE_REMINDER_REQUEST_CODES
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_ACTION
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_TAG
import com.openlattice.chronicle.services.notifications.eraseSurveyArtifacts
import com.openlattice.chronicle.services.notifications.reminderAlarms
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchArtifactErasureTest : ResearchErasureTestFixture() {
    @Test fun surveyErasureCancelsAlarmPostedNotificationAndItsBrowserCapability() {
        val code = 45
        prefs.edit().putStringSet(MOBILE_REMINDER_REQUEST_CODES, setOf(code.toString())).commit()
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduled = PendingIntent.getBroadcast(context, code,
            Intent(context, SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION), PendingIntent.FLAG_IMMUTABLE)
        alarm.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10_000, scheduled)
        val browser = PendingIntent.getActivity(context, code, Intent(Intent.ACTION_VIEW, Uri.parse("https://localhost/survey")),
            PendingIntent.FLAG_IMMUTABLE)
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.notify(SURVEY_NOTIFICATION_TAG, code, Notification.Builder(context, "survey")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(browser).build())
        stopOnPersistenceWorker { eraseSurveyArtifacts(context) }
        assertTrue(reminderAlarms(context).isEmpty())
        assertTrue(notifications.activeNotifications.isEmpty())
        assertThrows(PendingIntent.CanceledException::class.java) { browser.send() }
        assertThrows(PendingIntent.CanceledException::class.java) { scheduled.send() }
    }

    @Test fun withdrawalErasureClearsExpansionPullScheduleForTheNextEnrollment() {
        val schedulePrefs = context.getSharedPreferences("expansion_pull_schedule", Context.MODE_PRIVATE)
        schedulePrefs.edit().putLong("lastrun_battery_telemetry", System.currentTimeMillis()).commit()

        stopOnPersistenceWorker { eraseResearchSourceState(context) }

        assertTrue(schedulePrefs.all.isEmpty())
    }

    @Test fun acknowledgedWithdrawalClearsParticipantLinkedEpisodeBookkeeping() {
        val expected = owner
        val episodeStores = listOf("chronicle_access_missing_episodes", "capture_registration_status") +
            setOf(CollectionModuleId.SLEEP, CollectionModuleId.ACTIVITY_RECOGNITION).map { "capture_registration_episode_${it.id}" }
        episodeStores.forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit()
                .putString("${expected.studyId}:${expected.participantId}|episode", "2026-10-01T00:00:00Z").commit()
        }
        onPersistenceWorker { StorageAdmission.allowed(context, 0) }
        assertTrue(prefs.all.keys.any { it.startsWith("collection_storage_") })
        assertTrue(onPersistenceWorker { ParticipantWithdrawalManager.begin(context) })
        WithdrawalStateStore(context).acknowledgeServer(expected.id)

        assertEquals(ListenableWorker.Result.success(), onPersistenceWorker {
            ParticipantWithdrawalWorker(context, auditWorkerParameters()).doWork()
        })

        episodeStores.forEach { assertTrue(context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isEmpty()) }
        assertFalse(prefs.all.keys.any { it.startsWith("collection_storage_") })
        assertFalse(prefs.all.values.any { it.toString().contains(expected.participantId) })
    }

    @Test fun initialConsentRetainsReviewedHealthHistoryAndEnrollmentInstallationIsIdempotent() {
        stopOnPersistenceWorker {
            assertTrue(fence.installEnrollment(owner))
            fence.accepted(setOf(CollectionModuleId.HEALTH_CONNECT), initialReview = true)
        }
        val generation = fence.generation(CollectionModuleId.HEALTH_CONNECT)
        stopOnPersistenceWorker { assertFalse(fence.installEnrollment(owner)) }
        assertEquals(generation, fence.generation(CollectionModuleId.HEALTH_CONNECT))
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        val now = System.currentTimeMillis()
        val client = healthConnectTestClient(permissions = {
            setOf(HealthPermission.getReadPermission(StepsRecord::class), ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ)
        }) { request ->
            assertEquals(now - TimeUnit.DAYS.toMillis(1), requireNotNull(request.timeRangeFilter.startTime).toEpochMilli())
            emptyList()
        }
        val source = AndroidHealthMetricSource(context, clientProvider = { client }, nowMillis = { now })
        source.read(); source.acknowledgeRead()
    }

    @Test fun healthSourceErasureClearsDurableAndPendingReadStateAndUsesAcceptanceFloor() {
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        val sourcePrefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!
        sourcePrefs.edit().putLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), 10)
            .putString(healthCheckpointScopeKey(HealthConnectRecordType.STEPS), scope.key).commit()
        var expectedStart: Long? = null
        var now = System.currentTimeMillis()
        val client = healthConnectTestClient(permissions = {
            setOf(HealthPermission.getReadPermission(StepsRecord::class), ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ)
        }) { request ->
            expectedStart?.let { assertEquals(it, requireNotNull(request.timeRangeFilter.startTime).toEpochMilli()) }
            emptyList()
        }
        val source = AndroidHealthMetricSource(context, clientProvider = { client }, nowMillis = { now })
        source.read()
        eraseAndReaccept(CollectionModuleId.HEALTH_CONNECT)
        source.acknowledgeRead()
        assertFalse(sourcePrefs.contains(healthCheckpointEndKey(HealthConnectRecordType.STEPS)))
        expectedStart = fence.floor(CollectionModuleId.HEALTH_CONNECT)
        now = System.currentTimeMillis() + 1_000
        source.read(); source.acknowledgeRead()
        assertEquals(now, sourcePrefs.getLong(healthCheckpointEndKey(HealthConnectRecordType.STEPS), -1))
    }
}
