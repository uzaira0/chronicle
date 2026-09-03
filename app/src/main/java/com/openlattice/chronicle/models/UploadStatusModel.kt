package com.openlattice.chronicle.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.openlattice.chronicle.services.upload.COMBINED_UPLOAD_IMMEDIATE_WORK_NAME
import com.openlattice.chronicle.services.upload.COMBINED_UPLOAD_WORK_NAME

class UploadStatusModel(application: Application) : AndroidViewModel(application) {
    // Context-based getInstance: required now that WorkManager uses on-demand
    // initialization (the deprecated no-arg form throws when nothing initialized it yet).
    private val workManager = WorkManager.getInstance(application)

    // Observe both periodic automatic upload and manual Upload Now work.
    internal val outputWorkInfo: LiveData<List<WorkInfo>> = MediatorLiveData<List<WorkInfo>>().apply {
        addSource(workManager.getWorkInfosForUniqueWorkLiveData(COMBINED_UPLOAD_WORK_NAME)) { value = it }
        addSource(workManager.getWorkInfosForUniqueWorkLiveData(COMBINED_UPLOAD_IMMEDIATE_WORK_NAME)) { value = it }
    }
}
