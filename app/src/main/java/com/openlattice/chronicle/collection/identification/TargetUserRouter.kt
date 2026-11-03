package com.openlattice.chronicle.collection.identification

import android.content.Context
import androidx.annotation.StringRes
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

/** Routes authorized user writes through the module; disabling can reset the label. */
object TargetUserRouter {

    fun failureMessage(result: ModuleResult): Int =
        if (result is ModuleResult.Skipped) R.string.user_identification_unavailable else R.string.user_save_failed

    /** What a screen shows for [user]; only [DeviceUser.stored] is ever written. */
    @StringRes
    fun label(user: DeviceUser): Int = when (user) {
        DeviceUser.STUDY_PARTICIPANT -> R.string.user_study_participant_label
        DeviceUser.SOMEONE_ELSE -> R.string.user_someone_else_label
        DeviceUser.NOT_SET -> R.string.user_unassigned_label
    }

    fun setTargetUser(
        context: Context,
        user: DeviceUser,
        enrollmentSettings: EnrollmentSettings? = null,
    ): ModuleResult = try {
        // Construct inside the guarded block. Default-argument expressions are evaluated by the
        // caller before this method starts, so local-store recovery failures would escape the
        // router's error contract.
        val settings = enrollmentSettings ?: EnrollmentSettings(context)
        var result: ModuleResult = ModuleResult.Skipped("no active study enrollment")
        val persisted = ResearchPersistenceGate.persistIfActive(context) {
            synchronized(this) {
                // Re-read both scope layers inside the same withdrawal barrier as the actual write.
                // An Activity or notification that was already open cannot outlive study authorization,
                // a participant toggle-off, or a terminal withdrawal and then persist a late label.
                result = when (
                    targetUserWriteRoute(
                        studyAuthorized = settings.isUserIdentificationStudyAuthorized(),
                        participantEnabled = settings.isUserIdentificationEnabled() &&
                            CollectionGate.collects(context, CollectionModuleId.USER_IDENTIFICATION),
                        resettingToUnassigned = user == DeviceUser.NOT_SET,
                    )
                ) {
                    TargetUserWriteRoute.MODULE -> UserIdentificationModuleHolder.get(context).setTargetUser(user.stored)
                    TargetUserWriteRoute.RESET_TO_UNASSIGNED -> {
                        settings.setTargetUser(user.stored)
                        ModuleResult.Ok(1)
                    }
                    TargetUserWriteRoute.REJECT ->
                        ModuleResult.Skipped("user identification is outside the active study scope")
                }
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
