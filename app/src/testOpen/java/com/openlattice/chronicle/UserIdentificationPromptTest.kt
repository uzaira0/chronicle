package com.openlattice.chronicle

import android.content.Context
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.ResearchPersistenceBarrier
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.ui.awaitDashboardUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserIdentificationPromptTest {
    @get:org.junit.Rule val workManagerRule = WorkManagerRule()

    @Test fun delayedChoiceCompletesOnTheRecreatedPrompt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AuditStores.install(context, enrolled = true)
        val lock = java.util.concurrent.locks.ReentrantReadWriteLock()
        onPersistenceWorker {
            ResearchPersistenceGate.initialize(context,
                ResearchPersistenceBarrier(lock))
        }
        val db = ChronicleDb.getInstance(context)
        val controller = Robolectric.buildActivity(UserIdentificationActivity::class.java).setup()
        lock.writeLock().lock()
        try {
            controller.get().findViewById<Button>(R.id.participant_user_btn).performClick()
            awaitDashboardUi { lock.hasQueuedThreads() }
            controller.recreate()
            lock.writeLock().unlock()
            awaitDashboardUi { controller.get().isFinishing }
            assertEquals(DeviceUser.STUDY_PARTICIPANT.stored, EnrollmentSettings(context).getCurrentUser())
            assertEquals(1, db.userQueueEntryData().getUserTimestamps().size)
        } finally {
            if (lock.isWriteLockedByCurrentThread) lock.writeLock().unlock()
            controller.pause().stop().destroy()
            db.close()
        }
    }

    @Test fun bothPromptButtonsUseTheCurrentLocalStoreAfterRecovery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val choices = listOf(
            R.id.participant_user_btn to DeviceUser.STUDY_PARTICIPANT,
            R.id.someone_else_user_btn to DeviceUser.SOMEONE_ELSE,
        )
        for ((button, user) in choices) {
            AuditStores.install(context, enrolled = true)
            onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
            val db = ChronicleDb.getInstance(context)
            val controller = Robolectric.buildActivity(UserIdentificationActivity::class.java).setup()
            val activity = controller.get()
            try {
                activity.findViewById<Button>(button).performClick()
                awaitDashboardUi { activity.isFinishing }
                assertEquals(user.stored, EnrollmentSettings(context).getCurrentUser())
                assertEquals(1, db.userQueueEntryData().getUserTimestamps().size)
            } finally { controller.pause().stop().destroy() }
            db.close()
        }
    }

    /** A prompt left open while the study ends (Pilot: participant withdrawn) writes no label when it is answered. */
    @Test fun aPromptLeftOpenAcrossTheEndOfTheStudyWritesNothing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        val db = ChronicleDb.getInstance(context)
        val before = onPersistenceWorker { db.userQueueEntryData().getUserTimestamps() }
        org.robolectric.shadows.ShadowToast.reset()
        val controller = Robolectric.buildActivity(UserIdentificationActivity::class.java).setup()
        try {
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(context, db.uploadServerDao().getConfiguredServer()!!,
                    ResearchErasureFence(context).settingsGeneration(),
                    ParticipationStatus.NOT_ENROLLED, emptyList())
            })
            controller.get().findViewById<Button>(R.id.participant_user_btn).performClick()
            awaitDashboardUi { org.robolectric.shadows.ShadowToast.getLatestToast() != null || controller.get().isFinishing }
            assertEquals(before, onPersistenceWorker { db.userQueueEntryData().getUserTimestamps() })
        } finally {
            controller.pause().stop().destroy()
            db.close()
        }
    }
}
