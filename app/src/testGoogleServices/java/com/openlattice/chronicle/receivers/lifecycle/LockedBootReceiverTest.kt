package com.openlattice.chronicle.receivers.lifecycle

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LockedBootReceiverTest {
    @Test fun unreadableDeviceProtectedStorageReturnsNormally() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        var storageReads = 0
        var serviceStarts = 0
        val context = object : ContextWrapper(app) {
            override fun createDeviceProtectedStorageContext(): Context {
                storageReads++
                error("Device-protected store unavailable")
            }
            override fun startForegroundService(intent: Intent): ComponentName? {
                serviceStarts++
                return null
            }
        }
        LockedBootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(1, storageReads)
        assertEquals(0, serviceStarts)
    }

    @Test fun startRefusalRetainsSnapshotAndReturnsNormally() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val snapshot = DirectBootSensorSnapshot(app)
        assertTrue(snapshot.write(mapOf(AndroidSensorType.accelerometer to DirectBootSensorSnapshot.SensorConfig()), "owner"))
        var starts = 0
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): ComponentName? {
                starts++
                throw ForegroundServiceStartNotAllowedException("Test background start refusal")
            }
        }
        LockedBootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(1, starts)
        assertTrue(snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS))
    }
}
