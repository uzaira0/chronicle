package com.openlattice.chronicle.services.sync

import android.content.Context
import com.openlattice.chronicle.collection.audio.AUDIO_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.audio.AudioUploadWorker
import com.openlattice.chronicle.collection.audio.scheduleAudioUploadWork
import com.openlattice.chronicle.collection.interaction.INTERACTION_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.interaction.InteractionUploadWorker

/** Restricted collectors are linked only into controlled research/open distributions. */
internal fun restrictedAuxiliaryUploads(): List<AuxiliaryUploadDescriptor> = listOf(
    AuxiliaryUploadDescriptor(INTERACTION_UPLOAD_WORK_NAME, InteractionUploadWorker::class.java),
    AuxiliaryUploadDescriptor(AUDIO_UPLOAD_WORK_NAME, AudioUploadWorker::class.java),
)

internal fun scheduleRestrictedUploadWork(context: Context) {
    scheduleAudioUploadWork(context)
}
