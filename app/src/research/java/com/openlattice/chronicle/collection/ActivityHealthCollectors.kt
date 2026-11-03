package com.openlattice.chronicle.collection

import android.content.Context
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import com.openlattice.chronicle.collection.activity.ActivityRecognitionIntegration
import com.openlattice.chronicle.collection.activity.SleepActivityCaptureController
import com.openlattice.chronicle.collection.activity.toAndroidActivityRecognitionEvent
import com.openlattice.chronicle.collection.activity.toAndroidSleepEvent
import com.openlattice.chronicle.collection.device.AndroidHealthMetricSource
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate
import com.openlattice.chronicle.collection.device.HealthConnectPermissions
import com.openlattice.chronicle.collection.device.HealthMetricModuleHolder
import com.openlattice.chronicle.collection.device.pullExpansionModule
import com.openlattice.chronicle.collection.device.toAndroidHealthMetricEvent
import com.openlattice.chronicle.services.upload.DedicatedUploadSource
import com.openlattice.chronicle.services.upload.RestrictedUploadApiFactory
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.activityRecognitionSampleDao
import com.openlattice.chronicle.storage.healthMetricSampleDao
import com.openlattice.chronicle.storage.sleepSampleDao

/** Sleep, activity recognition and Health Connect: the research build is the only one that links their SDKs. */
internal object ActivityHealthCollectors : ActivityHealthCollection {
    private const val TAG = "ActivityHealthCollectors"

    override fun eraseActivityRegistration(context: Context, module: CollectionModuleId) =
        SleepActivityCaptureController.eraseRegistration(context, module)

    override fun eraseHealthSource(context: Context) = AndroidHealthMetricSource.clearCheckpoint(context)

    override fun activityRecognitionAvailable(context: Context): Boolean = ActivityRecognitionIntegration.isAvailable(context)

    override fun unregisterActivityRecognition(context: Context) = ActivityRecognitionIntegration.unregisterAll(context)

    override fun healthConnectAvailable(context: Context): Boolean = HealthConnectPermissions.isAvailable(context)

    override fun healthConnectGranted(context: Context): Boolean = HealthConnectPermissions.allGranted(context)

    override fun healthPermissionsToRequest(context: Context, configured: Set<HealthConnectRecordType>): Set<String> =
        HealthConnectPermissions.permissionsToRequest(context, configured)

    override fun healthPermissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        HealthConnectPermissions.requestContract()

    override fun collect(context: Context, schedule: ExpansionPullSchedule?, nowMs: Long): Boolean {
        val health = pullExpansionModule(CollectionModuleId.HEALTH_CONNECT, schedule, nowMs) {
            HealthMetricModuleHolder.get(context).sample()
        }
        runCatching { ActivityRecognitionIntegration.ensureRegistration(context) }
            .onFailure { Log.w(TAG, "Sleep/activity registration refresh failed", it) }
        return health
    }

    override fun uploadStreams(
        delegate: ExpansionUploadWorkerDelegate,
        servers: List<UploadServerEntity>,
    ): Map<UploadQueueTable, () -> Int> = linkedMapOf(
        UploadQueueTable.SLEEP to {
            delegate.uploadStream(
                servers,
                source = DedicatedUploadSource(
                    elementType = AndroidSleepEvent::class.java,
                    getOldest = { delegate.db.sleepSampleDao().getOldest(it) },
                    idOf = { it.id },
                    toDto = { it.toAndroidSleepEvent() },
                    table = UploadQueueTable.SLEEP,
                    deleteByIds = { delegate.db.sleepSampleDao().deleteByIds(it) },
                    plainUpload = { studyId, server, headers, body ->
                        RestrictedUploadApiFactory.get(server.url)
                            .uploadAndroidSleepData(studyId, server.participantId, headers, body)
                    },
                ),
            )
        },
        UploadQueueTable.ACTIVITY_RECOGNITION to {
            delegate.uploadStream(
                servers,
                source = DedicatedUploadSource(
                    elementType = AndroidActivityRecognitionEvent::class.java,
                    getOldest = { delegate.db.activityRecognitionSampleDao().getOldest(it) },
                    idOf = { it.id },
                    toDto = { it.toAndroidActivityRecognitionEvent() },
                    table = UploadQueueTable.ACTIVITY_RECOGNITION,
                    deleteByIds = { delegate.db.activityRecognitionSampleDao().deleteByIds(it) },
                    plainUpload = { studyId, server, headers, body ->
                        RestrictedUploadApiFactory.get(server.url)
                            .uploadAndroidActivityRecognitionData(studyId, server.participantId, headers, body)
                    },
                ),
            )
        },
        UploadQueueTable.HEALTH_CONNECT to {
            delegate.uploadStream(
                servers,
                source = DedicatedUploadSource(
                    elementType = AndroidHealthMetricEvent::class.java,
                    getOldest = { delegate.db.healthMetricSampleDao().getOldest(it) },
                    idOf = { it.id },
                    toDto = { it.toAndroidHealthMetricEvent() },
                    table = UploadQueueTable.HEALTH_CONNECT,
                    deleteByIds = { delegate.db.healthMetricSampleDao().deleteByIds(it) },
                    plainUpload = { studyId, server, headers, body ->
                        RestrictedUploadApiFactory.get(server.url)
                            .uploadAndroidHealthMetricData(studyId, server.participantId, headers, body)
                    },
                ),
            )
        },
    )
}
