package com.openlattice.chronicle.collection.state

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.services.upload.*
import com.openlattice.chronicle.storage.UploadServerEntity

/** Persisted provider failures supplement OS permission checks; each recovery ends its episode. */
internal object CaptureRegistrationStatus {
    private const val PREFS = "capture_registration_status"
    fun failedModules(context: Context): Set<CollectionModuleId> {
        val owner = ResearchPersistenceGate.captureOwner(context) ?: return emptySet()
        val scope = ResearchErasureFence.enrollmentKey(owner)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return setOf(CollectionModuleId.SLEEP, CollectionModuleId.ACTIVITY_RECOGNITION)
            .filterTo(linkedSetOf()) { prefs.getString(it.id, null) == scope }
    }
    fun recordOutcome(context: Context, owner: UploadServerEntity?, module: CollectionModuleId, success: Boolean) {
        if (owner == null) return
        ResearchPersistenceGate.runIfExpectedOwner(context, owner) {
            if (CollectionLoopStore.of(context).loadAll()[module]?.phase != CollectionModulePhase.ACTIVE) return@runIfExpectedOwner false
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            check(prefs.edit().apply {
                if (success) remove(module.id) else putString(module.id, ResearchErasureFence.enrollmentKey(owner))
            }.commit())
            val family = if (module == CollectionModuleId.SLEEP) LocalUploadModuleFamily.SLEEP else LocalUploadModuleFamily.ACTIVITY_RECOGNITION
            recordAccessEpisodes(LocalUploadDiagnosticsStore.of(context),
                context.getSharedPreferences("capture_registration_episode_${module.id}", Context.MODE_PRIVATE),
                "${owner.studyId}:${owner.participantId}", if (success) emptySet() else setOf(family), setOf(family))
            true
        }
    }
}
