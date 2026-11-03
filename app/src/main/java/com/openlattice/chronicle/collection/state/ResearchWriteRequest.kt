package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.storage.UploadServerEntity

internal class ResearchWriteRequest(
    val moduleId: CollectionModuleId?,
    val records: Int = 1,
    val expectedOwner: UploadServerEntity?,
    val onRefused: (Int) -> Unit = {},
)
