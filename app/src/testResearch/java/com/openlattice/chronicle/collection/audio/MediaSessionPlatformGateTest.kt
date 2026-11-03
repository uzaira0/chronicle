package com.openlattice.chronicle.collection.audio

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.services.notifications.NotificationListener
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.audioActivitySampleDao
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
    @get:org.junit.Rule val backgroundPersistence = BackgroundPersistenceRule()
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
        AuditStores.install(app, true, mapOf(CollectionModuleId.AUDIO_ACTIVITY to CollectionModuleSetting(enabled = true)))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(app)
        val db = ChronicleDb.getInstance(app)
        try {
            manager.setNotificationListenerAccessGranted(component, false)
            controller.snapshot()
            assertEquals(0, sessions.reads)
            assertEquals(1, db.audioActivitySampleDao().count())
            manager.setNotificationListenerAccessGranted(component, true)
            controller.snapshot()
            assertEquals(1, sessions.reads)
            val samples = db.audioActivitySampleDao().getOldest(10)
            assertEquals(2, samples.size)
            samples.forEach { assertNull(it.audioPackage) }
        } finally {
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            EnrollmentTestStores.db = null
            EnrollmentTestStores.prefs = null
            db.close()
        }
    }
}
