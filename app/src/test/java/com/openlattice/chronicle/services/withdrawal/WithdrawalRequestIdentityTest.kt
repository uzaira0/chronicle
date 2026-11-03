package com.openlattice.chronicle.services.withdrawal

import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.directboot.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Header
import java.util.UUID

class WithdrawalRequestIdentityTest {

    @Test
    fun `begin commits one canonical request id that survives retry and process recreation`() {
        val prefs = InMemorySharedPreferences()
        val firstStore = WithdrawalStateStore(prefs)

        val requestId = firstStore.beginWithdrawal()

        assertEquals(WithdrawalState.PENDING, firstStore.state())
        assertEquals(UUID.fromString(requestId).toString(), requestId)
        assertEquals(requestId.lowercase(), requestId)
        assertEquals(
            requestId,
            WithdrawalStateStore(prefs).withdrawalRequestIdForRetry(),
        )
        assertEquals(requestId, WithdrawalStateStore(prefs).beginWithdrawal())
    }

    @Test
    fun `needs support and recovery reset retain the request id until reenrollment commits`() {
        val prefs = InMemorySharedPreferences()
        val store = WithdrawalStateStore(prefs)
        val requestId = store.beginWithdrawal()

        store.setState(WithdrawalState.NEEDS_SUPPORT)
        assertEquals(requestId, WithdrawalStateStore(prefs).withdrawalRequestIdForRetry())

        store.resetForReenrollment()
        assertEquals(requestId, store.persistedWithdrawalRequestId())

        store.completeReenrollment(UUID.randomUUID(), "new-participant")
        assertNull(store.persistedWithdrawalRequestId())
    }

    @Test
    fun `confirmed completion clears the old id and a later withdrawal uses a new one`() {
        val prefs = InMemorySharedPreferences()
        val store = WithdrawalStateStore(prefs)
        val first = store.beginWithdrawal()

        store.setState(WithdrawalState.COMPLETE)
        assertNull(store.persistedWithdrawalRequestId())

        store.resetForReenrollment()
        val second = store.beginWithdrawal()
        assertNotEquals(first, second)
    }

    /**
     * A state name this build cannot read (written by a newer build before a downgrade, or damaged) must keep
     * collection stopped and send the participant to the study team, never read as "no withdrawal".
     */
    @Test
    fun `an unreadable stored withdrawal state reads as needs support`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("participant_withdrawal_state", "ARCHIVED").commit()
        val store = WithdrawalStateStore(prefs)

        assertEquals(WithdrawalState.NEEDS_SUPPORT, store.state())
        assertEquals(WithdrawalState.NEEDS_SUPPORT, store.stateOrThrow())
        assertTrue(store.state().requiresResolution)
    }

    /**
     * A pending withdrawal persisted by a client that predated request ids (or holding a non-canonical id) gets one
     * canonical id, committed before any request, and every later retry and process restart sends that same id.
     */
    @Test
    fun `a legacy pending withdrawal without a canonical request id gets one durable id`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("participant_withdrawal_state", WithdrawalState.PENDING.name).commit()

        val generated = WithdrawalStateStore(prefs).withdrawalRequestIdForRetry()
        assertEquals(UUID.fromString(generated).toString(), generated)
        assertEquals(generated, WithdrawalStateStore(prefs).withdrawalRequestIdForRetry())

        prefs.edit().putString("participant_withdrawal_request_id", generated.uppercase()).commit()
        val replaced = WithdrawalStateStore(prefs).withdrawalRequestIdForRetry()
        assertEquals(UUID.fromString(replaced).toString(), replaced)
        assertEquals(replaced, WithdrawalStateStore(prefs).persistedWithdrawalRequestId())
    }

    @Test
    fun `withdrawal endpoint requires the durable request id header`() {
        val method = ChronicleStudyApi::class.java.methods.single {
            it.name == "withdrawCurrentEnrollment"
        }
        val headerNames = method.parameterAnnotations.map { annotations ->
            annotations.filterIsInstance<Header>().single().value
        }

        assertEquals(
            listOf(
                "X-Chronicle-Random-Device-Id",
                "X-Api-Key",
                "X-Chronicle-Withdrawal-Request-Id",
            ),
            headerNames,
        )
        assertTrue(method.parameterTypes.all { it == String::class.java })
    }
}
