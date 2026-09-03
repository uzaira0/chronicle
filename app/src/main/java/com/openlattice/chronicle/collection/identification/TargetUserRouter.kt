package com.openlattice.chronicle.collection.identification

import android.content.Context
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException

internal enum class TargetUserWriteRoute {
    MODULE,
    RESET_TO_UNASSIGNED,
    REJECT,
}

internal fun targetUserWriteRoute(
    studyAuthorized: Boolean,
    participantEnabled: Boolean,
    resettingToUnassigned: Boolean,
): TargetUserWriteRoute = when {
    !studyAuthorized -> TargetUserWriteRoute.REJECT
    participantEnabled -> TargetUserWriteRoute.MODULE
    resettingToUnassigned -> TargetUserWriteRoute.RESET_TO_UNASSIGNED
    else -> TargetUserWriteRoute.REJECT
}

/**
 * Routes a target-user write to either the Phase 7 module-manager path or the legacy
 * inline `EnrollmentSettings.setTargetUser` path.
 *
 * This is the single owner of the Phase 7A migration-switch decision. It lives in `:app`
 * (in the `collection.identification` package) precisely so that `preferences` does not
 * depend on `collection.*` — the previous back-edge. `EnrollmentSettings.setTargetUser`
 * is now the plain legacy write with no knowledge of the module path; callers route
 * through this object instead of calling `EnrollmentSettings.setTargetUser` directly
 * when the migration switch may apply.
 *
 * Behaviour is byte-for-byte identical to the pre-refactor `EnrollmentSettings.setTargetUser`:
 *
 *  - When [UserIdentificationMigration.USE_MODULE_MANAGER_USER_IDENTIFICATION_PATH] is
 *    `true` **and** user identification is enabled, the write routes through
 *    [UserIdentificationCollectionModule].
 *  - Otherwise (switch off, or the disable transition writing the `user_unassigned`
 *    "Not set" label while user identification is off) the write falls through to the
 *    legacy [EnrollmentSettings.setTargetUser] inline body. A disabled module is a no-op
 *    by contract and must not suppress the "Not set" write.
 *
 * The switch is now `true` (activated after parity), so the *enabled* write routes through
 * [UserIdentificationCollectionModule]; the disable→`user_unassigned` write still falls
 * through to the legacy path because the router gates the module path on the enabled flag.
 */
object TargetUserRouter {

    /**
     * Sets the device target user, choosing the module path or the legacy path per the
     * Phase 7A migration switch. [enrollmentSettings] is reused when the caller already
     * holds one; otherwise a fresh instance is constructed (identical to the historical
     * call sites that did `EnrollmentSettings(context).setTargetUser(...)`).
     */
    @Synchronized
    fun setTargetUser(
        context: Context,
        user: String,
        enrollmentSettings: EnrollmentSettings? = null,
    ): ModuleResult = try {
        // Construct inside the guarded block. Default-argument expressions are evaluated by the
        // caller before this method starts, which previously let local-store recovery failures
        // escape the router's error contract.
        val settings = enrollmentSettings ?: EnrollmentSettings(context)
        var result: ModuleResult = ModuleResult.Skipped("no active study enrollment")
        val persisted = ResearchPersistenceGate.persistIfActive(context) {
            // Re-read both scope layers inside the same withdrawal barrier as the actual write.
            // An Activity or notification that was already open cannot outlive study authorization,
            // a participant toggle-off, or a terminal withdrawal and then persist a late label.
            result = when (
                targetUserWriteRoute(
                    studyAuthorized = settings.isUserIdentificationStudyAuthorized(),
                    participantEnabled = settings.isUserIdentificationEnabled() &&
                        CollectionGate.collects(context, CollectionModuleId.USER_IDENTIFICATION),
                    resettingToUnassigned = user == context.getString(R.string.user_unassigned),
                )
            ) {
                TargetUserWriteRoute.MODULE -> {
                    if (UserIdentificationMigration.USE_MODULE_MANAGER_USER_IDENTIFICATION_PATH) {
                        UserIdentificationModuleHolder.get(context).setTargetUser(user)
                    } else {
                        settings.setTargetUser(user)
                        ModuleResult.Ok(1)
                    }
                }
                TargetUserWriteRoute.RESET_TO_UNASSIGNED -> {
                    settings.setTargetUser(user)
                    ModuleResult.Ok(1)
                }
                TargetUserWriteRoute.REJECT ->
                    ModuleResult.Skipped("user identification is outside the active study scope")
            }
        }
        if (persisted) result else ModuleResult.Skipped("no active study enrollment")
    } catch (error: LocalStoreRecoveryRequiredException) {
        throw error
    } catch (error: Exception) {
        ModuleResult.Failed(
            error,
            redactedMessage = "target-user write failed: ${error.javaClass.simpleName}",
        )
    }
}
