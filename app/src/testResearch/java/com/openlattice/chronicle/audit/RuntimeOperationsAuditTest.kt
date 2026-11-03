package com.openlattice.chronicle.audit

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.SleepSegmentEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.activity.SleepActivityReceiver
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.STORAGE_PAUSE_NOTIFICATION_ID
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.notifications.NotificationDetails
import com.openlattice.chronicle.services.notifications.REMINDER_ALARM_STATE_PREFS
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_ACTION
import com.openlattice.chronicle.services.notifications.armReminder
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.ui.DashboardDataRepository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

@Suppress("DEPRECATION")
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
@RunWith(RobolectricTestRunner::class)
class RuntimeOperationsAuditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun init() { ResearchPersistenceGate.resetAfterLocalStoreRecovery(); ResearchPersistenceGate.initialize(context) }
    private fun count(family: LocalUploadModuleFamily, issue: LocalOperationalIssue): Int =
        ChronicleDb.getInstance(context).openHelper.readableDatabase
        .query("SELECT coalesce(sum(count),0) FROM upload_diagnostics WHERE moduleFamily = ? AND issueCode = ?", arrayOf(family.name, issue.name))
        .use { it.moveToFirst();it.getInt(0) }
    private fun await(check: () -> Boolean) { repeat(100) { if(check())return;Thread.sleep(20) }; assertTrue(check()) }
    @Test fun diagnosticsStayOnTheCallingTransactionAndRollBackWithIt() = onPersistenceWorker {
        AuditStores.install(context, true); init()
        val family = LocalUploadModuleFamily.LOCAL_STORE
        val issue = LocalOperationalIssue.LOCAL_WRITE_FAILED
        val db = ChronicleDb.getInstance(context)
        assertThrows(IllegalStateException::class.java) {
            db.runInTransaction {
                LocalUploadDiagnosticsStore.of(context).recordOperational(family, issue)
                assertEquals(1, count(family, issue))
                error("synthetic rollback")
            }
        }
        assertEquals(0, count(family, issue))
    }
    @Test fun lifecycleAndSleepDatabaseWriteFailuresAreOwnerScopedAndCountedOnce() {
        val intent = onPersistenceWorker {
        AuditStores.install(context,true, mapOf(CollectionModuleId.SLEEP to CollectionModuleSetting(enabled=true)))
        init()
        ShadowLog.clear()
        val db = ChronicleDb.getInstance(context)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_lifecycle BEFORE INSERT ON dataQueue " +
                "BEGIN SELECT RAISE(ABORT, 'UNIQUE_PRIVATE_EVENT_SENTINEL'); END",
        )
        DeviceLifecycleEventRecorder.recordAsync(context, DeviceLifecycleEventRecorder.lowMemoryEvent(10))
        await { count(LocalUploadModuleFamily.USAGE_LIFECYCLE, LocalOperationalIssue.LOCAL_WRITE_FAILED) == 1 }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_lifecycle")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_sleep BEFORE INSERT ON sleep_samples " +
                "BEGIN SELECT RAISE(ABORT, 'UNIQUE_PRIVATE_SLEEP_SENTINEL'); END",
        )
        val event = SleepSegmentEvent(System.currentTimeMillis()-1000,System.currentTimeMillis(),0,0,0)
        val intent = Intent(SleepActivityReceiver.ACTION_SLEEP_ACTIVITY).setPackage(context.packageName)
            .putExtra("registration_module",CollectionModuleId.SLEEP.id)
            .putExtra("registration_scope", ResearchPersistenceGate.observationScope(context,CollectionModuleId.SLEEP)!!.key)
        SafeParcelableSerializer.serializeIterableToIntentExtra(
            listOf(event), intent, "com.google.android.location.internal.EXTRA_SLEEP_SEGMENT_RESULT")
        intent
        }
        val receiver = SleepActivityReceiver()
        ContextCompat.registerReceiver(context,receiver,IntentFilter(intent.action),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(intent);shadowOf(Looper.getMainLooper()).idle()
        onPersistenceWorker {
        val db = ChronicleDb.getInstance(context)
        await { count(LocalUploadModuleFamily.SLEEP, LocalOperationalIssue.LOCAL_WRITE_FAILED) == 1 }
        assertEquals(1,count(LocalUploadModuleFamily.USAGE_LIFECYCLE, LocalOperationalIssue.LOCAL_WRITE_FAILED))
        assertEquals(0,db.queueEntryData().getSize())
        db.openHelper.readableDatabase.query(
            "SELECT studyId,participantId,errorType FROM upload_diagnostics WHERE issueCode = ?",
            arrayOf(LocalOperationalIssue.LOCAL_WRITE_FAILED.name)).use {
            assertEquals(2,it.count);while(it.moveToNext()){assertNotNull(it.getString(0));assertNotNull(it.getString(1));assertTrue(it.isNull(2))}
        }
        }
        assertTrue(ShadowLog.getLogs().any { it.type == Log.ERROR && it.msg.contains("lifecycle", ignoreCase = true) })
        assertTrue(ShadowLog.getLogs().any { it.type == Log.ERROR && it.tag == "SleepActivityReceiver" &&
            it.msg.startsWith("Failed to persist 1 sleep sample(s)") })
        assertFalse(ShadowLog.getLogs().any { it.msg.contains("UNIQUE_PRIVATE") })
        context.unregisterReceiver(receiver)
    }
    @Test fun alarmFailureLeavesNoArmedStateAndEligibilityRetriesSuccessfully() = onPersistenceWorker {
        AuditStores.install(context,true);init()
        val details=NotificationDetails("form",NotificationType.QUESTIONNAIRE,"FREQ=DAILY","Synthetic","Synthetic",serverUrl="https://localhost",
            accessCode="a".repeat(40))
        val settings=EnrollmentSettings(context)
        settings.setMobileReminderRequestCodes(setOf(details.requestCode()))
        val failing=object:ContextWrapper(context){override fun getSystemService(name:String):Any? {
            if(name==Context.ALARM_SERVICE)throw SecurityException("unique-alarm-sentinel");return super.getSystemService(name)
        }}
        val intent=Intent(context,SurveyNotificationsReceiver::class.java).setAction(
            SURVEY_NOTIFICATION_ACTION)
        assertNull(armReminder(failing,details,intent))
        assertEquals(1,count(LocalUploadModuleFamily.APP_RUNTIME, LocalOperationalIssue.COLLECTION_ACCESS_MISSING))
        assertFalse(details.requestCode() in settings.getMobileReminderRequestCodes())
        assertFalse(context.getSharedPreferences(REMINDER_ALARM_STATE_PREFS,Context.MODE_PRIVATE).contains(details.requestCode().toString()))
        assertTrue(ShadowLog.getLogs().any { it.type == Log.ERROR && it.msg.contains("reminder", ignoreCase = true) })
        assertFalse(ShadowLog.getLogs().any { it.msg.contains("unique-alarm-sentinel") })
        settings.setMobileReminderRequestCodes(setOf(details.requestCode()))
        assertNotNull(armReminder(context,details,intent))
        assertNull(armReminder(context,details.copy(id="not-registered"),intent))
        assertEquals(1,count(LocalUploadModuleFamily.APP_RUNTIME, LocalOperationalIssue.COLLECTION_ACCESS_MISSING))
        Unit
    }
    /** The process died after the episode checkpoint and before its diagnostic: the next successful arm records it, then ends it. */
    @Test fun aReminderFailureEpisodeWhoseDiagnosticNeverPersistedIsReportedWhenTheReminderArms() = onPersistenceWorker {
        AuditStores.install(context,true);init()
        val details=NotificationDetails("form",NotificationType.QUESTIONNAIRE,"FREQ=DAILY","Synthetic","Synthetic",serverUrl="https://localhost",
            accessCode="a".repeat(40))
        EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(details.requestCode()))
        val owner=ResearchPersistenceGate.captureOwner(context)!!
        val failedAt=OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).truncatedTo(ChronoUnit.MILLIS)
        val failures=context.getSharedPreferences("reminder_arm_failures",Context.MODE_PRIVATE)
        val key=details.requestCode().toString()
        assertTrue(failures.edit().putString(key,"${ResearchErasureFence.enrollmentKey(owner)}|${UUID.randomUUID()}|$failedAt").commit())
        val intent=Intent(context,SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION)

        assertNotNull(armReminder(context,details,intent))
        assertNotNull(armReminder(context,details,intent))

        assertEquals(1,count(LocalUploadModuleFamily.APP_RUNTIME, LocalOperationalIssue.COLLECTION_ACCESS_MISSING))
        ChronicleDb.getInstance(context).openHelper.readableDatabase.query(
            "SELECT firstOccurredAt FROM upload_diagnostics WHERE issueCode = ?", arrayOf(LocalOperationalIssue.COLLECTION_ACCESS_MISSING.name)).use {
            assertTrue(it.moveToFirst());assertTrue(OffsetDateTime.parse(it.getString(0)).isEqual(failedAt))
        }
        assertFalse(failures.contains(key))
    }
    @Test fun lowStorageIsVisibleAndRetainsQueueUntilCapacityRecovers() = onPersistenceWorker {
        AuditStores.install(context,true);init()
        val db=ChronicleDb.getInstance(context);db.queueEntryData().insertEntry(QueueEntry(10,1,byteArrayOf(1,2,3)))
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(StorageAdmission.allowed(context,0));assertFalse(StorageAdmission.allowed(context,0))
        val notifications = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        assertNotNull(notifications.getNotification(STORAGE_PAUSE_NOTIFICATION_ID))
        val paused=runBlocking { DashboardDataRepository.load(context) }
        assertEquals(0,paused.collection.active);assertTrue(paused.collection.message.contains("storage",ignoreCase=true))
        assertEquals(1,count(LocalUploadModuleFamily.LOCAL_STORE, LocalOperationalIssue.COLLECTION_PAUSED_STORAGE))
        assertEquals(1,db.queueEntryData().getSize())
        assertTrue(StorageAdmission.allowed(context,Long.MAX_VALUE))
        assertNull(notifications.getNotification(STORAGE_PAUSE_NOTIFICATION_ID))
        val resumed=runBlocking { DashboardDataRepository.load(context) }
        assertTrue(resumed.collection.active>0);assertFalse(resumed.collection.message.contains("storage",ignoreCase=true))
        assertEquals(1,
            db.queueEntryData().getSize())
        Unit
    }

}
