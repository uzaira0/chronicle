package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.preferences.IDENTIFY_USER
import android.app.Application
import androidx.work.WorkManager
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.collection.usage.UsageEventPoller
import com.openlattice.chronicle.collection.usage.UsageEventsCollectionModule
import com.openlattice.chronicle.collection.usage.UsageModulePersistence
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.usage.UsageModuleCollectionDelegate
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.services.notifications.userIdentificationMayRun
import com.openlattice.chronicle.services.sensors.SensorSettingsRefreshWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.ui.userIdentificationControlState
import java.time.OffsetDateTime
import java.util.NavigableMap
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

class ResearchEnrollmentErasureTest : ResearchErasureTestFixture() {
    @Test fun identificationAddedAfterEnrollmentUnlocksSettingsAndPromptsOnlyAfterConsent() {
        val module = CollectionModuleId.USER_IDENTIFICATION
        val preview = ChronicleJson.moshi
            .adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(javaClass.getResource("/enrollment-preview.json")!!.readText())!!
        val initialSettings = preview.manifest.collectionSettings.copy(settingsVersion = 1,
            modules = AndroidDataCollectionSetting.androidSupportedModuleIds.associateWith {
                CollectionModuleSetting(enabled = false)
            })
        val manifest = preview.manifest.copy(studyId = UUID.fromString(owner.studyId),
            participantId = owner.participantId, serverOrigin = owner.url, collectionSettings = initialSettings,
            settingsVersion = initialSettings.settingsVersion)
        val expected = owner.copy(studyDisclosureJson = ChronicleJson.moshi
            .adapter(MobileEnrollmentManifest::class.java).toJson(manifest),
            disclosureVersion = manifest.participantPolicy.version, manifestDigest = preview.manifestDigest)
        db.uploadServerDao().update(expected)
        assertEquals(SettingsApplicationOutcome.APPLIED, onPersistenceWorker {
            coordinator.applyFetchedSettings(expected, fence.settingsGeneration(), initialSettings)
        })
        prefs.edit().putBoolean(IDENTIFY_USER, true).commit()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val revised = initialSettings.copy(settingsVersion = 2,
            modules = initialSettings.modules + (module to CollectionModuleSetting(enabled = true)))

        assertEquals(SettingsApplicationOutcome.APPLIED, onPersistenceWorker {
            coordinator.applyFetchedSettings(expected, fence.settingsGeneration(), revised)
        })
        val settings = EnrollmentSettings(context)
        assertFalse(settings.isUserIdentificationStudyAuthorized())
        assertFalse(userIdentificationMayRun(context))
        withStudyApi { method ->
            check(method == "reportCollectionAck")
            OK()
        }.use {
            assertTrue(onPersistenceWorker {
                coordinator.applyDecisions(setOf(module), emptySet(), ConsentTrigger.PARTICIPANT_TOGGLE)
            })
        }

        assertTrue(settings.isUserIdentificationStudyAuthorized())
        assertTrue(userIdentificationControlState(
            ResearchPersistenceGate.isActiveEnrollment(context), settings.isUserIdentificationStudyAuthorized(),
            settings.isUserIdentificationEnabled()).targetChoicesEnabled)
        assertTrue(userIdentificationMayRun(context))
        assertEquals(expected.studyDisclosureJson, owner.studyDisclosureJson)
        val result = onPersistenceWorker {
            TargetUserRouter
                .setTargetUser(context, DeviceUser.STUDY_PARTICIPANT, settings)
        }
        assertTrue(result.toString(), result is ModuleResult.Ok)
        assertEquals(DeviceUser.STUDY_PARTICIPANT.stored, settings.getCurrentUser())
    }

    @Test fun staleSettingsResponseCannotReopenADeclinedModule() {
        val expected = owner
        val generation = fence.settingsGeneration()
        decide(CollectionModuleId.BATTERY_TELEMETRY, false)
        assertEquals(SettingsApplicationOutcome.RETRY,
            onPersistenceWorker { coordinator.applyFetchedSettings(expected, generation, AndroidDataCollectionSetting()) })
        assertEquals(ParticipantDecision.DECLINED, CollectionLoopStore.of(context).loadAll()
            .getValue(CollectionModuleId.BATTERY_TELEMETRY).decision)
    }

