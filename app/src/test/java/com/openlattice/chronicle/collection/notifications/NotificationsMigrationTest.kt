package com.openlattice.chronicle.collection.notifications

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Phase 9 migration-switch value (refactor plan §9, design §1C.4 —
 * mirrors `UsageWorkerMigrationTest` / `LifecycleWorkerMigrationTest` /
 * `UploadTelemetryMigrationTest`).
 *
 * The module-manager questionnaire-notification path is now ACTIVE in production: its
 * parity tests pass and the switch has been deliberately flipped on. This test fails if
 * [NotificationsMigration.USE_MODULE_MANAGER_QUESTIONNAIRE_PATH] is ever flipped back to
 * `false` without a deliberate, separately-reviewed change — it pins the activated
 * module-manager questionnaire-loop behaviour for `NotificationsWorker`.
 *
 */
class NotificationsMigrationTest {

    @Test
    fun moduleManagerQuestionnairePathIsActiveSoTheWorkerUsesTheModulePath() {
        assertTrue(
            "NotificationsMigration.USE_MODULE_MANAGER_QUESTIONNAIRE_PATH must be " +
                "true — the module-manager questionnaire path is active (refactor plan §9).",
            NotificationsMigration.USE_MODULE_MANAGER_QUESTIONNAIRE_PATH,
        )
    }
}
