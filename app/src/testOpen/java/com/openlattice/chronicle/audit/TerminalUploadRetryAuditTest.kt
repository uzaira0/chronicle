package com.openlattice.chronicle.audit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.state.*
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.study.StudyEncryptionSetting
import com.openlattice.chronicle.services.upload.*
import com.openlattice.chronicle.storage.*
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.utils.Utils
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TerminalUploadRetryAuditTest {
    @get:org.junit.Rule val persistence=BackgroundPersistenceRule()
    @Test fun terminalBatchIsAttemptedOnceRetainedAndUnblockedOnlyAfterCorrection() {
        val context=ApplicationProvider.getApplicationContext<Context>();AuditStores.install(context,true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery();ResearchPersistenceGate.initialize(context)
        val db=ChronicleDb.getInstance(context);val owner=db.uploadServerDao().getConfiguredServer()!!
        EncryptionSettingStore.of(context).put(UUID.fromString(owner.studyId),StudyEncryptionSetting(enabled=false))
        val event=ExtractedUsageEvent(appPackageName="a.b.c",interactionType="Activity Resumed",timestamp=OffsetDateTime.now(),timezone="UTC",user="",applicationLabel="Synthetic")
        db.queueEntryData().insertEntry(QueueEntry(System.currentTimeMillis(),1,JsonSerializer.serializeQueueEntry(listOf(event))))
        var calls=0;var rejected=true
        val api=Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,arrayOf(ChronicleStudyApi::class.java)){_,m,args->
            if(m.name=="uploadAndroidUsageEventData"){calls++;if(rejected)throw retrofit2.HttpException(retrofit2.Response.error<Any>(413,"synthetic refusal".toResponseBody()));(args!![4] as com.openlattice.chronicle.android.ChronicleData).size}else null
        } as ChronicleStudyApi
        @Suppress("UNCHECKED_CAST") val cache=UploadWorker::class.java.getDeclaredField("studyApiCache").apply{isAccessible=true}.get(null) as MutableMap<String,ChronicleStudyApi>
        cache["https://localhost|${Utils.mobileSigningSecretFingerprint(null)}"]=api
        val uploader=UploadWorkerDelegate(context,db)
        assertEquals(1,uploader.execute());assertEquals(1,calls);assertEquals(1,db.queueEntryData().getSize())
        val blocked = kotlinx.coroutines.runBlocking { com.openlattice.chronicle.ui.DashboardDataRepository.load(context) }
        assertTrue(blocked.serverHealth.message.contains("retained", ignoreCase = true))
        assertTrue(blocked.servers.single().healthLabel.contains("retained", ignoreCase = true))
        uploader.execute();assertEquals("terminal request must not repeat unchanged",1,calls);assertEquals(1,db.queueEntryData().getSize())
        rejected=false
        db.openHelper.writableDatabase.execSQL("UPDATE collection_module_state SET appliedVersion=appliedVersion+1")
        assertEquals(0,uploader.execute());assertEquals(2,calls);assertEquals(0,db.queueEntryData().getSize())
        for (attempt in 1..12) repeat(10) {
            val delay = UploadRetryGate.retryDelayMillis(attempt)
            assertTrue(delay in 15000L..900000L)
        }
        for (status in listOf(429,500,503)) {
            val error = retrofit2.HttpException(retrofit2.Response.error<Any>(status,"synthetic transient".toResponseBody()))
            UploadRetryGate.recordFailure(context,owner,LocalUploadModuleFamily.USAGE_LIFECYCLE,error,db)
            assertFalse(UploadRetryGate.shouldAttempt(context,owner,LocalUploadModuleFamily.USAGE_LIFECYCLE,db))
            assertTrue(UploadRetryGate.shouldAttempt(context,owner,LocalUploadModuleFamily.USAGE_LIFECYCLE,db,System.currentTimeMillis()+901000))
        }
    }
}
