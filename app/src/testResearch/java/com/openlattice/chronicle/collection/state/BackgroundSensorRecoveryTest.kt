package com.openlattice.chronicle.collection.state

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PowerManager
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.receivers.lifecycle.PowerSaveModeReceiver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [31])
class BackgroundSensorRecoveryTest : ResearchErasureTestFixture() {
    @Test fun pauseAndResumeKeepTheRuntimeAliveWithoutAForegroundActivity() {
        exerciseRecovery { background, gateway ->
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(background, owner,
                    fence.settingsGeneration(), ParticipationStatus.PAUSED, emptyList())
            })
            assertFalse(ResearchPersistenceGate.collectsNow(context,
                CollectionModuleId.SENSOR_ACCELEROMETER))
            gateway.registeredContinuous.clear()
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(background, owner,
                    fence.settingsGeneration(), ParticipationStatus.ENROLLED, emptyList())
            })
        }
    }

    @Test fun powerSaveRoundTripKeepsTheRuntimeAliveWithoutAForegroundActivity() {
        exerciseRecovery { background, gateway ->
            SensorSettings(context).applyResolvedSensors(mapOf(AndroidSensorType.accelerometer to AndroidSensorSetting()))
            val power = shadowOf(context.getSystemService(PowerManager::class.java))
            val receiver = PowerSaveModeReceiver()
            listOf(true, false).forEach { saving ->
                power.setIsPowerSaveMode(saving)
                gateway.powerSaveMode = saving
                receiver.onReceive(background, Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            }
        }
    }

    private fun exerciseRecovery(transition: (Context, FakeSensorGateway) -> Unit) {
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler()
        val runtime = runtime(gateway = gateway, scheduler = scheduler)
        var running = true
        val background = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): ComponentName? =
                throw ForegroundServiceStartNotAllowedException("No resumed activity")
            override fun stopService(intent: Intent): Boolean {
                running = false
                runtime.stop()
                return true
            }
        }
        try {
            onPersistenceWorker { runtime.start() }
            assertTrue(gateway.registeredContinuous.isNotEmpty())
            transition(background, gateway)
            assertTrue("Temporary collection gates must preserve the foreground runtime", running)
            onPersistenceWorker { scheduler.runAll(20) }
            assertTrue("A background resume must register the sensor again",
                AndroidSensorType.accelerometer in gateway.registeredContinuous)
        } finally { runtime.stop() }
    }
}
