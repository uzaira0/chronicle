@file:Suppress("DEPRECATION")

package com.openlattice.chronicle

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.interaction.InteractionCollectionService
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.interactionSampleDao
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ExternalCallbackCrashRegressionTest {
    @get:org.junit.Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun privacyPolicyTapReportsMissingBrowserWithoutCrashing() {
        shadowOf(context as Application).checkActivities(true)
        try {
            val activity = Robolectric.buildActivity(HealthConnectRationaleActivity::class.java).setup().get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            fun button(group: ViewGroup): Button? {
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child is Button) return child
                    if (child is ViewGroup) button(child)?.let { return it }
                }
                return null
            }
            assertTrue(button(root)!!.performClick())
            assertEquals(context.getString(R.string.no_browser_for_link), ShadowToast.getTextOfLatestToast())
        } finally { shadowOf(context).checkActivities(false) }
    }

    @Test fun invertedAccessibilityBoundsAreRejectedAndValidEventsStillPersist() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val study = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val prefs = context.getSharedPreferences("accessibility-bounds", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, study.toString()).putString(PARTICIPANT_ID, "participant")
            .putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EnrollmentTestStores.prefs = prefs
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        EnrollmentTestStores.db = db
        val service = Robolectric.buildService(InteractionCollectionService::class.java).create().get()
        try {
            db.uploadServerDao().insert(UploadServerEntity(name = "study",
            url = "https://localhost", apiKey = "test-device-key", studyId = study.toString(),
                participantId = "participant", sourceRandomDeviceId = "device"))
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(CollectionModuleId.INTERACTION_EVENTS.id,
                true, "ACCEPTED", 1, false, 1)))
            ResearchPersistenceGate.initialize(context)
            val initialized = java.util.concurrent.CountDownLatch(1)
            ResearchPersistenceGate.executeAsync { initialized.countDown() }
            assertTrue(initialized.await(5, TimeUnit.SECONDS))
            assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.INTERACTION_EVENTS))
            val policy = InteractionPolicySettings(context)
            assertTrue(policy.save(study, 1, true, InteractionPolicy.DEFAULT))
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            fun event(rect: Rect): AccessibilityEvent {
                val node = AccessibilityNodeInfo.obtain().apply { setBoundsInScreen(rect) }
                return AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
                    packageName = "test.app"; className = "android.widget.Button"; eventTime = 1
                    shadowOf(this).setSourceNode(node)
                }
            }
            service.onAccessibilityEvent(event(Rect(100, 0, 0, 100)))
            service.onAccessibilityEvent(event(Rect(0, 100, 100, 0)))
            assertEquals(0, db.interactionSampleDao().count())
            service.onAccessibilityEvent(event(Rect(0, 0, 100, 100)))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (db.interactionSampleDao().count() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, db.interactionSampleDao().count())
        } finally {
            service.onDestroy()
            InteractionPolicySettings.invalidateMemoryCache()
            EnrollmentTestStores.db = null; EnrollmentTestStores.prefs = null; db.close()
        }
    }
}
