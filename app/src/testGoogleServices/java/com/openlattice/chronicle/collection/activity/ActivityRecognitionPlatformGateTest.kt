package com.openlattice.chronicle.collection.activity

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActivityRecognitionPlatformGateTest {
    @Test @Config(sdk = [Build.VERSION_CODES.P, 29, 36])
    fun activityRequestsUseThePermissionForTheCurrentPlatform() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permission = if (Build.VERSION.SDK_INT < 29) ModulePermissions.LEGACY_ACTIVITY_RECOGNITION
            else ModulePermissions.ACTIVITY_RECOGNITION
        val check = SleepActivityCaptureController.javaClass.getDeclaredMethod("hasActivityRecognitionPermission", Context::class.java)
            .apply { isAccessible = true }
        shadowOf(app).denyPermissions(permission)
        assertEquals(false, check.invoke(SleepActivityCaptureController, app))
        shadowOf(app).grantPermissions(permission)
        assertEquals(true, check.invoke(SleepActivityCaptureController, app))
    }

    @Test @Config(sdk = [30, Build.VERSION_CODES.S, 36])
    fun mutableActivityCallbackIsExplicitAndUsesTheAndroid12Flag() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val create = SleepActivityCaptureController.javaClass.getDeclaredMethod("pendingIntent",
            Context::class.java, CollectionModuleId::class.java, String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val pending = create.invoke(SleepActivityCaptureController, app, CollectionModuleId.SLEEP,
            "guard-test", PendingIntent.FLAG_UPDATE_CURRENT) as PendingIntent
        assertEquals(ComponentName(app, SleepActivityReceiver::class.java), shadowOf(pending).savedIntent.component)
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, shadowOf(pending).flags and PendingIntent.FLAG_MUTABLE != 0)
        assertFalse(shadowOf(pending).isImmutable)
    }
}
