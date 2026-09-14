package com.openlattice.chronicle.services.sync

import android.content.Context
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.audio.AUDIO_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.audio.AudioUploadWorker
import com.openlattice.chronicle.collection.audio.scheduleAudioUploadWork
import com.openlattice.chronicle.collection.interaction.INTERACTION_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.interaction.InteractionUploadWorker

/**
 * The audio and interaction collectors are compiled into this source set for every distribution
 * that links the Google-services graph, but only a distribution with
 * `ALLOW_RESTRICTED_RESEARCH_PERMISSIONS` on ever runs them: OPEN never starts the accessibility
 * service or the notification listener, so scheduling their upload workers would fan out queues
 * the artifact does not collect for.
 */
internal fun restrictedAuxiliaryUploads(): List<AuxiliaryUploadDescriptor> =
    if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
        listOf(
            AuxiliaryUploadDescriptor(INTERACTION_UPLOAD_WORK_NAME, InteractionUploadWorker::class.java),
            AuxiliaryUploadDescriptor(AUDIO_UPLOAD_WORK_NAME, AudioUploadWorker::class.java),
        )
    } else {
        emptyList()
    }

internal fun scheduleRestrictedUploadWork(context: Context) {
    if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) return
    scheduleAudioUploadWork(context)
}
