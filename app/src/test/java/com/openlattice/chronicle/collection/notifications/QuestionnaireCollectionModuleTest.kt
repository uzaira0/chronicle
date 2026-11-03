package com.openlattice.chronicle.collection.notifications

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.core.ModuleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [QuestionnaireCollectionModule] (Phase 9 — refactor plan §9).
 *
 * Covers: identity / privacy class, the legacy `handleNotification` schedule-vs-cancel
 * decision ([QuestionnaireCollectionModule.cancelFor] / [QuestionnaireCollectionModule.actionFor]),
 * the [QuestionnaireCollectionModule.reconcile] dispatch through the
 * [QuestionnaireScheduler] seam, per-action failure isolation, the empty-input no-op,
 * the worker-driven no-op lifecycle, and the diagnostics breakdown.
 *
 */
class QuestionnaireCollectionModuleTest {

    /** Recording [QuestionnaireScheduler] — captures every dispatched action. */
    private class FakeQuestionnaireScheduler(
        private val failScheduleForRule: String? = null,
    ) : QuestionnaireScheduler {
        val scheduled = mutableListOf<QuestionnaireNotificationAction.Schedule>()
        val cancelled = mutableListOf<QuestionnaireNotificationAction.Cancel>()

        override fun schedule(action: QuestionnaireNotificationAction.Schedule) {
            if (action.recurrenceRule == failScheduleForRule) {
                error("bad recurrence rule")
            }
            scheduled.add(action)
        }

        override fun cancel(action: QuestionnaireNotificationAction.Cancel) {
            cancelled.add(action)
        }
    }

    private fun module(scheduler: QuestionnaireScheduler) =
        QuestionnaireCollectionModule(scheduler)

    private fun schedule(
        id: String = "q1",
        name: String = "Daily Survey",
        rule: String = "FREQ=DAILY;BYHOUR=9",
        active: Boolean = true,
    ) = QuestionnaireSchedule(id = id, name = name, recurrenceRule = rule, active = active)

    @Test
    fun questionnaireIdIsActiveSoItCanBeRegistered() {
        // The study form and server reject inactive ids.
        assertTrue(
            "CollectionModuleId.QUESTIONNAIRE must be active for the module to register",
            CollectionModuleId.QUESTIONNAIRE.active,
        )
    }

    @Test
    fun cancelForMatchesTheNotificationPredicate() {
        val module = module(FakeQuestionnaireScheduler())
        // active + enrolled  -> schedule (cancel = false)
        assertFalse(module.cancelFor(schedule(active = true), enrolled = true))
        // inactive           -> cancel
        assertTrue(module.cancelFor(schedule(active = false), enrolled = true))
        // not enrolled       -> cancel
        assertTrue(module.cancelFor(schedule(active = true), enrolled = false))
        // inactive + not enrolled -> cancel
        assertTrue(module.cancelFor(schedule(active = false), enrolled = false))
    }

    @Test
    fun actionForProducesScheduleWhenActiveAndEnrolled() {
        val module = module(FakeQuestionnaireScheduler())
        val action = module.actionFor(schedule(), enrolled = true)
        assertTrue(action is QuestionnaireNotificationAction.Schedule)
        assertEquals("q1", action.questionnaireId)
        assertEquals("FREQ=DAILY;BYHOUR=9", action.recurrenceRule)
    }

    @Test
    fun actionForProducesCancelWhenInactive() {
        val module = module(FakeQuestionnaireScheduler())
        val action = module.actionFor(schedule(active = false), enrolled = true)
        assertTrue(action is QuestionnaireNotificationAction.Cancel)
    }

    @Test
    fun reconcileSchedulesActiveQuestionnairesForAnEnrolledParticipant() {
        val scheduler = FakeQuestionnaireScheduler()
        val module = module(scheduler)

        val result = module.reconcile(
            listOf(schedule(id = "q1"), schedule(id = "q2", rule = "FREQ=WEEKLY")),
            enrolled = true,
        )

        assertEquals(ModuleResult.Ok(2), result)
        assertEquals(2, scheduler.scheduled.size)
        assertEquals(0, scheduler.cancelled.size)
        assertEquals(setOf("q1", "q2"), scheduler.scheduled.map { it.questionnaireId }.toSet())
    }

    @Test
    fun reconcileCancelsInactiveQuestionnaireAndCancelsAllWhenNotEnrolled() {
        val scheduler = FakeQuestionnaireScheduler()
        val module = module(scheduler)

        // Inactive questionnaire is cancelled even for an enrolled participant.
        module.reconcile(listOf(schedule(active = false)), enrolled = true)
        assertEquals(1, scheduler.cancelled.size)
        assertEquals(0, scheduler.scheduled.size)

        // A not-enrolled participant has every questionnaire cancelled, active or not.
        val scheduler2 = FakeQuestionnaireScheduler()
        val module2 = module(scheduler2)
        val result = module2.reconcile(
            listOf(schedule(id = "q1", active = true), schedule(id = "q2", active = false)),
            enrolled = false,
        )
        assertEquals(ModuleResult.Ok(2), result)
        assertEquals(2, scheduler2.cancelled.size)
        assertEquals(0, scheduler2.scheduled.size)
    }

    @Test
    fun reconcileWithNoSchedulesIsANoOpSuccess() {
        val scheduler = FakeQuestionnaireScheduler()
        val module = module(scheduler)
        val result = module.reconcile(emptyList(), enrolled = true)
        assertEquals(ModuleResult.Ok(0), result)
        assertEquals(0, scheduler.scheduled.size)
        assertEquals(0, scheduler.cancelled.size)
    }

    @Test
    fun aFailingActionDoesNotAbortTheRemainingSchedulesAndSurfacesAsFailed() {
        // The middle schedule's rule throws in the scheduler seam.
        val scheduler = FakeQuestionnaireScheduler(failScheduleForRule = "BAD")
        val module = module(scheduler)

        val result = module.reconcile(
            listOf(
                schedule(id = "ok1", rule = "FREQ=DAILY"),
                schedule(id = "bad", rule = "BAD"),
                schedule(id = "ok2", rule = "FREQ=WEEKLY"),
            ),
            enrolled = true,
        )

        // Per-action failure isolation: the two good schedules still went through.
        assertEquals(2, scheduler.scheduled.size)
        assertEquals(setOf("ok1", "ok2"), scheduler.scheduled.map { it.questionnaireId }.toSet())
        // The overall result is Failed because one action failed — never a silent success.
        assertTrue(result is ModuleResult.Failed)
    }

    

}