    @Test fun retiredEnrollmentSettingsCannotEraseTheReplacementEnrollment() {
        val expected = owner
        val generation = fence.settingsGeneration()
        stopOnPersistenceWorker {
            fence.erase(CollectionModuleId.values().toSet(), durableIntent = false)
            db.uploadServerDao().delete(expected.id)
            db.uploadServerDao().insert(expected.copy(id = 0, participantId = "participant-B", createdAt = OffsetDateTime.now().toString()))
            prefs.edit().putString(PARTICIPANT_ID, "participant-B").commit()
            db.queueEntryData().insertEntry(row())
        }
        assertEquals(SettingsApplicationOutcome.RETRY,
            onPersistenceWorker { coordinator.applyFetchedSettings(expected, generation, AndroidDataCollectionSetting()) })
        assertEquals(1, db.queueEntryData().getSize())
    }

    @Test fun enrollmentMonitorCannotApplyRetiredStatusToAReplacementWithTheSameParticipantIds() {
        val expected = owner
        val applyStatus = { status: ParticipationStatus ->
            ResearchPersistenceGate.applyParticipationStatus(context, expected, fence.settingsGeneration(), status, emptyList())
        }
        WithdrawalStateStore(context).setState(WithdrawalState.PENDING)
        assertFalse(onPersistenceWorker { applyStatus(ParticipationStatus.ENROLLED) })
        stopOnPersistenceWorker {
            db.uploadServerDao().delete(expected.id)
            db.uploadServerDao().insert(expected.copy(id = 0, createdAt = OffsetDateTime.now().toString()))
            WithdrawalStateStore(context).setState(WithdrawalState.NONE)
        }
        assertFalse(onPersistenceWorker { applyStatus(ParticipationStatus.NOT_ENROLLED) })
        assertEquals(ParticipationStatus.ENROLLED, EnrollmentSettings(context).getParticipationStatus())
    }

