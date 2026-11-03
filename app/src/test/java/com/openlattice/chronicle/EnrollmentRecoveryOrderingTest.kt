package com.openlattice.chronicle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.CollectionAckRetryQueue
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.services.enrollment.pollParticipationStatus
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.eraseModuleAuxiliaryState
import com.openlattice.chronicle.collection.state.eraseResearchSourceState
import com.openlattice.chronicle.collection.state.validateEnrollmentModulePartition
import com.openlattice.chronicle.api.EnrollmentResponse
import com.openlattice.chronicle.data.HardStop
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.lifecycle.LifecycleEventMapper
import com.openlattice.chronicle.generated.EnrollmentAttemptContracts
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.INTERACTION_NETWORK_CONNECTED
import com.openlattice.chronicle.services.lifecycle.RoomLifecycleDedupeStore
import com.openlattice.chronicle.services.lifecycle.persistLifecycleEntries
import com.openlattice.chronicle.services.lifecycle.sampledState
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.storage.LifecycleStateEntity
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.study.EnrollmentWithdrawalResponse
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class EnrollmentRecoveryOrderingTest {
    @get:Rule val persistence = BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb

    @Before fun installPendingAttempt() {
        db = EnrollmentTestStores.install(context)
        reservePendingAttempt()
    }

    private fun reservePendingAttempt() {
        val preview = EnrollmentTestStores.enrollmentPreview()
        val settings = preview.manifest.collectionSettings
        reserveEnrollment(context, EnrollmentSubmission(
            preview = preview,
            partition = validateEnrollmentModulePartition(settings, settings.effectiveEnabledModuleIds(), emptySet(), emptySet()),
            serverUrl = preview.manifest.serverOrigin,
            enrollmentAccessCode = "replay-test-invitation",
        ))
    }

    @After fun closeStore() { db.close() }

    private fun pendingDeviceId() = requireNotNull(db.uploadServerDao().getRecoverablePendingEnrollment()).sourceRandomDeviceId

    @Test fun aStopAfterEnrollmentBeforeItsFirstPollClearsTheNewEnrollmentsDiagnostics() {
        for (published in listOf(7L, 8L)) {
            if (published == 8L) {
                ResearchPersistenceGate.stop {
                    ParticipantWithdrawalManager.clearLocalEnrollment(context, LocalUploadDiagnosticsStore.of(context),
                        db.uploadServerDao().getAll(), false)
                }
                reservePendingAttempt()
            }
            EnrollmentTestStores.installApi(studyApi { method, args ->
                when (method) {
                    "enroll" -> ChronicleJson.moshi.adapter(EnrollmentResponse::class.java).fromJson(
                        """{"chronicleId":"${UUID.randomUUID()}","apiKey":"${args!![7]}","hardStopGeneration":7}""")!!
                    "reportCollectionAck" -> OK()
                    else -> error(method)
                }
            }).use { assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context)) }
            val owner = db.uploadServerDao().getConfiguredServer()!!
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(UUID.randomUUID().toString(), owner.studyId,
                owner.participantId, owner.sourceRandomDeviceId, "epoch", "2026-10-30", "USAGE", "TIMEOUT", 1,
                now.toString(), now.toString(), null, null))
            ResearchPersistenceGate.applyParticipationStatus(context, owner,
                ResearchErasureFence(context).settingsGeneration(),
                ParticipationStatus.ENROLLED, emptyList(), HardStop(published, now.minusHours(1)))
            val pending = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM upload_diagnostics").use {
                it.moveToFirst(); it.getInt(0)
            }
            assertEquals("enrollment baseline 7, first poll $published", if (published == 7L) 1 else 0, pending)
        }
    }

    /** Every replay of one reservation sends the device id drawn for it, and that id is a random UUID. */
    @Test fun aRetryOfTheSameReservationKeepsItsDeviceId() {
        val reserved = pendingDeviceId()
        val sent = mutableListOf<String>()
        EnrollmentTestStores.installApi(studyApi { method, args ->
            assertEquals("enroll", method)
            sent += args!![2] as String
            throw IOException("reply lost")
        }).use {
            repeat(2) { assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context)) }
        }
        assertEquals(listOf(reserved, reserved), sent)
        assertEquals(reserved, UUID.fromString(reserved).toString())
    }

    /** A cancelled attempt leaves no id behind; the next reservation draws a new one. */
    @Test fun aNewReservationAfterACancelledAttemptGetsANewDeviceId() {
        val cancelled = pendingDeviceId()
        assertTrue(EnrollmentRecoveryManager.cancelPendingAttempt(context))
        reservePendingAttempt()
        assertNotEquals(cancelled, pendingDeviceId())
    }

    /** Re-enrolling after a completed withdrawal is a new enrollment, with a new device id. */
    @Test fun reEnrollmentAfterWithdrawalGetsANewDeviceId() {
        EnrollmentTestStores.installApi(studyApi { method, args ->
            when (method) {
                "enroll" -> EnrollmentResponse(UUID.randomUUID(), apiKey = args!![7] as String)
                "reportCollectionAck" -> OK()
                else -> error("unexpected API $method")
            }
        }).use { assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context)) }
        val enrolled = db.uploadServerDao().getAll().single()
        ResearchPersistenceGate.stop {
            ParticipantWithdrawalManager.clearLocalEnrollment(context, LocalUploadDiagnosticsStore.of(context), listOf(enrolled), false)
        }
        assertEquals(0, db.uploadServerDao().count())
        reservePendingAttempt()
        assertNotEquals(enrolled.sourceRandomDeviceId, pendingDeviceId())
    }

    @Test fun cancellingAnUncertainRequestKeepsItsKeyUntilTheServerAcknowledgesWithdrawal() {
        val dao = db.uploadServerDao()
        var available = false
        val api = studyApi { method, args ->
            when (method) {
                "enroll" -> throw IOException("lost response after remote commit")
                "withdrawCurrentEnrollment" -> {
                    val pending = requireNotNull(dao.getRecoverablePendingEnrollment())
                    assertEquals(pending.pendingProposedApiKey, args!![1])
                    assertEquals(pending.pendingEnrollmentAttemptId, args[2])
                    if (!available) throw IOException("offline")
                    withdrawalReceipt(args[2] as String)
                }
                else -> error("unexpected API $method")
            }
        }
        EnrollmentTestStores.installApi(api).use {
            EnrollmentRecoveryManager.resumeIfNeeded(context)
            assertFalse(EnrollmentRecoveryManager.cancelPendingAttempt(context))
            assertNotNull(dao.getRecoverablePendingEnrollment()?.pendingProposedApiKey)
            available = true
            assertTrue(EnrollmentRecoveryManager.cancelPendingAttempt(context))
            assertNull(dao.getRecoverablePendingEnrollment())
        }
    }

    @Test fun anIssuedRowWithoutACredentialCannotActivate() {
        val dao = db.uploadServerDao()
        val pending = requireNotNull(dao.getRecoverablePendingEnrollment())
        dao.update(pending.copy(enrollmentIssuedAtEpochMillis = System.currentTimeMillis(), apiKey = null,
            pendingEnrollmentAttemptId = null, pendingEnrollmentAccessCode = null, pendingProposedApiKey = null))
        EnrollmentRecoveryManager.resumeIfNeeded(context)
        assertFalse(requireNotNull(dao.getById(pending.id)).enabled)
    }

    @Test fun replayPersistsTimingBeforeHttpAndKeepsItOnRetryWithTheHttpStatus() {
        val dao = db.uploadServerDao()
        assertNull(dao.getRecoverablePendingEnrollment()!!.pendingEnrollmentFirstRequestAtEpochMillis)
        var requests = 0
        val api = studyApi { method, _ ->
            assertEquals("enroll", method)
            requests++
            val durable = dao.getRecoverablePendingEnrollment()!!
            assertNotNull(durable.pendingEnrollmentFirstRequestAtEpochMillis)
            throw ChronicleCallException("POST", "request", "", 503)
        }
        EnrollmentTestStores.installApi(api).use {
            assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            val saved = dao.getRecoverablePendingEnrollment()!!
            assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            val retried = dao.getRecoverablePendingEnrollment()!!
            assertEquals(saved.pendingEnrollmentFirstRequestAtEpochMillis, retried.pendingEnrollmentFirstRequestAtEpochMillis)
        }
        assertEquals(2, requests)
        assertTrue(ShadowLog.getLogs().any { it.msg.contains("HTTP 503") })
    }

    @Test fun recoveryAfterServerAckCommitReplaysTheOriginalEvidence() {
        val dao = db.uploadServerDao()
        var first: CollectionAcknowledgment? = null
        var reports = 0
        var issuedBeforeSetup: UploadServerEntity? = null
        val api = studyApi { method, args ->
            when (method) {
                "enroll" -> EnrollmentResponse(UUID.randomUUID(), apiKey = args!![7] as String)
                "reportCollectionAck" -> {
                    val ack = args!![4] as CollectionAcknowledgment
                    reports++
                    if (first == null) {
                        first = ack
                        issuedBeforeSetup = dao.getConfiguredServer()
                        // The server committed, but setup has not completed locally.
                        throw IOException("lost acknowledgment response")
                    }
                    assertEquals("Recovery must replay the immutable enrollment acknowledgment", first, ack)
                    OK()
                }
                else -> error("unexpected study API $method")
            }
        }
        EnrollmentTestStores.installApi(api).use {
            assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            dao.update(checkNotNull(issuedBeforeSetup))
            assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            val deliveredReports = reports
            dao.update(checkNotNull(issuedBeforeSetup))
            assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            assertEquals("A delivered enrollment acknowledgment is not regenerated during recovery", deliveredReports, reports)
        }
        assertTrue(reports >= 2)
    }

    @Test fun lifecycleErasurePreservesEnrollmentMetadataAndStillRetiresObservations() {
        val events = listOf("network:wifi", "power-save:on").map { state ->
            DeviceLifecycleEventRecorder.buildEvent(state, INTERACTION_NETWORK_CONNECTED, 0)
        } + checkNotNull(LifecycleEventMapper.batteryStateEvent(plugged = 1, level = 80, scale = 100, timestampMillis = 0))
        persistLifecycleEntries(db, listOf(QueueEntry(0, 1, JsonSerializer.serializeQueueEntry(events))))
        val dao = db.lifecycleStateDao()
        val metadata = LifecycleStateEntity("enrollment-metadata", "evidence", 0)
        dao.upsert(metadata)
        val dedupe = RoomLifecycleDedupeStore(db)
        assertTrue(events.none { dedupe.shouldPersist(it, System.currentTimeMillis()) })
        eraseModuleAuxiliaryState(context, CollectionModuleId.DEVICE_LIFECYCLE)
        events.forEach { event ->
            assertTrue(dedupe.shouldPersist(event, System.currentTimeMillis()))
            assertNull(dao.get(checkNotNull(sampledState(event)).first))
        }
        assertEquals("Lifecycle erasure must preserve unrelated enrollment metadata", metadata, dao.get(metadata.key))
        eraseResearchSourceState(context)
        assertNull(dao.get(metadata.key))
    }

    @Test fun aLostEnrollReplyRetriesTheSameAttemptAndCompletesWithTheProposedKey() {
        val dao = db.uploadServerDao()
        val sent = mutableListOf<Pair<String, String>>()
        val api = studyApi { method, args ->
            when (method) {
                "enroll" -> {
                    sent += (args!![6] as String) to (args[7] as String)
                    // The server committed the first request, but its reply never reached the device.
                    if (sent.size == 1) throw IOException("connection reset after the request was sent")
                    EnrollmentResponse(UUID.randomUUID(), apiKey = args[7] as String)
                }
                "reportCollectionAck" -> OK()
                else -> error("unexpected study API $method")
            }
        }
        EnrollmentTestStores.installApi(api).use {
            assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertEquals("the retry resends the exact attempt and proposed key", listOf(sent[0], sent[0]), sent)
        val row = dao.getAll().single()
        assertTrue(row.enabled)
        assertEquals(sent[0].second, row.apiKey)
    }

    /** A phone clock an hour ahead reads the invitation as expired; the server, whose clock decides, accepts it. */
    @Test fun aPhoneClockPastTheInvitationExpiryStillSendsAndTheServerAccepts() {
        val dao = db.uploadServerDao()
        dao.update(expireInvitation(requireNotNull(dao.getRecoverablePendingEnrollment()), System.currentTimeMillis() - 3_600_000))
        val requests = mutableListOf<String>()
        EnrollmentTestStores.installApi(studyApi { method, args ->
            requests += method
            when (method) {
                "enroll" -> EnrollmentResponse(UUID.randomUUID(), apiKey = args!![7] as String)
                "reportCollectionAck" -> OK()
                else -> error("unexpected study API $method")
            }
        }).use {
            assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertEquals("enroll", requests.first())
        assertTrue(dao.getAll().single().enabled)
    }

    /** The server's refusal of an expired invitation, not the phone's clock, ends the attempt. */
    @Test fun theServersRefusalOfAnExpiredInvitationEndsTheAttempt() {
        val dao = db.uploadServerDao()
        val expired = expireInvitation(requireNotNull(dao.getRecoverablePendingEnrollment()), System.currentTimeMillis() + 3_600_000)
        dao.update(expired)
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            val status = when (method) {
                "enroll" -> HttpURLConnection.HTTP_UNAUTHORIZED
                "withdrawCurrentEnrollment" -> HttpURLConnection.HTTP_GONE
                else -> error("unexpected API $method")
            }
            throw ChronicleCallException(method, "request", "", status)
        }).use {
            assertEquals(EnrollmentRecoveryResult.TERMINAL_FAILURE, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertNull(dao.getRecoverablePendingEnrollment())
    }

    @Test fun aPossiblySentRequestReplaysEvenAfterTheInvitationExpires() {
        val dao = db.uploadServerDao()
        val firstRequestAt = System.currentTimeMillis() - 1_000
        dao.update(expireInvitation(requireNotNull(dao.getRecoverablePendingEnrollment()), firstRequestAt).copy(
            pendingEnrollmentFirstRequestAtEpochMillis = firstRequestAt,
        ))
        var requests = 0
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            assertEquals("enroll", method)
            requests++
            throw ChronicleCallException("POST", "request", "", 503)
        }).use {
            assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertEquals(1, requests)
        assertNotNull(dao.getRecoverablePendingEnrollment()?.pendingProposedApiKey)
    }

    @Test fun aRejectedEnrollmentWhoseKeyWasNeverInstalledEndsSoANewAttemptCanStart() {
        val dao = db.uploadServerDao()
        val rejected = requireNotNull(dao.getRecoverablePendingEnrollment())
        val requests = mutableListOf<String>()
        EnrollmentTestStores.installApi(studyApi { method, args ->
            requests += method
            val status = when (method) {
                "enroll" -> HttpURLConnection.HTTP_UNAUTHORIZED
                "withdrawCurrentEnrollment" -> {
                    assertEquals(rejected.pendingProposedApiKey, args!![1])
                    assertEquals(rejected.pendingEnrollmentAttemptId, args[2])
                    HttpURLConnection.HTTP_GONE
                }
                else -> error("unexpected API $method")
            }
            throw ChronicleCallException(method, "request", "", status)
        }).use {
            assertEquals(EnrollmentRecoveryResult.TERMINAL_FAILURE, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertEquals(listOf("enroll", "withdrawCurrentEnrollment"), requests)
        assertNull(dao.getRecoverablePendingEnrollment())
        assertStartsAFreshAttempt(rejected)
    }

    /** An attempt the study team cancelled ends as cancelled, not as a failure; the phone's cancel is acknowledged. */
    @Test fun anAttemptTheStudyTeamCancelledEndsAsCancelled() {
        val dao = db.uploadServerDao()
        val cancelled = requireNotNull(dao.getRecoverablePendingEnrollment())
        val requests = mutableListOf<String>()
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            requests += method
            when (method) {
                "enroll" -> throw ChronicleCallException(method, "request",
                    coded(EnrollmentAttemptContracts.ENROLLMENT_CANCELLED_CODE), HttpURLConnection.HTTP_GONE)
                "withdrawCurrentEnrollment" -> withdrawalReceipt(requireNotNull(cancelled.pendingEnrollmentAttemptId))
                else -> error("unexpected API $method")
            }
        }).use {
            assertEquals(EnrollmentRecoveryResult.CANCELLED, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertEquals(listOf("enroll", "withdrawCurrentEnrollment"), requests)
        assertNull(dao.getRecoverablePendingEnrollment())
        assertEquals(R.string.enrollment_attempt_cancelled, recoveryEndMessage(EnrollmentRecoveryResult.CANCELLED))
        assertStartsAFreshAttempt(cancelled)
    }

    /** The key installed and the enrollment completed here, but its ENROLLMENT ack is still queued (offline). */
    private fun enrollWithTheAckUndelivered() {
        EnrollmentTestStores.installApi(studyApi { method, args ->
            when (method) {
                "enroll" -> EnrollmentResponse(UUID.randomUUID(), apiKey = args!![7] as String)
                "reportCollectionAck" -> throw IOException("offline")
                else -> error("unexpected API $method")
            }
        }).use { assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context)) }
        assertEquals(1, CollectionAckRetryQueue.of(context).load().size)
        db.queueEntryData().insertEntry(QueueEntry(0, 1, JsonSerializer.serializeQueueEntry(emptyList())))
    }

    /**
     * The study team cancelled the attempt on the dashboard after the key installed and before the ack landed.
     * The server's coded 410 ([call] is the status poll or the ack retry) ends the enrollment here as cancelled: the
     * queue is cleared, no withdrawal is requested, the cancel is shown and a new invitation is accepted. An uncoded
     * 410 is not a cancel and keeps today's behaviour.
     */
    private fun assertCodedGoneEndsTheEnrollment(call: String, run: () -> Unit) {
        enrollWithTheAckUndelivered()
        for (body in listOf("", coded(EnrollmentAttemptContracts.ENROLLMENT_CANCELLED_CODE))) {
            val requests = mutableListOf<String>()
            EnrollmentTestStores.installApi(studyApi { method, _ ->
                requests += method
                when (method) {
                    call -> throw ChronicleCallException(method, "request", body, HttpURLConnection.HTTP_GONE)
                    else -> throw IOException("offline")
                }
            }).use { runCatching(run) }
            assertTrue(requests.toString(), call in requests && "withdrawCurrentEnrollment" !in requests)
            if (body.isEmpty()) {
                assertNotNull("an uncoded 410 keeps the enrollment", db.uploadServerDao().getConfiguredServer())
                assertEquals(WithdrawalState.NONE, WithdrawalStateStore(context).state())
                assertEquals(1, db.queueEntryData().getSize())
            }
        }
        assertNull(db.uploadServerDao().getConfiguredServer())
        assertEquals(WithdrawalState.CANCELLED, WithdrawalStateStore(context).state())
        assertEquals("the hard stop cleared the queue", 0, db.queueEntryData().getSize())
        assertTrue(CollectionAckRetryQueue.of(context).load().isEmpty())
        EnrollmentTestStores.installApi(studyApi { method, _ -> error("unexpected API $method") }).use {
            assertEquals(EnrollmentRecoveryResult.CANCELLED, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertEquals(R.string.enrollment_attempt_cancelled, recoveryEndMessage(EnrollmentRecoveryResult.CANCELLED))
        reservePendingAttempt()
        assertNotNull("a new invitation is accepted", db.uploadServerDao().getRecoverablePendingEnrollment())
    }

    @Test fun aCodedGoneOnTheStatusPollEndsTheEnrollmentAsCancelled() =
        assertCodedGoneEndsTheEnrollment("getDeviceParticipationStatus") { pollParticipationStatus(context) }

    @Test fun aCodedGoneOnTheAckRetryEndsTheEnrollmentAsCancelled() =
        assertCodedGoneEndsTheEnrollment("reportCollectionAck") { CollectionLoopCoordinator(context).sync() }

    /** A study edit during review ends this attempt with "open the link again", and a new attempt can start. */
    @Test fun aStudyEditDuringReviewEndsAsStudyChanged() {
        val dao = db.uploadServerDao()
        val changed = requireNotNull(dao.getRecoverablePendingEnrollment())
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            when (method) {
                "enroll" -> throw ChronicleCallException(method, "request",
                    coded(EnrollmentAttemptContracts.ENROLLMENT_STUDY_CHANGED_CODE), HttpURLConnection.HTTP_CONFLICT)
                "withdrawCurrentEnrollment" -> throw ChronicleCallException(method, "request", "", HttpURLConnection.HTTP_GONE)
                else -> error("unexpected API $method")
            }
        }).use {
            assertEquals(EnrollmentRecoveryResult.STUDY_CHANGED, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertNull(dao.getRecoverablePendingEnrollment())
        assertEquals(R.string.enrollment_study_changed, recoveryEndMessage(EnrollmentRecoveryResult.STUDY_CHANGED))
        assertStartsAFreshAttempt(changed)
    }

    @Test fun anUncodedStudyConflictKeepsTheAttemptUntilTheServerCanEnrollIt() {
        val pending = requireNotNull(db.uploadServerDao().getRecoverablePendingEnrollment())
        var conflict = true
        EnrollmentTestStores.installApi(studyApi { method, args ->
            when (method) {
                "enroll" -> {
                    assertEquals(pending.pendingEnrollmentAttemptId, args!![6])
                    assertEquals(pending.pendingProposedApiKey, args[7])
                    if (conflict) throw ChronicleCallException(method, "request", "", HttpURLConnection.HTTP_CONFLICT)
                    EnrollmentResponse(UUID.randomUUID(), apiKey = args[7] as String)
                }
                "reportCollectionAck" -> OK()
                else -> error("unexpected API $method")
            }
        }).use {
            assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            assertEquals(pending.pendingEnrollmentAttemptId, db.uploadServerDao().getRecoverablePendingEnrollment()?.pendingEnrollmentAttemptId)
            conflict = false
            assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
    }

    /**
     * Pilot: a server that cannot serve this app version (400 for a header contract mismatch, a missing route...)
     * answers the attempt's only request before binding anything. The attempt ends at once with the study-team message,
     * no withdrawal is asked of a server that just refused this app, and a new attempt can start. Other final refusals
     * (403) end the same way with the generic refusal.
     */
    @Test fun aFinalRefusalOfTheOnlyRequestEndsTheAttemptWithoutAWithdrawal() {
        val cases = listOf(400, 404, 405, 410, 415, 422).map { it to EnrollmentRecoveryResult.SERVER_INCOMPATIBLE } +
            listOf(403).map { it to EnrollmentRecoveryResult.SERVER_REFUSED }
        for ((status, expected) in cases) {
            db.close()
            db = EnrollmentTestStores.install(context)
            reservePendingAttempt()
            val refused = requireNotNull(db.uploadServerDao().getRecoverablePendingEnrollment())
            val requests = mutableListOf<String>()
            EnrollmentTestStores.installApi(studyApi { method, _ ->
                requests += method
                throw ChronicleCallException(method, "request", """{"status":$status,"message":"refused"}""", status)
            }).use {
                assertEquals("HTTP $status", expected, EnrollmentRecoveryManager.resumeIfNeeded(context))
            }
            assertEquals("HTTP $status", listOf("enroll"), requests)
            assertNull("HTTP $status", db.uploadServerDao().getRecoverablePendingEnrollment())
            assertStartsAFreshAttempt(refused)
        }
        assertEquals(R.string.enrollment_server_incompatible, recoveryEndMessage(EnrollmentRecoveryResult.SERVER_INCOMPATIBLE))
        assertEquals(R.string.enrollment_server_rejected, recoveryEndMessage(EnrollmentRecoveryResult.SERVER_REFUSED))
    }

    /**
     * An earlier request's outcome is unknown (its reply was lost), so the server may hold a binding from it. A
     * later final refusal ends the attempt only on the server's withdrawal answer; until then the attempt is kept.
     */
    @Test fun aFinalRefusalAfterAnUnknownOutcomeEndsOnlyOnTheServersWithdrawalAnswer() {
        val dao = db.uploadServerDao()
        var withdrawal: Int = HttpURLConnection.HTTP_BAD_REQUEST
        var sends = 0
        val requests = mutableListOf<String>()
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            requests += method
            when (method) {
                "enroll" -> if (sends++ == 0) throw IOException("reply lost")
                    else throw ChronicleCallException(method, "request", "", HttpURLConnection.HTTP_BAD_REQUEST)
                "withdrawCurrentEnrollment" -> throw ChronicleCallException(method, "request", "", withdrawal)
                else -> error("unexpected API $method")
            }
        }).use {
            assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            assertNotNull(dao.getRecoverablePendingEnrollment()?.pendingProposedApiKey)
            withdrawal = HttpURLConnection.HTTP_GONE
            assertEquals(EnrollmentRecoveryResult.SERVER_INCOMPATIBLE, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertEquals(listOf("enroll", "enroll", "withdrawCurrentEnrollment", "enroll", "withdrawCurrentEnrollment"), requests)
        assertNull(dao.getRecoverablePendingEnrollment())
    }

    /** A failure a retry may pass keeps the exact attempt for its replay, including an uncoded study conflict. */
    @Test fun failuresARetryMayPassKeepTheAttempt() {
        val dao = db.uploadServerDao()
        val pending = requireNotNull(dao.getRecoverablePendingEnrollment())
        val failures = listOf(IOException("offline"), SocketTimeoutException("timeout")) +
            listOf(408, 409, 429, 500, 502, 503, 504).map { ChronicleCallException("POST", "request", "", it) }
        for (failure in failures) {
            EnrollmentTestStores.installApi(studyApi { method, _ ->
                assertEquals("enroll", method)
                throw failure
            }).use {
                assertEquals(failure.toString(), EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED,
                    EnrollmentRecoveryManager.resumeIfNeeded(context))
            }
            assertEquals(failure.toString(), pending.pendingEnrollmentAttemptId,
                dao.getRecoverablePendingEnrollment()?.pendingEnrollmentAttemptId)
        }
    }

    /** The server's answer decides. After a lost reply, its "never installed" (410) to the cancellation ends the attempt. */
    @Test fun cancellingAnUnknownOutcomeEndsOnTheServersNeverInstalledAnswer() {
        val dao = db.uploadServerDao()
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            when (method) {
                "enroll" -> throw IOException("enrollment response lost")
                "withdrawCurrentEnrollment" -> throw ChronicleCallException(method, "request", "", HttpURLConnection.HTTP_GONE)
                else -> error("unexpected API $method")
            }
        }).use {
            assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            val sent = requireNotNull(dao.getRecoverablePendingEnrollment())
            assertTrue(EnrollmentRecoveryManager.cancelPendingAttempt(context))
            assertNull(dao.getRecoverablePendingEnrollment())
            assertStartsAFreshAttempt(sent)
        }
    }

    /**
     * Two clocks. The first request was stamped while the phone's clock was right; the server's replay
     * window runs from its own bind. The phone clock then moves 3 h ahead or behind. Whatever the phone reads, the
     * attempt replays while the server says it is live, and an attempt the server says has expired ends on its refusal.
     */
    @Test fun theServerAloneDecidesWhetherASentAttemptIsLiveWithThePhoneClockThreeHoursOff() {
        val hour = TimeUnit.HOURS.toMillis(1)
        val serverWindow = 24 * hour
        for (skew in listOf(3 * hour, -3 * hour)) for ((sinceBind, live) in listOf(22 * hour to true, 25 * hour to false)) {
            val case = "phone ${skew / hour} h, ${sinceBind / hour} h after the server bound the attempt"
            db.close()
            db = EnrollmentTestStores.install(context)
            reservePendingAttempt()
            val dao = db.uploadServerDao()
            fun serverNow() = System.currentTimeMillis() - skew
            val boundAt = serverNow() - sinceBind
            dao.update(requireNotNull(dao.getRecoverablePendingEnrollment()).copy(
                pendingEnrollmentFirstRequestAtEpochMillis = boundAt,
            ))
            val requests = mutableListOf<String>()
            val result = EnrollmentTestStores.installApi(studyApi { method, args ->
                requests += method
                when (method) {
                    "enroll" -> if (serverNow() < boundAt + serverWindow) EnrollmentResponse(UUID.randomUUID(), apiKey = args!![7] as String)
                        else throw ChronicleCallException(method, "request", "", HttpURLConnection.HTTP_UNAUTHORIZED)
                    "withdrawCurrentEnrollment" -> throw ChronicleCallException(method, "request", "", HttpURLConnection.HTTP_GONE)
                    "reportCollectionAck" -> OK()
                    else -> error("unexpected API $method")
                }
            }).use { EnrollmentRecoveryManager.resumeIfNeeded(context) }
            assertEquals(case, "enroll", requests.firstOrNull())
            if (live) {
                assertEquals(case, EnrollmentRecoveryResult.COMPLETED, result)
                assertTrue(case, dao.getAll().single().enabled)
            } else {
                assertEquals(case, EnrollmentRecoveryResult.TERMINAL_FAILURE, result)
                assertNull(case, dao.getRecoverablePendingEnrollment())
            }
        }
    }

    private fun assertStartsAFreshAttempt(previous: UploadServerEntity) {
        reservePendingAttempt()
        val fresh = requireNotNull(db.uploadServerDao().getRecoverablePendingEnrollment())
        assertTrue(previous.pendingEnrollmentAttemptId != fresh.pendingEnrollmentAttemptId)
        assertTrue(previous.pendingProposedApiKey != fresh.pendingProposedApiKey)
    }

    @Test fun cancellationRetainsTheAttemptWhenTheReceiptNamesAnotherRequest() {
        val dao = db.uploadServerDao()
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            when (method) {
                "enroll" -> throw IOException("enrollment response lost")
                "withdrawCurrentEnrollment" -> withdrawalReceipt(UUID.randomUUID().toString())
                else -> error("unexpected API $method")
            }
        }).use {
            assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
            val sent = requireNotNull(dao.getRecoverablePendingEnrollment())
            assertFalse(EnrollmentRecoveryManager.cancelPendingAttempt(context))
            assertEquals(sent, dao.getRecoverablePendingEnrollment())
        }
    }

    private fun coded(code: String) = """{"status":0,"code":"$code"}"""

    private fun withdrawalReceipt(requestId: String) =
        EnrollmentWithdrawalResponse(UUID.fromString(requestId))

    @Test fun transientWithdrawalFailuresRetainTheSameAttemptForRecoveryAndCancellation() {
        val dao = db.uploadServerDao()
        val pending = requireNotNull(dao.getRecoverablePendingEnrollment())
        val failures = listOf(
            ChronicleCallException("DELETE", "request", "", HttpURLConnection.HTTP_UNAUTHORIZED),
            ChronicleCallException("DELETE", "request", "", HttpURLConnection.HTTP_UNAVAILABLE),
            ChronicleCallException("DELETE", "request", "", 429),
            IOException("offline"),
            SocketTimeoutException("timeout"),
        )
        for (failure in failures) {
            EnrollmentTestStores.installApi(studyApi { method, _ ->
                when (method) {
                    "enroll" -> throw ChronicleCallException(method, "request", "", HttpURLConnection.HTTP_UNAUTHORIZED)
                    "withdrawCurrentEnrollment" -> throw failure
                    else -> error("unexpected API $method")
                }
            }).use {
                assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
                assertFalse(EnrollmentRecoveryManager.cancelPendingAttempt(context))
            }
            val retained = requireNotNull(dao.getRecoverablePendingEnrollment())
            assertEquals(pending.pendingEnrollmentAttemptId, retained.pendingEnrollmentAttemptId)
            assertEquals(pending.pendingProposedApiKey, retained.pendingProposedApiKey)
            assertNotNull(retained.pendingEnrollmentFirstRequestAtEpochMillis)
        }
    }

    @Test fun aReplyWithADifferentCredentialIsNeverActivatedAndEndsTheAttempt() {
        val dao = db.uploadServerDao()
        val answered = requireNotNull(dao.getRecoverablePendingEnrollment())
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            if (method == "enroll") EnrollmentResponse(UUID.randomUUID(), apiKey = "ck_ABCDEFGH_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345")
            // The server never installed the proposed key, so withdrawing with it is refused as final.
            else throw ChronicleCallException(method, "request", "", HttpURLConnection.HTTP_GONE)
        }).use {
            assertEquals(EnrollmentRecoveryResult.TERMINAL_FAILURE, EnrollmentRecoveryManager.resumeIfNeeded(context))
        }
        assertEquals(0, dao.countEnabled())
        assertNull(dao.getRecoverablePendingEnrollment())
        assertStartsAFreshAttempt(answered)
    }

    /** The invitation the phone holds now reads as expiring at [atEpochMillis]; only the server judges it. */
    private fun expireInvitation(row: UploadServerEntity, atEpochMillis: Long): UploadServerEntity {
        val adapter = ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java)
        val expiresAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(atEpochMillis), ZoneOffset.UTC)
        val manifest = requireNotNull(adapter.fromJson(requireNotNull(row.studyDisclosureJson)))
        return row.copy(
            studyDisclosureJson = adapter.toJson(manifest.copy(issuedAt = expiresAt.minusHours(1), expiresAt = expiresAt)),
        )
    }
}
