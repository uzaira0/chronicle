package com.openlattice.chronicle.audit

import android.app.Service
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.*
import com.openlattice.chronicle.services.sensors.HardwareSensorService
import com.openlattice.chronicle.storage.ChronicleDb
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],shadows=[ForegroundStartAuditTest.RestrictedForeground::class])
class ForegroundStartAuditTest {
    @Implements(Service::class)
    class RestrictedForeground:org.robolectric.shadows.ShadowService(){
        @Implementation override fun startForeground(id:Int,notification:Notification){throw SecurityException("synthetic foreground restriction")}
    }
    @Test fun rejectedForegroundCreationStopsWithoutStartingCollectorsAndRecordsEvidence() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        AuditStores.install(context,true,mapOf(CollectionModuleId.SENSOR_ACCELEROMETER to CollectionModuleSetting(true)))
        onPersistenceWorker{ResearchPersistenceGate.resetAfterLocalStoreRecovery();ResearchPersistenceGate.initialize(context)}
        val controller=Robolectric.buildService(HardwareSensorService::class.java)
        controller.create();val service=controller.get()
        try {
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertNull(service.javaClass.getDeclaredField("controller").apply{isAccessible=true}.get(service))
            assertEquals(Service.START_NOT_STICKY,service.onStartCommand(null,0,1))
            val db=ChronicleDb.getInstance(context)
            repeat(100){
                val count=db.openHelper.readableDatabase.query("SELECT count(*) FROM upload_diagnostics WHERE moduleFamily='SENSOR' AND issueCode='COLLECTION_ACCESS_MISSING'").use{it.moveToFirst();it.getInt(0)}
                if(count==0)Thread.sleep(20)
            }
            db.openHelper.readableDatabase.query("SELECT count(*) FROM upload_diagnostics WHERE moduleFamily='SENSOR' AND issueCode='COLLECTION_ACCESS_MISSING'").use{it.moveToFirst();assertEquals(1,it.getInt(0))}
        } finally{controller.destroy()}
    }
}