    @Test fun pausedAndCompletedResumeEnqueuesAvailabilityRefreshOnce() {
        val apply = { status: ParticipationStatus ->
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), status, emptyList())
        }
        fun refreshes() = WorkManager.getInstance(context).getWorkInfosByTag(
            SensorSettingsRefreshWorker::class.java.name,
        ).get(5, TimeUnit.SECONDS).filterNot { it.state.isFinished }.map { it.id }
        for (status in listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)) {
            val previous = refreshes()
            assertTrue(onPersistenceWorker { apply(status) })
            assertFalse(ResearchPersistenceGate.isActiveEnrollment(context))
            assertTrue(ResearchPersistenceGate.isEnrolledIgnoringStatus(context))
            assertEquals(previous, refreshes())
            assertFalse(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration() - 1,
                    ParticipationStatus.ENROLLED, emptyList())
            })
            assertEquals(previous, refreshes())

            assertTrue(onPersistenceWorker { apply(ParticipationStatus.ENROLLED) })
            assertTrue(ResearchPersistenceGate.isActiveEnrollment(context))
            val resumed = refreshes()
            assertEquals("$status resume must enqueue the availability report", 1, resumed.size)
            assertNotEquals(previous, resumed)
            assertTrue(onPersistenceWorker { apply(ParticipationStatus.ENROLLED) })
            assertEquals("unchanged enrollment must not replace the pending report", resumed, refreshes())
        }
    }

    @Test fun pausedAndCompletedEnrollmentDrainsQueuedRowsButCannotCollect() {
        for (status in listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)) {
            db.queueEntryData().insertEntry(row())
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), status, emptyList())
            })
            assertNull(ResearchPersistenceGate.runIfActive(context) { "collection" })
            assertEquals(1, ResearchPersistenceGate.runIfEnrolled(context) {
                val queued = db.queueEntryData().getNextEntries(1)
                db.queueEntryData().deleteEntries(queued)
                queued.size
            })
            assertEquals(0, db.queueEntryData().getSize())
        }
        WithdrawalStateStore(context).setState(WithdrawalState.PENDING)
        assertNull(ResearchPersistenceGate.runIfEnrolled(context) { "upload" })
    }

    @Test fun resumedUsageExcludesPausedHistoryAndKeepsAlreadyQueuedRecords() {
        val module = CollectionModuleId.USAGE_EVENTS
        val initialScope = ResearchPersistenceGate.observationScope(context, module)!!
        val checkpoint = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), initialScope.key, initialScope.floorMillis)
        val lastPoll = System.currentTimeMillis()
        val minute = TimeUnit.MINUTES.toMillis(1)
        checkpoint.commitPollTimestamp(lastPoll)
        val unreadEvent = event(lastPoll + 2 * minute)
        val pausedEvent = event(lastPoll + 4 * minute)
        val retained = row()
        db.queueEntryData().insertEntry(retained)
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(),
                ParticipationStatus.PAUSED, listOf(CaptureWindow(utc(lastPoll + 4 * minute))))
        })
        // A skipped poll during the pause must leave the committed bookmark untouched.
        assertTrue(onPersistenceWorker { UsageModuleCollectionDelegate(context).execute() })
        assertEquals(lastPoll, checkpoint.readPreviousPollTimestamp())
        val resumedAt = lastPoll + 19 * minute
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(),
                ParticipationStatus.ENROLLED, listOf(CaptureWindow(utc(lastPoll + 4 * minute), utc(resumedAt))))
        })
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        val scope = ResearchPersistenceGate.observationScope(context, module)!!
        assertEquals(initialScope.key to initialScope.floorMillis, scope.key to scope.floorMillis)
        assertTrue(lastPoll + 4 * minute until resumedAt in scope.suspended)
        val resumedEvent = event(resumedAt)
        val resumedCheckpoint = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), scope.key, scope.floorMillis)
        val collector = UsageEventsCollectionModule(
            poller = object : UsageEventPoller {
                override fun poll(previousPollTimestamp: Long, currentPollTimestamp: Long,
                                  users: NavigableMap<Long, String>) =
                    ChronicleData(listOf(unreadEvent, pausedEvent, resumedEvent).filter {
                        it.timestamp.toInstant().toEpochMilli() in previousPollTimestamp until currentPollTimestamp
                    })
            },
            checkpointStore = resumedCheckpoint,
            previousPollTimestampFallback = { error("The unread bookmark must survive initialization") },
        )
        val outcome = requireNotNull(collector.pollWindow(
            TreeMap(), CollectionWindow(0, resumedAt + 2)))
        assertEquals(lastPoll, outcome.previousPollTimestamp)
        assertTrue(onPersistenceWorker {
            UsageModulePersistence.persist(listOf(QueueEntry(resumedAt, 2,
                JsonSerializer.serializeQueueEntry(outcome.events))), resumedAt + 2,
                ResearchPersistenceGate.usageSink(context), collector::commitCheckpoint, { db.runInTransaction(it) })
        })
        val entries = db.queueEntryData().getNextEntries(10)
        assertEquals(2, entries.size)
        assertArrayEquals(retained.data, entries.first().data)
        assertEquals(listOf(unreadEvent.timestamp, resumedEvent.timestamp),
            JsonSerializer.deserializeQueueEntry(entries.last().data)
                .filterIsInstance<ExtractedUsageEvent>().map { it.timestamp })
        assertEquals(resumedAt + 2, resumedCheckpoint.readPreviousPollTimestamp())
    }

    @Test fun forbiddenUploadRetriesAfterParticipationResumes() = forbiddenUploadRecovers {
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), ParticipationStatus.PAUSED, emptyList())
        })
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), ParticipationStatus.ENROLLED, emptyList())
        })
    }

    @Test fun forbiddenUploadRetriesAfterConsentResolution() = forbiddenUploadRecovers {
        consent(CollectionModuleId.BATTERY_TELEMETRY, ParticipantDecision.UNDECIDED)
        decide(CollectionModuleId.BATTERY_TELEMETRY, true)
    }
}
