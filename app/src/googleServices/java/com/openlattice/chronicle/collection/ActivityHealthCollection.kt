package com.openlattice.chronicle.collection

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity

/** Sleep, activity recognition and Health Connect; each flavor's `ActivityHealthCollectors` provides it. */
internal interface ActivityHealthCollection {
    fun eraseActivityRegistration(context: Context, module: CollectionModuleId)

    fun eraseHealthSource(context: Context)

    fun activityRecognitionAvailable(context: Context): Boolean

    fun unregisterActivityRecognition(context: Context)

    fun healthConnectAvailable(context: Context): Boolean

    fun healthConnectGranted(context: Context): Boolean

    fun healthPermissionsToRequest(context: Context, configured: Set<HealthConnectRecordType>): Set<String>

    fun healthPermissionContract(): ActivityResultContract<Set<String>, Set<String>>

    /** Pulls Health Connect and refreshes the sleep/activity registrations; false when the pull must retry. */
    fun collect(context: Context, schedule: ExpansionPullSchedule?, nowMs: Long): Boolean

    fun uploadStreams(
        delegate: ExpansionUploadWorkerDelegate,
        servers: List<UploadServerEntity>,
    ): Map<UploadQueueTable, () -> Int>
}
