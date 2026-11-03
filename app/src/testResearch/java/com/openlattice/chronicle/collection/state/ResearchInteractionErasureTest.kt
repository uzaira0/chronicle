package com.openlattice.chronicle.collection.state

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.interaction.InteractionCollectionService
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.DeviceStateSampler
import com.openlattice.chronicle.services.lifecycle.persistLifecycleEntries
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.interactionSampleDao
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric

class ResearchInteractionErasureTest : ResearchErasureTestFixture() {
    @Test fun interactionErasurePreventsDeclinedScrollAndPreviousEpisodeFromEnteringReacceptedRows() {
        val policy = InteractionPolicySettings(context)
        assertTrue(policy.save(UUID.fromString(owner.studyId), 1, true, InteractionPolicy.DEFAULT))
        val service = Robolectric.buildService(InteractionCollectionService::class.java).create(
            ).get()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        try {
            service.onAccessibilityEvent(scroll(100, 10))
            awaitInteractionRows(1)
            val initial = db.interactionSampleDao().getOldest(10).single()
            decide(CollectionModuleId.INTERACTION_EVENTS, false)
            service.onAccessibilityEvent(scroll(200, -10))
            assertEquals(0, db.interactionSampleDao().count())
            decide(CollectionModuleId.INTERACTION_EVENTS, true)
            service.onAccessibilityEvent(scroll(300, -10))
            awaitInteractionRows(1)
            val accepted = db.interactionSampleDao().getOldest(10).single()
            assertNotEquals(initial.episodeId, accepted.episodeId)
            assertNull(accepted.dwellMillisSincePrev)
            assertNull(accepted.scrollReversed)
        } finally { service.onDestroy() }
    }

    @Test fun unchangedInteractionPolicyPreservesItsPublishedGeneration() {
        val policy = InteractionPolicySettings(context)
        val study = UUID.fromString(owner.studyId)
        assertTrue(policy.save(study, 1, true, InteractionPolicy.DEFAULT))
        val observed = policy.currentSnapshot()!!
        InteractionPolicySettings(context)
        assertTrue(policy.save(study, 1, true, InteractionPolicy.DEFAULT))
        assertTrue(policy.isCurrent(observed))
    }

    @Test fun queuedAcceptedInteractionSurvivesAnIdenticalSettingsPublication() {
        queuedInteraction(erase = false, expectedRows = 1)
    }

    @Test fun erasedQueuedInteractionCannotReappearAfterReacceptance() {
        queuedInteraction(erase = true, expectedRows = 0)
    }

    @Test fun acceptedCallbackMayFinishDuringHoldWhileNewObservationsRemainClosed() {
        val module = CollectionModuleId.NOTIFICATION_ACTIVITY
        val origin = ResearchPersistenceGate.captureObservation(context, module)
        val expected = owner
        consent(module, ParticipantDecision.ACCEPTED, enabled = false)
        assertFalse(ResearchPersistenceGate.captureObservation(context, module).isCurrent())
        assertTrue(origin.persist {
            assertTrue(ResearchPersistenceGate.persistIfCollecting(context, ResearchWriteRequest(moduleId = module, expectedOwner = expected)) {
                db.queueEntryData().insertEntry(row())
            })
        })
        assertEquals(1, db.queueEntryData().getSize())
    }

    @Test fun lifecycleSamplerCannotWriteDeclinedStateAndDiscardClearsItsPreferences() {
        val events = DeviceStateSampler(context).poll()
        persistLifecycleEntries(db, listOf(
            QueueEntry(System.currentTimeMillis(), 1, JsonSerializer.serializeQueueEntry(events))))
        assertNotNull(db.lifecycleStateDao().get("network_state"))
        decide(CollectionModuleId.DEVICE_LIFECYCLE, false)
        DeviceStateSampler(context).poll()
        assertNull(db.lifecycleStateDao().get("network_state"))
        assertTrue(context.getSharedPreferences("chronicle_device_state", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(context.getSharedPreferences("chronicle_lifecycle_recorder", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test fun lifecycleObservationQueuedBeforeErasureCannotPersistAfterReacceptance() {
        onPersistenceWorker {
            ResearchPersistenceGate.stop {
                DeviceLifecycleEventRecorder.recordObserved(context) {
                    listOfNotNull(DeviceLifecycleEventRecorder.lowMemoryEvent(10))
                }
                decideOnWorker(CollectionModuleId.DEVICE_LIFECYCLE, false)
                decideOnWorker(CollectionModuleId.DEVICE_LIFECYCLE, true)
            }
        }
        val drained = CountDownLatch(1)
        DeviceLifecycleEventRecorder.recordObserved(context) { drained.countDown(); emptyList() }
        assertTrue(drained.await(5, TimeUnit.SECONDS))
        assertEquals(0, db.queueEntryData().getSize())
    }
}
