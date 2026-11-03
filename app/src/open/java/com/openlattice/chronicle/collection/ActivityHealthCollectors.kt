package com.openlattice.chronicle.collection

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity

/** The open build links neither Play services location nor Health Connect: sleep, activity and health are absent. */
internal object ActivityHealthCollectors : ActivityHealthCollection {
    override fun eraseActivityRegistration(context: Context, module: CollectionModuleId) =
        UnlinkedActivityHealth.eraseActivityRegistration(context, module)

    override fun eraseHealthSource(context: Context) = UnlinkedActivityHealth.eraseHealthSource(context)

    override fun activityRecognitionAvailable(context: Context): Boolean = false

    override fun unregisterActivityRecognition(context: Context) = Unit

    override fun healthConnectAvailable(context: Context): Boolean = false

    override fun healthConnectGranted(context: Context): Boolean = false

    override fun healthPermissionsToRequest(context: Context, configured: Set<HealthConnectRecordType>): Set<String> = emptySet()

    override fun healthPermissionContract(): ActivityResultContract<Set<String>, Set<String>> = UnlinkedActivityHealth.permissionContract

    override fun collect(context: Context, schedule: ExpansionPullSchedule?, nowMs: Long): Boolean = true

    override fun uploadStreams(
        delegate: ExpansionUploadWorkerDelegate,
        servers: List<UploadServerEntity>,
    ): Map<UploadQueueTable, () -> Int> = emptyMap()
}
