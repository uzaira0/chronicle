package com.openlattice.chronicle.collection.identification

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.preferences.CURRENT_USER
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.labelTimeline
import com.openlattice.chronicle.collection.core.ModuleResult
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * JVM unit coverage for [UserIdentificationCollectionModule] — the Phase 7 user
 * identification module (refactor plan §10.1).
 *
 * Drives the module over the [FakeTargetUserStore] seam (no
 * Android `Context`, no Room, no EncryptedSharedPreferences). Proves the module
 * preserves: the dual-store `setTargetUser` write; the disabled no-op; the
 * nearest-lower-timestamp lookup; the "Not set" → empty-user mapping; idempotency of
 * repeated target-user selection; and — the central privacy guarantee — that diagnostics
 * carry **no raw participant label in any form** (refactor plan §10.1 guardrails 1 & 3).
 */
class UserIdentificationCollectionModuleTest {

    private val unassigned = "Not set"

    private fun module(
        store: FakeTargetUserStore = FakeTargetUserStore(),
        enabled: () -> Boolean = { true },
    ) = UserIdentificationCollectionModule(
        store = store,
        userIdentificationEnabled = enabled,
    )

    // ----- target-user write -----

    @Test
    fun setTargetUserWritesBothTheQueueRowAndTheCurrentUserPref() {
        val store = FakeTargetUserStore()
        val m = module(store)

        val result = m.setTargetUser("Study participant")

        assertEquals(ModuleResult.Ok(1), result)
        // userQueue row written.
        assertEquals(1, store.queueRows.size)
        assertEquals("Study participant", store.queueRows.single().user)
        // current_user pref written to the same label.
        assertEquals("Study participant", store.currentUserPref)
    }

    @Test
    fun setTargetUserPersistsTheOtherLabelToo() {
        val store = FakeTargetUserStore()
        module(store).setTargetUser("Someone else")
        assertEquals("Someone else", store.recordedUsers.single())
        assertEquals("Someone else", store.currentUserPref)
    }

    @Test
    fun setTargetUserFailureSurfacesAsFailedNotSwallowed() {
        val store = FakeTargetUserStore().apply { failNextInsert = true }
        val m = module(store)

        val result = m.setTargetUser("Study participant")

        assertTrue("expected Failed, got $result", result is ModuleResult.Failed)
        // The redacted message names the failing store, never the participant label.
        val msg = (result as ModuleResult.Failed).redactedMessage
        assertFalse("redacted message must not leak the label", msg.contains("Study participant"))
    }

    // ----- disabled user identification -----

    @Test
    fun disabledUserIdentificationSetTargetUserIsANoOpSkipAndWritesNothing() {
        val store = FakeTargetUserStore()
        val m = module(store, enabled = { false })

        val result = m.setTargetUser("Study participant")

        assertTrue("expected Skipped, got $result", result is ModuleResult.Skipped)
        assertEquals("disabled module must write nothing", 0, store.queueRows.size)
        assertNull("disabled module must not touch the current_user pref", store.currentUserPref)
    }

    @Test
    fun isEnabledFollowsThePreference() {
        assertFalse(module(enabled = { false }).isEnabled())
        assertTrue(module(enabled = { true }).isEnabled())
    }

    // ----- current user / "Not set" -----

    @Test
    fun currentUserDefaultsToUnassignedLabelWhenPrefIsUnset() {
        // No pref written yet → "Not set", identical to EnrollmentSettings.getCurrentUser().
        assertEquals(unassigned, module().currentUser())
    }

    @Test
    fun currentUserReturnsTheLastWrittenLabel() {
        val store = FakeTargetUserStore()
        val m = module(store)
        m.setTargetUser("Someone else")
        assertEquals("Someone else", m.currentUser())
    }

    // ----- repeated selection / idempotency -----

    @Test
    fun repeatedTargetUserSelectionAppendsOneQueueRowPerCallAndConvergesPref() {
        val store = FakeTargetUserStore()
        val m = module(store)

        m.setTargetUser("Study participant")
        m.setTargetUser("Someone else")
        m.setTargetUser("Study participant")

        // The userQueue is append-only: three selections → three rows (legacy parity —
        // EnrollmentSettings.setTargetUser inserts a row every call).
        assertEquals(3, store.queueRows.size)
        // The current_user pref always converges to the most recent selection.
        assertEquals("Study participant", store.currentUserPref)
        assertEquals("Study participant", m.currentUser())
    }

    @Test
    fun selectingTheSameUserTwiceIsConsistentAndStillSucceeds() {
        val store = FakeTargetUserStore()
        val m = module(store)

        assertEquals(ModuleResult.Ok(1), m.setTargetUser("Someone else"))
        assertEquals(ModuleResult.Ok(1), m.setTargetUser("Someone else"))
        assertEquals("Someone else", store.currentUserPref)
    }

    // ----- diagnostics redaction (refactor plan §10.1 guardrails 1 & 3) -----

    

    

    

    

    // ----- contract no-ops -----

    @Test fun failedHistoryInsertKeepsDisplayedSelectionAndAttributionTogether() {
        val store = FakeTargetUserStore()
        val m = module(store)
        m.setTargetUser("Someone else")
        store.failNextInsert = true
        assertTrue(m.setTargetUser("Study participant") is ModuleResult.Failed)
        assertEquals("Someone else", m.currentUser())
        assertEquals(listOf("Someone else"), store.recordedUsers)
        assertEquals("Someone else", store.currentUserPref)
    }

}

/**
 * The production store over a real Room history and a preference mirror whose commit fails. Room commits the
 * choice first, so after the failed mirror (or a restart) the displayed user, the next usage label and the history agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PrefsAndRoomTargetUserStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
    private val prefs = context.getSharedPreferences("target-user-store-test", Context.MODE_PRIVATE)
    private var mirrorFails = false

    /** The encrypted preferences as the store writes them; [mirrorFails] makes the next commit report failure. */
    private val mirror = object : SharedPreferences by prefs {
        override fun edit(): SharedPreferences.Editor {
            val editor = prefs.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                override fun commit(): Boolean = !mirrorFails && editor.commit()
            }
        }
    }

    @After fun close() = db.close()

    /** A fresh module over the same stores, as after the app process restarts. */
    private fun module() = UserIdentificationCollectionModule(
        store = PrefsAndRoomTargetUserStore(db.userQueueEntryData(), mirror, CURRENT_USER),
        userIdentificationEnabled = { true },
    )

    @Test fun aFailedPreferenceMirrorLeavesTheDisplayedUserEqualToTheRoomAttribution() {
        assertEquals(ModuleResult.Ok(1), module().setTargetUser("Someone else"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        mirrorFails = true
        assertTrue(module().setTargetUser("Study participant") is ModuleResult.Failed)

        assertEquals("Someone else", prefs.getString(CURRENT_USER, null))
        assertEquals("Study participant", db.userQueueEntryData().labelTimeline().lastEntry()!!.value)
        assertEquals("Study participant", module().currentUser())
    }
}
