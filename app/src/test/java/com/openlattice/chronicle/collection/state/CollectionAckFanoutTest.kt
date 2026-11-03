package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.BuildConfig
import java.io.IOException

import com.openlattice.chronicle.TEST_SERVER_HOST
import com.openlattice.chronicle.assumeTestServerHostTrusted
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.serialization.JsonSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CollectionAckFanoutTest {

    private fun server(
        id: Long,
        studyId: String = "00000000-0000-0000-0000-00000000000$id",
        enabled: Boolean = true,
    ) = UploadServerEntity(
        id = id,
        name = "server-$id",
        url = "https://$TEST_SERVER_HOST",
        studyId = studyId,
        participantId = "participant-$id",
        sourceRandomDeviceId = "device-$id",
        apiKey = "api-key-$id",
        disclosureVersion = "disclosure-$id",
        manifestDigest = "a".repeat(64),
        enabled = enabled,
    )

    @Test
    fun retriedAckReplaysTheAppVersionItWasRecordedWith() {
        val record = PendingCollectionAckRecord.from(
            server = server(1),
            accepted = setOf(CollectionModuleId.USAGE_EVENTS),
            declined = emptySet(),
            trigger = ConsentTrigger.ENROLLMENT,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
            settingsVersion = 3,
            appVersion = "2026.01.01-old",
        )
        val roundTripped = JsonSerializer.fromJson<List<PendingCollectionAckRecord>>(
            JsonSerializer.toJson(listOf(record)),
        )!!.single()
        assertEquals("2026.01.01-old", roundTripped.toAcknowledgmentOrNull()!!.appVersion)
        // Records written before appVersion existed replay null, matching the server's stored receipt.
        assertEquals(null, roundTripped.copy(appVersion = null).toAcknowledgmentOrNull()!!.appVersion)
        assertEquals(
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            PendingCollectionAckRecord.from(
                server = server(1),
                accepted = setOf(CollectionModuleId.USAGE_EVENTS),
                declined = emptySet(),
                trigger = ConsentTrigger.ENROLLMENT,
                acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
            ).appVersion,
        )
    }

    @Test
    fun retryQueueDeduplicatesPendingCollectionAcks() {
        val persistence = FakeAckPersistence()
        val queue = CollectionAckRetryQueue(persistence)
        val record = PendingCollectionAckRecord.from(
            server = server(1),
            accepted = setOf(CollectionModuleId.USAGE_EVENTS),
            declined = emptySet(),
            trigger = ConsentTrigger.PARTICIPANT_TOGGLE,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
        )

        queue.enqueue(listOf(record, record))
        queue.enqueue(listOf(record))

        assertEquals(listOf(record), persistence.records)
    }

    @Test
    fun pendingAckRecordRoundTripsThroughAppJsonMapper() {
        val expected = listOf(
            PendingCollectionAckRecord.from(
                server = server(1),
                accepted = setOf(CollectionModuleId.USAGE_EVENTS),
                declined = setOf(CollectionModuleId.BATTERY_TELEMETRY),
                unavailable = setOf(CollectionModuleId.SENSOR_GYROSCOPE),
                trigger = ConsentTrigger.ENROLLMENT,
                acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
                settingsVersion = 7,
            ).copy(decisionSeq = 5),
        )

        val json = JsonSerializer.toJson(expected)
        val actual = JsonSerializer.fromJson<List<PendingCollectionAckRecord>>(json)

        assertEquals(expected, actual)
        assertEquals(server(1).studyId, actual?.single()?.studyId)
        assertEquals(server(1).participantId, actual?.single()?.participantId)
        assertEquals(server(1).sourceRandomDeviceId, actual?.single()?.sourceRandomDeviceId)
        assertEquals(7, actual?.single()?.toAcknowledgmentOrNull()?.settingsVersion)
        assertEquals(5L, actual?.single()?.toAcknowledgmentOrNull()?.decisionSeq)
        assertEquals(
            setOf(CollectionModuleId.SENSOR_GYROSCOPE),
            actual?.single()?.toAcknowledgmentOrNull()?.unavailableModules,
        )
    }

    @Test
    fun corruptUnavailableRetryEvidenceFailsClosedInsteadOfChangingTheSet() {
        val corrupt = PendingCollectionAckRecord(
            serverId = 1,
            acceptedModuleIds = listOf(CollectionModuleId.USAGE_EVENTS.id),
            declinedModuleIds = emptyList(),
            unavailableModuleIds = listOf("unknown_sensor"),
            trigger = ConsentTrigger.ENROLLMENT.name,
            acknowledgedAt = "2026-07-02T12:34:56Z",
        )

        assertEquals(null, corrupt.toAcknowledgmentOrNull())
    }

    @Test
    fun retryPendingAckRemovesSuccessesAndKeepsFailures() {
        assumeTestServerHostTrusted()
        val pending = listOf(
            PendingCollectionAckRecord.from(
                server = server(1),
                accepted = setOf(CollectionModuleId.USAGE_EVENTS),
                declined = emptySet(),
                trigger = ConsentTrigger.ENROLLMENT,
                acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
            ),
            PendingCollectionAckRecord.from(
                server = server(2),
                accepted = setOf(CollectionModuleId.BATTERY_TELEMETRY),
                declined = emptySet(),
                trigger = ConsentTrigger.ENROLLMENT,
                acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:35:56Z"),
            ),
        )
        val attempted = mutableListOf<Long>()
        val failures = mutableListOf<Long>()

        val result = CollectionLoopCoordinator.retryPendingCollectionAcks(
            pending = pending,
            servers = listOf(server(1), server(2)),
            report = { server, _, acknowledgment ->
                attempted += server.id
                assertTrue(acknowledgment.acknowledgedModules.isNotEmpty())
                if (server.id == 2L) error("server failed")
            },
            onFailure = { server, _ -> failures += server.id },
        )

        assertFalse(result.allAttemptedSucceeded)
        assertEquals(listOf(1L, 2L), attempted)
        assertEquals(listOf(2L), failures)
        assertEquals(setOf(pending[0].stableKey()), result.removedStableKeys)
    }

    @Test fun failedOlderDecisionDefersNewerDecisionForTheSameEnrollment() {
        assumeTestServerHostTrusted()
        val destination = server(1)
        val accepted = PendingCollectionAckRecord.from(destination,
            setOf(CollectionModuleId.BATTERY_TELEMETRY), emptySet(), trigger = ConsentTrigger.PARTICIPANT_TOGGLE,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"))
        val declined = accepted.copy(acceptedModuleIds = emptyList(),
            declinedModuleIds = accepted.acceptedModuleIds, acknowledgedAt = "2026-07-02T12:35:56Z")
        val pending = listOf(accepted, declined)
        val delivered = mutableListOf<CollectionAcknowledgment>()

        val failed = CollectionLoopCoordinator.retryPendingCollectionAcks(pending, listOf(destination), report = { _, _, ack ->
            if (ack.acknowledgedModules.isNotEmpty()) throw IOException("offline")
            delivered += ack
        })
        assertFalse(failed.allAttemptedSucceeded)
        assertTrue(failed.removedStableKeys.isEmpty())
        assertTrue(delivered.isEmpty())

        val retry = CollectionLoopCoordinator.retryPendingCollectionAcks(pending, listOf(destination), report = { _, _, ack -> delivered += ack })
        assertTrue(retry.allAttemptedSucceeded)
        assertEquals(pending.map { it.toAcknowledgmentOrNull() }, delivered)
        assertEquals(pending.map { it.stableKey() }.toSet(), retry.removedStableKeys)
    }

    @Test
    fun retryPendingAckKeepsExactDisabledDestinationAndDropsDifferentEnrollment() {
        val disabledRecord = PendingCollectionAckRecord.from(
            server = server(1),
            accepted = setOf(CollectionModuleId.USAGE_EVENTS),
            declined = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
        )
        val deletedRecord = PendingCollectionAckRecord.from(
            server = server(2),
            accepted = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            declined = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
        )
        var reports = 0
        val discarded = mutableListOf<PendingCollectionAckDiscardReason>()

        val result = CollectionLoopCoordinator.retryPendingCollectionAcks(
            pending = listOf(disabledRecord, deletedRecord),
            servers = listOf(server(1, enabled = false)),
            report = { _, _, _ -> reports += 1 },
            onDiscard = { _, reason -> discarded += reason },
        )

        assertFalse(result.allAttemptedSucceeded)
        assertEquals(0, reports)
        assertEquals(setOf(deletedRecord.stableKey()), result.removedStableKeys)
        assertEquals(listOf(PendingCollectionAckDiscardReason.ENROLLMENT_IDENTITY_MISMATCH), discarded)
    }

    @Test
    fun retryPendingAckRetainsRecordWhenNoAuthoritativeDestinationCanBeRead() {
        val pending = PendingCollectionAckRecord.from(
            server = server(1),
            accepted = setOf(CollectionModuleId.USAGE_EVENTS),
            declined = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
        )

        val result = CollectionLoopCoordinator.retryPendingCollectionAcks(
            pending = listOf(pending),
            servers = emptyList(),
            report = { _, _, _ -> error("no destination should be contacted") },
        )

        assertFalse(result.allAttemptedSucceeded)
        assertTrue(result.removedStableKeys.isEmpty())
    }

    @Test
    fun retryDropsAcknowledgmentWhenServerIdWasReusedByAnotherEnrollment() {
        val oldServer = server(1)
        val pending = PendingCollectionAckRecord.from(
            server = oldServer,
            accepted = setOf(CollectionModuleId.USAGE_EVENTS),
            declined = emptySet(),
            trigger = ConsentTrigger.ENROLLMENT,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
        )
        val replacement = oldServer.copy(
            studyId = "22222222-2222-2222-2222-222222222222",
            participantId = "replacement-participant",
            sourceRandomDeviceId = "replacement-device",
        )
        var reports = 0
        val discarded = mutableListOf<PendingCollectionAckDiscardReason>()

        val result = CollectionLoopCoordinator.retryPendingCollectionAcks(
            pending = listOf(pending),
            servers = listOf(replacement),
            report = { _, _, _ -> reports += 1 },
            onDiscard = { _, reason -> discarded += reason },
        )

        assertTrue(result.allAttemptedSucceeded)
        assertEquals(0, reports)
        assertEquals(listOf(PendingCollectionAckDiscardReason.ENROLLMENT_IDENTITY_MISMATCH), discarded)
        assertEquals(setOf(pending.stableKey()), result.removedStableKeys)
    }

    @Test
    fun retryUsesExactIdentityWhenTheConfiguredRowWasRecreatedWithANewId() {
        assumeTestServerHostTrusted()
        val original = server(1)
        val pending = PendingCollectionAckRecord.from(
            server = original,
            accepted = setOf(CollectionModuleId.USAGE_EVENTS),
            declined = emptySet(),
            trigger = ConsentTrigger.ENROLLMENT,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
        )
        val recreated = original.copy(id = 99, name = "recreated")
        val attempted = mutableListOf<Long>()

        val result = CollectionLoopCoordinator.retryPendingCollectionAcks(
            pending = listOf(pending),
            servers = listOf(recreated),
            report = { server, _, _ -> attempted += server.id },
        )

        assertTrue(result.allAttemptedSucceeded)
        assertEquals(listOf(99L), attempted)
        assertEquals(setOf(pending.stableKey()), result.removedStableKeys)
    }

    @Test
    fun identityFreeRecordIsDiscardedEvenForTheMatchingEnrollment() {
        assumeTestServerHostTrusted()
        val current = server(1)
        val incompleteIdentity = PendingCollectionAckRecord(
            serverId = current.id,
            acceptedModuleIds = listOf(CollectionModuleId.USAGE_EVENTS.id),
            declinedModuleIds = emptyList(),
            trigger = ConsentTrigger.ENROLLMENT.name,
            acknowledgedAt = "2026-07-02T12:34:56Z",
            disclosureVersion = current.disclosureVersion,
            manifestDigest = current.manifestDigest,
        )
        var reports = 0
        val discarded = mutableListOf<PendingCollectionAckDiscardReason>()

        val result = CollectionLoopCoordinator.retryPendingCollectionAcks(
            pending = listOf(incompleteIdentity),
            servers = listOf(current),
            report = { _, _, _ -> reports++ },
            onDiscard = { _, reason -> discarded += reason },
        )

        assertTrue(result.allAttemptedSucceeded)
        assertEquals(0, reports)
        assertEquals(listOf(PendingCollectionAckDiscardReason.INCOMPLETE_IDENTITY), discarded)
        assertEquals(setOf(incompleteIdentity.stableKey()), result.removedStableKeys)
    }


    @Test
    fun partiallyPopulatedIdentityIsNeverNetworkReplayed() {
        val current = server(1)
        val partial = PendingCollectionAckRecord(
            serverId = current.id,
            studyId = current.studyId,
            acceptedModuleIds = listOf(CollectionModuleId.USAGE_EVENTS.id),
            declinedModuleIds = emptyList(),
            trigger = ConsentTrigger.ENROLLMENT.name,
            acknowledgedAt = "2026-07-02T12:34:56Z",
            disclosureVersion = current.disclosureVersion,
            manifestDigest = current.manifestDigest,
        )
        var reports = 0
        val discarded = mutableListOf<PendingCollectionAckDiscardReason>()

        val result = CollectionLoopCoordinator.retryPendingCollectionAcks(
            pending = listOf(partial),
            servers = listOf(current),
            report = { _, _, _ -> reports += 1 },
            onDiscard = { _, reason -> discarded += reason },
        )

        assertTrue(result.allAttemptedSucceeded)
        assertEquals(0, reports)
        assertEquals(listOf(PendingCollectionAckDiscardReason.INCOMPLETE_IDENTITY), discarded)
        assertEquals(setOf(partial.stableKey()), result.removedStableKeys)
    }

    @Test
    fun corruptIdentityOrAcknowledgmentIsDroppedEvenWithoutAReadableDestination() {
        val valid = PendingCollectionAckRecord.from(
            server = server(1),
            accepted = setOf(CollectionModuleId.USAGE_EVENTS),
            declined = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
        )
        val invalidIdentity = valid.copy(studyId = "not-a-uuid")
        val invalidAcknowledgment = valid.copy(acceptedModuleIds = listOf("unknown-module"))
        val discarded = mutableListOf<PendingCollectionAckDiscardReason>()

        val result = CollectionLoopCoordinator.retryPendingCollectionAcks(
            pending = listOf(invalidIdentity, invalidAcknowledgment),
            servers = emptyList(),
            report = { _, _, _ -> error("corrupt records must never reach the network") },
            onDiscard = { _, reason -> discarded += reason },
        )

        assertTrue(result.allAttemptedSucceeded)
        assertEquals(
            listOf(
                PendingCollectionAckDiscardReason.INVALID_ENROLLMENT_IDENTITY,
                PendingCollectionAckDiscardReason.INVALID_ACKNOWLEDGMENT,
            ),
            discarded,
        )
        assertEquals(
            setOf(invalidIdentity.stableKey(), invalidAcknowledgment.stableKey()),
            result.removedStableKeys,
        )
    }

    @Test
    fun keyedRemovalPreservesARecordEnqueuedAfterRetryLoadedItsSnapshot() {
        val persistence = FakeAckPersistence()
        val queue = CollectionAckRetryQueue(persistence)
        val first = PendingCollectionAckRecord.from(
            server = server(1),
            accepted = setOf(CollectionModuleId.USAGE_EVENTS),
            declined = emptySet(),
            trigger = ConsentTrigger.ENROLLMENT,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
        )
        val second = PendingCollectionAckRecord.from(
            server = server(2),
            accepted = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            declined = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:35:56Z"),
        )
        queue.enqueue(listOf(first))
        val loaded = CountDownLatch(1)
        val removeAllowed = CountDownLatch(1)

        val retry = thread {
            assertEquals(listOf(first), queue.load())
            loaded.countDown()
            assertTrue(removeAllowed.await(5, TimeUnit.SECONDS))
            queue.removeByStableKeys(setOf(first.stableKey()))
        }
        assertTrue(loaded.await(5, TimeUnit.SECONDS))
        queue.enqueue(listOf(second))
        removeAllowed.countDown()
        retry.join(5_000)

        assertEquals(listOf(second), queue.load())
    }

    @Test
    fun concurrentEnqueuesPreserveBothRecordsAndWithdrawalClearIsIdempotent() {
        val persistence = FakeAckPersistence()
        val firstQueue = CollectionAckRetryQueue(persistence)
        val secondQueue = CollectionAckRetryQueue(persistence)
        val first = PendingCollectionAckRecord.from(
            server = server(1),
            accepted = setOf(CollectionModuleId.USAGE_EVENTS),
            declined = emptySet(),
            trigger = ConsentTrigger.ENROLLMENT,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
        )
        val second = PendingCollectionAckRecord.from(
            server = server(2),
            accepted = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            declined = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:35:56Z"),
        )
        val start = CountDownLatch(1)
        val threads = listOf(
            thread { assertTrue(start.await(5, TimeUnit.SECONDS)); firstQueue.enqueue(listOf(first)) },
            thread { assertTrue(start.await(5, TimeUnit.SECONDS)); secondQueue.enqueue(listOf(second)) },
        )
        start.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals(setOf(first, second), firstQueue.load().toSet())
        firstQueue.clearForWithdrawal()
        secondQueue.clearForWithdrawal()
        assertTrue(firstQueue.load().isEmpty())
    }

    @Test
    fun withdrawalWaitsForAdmittedAckThenAtomicallyClearsItsFailedRetry() {
        val persistence = FakeAckPersistence()
        val queue = CollectionAckRetryQueue(persistence)
        val barrier = ResearchPersistenceBarrier()
        val active = AtomicBoolean(true)
        val requestEntered = CountDownLatch(1)
        val requestMayFail = CountDownLatch(1)
        val withdrawalReturned = CountDownLatch(1)
        val pending = PendingCollectionAckRecord.from(
            server = server(1),
            accepted = setOf(CollectionModuleId.USAGE_EVENTS),
            declined = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
        )

        val report = thread {
            barrier.persistIf(active::get) {
                requestEntered.countDown()
                assertTrue(requestMayFail.await(5, TimeUnit.SECONDS))
                queue.enqueue(listOf(pending))
            }
        }
        assertTrue(requestEntered.await(5, TimeUnit.SECONDS))
        val withdrawal = thread {
            barrier.stop {
                active.set(false)
                queue.clearForWithdrawal()
            }
            withdrawalReturned.countDown()
        }
        assertFalse(withdrawalReturned.await(100, TimeUnit.MILLISECONDS))
        requestMayFail.countDown()
        report.join(5_000)
        withdrawal.join(5_000)

        assertTrue(withdrawalReturned.await(1, TimeUnit.SECONDS))
        assertTrue(queue.load().isEmpty())
    }

    @Test
    fun ackWaitingBehindCompletedWithdrawalDoesNotSendOrEnqueue() {
        val persistence = FakeAckPersistence()
        val queue = CollectionAckRetryQueue(persistence)
        val barrier = ResearchPersistenceBarrier()
        val active = AtomicBoolean(true)
        val reports = AtomicInteger()
        barrier.stop {
            active.set(false)
            queue.clearForWithdrawal()
        }

        val admitted = barrier.persistIf(active::get) {
            reports.incrementAndGet()
            queue.enqueue(
                listOf(
                    PendingCollectionAckRecord.from(
                        server = server(1),
                        accepted = setOf(CollectionModuleId.USAGE_EVENTS),
                        declined = emptySet(),
                        trigger = ConsentTrigger.SETTINGS_CHANGE,
                        acknowledgedAt = OffsetDateTime.parse("2026-07-02T12:34:56Z"),
                    ),
                ),
            )
        }

        assertFalse(admitted)
        assertEquals(0, reports.get())
        assertTrue(queue.load().isEmpty())
    }

    private class FakeAckPersistence : CollectionAckRetryPersistence {
        @Volatile
        var records: List<PendingCollectionAckRecord> = emptyList()

        override fun load(): List<PendingCollectionAckRecord> = records

        override fun save(records: List<PendingCollectionAckRecord>) {
            this.records = records
        }
    }
}
