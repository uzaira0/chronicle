package com.openlattice.chronicle.collection.identification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.IsolatedChronicleTestDb
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UserQueueEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for [UserIdentificationCollectionModule] against the real
 * SQLCipher-backed [ChronicleDb] `userQueue` table (refactor plan §10.1 step 16 —
 * "instrumented DB test for user queue").
 *
 * This exercises the module → [PrefsAndRoomTargetUserStore] → real-encrypted-Room
 * `userQueue` path that the JVM fakes cannot: it proves a target-user selection is
 * durably persisted as a `UserQueueEntry`, that the nearest-lower-timestamp lookup over
 * the real table behaves identically to the in-memory fake, and that a disabled module
 * writes nothing to the real table.
 *
 * The `current_user` pref write is verified through a private in-test [TargetUserStore]
 * that wraps the real Room DAO but keeps the pref in memory — the production
 * EncryptedSharedPreferences file is app-wide state and is intentionally not mutated by
 * this test. The dedicated JVM [UserIdentificationCollectionModuleTest] proves the
 * pref-write branch.
 *
 * Requires a connected device or emulator. When none is available the run is recorded as
 * a BLOCKER; the JVM `UserIdentificationCollectionModuleTest` plus the existing
 * `CollectionSinkInstrumentedTest` provide the strongest local proof of the boundary.
 */
@RunWith(AndroidJUnit4::class)
class UserIdentificationModuleInstrumentedTest {

    private lateinit var db: ChronicleDb
    private lateinit var isolatedDb: IsolatedChronicleTestDb

    @Before
    fun setUp() {
        isolatedDb = IsolatedChronicleTestDb.create("user_identification")
        db = isolatedDb.db
    }

    @After
    fun tearDown() {
        isolatedDb.close()
    }

    /**
     * [TargetUserStore] over the real `userQueue` Room DAO with an in-memory `current_user`
     * pref slot — keeps the assertions on the real encrypted DB without mutating the
     * app-wide EncryptedSharedPreferences file.
     */
    private inner class RoomBackedTargetUserStore : TargetUserStore {
        var pref: String? = null
        override fun insertUserQueueEntry(entry: UserQueueEntry) {
            db.userQueueEntryData().insertEntries(listOf(entry))
        }
        override fun writeCurrentUserPref(user: String) { pref = user }
        override fun readCurrentUserPref(unassignedDefault: String): String = pref ?: unassignedDefault
        override fun userQueueDepth(): Int = db.userQueueEntryData().getUserTimestamps().size
        override fun userTimestamps() =
            db.userQueueEntryData().getUserTimestamps()
                .associateTo(java.util.TreeMap<Long, String>()) { it.writeTimestamp to it.user }
    }

    private fun module(store: TargetUserStore, enabled: Boolean) =
        UserIdentificationCollectionModule(
            store = store,
            userIdentificationEnabled = { enabled },
            unassignedUserLabel = "Not set",
            log = NoOpCollectionLog,
        )

    @Test
    fun setTargetUserPersistsAUserQueueEntryThroughSqlCipherUserQueue() {
        val store = RoomBackedTargetUserStore()
        val m = module(store, enabled = true)

        val result = m.setTargetUser("Target child")

        assertEquals(ModuleResult.Ok(1), result)
        val rows = db.userQueueEntryData().getUserTimestamps()
        assertEquals(1, rows.size)
        assertEquals("Target child", rows.single().user)
        assertEquals("Target child", store.pref)
    }

    @Test
    fun repeatedSelectionsAppendRowsAndTheTimestampLookupResolvesTheLatest() {
        val store = RoomBackedTargetUserStore()
        val m = module(store, enabled = true)

        m.setTargetUser("Target child")
        Thread.sleep(2) // ensure a strictly greater writeTimestamp for the second row
        m.setTargetUser("Other")

        assertEquals(2, db.userQueueEntryData().getUserTimestamps().size)
        val users = m.loadUserTimestampMap()
        // The lookup intentionally uses lowerEntry, so prove the event is strictly after the
        // persisted millisecond instead of relying on the test thread crossing a clock tick.
        assertEquals("Other", m.resolveUserForEvent(users.lastKey() + 1L, users))
    }

    @Test
    fun disabledModuleWritesNothingToTheRealUserQueue() {
        val store = RoomBackedTargetUserStore()
        val m = module(store, enabled = false)

        val result = m.setTargetUser("Target child")

        assertTrue("expected Skipped, got $result", result is ModuleResult.Skipped)
        assertEquals(0, db.userQueueEntryData().getUserTimestamps().size)
    }

    @Test
    fun notSetSelectionResolvesToEmptyUserOverTheRealTable() {
        val store = RoomBackedTargetUserStore()
        val m = module(store, enabled = true)

        m.setTargetUser("Not set")

        val users = m.loadUserTimestampMap()
        assertEquals(1, users.size)
        // The "Not set" label maps to the empty user, never the literal label.
        assertEquals("", m.resolveUserForEvent(System.currentTimeMillis(), users))
    }

    @Test
    fun diagnosticsOverTheRealTableCarryNoRawParticipantLabel() {
        val store = RoomBackedTargetUserStore()
        val m = module(store, enabled = true)
        m.setTargetUser("Target child")

        val d = m.diagnostics()
        val rendered = d.lastResult.orEmpty() + d.lastError.orEmpty() +
            d.redactedParticipantRef.orEmpty() + d.notTracked.joinToString()
        assertFalse(rendered.contains("Target child"))
        assertEquals(1, d.queueDepth)
    }
}
