package com.openlattice.chronicle.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.LocalStoreRecoveryActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StorageFailureBoundaryTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun close() = onPersistenceWorker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ShadowToast.reset()
    }

    @Test fun programmingErrorKeepsTheParticipantOnTheCurrentScreen() {
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { host ->
            val fragment = Fragment()
            host.get().supportFragmentManager.beginTransaction().add(fragment, "screen").commitNow()
            fragment.storageFailureHandler().handleException(EmptyCoroutineContext, IllegalArgumentException("Invalid action"))
            assertFalse(host.get().isFinishing)
            assertNull(shadowOf(host.get()).nextStartedActivity)
        }
    }

    /** A full disk is not a damaged store; the participant stays put and is asked to free space. */
    @Test fun diskFullKeepsTheParticipantOnTheScreenAndAsksToFreeSpace() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        val module = CollectionModuleId.BATTERY_TELEMETRY
        assertTrue(ResearchPersistenceGate.collectsNow(context, module))
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { host ->
            val fragment = Fragment()
            host.get().supportFragmentManager.beginTransaction().add(fragment, "screen").commitNow()
            for (error in listOf(SQLiteFullException("database or disk is full"),
                IllegalStateException("consent write failed", RuntimeException(SQLiteFullException("disk is full"))))) {
                ShadowToast.reset()
                assertTrue(ResearchPersistenceGate.isStorageFailure(error))
                fragment.storageFailureHandler().handleException(EmptyCoroutineContext, error)
                assertFalse(host.get().isFinishing)
                assertNull(shadowOf(host.get()).nextStartedActivity)
                assertEquals(host.get().getString(R.string.settings_control_storage_paused), ShadowToast.getTextOfLatestToast())
                assertFalse("a full disk closes admission", ResearchPersistenceGate.collectsNow(context, module))
                assertTrue(onPersistenceWorker { CollectionLoopStore.of(context).loadAll().isNotEmpty() })
                assertFalse("a successful read cannot reopen admission", ResearchPersistenceGate.collectsNow(context, module))
                assertThrows(SQLiteFullException::class.java) {
                    onPersistenceWorker { ResearchPersistenceGate.stop { throw SQLiteFullException("still full") } }
                }
                assertFalse("a failed retry keeps admission closed", ResearchPersistenceGate.collectsNow(context, module))
                onPersistenceWorker {
                    val server = EnrollmentTestStores.db!!.uploadServerDao().getConfiguredServer()!!
                    assertEquals(1, ResearchPersistenceGate.setServerEnabled(context, server.id, true))
                }
                assertTrue("a successful write reopens admission", ResearchPersistenceGate.collectsNow(context, module))
            }
        }
    }

    /** Every route (startup, enrollment, settings, server edit): a full disk wrapped for recovery never opens the reset. */
    @Test fun storeRecoveryRouteNeverOffersTheResetForAFullDisk() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.BATTERY_TELEMETRY))
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { host ->
            val full = LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED,
                IllegalStateException("reconcile", SQLiteFullException("database or disk is full")))
            assertFalse(host.get().openStoreRecovery(full))
            assertFalse("every recovery route closes admission", ResearchPersistenceGate.collectsNow(context, CollectionModuleId.BATTERY_TELEMETRY))
            assertNull(shadowOf(host.get()).nextStartedActivity)
            assertEquals(host.get().getString(R.string.settings_control_storage_paused), ShadowToast.getTextOfLatestToast())
            val damaged = LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED,
                SQLiteException("key write refused"))
            assertTrue(host.get().openStoreRecovery(damaged))
            assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(host.get()).nextStartedActivity.component?.className)
        }
    }

    /**
     * A failed preference commit leaves the new consent fence in memory. A readable Room store does not prove that
     * retrying against that fence is safe: after restart the old consent may return. Keep this path in recovery.
     */
    @Test fun refusedPreferenceCommitDuringAnOptionalDeclineRequiresRecovery() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        val module = CollectionModuleId.BATTERY_TELEMETRY
        assertTrue(ResearchPersistenceGate.collectsNow(context, module))
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { host ->
            val fragment = Fragment()
            host.get().supportFragmentManager.beginTransaction().add(fragment, "screen").commitNow()
            val failure = onPersistenceWorker {
                runBlocking {
                    participantStorageWrite {
                        CollectionLoopCoordinator(FullDisk(context)).applyDecisions(
                            accepted = emptySet(), declined = setOf(module), trigger = ConsentTrigger.PARTICIPANT_TOGGLE)
                    }
                }
            }.exceptionOrNull()
            requireNotNull(failure) { "the refused commit must fail the decline" }
            fragment.storageFailureHandler().handleException(EmptyCoroutineContext, failure)
            assertEquals(LocalStoreRecoveryActivity::class.java.name,
                shadowOf(host.get()).nextStartedActivity?.component?.className)
            assertTrue(host.get().isFinishing)
            assertFalse("a refused write closes admission", ResearchPersistenceGate.collectsNow(context, module))
            assertTrue("the store still reads", onPersistenceWorker { CollectionLoopStore.of(context).loadAll().isNotEmpty() })
        }
    }

    /** Every preference commit is refused, as SharedPreferencesImpl does when the disk is full. */
    private class FullDisk(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val prefs = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor = RefusingEditor(prefs.edit())
            }
        }
    }

    private class RefusingEditor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor by editor {
        override fun putString(key: String?, value: String?) = also { editor.putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = also { editor.putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = also { editor.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = also { editor.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = also { editor.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = also { editor.putBoolean(key, value) }
        override fun remove(key: String?) = also { editor.remove(key) }
        override fun clear() = also { editor.clear() }
        override fun commit(): Boolean { editor.apply(); return false }
        override fun apply() = Unit
    }

    @Test fun storageFailureStillOpensExplicitRecovery() {
        for (error in listOf(SQLiteException("Store unavailable"), SQLiteDatabaseCorruptException("Store corrupt"))) {
            Robolectric.buildActivity(FragmentActivity::class.java).setup().use { host ->
                val fragment = Fragment()
                host.get().supportFragmentManager.beginTransaction().add(fragment, "screen").commitNow()
                fragment.storageFailureHandler().handleException(EmptyCoroutineContext, error)
                assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(host.get()).nextStartedActivity.component?.className)
                assertTrue(host.get().isFinishing)
            }
        }
    }
}
