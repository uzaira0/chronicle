package com.openlattice.chronicle.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.activity.SleepActivityReceiver
import com.openlattice.chronicle.collection.state.*
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.notifications.*
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.sleepSampleDao
import com.openlattice.chronicle.ui.DashboardDataRepository
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.utils.Utils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Proxy
import java.util.UUID

@Suppress("DEPRECATION")
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner::class)
class RuntimeOperationsAuditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun init() { ResearchPersistenceGate.resetAfterLocalStoreRecovery(); ResearchPersistenceGate.initialize(context) }
    private fun count(family: String, issue: String): Int = ChronicleDb.getInstance(context).openHelper.readableDatabase
        .query("SELECT coalesce(sum(count),0) FROM upload_diagnostics WHERE moduleFamily = ? AND issueCode = ?", arrayOf(family,issue))
        .use { it.moveToFirst();it.getInt(0) }
    private fun await(check: () -> Boolean) { repeat(100) { if(check())return;Thread.sleep(20) }; assertTrue(check()) }
    @Test fun outerLifecycleAndSleepWriteFailuresAreOwnerScopedAndCountedOnce() {
        val intent = onPersistenceWorker {
        AuditStores.install(context,true, mapOf(CollectionModuleId.SLEEP to CollectionModuleSetting(enabled=true)))
        init()
        val failing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                if(name=="chronicle_lifecycle_recorder") throw IllegalStateException("UNIQUE_PRIVATE_EVENT_SENTINEL")
                return super.getSharedPreferences(name,mode)
            }
        }
        DeviceLifecycleEventRecorder.recordAsync(failing, DeviceLifecycleEventRecorder.lowMemoryEvent(10))
        await { count("USAGE_LIFECYCLE","LOCAL_WRITE_FAILED") == 1 }
        val db = ChronicleDb.getInstance(context)
        db.sleepSampleDao()
        val holders = Class.forName("com.openlattice.chronicle.storage.RestrictedRoomDaoAccessKt")
            .getDeclaredField("restrictedDaos").apply { isAccessible=true }.get(null) as Map<*, *>
        val holder = holders[db]!!
        val field = holder.javaClass.declaredFields.first { it.name.startsWith("sleep") }.apply { isAccessible=true }
        field.set(holder, lazy<com.openlattice.chronicle.storage.SleepSampleDao> { throw IllegalStateException("UNIQUE_PRIVATE_SLEEP_SENTINEL") })
        val event = com.google.android.gms.location.SleepSegmentEvent(System.currentTimeMillis()-1000,System.currentTimeMillis(),0,0,0)
        val intent = Intent(SleepActivityReceiver.ACTION_SLEEP_ACTIVITY).setPackage(context.packageName)
            .putExtra("registration_module",CollectionModuleId.SLEEP.id)
            .putExtra("registration_scope", ResearchPersistenceGate.observationScope(context,CollectionModuleId.SLEEP)!!.first)
        com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer.serializeIterableToIntentExtra(
            listOf(event), intent, "com.google.android.location.internal.EXTRA_SLEEP_SEGMENT_RESULT")
        intent
        }
        val receiver = SleepActivityReceiver()
        androidx.core.content.ContextCompat.registerReceiver(context,receiver,android.content.IntentFilter(intent.action),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(intent);shadowOf(Looper.getMainLooper()).idle()
        onPersistenceWorker {
        val db = ChronicleDb.getInstance(context)
        await { count("SLEEP","LOCAL_WRITE_FAILED") == 1 }
        assertEquals(1,count("USAGE_LIFECYCLE","LOCAL_WRITE_FAILED"));assertEquals(0,db.queueEntryData().getSize())
        db.openHelper.readableDatabase.query("SELECT studyId,participantId,errorType FROM upload_diagnostics WHERE issueCode='LOCAL_WRITE_FAILED'").use {
            assertEquals(2,it.count);while(it.moveToNext()){assertNotNull(it.getString(0));assertNotNull(it.getString(1));assertTrue(it.isNull(2))}
        }
        }
        context.unregisterReceiver(receiver)
    }
    @Test fun alarmFailureLeavesNoArmedStateAndEligibilityRetriesSuccessfully() = onPersistenceWorker {
        AuditStores.install(context,true);init()
        val details=NotificationDetails("form",NotificationType.QUESTIONNAIRE,"FREQ=DAILY","Synthetic","Synthetic",serverUrl="https://localhost",accessCode="a".repeat(40))
        val settings=com.openlattice.chronicle.preferences.EnrollmentSettings(context)
        settings.setMobileReminderRequestCodes(setOf(details.requestCode()))
        val failing=object:ContextWrapper(context){override fun getSystemService(name:String):Any? {
            if(name==Context.ALARM_SERVICE)throw SecurityException("unique-alarm-sentinel");return super.getSystemService(name)
        }}
        val intent=Intent(context,com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION)
        assertNull(armReminder(failing,details,intent))
        assertEquals(1,count("APP_RUNTIME","COLLECTION_ACCESS_MISSING"))
        assertTrue(details.requestCode() in settings.getMobileReminderRequestCodes())
        assertFalse(context.getSharedPreferences(REMINDER_ALARM_STATE_PREFS,Context.MODE_PRIVATE).contains(details.requestCode().toString()))
        assertNotNull(armReminder(context,details,intent))
        assertNull(armReminder(context,details.copy(id="not-registered"),intent))
        assertEquals(1,count("APP_RUNTIME","COLLECTION_ACCESS_MISSING"))
        Unit
    }
    @Test fun lowStorageIsVisibleAndRetainsQueueUntilCapacityRecovers() = onPersistenceWorker {
        AuditStores.install(context,true);init()
        val db=ChronicleDb.getInstance(context);db.queueEntryData().insertEntry(com.openlattice.chronicle.storage.QueueEntry(10,1,byteArrayOf(1,2,3)))
        assertFalse(StorageAdmission.allowed(context,0));assertFalse(StorageAdmission.allowed(context,0))
        val paused=runBlocking { DashboardDataRepository.load(context) }
        assertEquals(0,paused.collection.active);assertTrue(paused.collection.message.contains("storage",ignoreCase=true))
        assertEquals(1,count("LOCAL_STORE","COLLECTION_PAUSED_STORAGE"));assertEquals(1,db.queueEntryData().getSize())
        assertTrue(StorageAdmission.allowed(context,Long.MAX_VALUE))
        val resumed=runBlocking { DashboardDataRepository.load(context) }
        assertTrue(resumed.collection.active>0);assertFalse(resumed.collection.message.contains("storage",ignoreCase=true));assertEquals(1,db.queueEntryData().getSize())
        Unit
    }
    @Test fun questionnaireSchedulingLogsOnlyCountsAndRedactedIdentifiers() = onPersistenceWorker {
        AuditStores.install(context,true);init()
        val worker=NotificationsWorker(context,auditWorkerParameters())
        val sentinel="UNIQUE_PRIVATE_QUESTIONNAIRE_PROMPT_AND_CHOICE"
        val api=Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,arrayOf(ChronicleStudyApi::class.java)){_,method,_->when(method.name){
            "getParticipationStatus"->com.openlattice.chronicle.data.ParticipationStatus.ENROLLED
            "isNotificationsEnabled"->false
            "getStudyQuestionnaires"->mapOf(UUID.randomUUID() to mapOf(org.apache.olingo.commons.api.edm.FullQualifiedName("synthetic","prompt") to setOf<Any>(sentinel)))
            else->null
        }} as ChronicleStudyApi
        fun set(name:String,value:Any){worker.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(worker,value)}
        val settings=com.openlattice.chronicle.preferences.EnrollmentSettings(context)
        set("enrollmentSettings",settings);set("studyId",settings.getStudyId());set("participantId",settings.getParticipantId());set("chronicleApi",api)
        ShadowLog.clear()
        worker.javaClass.getDeclaredMethod("workHelper").apply{isAccessible=true}.invoke(worker)
        val logs=ShadowLog.getLogs().joinToString("\n"){it.msg}
        assertFalse(logs,logs.contains(sentinel));assertTrue(logs.contains("questionnaire count: 1",ignoreCase=true));assertTrue(logs.contains("requestCode"))
        Unit
    }
}
