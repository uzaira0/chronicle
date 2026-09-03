package com.openlattice.chronicle.collection.identification

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.FixedCollectionClock
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.core.RecordingCollectionLog
import com.openlattice.chronicle.collection.core.TestContexts
import com.openlattice.chronicle.storage.UserQueueEntry
import java.util.TreeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit coverage for [UserIdentificationCollectionModule] — the Phase 7 user
 * identification module (refactor plan §10.1).
 *
 * Drives the module over the [FakeTargetUserStore] / [FixedCollectionClock] seams (no
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
        clock: FixedCollectionClock = FixedCollectionClock(1_700_000_000_000L),
        log: com.openlattice.chronicle.collection.core.CollectionLog = NoOpCollectionLog,
    ) = UserIdentificationCollectionModule(
        store = store,
        userIdentificationEnabled = enabled,
        unassignedUserLabel = unassigned,
        clock = clock,
        log = log,
    )

    @Test
    fun moduleDeclaresUserIdentificationIdentityAndPrivacyClass() {
        val m = module()
        assertEquals(CollectionModuleId.USER_IDENTIFICATION, m.id)
        assertEquals(CollectionPrivacyClass.LOCAL_PARTICIPANT_LABEL, m.privacyClass)
        assertEquals(m.id.privacyClass, m.privacyClass)
    }

    // ----- target-user write -----

    @Test
    fun setTargetUserWritesBothTheQueueRowAndTheCurrentUserPref() {
        val store = FakeTargetUserStore()
        val m = module(store)

        val result = m.setTargetUser("Target child")

        assertEquals(ModuleResult.Ok(1), result)
        // userQueue row written.
        assertEquals(1, store.queueRows.size)
        assertEquals("Target child", store.queueRows.single().user)
        // current_user pref written to the same label.
        assertEquals("Target child", store.currentUserPref)
    }

    @Test
    fun setTargetUserPersistsTheOtherLabelToo() {
        val store = FakeTargetUserStore()
        module(store).setTargetUser("Other")
        assertEquals("Other", store.recordedUsers.single())
        assertEquals("Other", store.currentUserPref)
    }

    @Test
    fun setTargetUserFailureSurfacesAsFailedAndIsLoggedNotSwallowed() {
        val store = FakeTargetUserStore().apply { failNextInsert = true }
        val log = RecordingCollectionLog()
        val m = module(store, log = log)

        val result = m.setTargetUser("Target child")

        assertTrue("expected Failed, got $result", result is ModuleResult.Failed)
        assertEquals(CollectionModuleStatus.FAILED, m.status())
        assertTrue("failure must be logged, not swallowed", log.problems.isNotEmpty())
        // The redacted message names the failing store, never the participant label.
        val msg = (result as ModuleResult.Failed).redactedMessage
        assertFalse("redacted message must not leak the label", msg.contains("Target child"))
    }

    // ----- disabled user identification -----

    @Test
    fun disabledUserIdentificationSetTargetUserIsANoOpSkipAndWritesNothing() {
        val store = FakeTargetUserStore()
        val m = module(store, enabled = { false })

        val result = m.setTargetUser("Target child")

        assertTrue("expected Skipped, got $result", result is ModuleResult.Skipped)
        assertEquals("disabled module must write nothing", 0, store.queueRows.size)
        assertNull("disabled module must not touch the current_user pref", store.currentUserPref)
    }

    @Test
    fun disabledModuleReportsDisabledStatus() {
        assertEquals(CollectionModuleStatus.DISABLED, module(enabled = { false }).status())
        assertFalse(module(enabled = { false }).isEnabled())
    }

    @Test
    fun enabledModuleReportsIdleStatusBeforeAnyWrite() {
        assertEquals(CollectionModuleStatus.IDLE, module(enabled = { true }).status())
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
        m.setTargetUser("Other")
        assertEquals("Other", m.currentUser())
    }

    // ----- timestamp lookup + "Not set" → empty user -----

    @Test
    fun resolveUserForEventReturnsNearestLowerTimestampLabel() {
        val users = TreeMap<Long, String>().apply {
            put(100L, "Target child")
            put(300L, "Other")
        }
        val m = module()
        // Event at 250 → nearest strictly-lower entry is 100 → "Target child".
        assertEquals("Target child", m.resolveUserForEvent(250L, users))
        // Event at 350 → nearest strictly-lower entry is 300 → "Other".
        assertEquals("Other", m.resolveUserForEvent(350L, users))
    }

    @Test
    fun resolveUserForEventBeforeAnySelectionIsEmptyUser() {
        val users = TreeMap<Long, String>().apply { put(500L, "Target child") }
        // Event at 100 has no lower entry → empty string (legacy getTargetUser behaviour).
        assertEquals("", module().resolveUserForEvent(100L, users))
    }

    @Test
    fun resolveUserForEventEmptyMapIsEmptyUser() {
        assertEquals("", module().resolveUserForEvent(123L, TreeMap()))
    }

    @Test
    fun resolveUserForEventMapsNotSetLabelToEmptyUser() {
        val users = TreeMap<Long, String>().apply {
            put(100L, "Target child")
            put(300L, unassigned) // "Not set" — user identification was turned off here
        }
        val m = module()
        // Event after the "Not set" selection → empty user, NOT the literal "Not set".
        assertEquals("", m.resolveUserForEvent(350L, users))
        // Event before it still resolves to the real label.
        assertEquals("Target child", m.resolveUserForEvent(250L, users))
    }

    @Test
    fun loadUserTimestampMapReflectsTheQueueRows() {
        val store = FakeTargetUserStore()
        store.insertUserQueueEntry(UserQueueEntry(writeTimestamp = 10L, user = "Target child"))
        store.insertUserQueueEntry(UserQueueEntry(writeTimestamp = 20L, user = "Other"))
        val m = module(store)

        val map = m.loadUserTimestampMap()

        assertEquals(2, map.size)
        assertEquals("Target child", map[10L])
        assertEquals("Other", map[20L])
    }

    // ----- repeated selection / idempotency -----

    @Test
    fun repeatedTargetUserSelectionAppendsOneQueueRowPerCallAndConvergesPref() {
        val store = FakeTargetUserStore()
        val m = module(store)

        m.setTargetUser("Target child")
        m.setTargetUser("Other")
        m.setTargetUser("Target child")

        // The userQueue is append-only: three selections → three rows (legacy parity —
        // EnrollmentSettings.setTargetUser inserts a row every call).
        assertEquals(3, store.queueRows.size)
        // The current_user pref always converges to the most recent selection.
        assertEquals("Target child", store.currentUserPref)
        assertEquals("Target child", m.currentUser())
    }

    @Test
    fun selectingTheSameUserTwiceIsConsistentAndStillSucceeds() {
        val store = FakeTargetUserStore()
        val m = module(store)

        assertEquals(ModuleResult.Ok(1), m.setTargetUser("Other"))
        assertEquals(ModuleResult.Ok(1), m.setTargetUser("Other"))
        assertEquals("Other", store.currentUserPref)
    }

    // ----- diagnostics redaction (refactor plan §10.1 guardrails 1 & 3) -----

    @Test
    fun diagnosticsCarryNoRawParticipantLabelInAnyForm() {
        val store = FakeTargetUserStore()
        val secretLabel = "Target child"
        val m = module(store)
        m.setTargetUser(secretLabel)

        val d = m.diagnostics()
        val rendered = buildString {
            append(d.lastResult)
            append(d.lastError)
            append(d.redactedParticipantRef)
            append(d.notTracked.joinToString())
        }
        assertFalse("diagnostics must not contain the raw label", rendered.contains(secretLabel))
        // Not even a hash or a length: redactedParticipantRef is deliberately null.
        assertNull(d.redactedParticipantRef)
        assertFalse(rendered.contains(secretLabel.length.toString() + " chars"))
    }

    @Test
    fun diagnosticsExposeEnabledStateAndLastUpdateTimestampOnly() {
        val store = FakeTargetUserStore()
        val clock = FixedCollectionClock(1_700_000_123_456L)
        val m = module(store, enabled = { true }, clock = clock)

        m.setTargetUser("Other")

        val d = m.diagnostics()
        assertEquals(CollectionModuleId.USER_IDENTIFICATION, d.moduleId)
        assertEquals(CollectionPrivacyClass.LOCAL_PARTICIPANT_LABEL, d.privacyClass)
        assertEquals("OK", d.lastResult)
        assertEquals(1, d.itemsCollected)
        assertEquals(1, d.queueDepth)
        assertEquals(1_700_000_123_456L, d.lastRunEpochMs)
        assertTrue(d.notTracked.contains("userIdentificationEnabled=true"))
        assertTrue(d.notTracked.any { it == "lastTargetUserUpdate=1700000123456" })
    }

    @Test
    fun diagnosticsBeforeAnyUpdateMarkLastUpdateAsNotTracked() {
        val d = module(enabled = { false }).diagnostics()
        assertNull(d.lastRunEpochMs)
        assertTrue(d.notTracked.contains("lastTargetUserUpdate"))
        assertTrue(d.notTracked.contains("userIdentificationEnabled=false"))
    }

    @Test
    fun disabledNoOpWriteDoesNotRecordAnUpdateTimestamp() {
        val m = module(enabled = { false })
        m.setTargetUser("Target child")
        assertNull("a skipped write must not record a last-update timestamp", m.diagnostics().lastRunEpochMs)
    }

    // ----- contract no-ops -----

    @Test
    fun pushAndPollContractMethodsAreNoOpSkips() {
        val m = module()
        val ctx = TestContexts.stub()
        val window = CollectionWindow(startEpochMs = 0L, endEpochMs = 1_000L)
        assertTrue(m.start(ctx) is ModuleResult.Skipped)
        assertTrue(m.stop(ctx) is ModuleResult.Skipped)
        assertTrue(m.poll(ctx, window) is ModuleResult.Skipped)
        assertTrue(m.flush(ctx) is ModuleResult.Skipped)
    }
}
