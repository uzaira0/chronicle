package com.openlattice.chronicle.services.sync

import android.content.Context

/**
 * The public Play/Amazon distributions have no restricted collector graph. Keeping this no-op
 * implementation in the distribution source set means those variants cannot compile against the
 * audio or interaction collector projects.
 */
internal fun restrictedAuxiliaryUploads(): List<AuxiliaryUploadDescriptor> = emptyList()

internal fun scheduleRestrictedUploadWork(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
