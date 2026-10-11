package com.openlattice.chronicle.collection.audio

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.services.notifications.NotificationListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaSessionManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [MediaSessionPlatformGateTest.CountedSessions::class])
class MediaSessionPlatformGateTest {
    @Implements(MediaSessionManager::class)
    class CountedSessions : ShadowMediaSessionManager() {
        var reads = 0
        @Implementation override fun getActiveSessions(component: ComponentName?): List<MediaController> {
            reads++
            return emptyList()
        }
    }

    @Test @Config(sdk = [27, 36])
    fun mediaSessionReadChecksListenerGrantBeforeCallingThePlatform() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = shadowOf(requireNotNull(app.getSystemService(NotificationManager::class.java)) { "notification manager available" })
        val component = ComponentName(app, NotificationListener::class.java)
        val sessions = org.robolectric.shadow.api.Shadow.extract<CountedSessions>(
            requireNotNull(app.getSystemService(MediaSessionManager::class.java)) { "media session manager available" })
        val controller = AudioCaptureController(app)
        val read = controller.javaClass.getDeclaredMethod("activeController").apply { isAccessible = true }
        manager.setNotificationListenerAccessGranted(component, false)
        assertNull(read.invoke(controller))
        assertEquals(0, sessions.reads)
        manager.setNotificationListenerAccessGranted(component, true)
        assertNull(read.invoke(controller))
        assertEquals(1, sessions.reads)
    }
}
