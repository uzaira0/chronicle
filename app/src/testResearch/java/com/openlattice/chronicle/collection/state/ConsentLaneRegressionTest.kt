package com.openlattice.chronicle.collection.state

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.UploadWorkerDelegate
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.generated.CollectionConsentContracts
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * Consent survives settings edits except a disable then re-enable; decisions reach the server in order, a refused one
 * is reported and dropped, and queued rows wait for the decisions that cover them.
 */
class ConsentLaneRegressionTest : ResearchErasureTestFixture() {
    private val battery = CollectionModuleId.BATTERY_TELEMETRY
    private val activity = CollectionModuleId.IN_APP_ACTIVITY_CLASS
    private val on = CollectionModuleSetting(enabled = true)

    private fun settings(version: Int, batterySetting: CollectionModuleSetting = on) = AndroidDataCollectionSetting(
        modules = AndroidDataCollectionSetting.androidSupportedModuleIds.associateWith { CollectionModuleSetting(enabled = false) } +
            mapOf(CollectionModuleId.USAGE_EVENTS to on, activity to on, battery to batterySetting,
                CollectionModuleId.UPLOAD_TELEMETRY to on),
        settingsVersion = version)

    /** The enrollment's manifest, so its acknowledgments are bound to this enrollment. */
    private fun enroll(initial: AndroidDataCollectionSetting): UploadServerEntity {
        val preview = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(javaClass.getResource("/enrollment-preview.json")!!.readText())!!
        val manifest = preview.manifest.copy(studyId = UUID.fromString(owner.studyId), participantId = owner.participantId,
            serverOrigin = owner.url, collectionSettings = initial, settingsVersion = initial.settingsVersion)
        val expected = owner.copy(studyDisclosureJson = ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java)
            .toJson(manifest), disclosureVersion = manifest.participantPolicy.version, manifestDigest = preview.manifestDigest)
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        apply(initial)
        return expected
    }

    private fun apply(fetched: AndroidDataCollectionSetting) =
        assertEquals(SettingsApplicationOutcome.APPLIED,
            onPersistenceWorker { coordinator.applyFetchedSettings(owner, fence.settingsGeneration(), fetched) })

    private fun state(module: CollectionModuleId) = CollectionLoopStore.of(context).loadAll().getValue(module)

    private fun notifications() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .allNotifications

    private fun clearNotifications() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
    }

    private fun decideOffline(accepted: Set<CollectionModuleId>, declined: Set<CollectionModuleId>) =
        withStudyApi { throw IOException("offline") }.use {
            onPersistenceWorker { coordinator.applyDecisions(accepted, declined, ConsentTrigger.PARTICIPANT_TOGGLE) }
        }

    private fun retryAcks() = onPersistenceWorker { coordinator.sync() }

    private fun pendingAcks() = CollectionAckRetryQueue.of(context).load()

    @Test fun aBatteryIntervalEditKeepsBatteryAcceptedAndSamplingWithNoNotification() {
        enroll(settings(1))
        decideOffline(setOf(battery), emptySet())
        assertTrue(state(battery).accepted)
        clearNotifications()

        apply(settings(2, CollectionModuleSetting(enabled = true, collectionCadence = CollectionCadence(1800))))

        assertEquals(CollectionModulePhase.ACTIVE, state(battery).phase)
        assertTrue(ResearchPersistenceGate.collectsNow(context, battery))
        assertEquals(emptyList<Any>(), notifications())
    }

    @Test fun aDisableThenReenableAsksForANewDecision() {
        enroll(settings(1))
        decideOffline(setOf(battery), emptySet())
        apply(settings(2, CollectionModuleSetting(enabled = false)))
        clearNotifications()

        apply(settings(3))

        assertEquals(CollectionModulePhase.AWAITING_DECISION, state(battery).phase)
        assertFalse(ResearchPersistenceGate.collectsNow(context, battery))
        assertEquals(1, notifications().size)
    }

    /** No settings revision records a decision the participant did not make (B9). */
    @Test fun aSettingsRevisionRecordsNoAcknowledgment() {
        enroll(settings(1))
        decideOffline(setOf(battery), emptySet())
        CollectionAckRetryQueue.of(context).clearForWithdrawal()

        apply(settings(2, CollectionModuleSetting(enabled = true, collectionCadence = CollectionCadence(1800))))
        apply(settings(3, CollectionModuleSetting(enabled = true, required = true, collectionCadence = CollectionCadence(1800))))

        assertEquals(emptyList<PendingCollectionAckRecord>(), pendingAcks())
    }

    @Test fun aRefusedAcknowledgmentIsReportedAndDoesNotBlockTheNextOne() {
        enroll(settings(1))
        CollectionAckRetryQueue.of(context).clearForWithdrawal()
        decideOffline(emptySet(), setOf(activity))
        decideOffline(setOf(activity), emptySet())
        assertEquals(2, pendingAcks().size)
        val delivered = mutableListOf<CollectionAcknowledgment>()

        withStudyApi(onCall = { args -> args.filterIsInstance<CollectionAcknowledgment>().forEach(delivered::add) }) { method ->
            when (method) {
                "getDataCollectionSettings" -> settings(1)
                "reportCollectionAck" -> {
                    if (delivered.last().declinedModules.isNotEmpty()) throw ChronicleCallException("POST", "url",
                        """{"code":"${CollectionConsentContracts.COLLECTION_ACK_REJECTED_CODE}"}""", 400)
                    OK()
                }
                else -> error("unexpected $method")
            }
        }.use { retryAcks() }

        assertEquals(listOf(setOf(activity)), delivered.map { it.acknowledgedModules }.filter { it.isNotEmpty() })
        assertEquals(emptyList<PendingCollectionAckRecord>(), pendingAcks())
        assertEquals(listOf(400), LocalUploadDiagnosticsStore.of(context).pending().mapNotNull { it.httpStatus })
    }

    @Test fun aDecisionTakenWhilePausedReachesTheServerAfterResume() {
        val initial = settings(1)
        enroll(initial)
        CollectionAckRetryQueue.of(context).clearForWithdrawal()
        prefs.edit().putString(PARTICIPATION_STATUS, ParticipationStatus.PAUSED.name).commit()
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        withStudyApi { throw ChronicleCallException("POST", "url", "", 403) }.use {
            onPersistenceWorker { coordinator.applyDecisions(emptySet(), setOf(activity), ConsentTrigger.PARTICIPANT_TOGGLE) }
        }
        assertEquals(1, pendingAcks().size)

        prefs.edit().putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name).commit()
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        val delivered = mutableListOf<CollectionAcknowledgment>()
        withStudyApi(onCall = { args -> args.filterIsInstance<CollectionAcknowledgment>().forEach(delivered::add) }) { method ->
            if (method == "getDataCollectionSettings") initial else OK()
        }.use { assertTrue(onPersistenceWorker { coordinator.sync() }) }

        assertEquals(listOf(setOf(activity)), delivered.map { it.declinedModules })
        assertEquals(emptyList<PendingCollectionAckRecord>(), pendingAcks())
    }

    /** A field the study adds and the participant accepts later is not attached to events captured before. */
    @Test fun aFieldAcceptedMidStudyIsStrippedFromEventsCapturedBeforeTheAcceptance() {
        enroll(settings(1).let { it.copy(modules = it.modules + (activity to CollectionModuleSetting(enabled = false))) })
        decideOffline(setOf(CollectionModuleId.USAGE_EVENTS), emptySet())
        apply(settings(2))
        val before = event(System.currentTimeMillis() - 60_000)
        decideOffline(setOf(activity), emptySet())
        val after = event(System.currentTimeMillis() + 1_000)
        assertEquals(ModuleResult.Ok(1), ResearchPersistenceGate.usageSink(context)
            .write(listOf(QueueEntry(1, 1, JsonSerializer.serializeQueueEntry(listOf(before, after))))))

        val stored = db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10)
            .flatMap { JsonSerializer.deserializeQueueEntry(it.data) }.filterIsInstance<ExtractedUsageEvent>()
            .sortedBy { it.timestamp }
        assertEquals(2, stored.size)
        assertNull(stored.first().activityClass)
        assertEquals("MainActivity", stored.last().activityClass)
    }

    /** A row captured under a decision the server has not received waits for it, then carries its sequence number. */
    @Test fun rowsWaitForThePendingDecisionThatCoversThemAndCarryItsSequence() {
        enroll(settings(1))
        CollectionAckRetryQueue.of(context).clearForWithdrawal()
        decideOffline(setOf(CollectionModuleId.USAGE_EVENTS), setOf(activity))
        ResearchPersistenceGate.usageSink(context).write(listOf(row()))
        val headers = mutableListOf<Map<*, *>>()
        val delivered = mutableListOf<CollectionAcknowledgment>()
        var online = false
        var call: Array<out Any?> = emptyArray()
        withStudyApi(onCall = { args -> call = args }) { method ->
            when (method) {
                "getDataCollectionSettings" -> settings(1)
                "reportCollectionAck" -> if (online) OK().also { delivered += call[4] as CollectionAcknowledgment }
                    else throw IOException("offline")
                "uploadAndroidUsageEventData" -> {
                    headers += call[2] as Map<*, *>
                    UploadReceipt(decodedUploadBody<ChronicleData>(call[3]!!).mapTo(linkedSetOf()) {
                        checkNotNull((it as ChronicleUsageEvent).clientEventId) }, emptySet(), 1)
                }
                else -> error("unexpected $method")
            }
        }.use {
            onPersistenceWorker { UploadWorkerDelegate(context, db).execute() }
            assertEquals(emptyList<Map<*, *>>(), headers)
            assertEquals(1, db.queueEntryData().getSize())

            online = true
            retryAcks()
            onPersistenceWorker { UploadWorkerDelegate(context, db).execute() }
        }
        assertEquals(0, db.queueEntryData().getSize())
        val seq = checkNotNull(delivered.single().decisionSeq)
        assertEquals(seq.toString(), headers.single()[CollectionUploadEvidence.DECISION_SEQ_HEADER])
    }
}
