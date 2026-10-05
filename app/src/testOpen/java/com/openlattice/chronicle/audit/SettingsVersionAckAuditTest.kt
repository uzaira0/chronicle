package com.openlattice.chronicle.audit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.*
import com.openlattice.chronicle.collection.*
import com.openlattice.chronicle.collection.state.*
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.utils.Utils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class SettingsVersionAckAuditTest {
    @get:org.junit.Rule val persistence=BackgroundPersistenceRule()
    @Test fun settingsOnlyChangeDurablyAcknowledgesAppliedVersionOnceAndRetriesOffline() {
        val context=ApplicationProvider.getApplicationContext<Context>();AuditStores.install(context,true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery();ResearchPersistenceGate.initialize(context)
        val db=ChronicleDb.getInstance(context);val server=db.uploadServerDao().getConfiguredServer()!!
        val manifest=ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java).fromJson(server.studyDisclosureJson!!)!!
        db.collectionModuleStateDao().getAll().forEach { row ->
            val module = CollectionModuleId.fromIdOrNull(row.moduleId)!!
            db.collectionModuleStateDao().upsertAll(listOf(row.copy(appliedPolicySnapshot = manifest.collectionSettings.modules.getValue(module).consentPolicySnapshot())))
        }
        val changed=manifest.collectionSettings.copy(settingsVersion=manifest.collectionSettings.settingsVersion+1,
            modules=manifest.collectionSettings.modules + (CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(true,collectionCadence=CollectionCadence(1800))))
        val coordinator=CollectionLoopCoordinator(context)
        assertTrue(coordinator.applyFetchedSettings(server,ResearchErasureFence(context).settingsGeneration(),changed))
        val queue=CollectionAckRetryQueue.of(context)
        assertEquals(1,queue.load().size);assertEquals(changed.settingsVersion,queue.load().single().settingsVersion)
        assertTrue(queue.load().single().acceptedModuleIds.contains(CollectionModuleId.USAGE_EVENTS.id))
        assertTrue(coordinator.applyFetchedSettings(server,ResearchErasureFence(context).settingsGeneration(),changed))
        assertEquals(1,queue.load().size)
        var online=false;val delivered=mutableListOf<CollectionAcknowledgment>()
        val api=Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,arrayOf(ChronicleStudyApi::class.java)){_,m,args->
            if(m.name=="reportCollectionAck"){if(!online)throw java.io.IOException("offline synthetic");delivered+=(args!![4] as CollectionAcknowledgment);com.openlattice.chronicle.base.OK()}else null
        } as ChronicleStudyApi
        @Suppress("UNCHECKED_CAST") val cache=UploadWorker::class.java.getDeclaredField("studyApiCache").apply{isAccessible=true}.get(null) as MutableMap<String,ChronicleStudyApi>
        cache["https://localhost|${Utils.mobileSigningSecretFingerprint(null)}"]=api
        val retry=coordinator.javaClass.getDeclaredMethod("retryPendingCollectionAcks").apply{isAccessible=true}
        assertEquals(false,retry.invoke(coordinator));assertEquals(1,queue.load().size)
        online=true;assertEquals(org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n"){it.msg+it.throwable?.toString()},true,retry.invoke(coordinator));assertTrue(queue.load().isEmpty());assertEquals(changed.settingsVersion,delivered.single().settingsVersion)
        assertTrue(coordinator.applyFetchedSettings(server,ResearchErasureFence(context).settingsGeneration(),changed));assertTrue(queue.load().isEmpty())
    }
}
