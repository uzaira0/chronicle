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
                "X-Chronicle-Device-Id",
                "X-Api-Key",
                "X-Chronicle-Withdrawal-Request-Id",
            ),
            headerNames,
        )
        assertTrue(method.parameterTypes.all { it == String::class.java })
    }
}
