package com.openlattice.chronicle.services.withdrawal

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.ui.awaitDashboardUi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WithdrawalStateObservationTest {
    @Test fun callbacksFromEncryptedPreferenceWorkersAreDeliveredOnlyWhileStarted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("withdrawal-observation", Context.MODE_PRIVATE)
        var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        val observedPrefs = object : SharedPreferences by prefs {
            override fun registerOnSharedPreferenceChangeListener(callback: SharedPreferences.OnSharedPreferenceChangeListener) {
                listener = callback
            }
            override fun unregisterOnSharedPreferenceChangeListener(callback: SharedPreferences.OnSharedPreferenceChangeListener) {
                assertEquals(listener, callback)
                listener = null
            }
        }
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        val store = WithdrawalStateStore(observedPrefs)
        val received = mutableListOf<WithdrawalState>()
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        store.states.observe(owner) { received.add(it) }
        assertEquals(listOf(WithdrawalState.NONE), received)
        onPersistenceWorker {
            store.setState(WithdrawalState.PENDING)
            // EncryptedSharedPreferences invokes its listeners on the committing thread.
            requireNotNull(listener).onSharedPreferenceChanged(observedPrefs, null)
        }
        awaitDashboardUi { received.last() == WithdrawalState.PENDING }
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        assertEquals(null, listener)
        onPersistenceWorker { WithdrawalStateStore(prefs).setState(WithdrawalState.COMPLETE) }
        assertEquals(listOf(WithdrawalState.NONE, WithdrawalState.PENDING), received)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(listOf(WithdrawalState.NONE, WithdrawalState.PENDING, WithdrawalState.COMPLETE), received)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        assertEquals(null, listener)
    }
}
