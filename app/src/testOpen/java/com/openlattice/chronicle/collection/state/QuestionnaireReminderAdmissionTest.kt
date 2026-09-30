package com.openlattice.chronicle.collection.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * QUESTIONNAIRE is a non-choice module: it never has a consent decision, so reminder admission
 * must come from the enrollment manifest, not the accepted-module set (build 64 dropped every
 * reminder). Owner, withdrawal and erasure fences still refuse.
 */
@RunWith(RobolectricTestRunner::class)
class QuestionnaireReminderAdmissionTest {
    @get:org.junit.Rule val backgroundPersistence = BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun enroll(questionnaireEnabled: Boolean) {
        TestStores.install(
            context,
            enrolled = true,
            manifestOverrides = mapOf(CollectionModuleId.QUESTIONNAIRE to CollectionModuleSetting(enabled = questionnaireEnabled)),
        )
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(context)
    }

    private fun admitted(): Boolean {
        var posted = false
        val token = ResearchPersistenceGate.captureStudyEnabledObservation(context, CollectionModuleId.QUESTIONNAIRE)
        return token.persist { posted = true } && posted
    }

    @Test fun activeEnrollmentWithQuestionnaireEnabledIsAdmitted() {
        enroll(questionnaireEnabled = true)
        assertTrue(admitted())
    }

    @Test fun studyWithoutQuestionnaireIsRefused() {
        enroll(questionnaireEnabled = false)
        assertFalse(admitted())
    }

    @Test fun withdrawalAfterCaptureIsRefused() {
        enroll(questionnaireEnabled = true)
        val token = ResearchPersistenceGate.captureStudyEnabledObservation(context, CollectionModuleId.QUESTIONNAIRE)
        WithdrawalStateStore(context).setState(WithdrawalState.PENDING)
        assertFalse(token.persist { error("must not post") })
    }

    @Test fun pendingErasureAfterCaptureIsRefused() {
        enroll(questionnaireEnabled = true)
        val token = ResearchPersistenceGate.captureStudyEnabledObservation(context, CollectionModuleId.QUESTIONNAIRE)
        context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit()
            .putStringSet("pending", setOf(CollectionModuleId.USAGE_EVENTS.id)).commit()
        assertFalse(token.persist { error("must not post") })
    }
}
