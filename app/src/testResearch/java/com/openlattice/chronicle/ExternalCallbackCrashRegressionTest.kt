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
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.interactionSampleDao
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ExternalCallbackCrashRegressionTest {
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
        val prefsField = EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
        prefsField.set(EncryptedPrefsHelper, prefs)
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        val dbField = ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        dbField.set(null, db)
        val service = Robolectric.buildService(InteractionCollectionService::class.java).create().get()
        try {
            db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost", studyId = study.toString(),
                participantId = "participant", sourceDeviceId = "device"))
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(CollectionModuleId.INTERACTION_EVENTS.id,
                true, "ACCEPTED", 1, false, 1, null, null)))
            ResearchPersistenceGate.initialize(context)
            val initialized = java.util.concurrent.CountDownLatch(1)
            ResearchPersistenceGate.executeAsync { initialized.countDown() }
            assertTrue(initialized.await(5, TimeUnit.SECONDS))
            assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.INTERACTION_EVENTS))
            val policy = InteractionPolicySettings(context)
            assertTrue(policy.save(study, 1, true, InteractionPolicy.DEFAULT))
            service.javaClass.getDeclaredField("policySettings").apply { isAccessible = true }.set(service, policy)
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
            val tasks = service.javaClass.getDeclaredField("writeExecutor").apply { isAccessible = true }.get(service)
            val executor = tasks.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(tasks) as ExecutorService
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(1, db.interactionSampleDao().count())
        } finally {
            service.onDestroy()
            InteractionPolicySettings.invalidateMemoryCache()
            dbField.set(null, null); prefsField.set(EncryptedPrefsHelper, null); db.close()
        }
    }
}
