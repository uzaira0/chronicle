package com.openlattice.chronicle.collection

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.activity.ActivityRecognitionIntegration
import com.openlattice.chronicle.collection.activity.ActivityRecognitionModuleHolder
import com.openlattice.chronicle.collection.activity.SleepModuleHolder
import com.openlattice.chronicle.collection.activity.toAndroidActivityRecognitionEvent
import com.openlattice.chronicle.collection.activity.toAndroidSleepEvent
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate
import com.openlattice.chronicle.collection.device.AppNetworkUsageModuleHolder
import com.openlattice.chronicle.collection.device.HealthMetricModuleHolder
import com.openlattice.chronicle.collection.device.pullExpansionModule
import com.openlattice.chronicle.collection.device.toAndroidHealthMetricEvent
import com.openlattice.chronicle.collection.device.toAndroidAppNetworkUsageEvent
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.services.upload.RestrictedUploadApiFactory
import com.openlattice.chronicle.services.upload.RestrictedPendingUploadCounts
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.activityRecognitionSampleDao
import com.openlattice.chronicle.storage.audioActivitySampleDao
import com.openlattice.chronicle.storage.audioContentSampleDao
import com.openlattice.chronicle.storage.healthMetricSampleDao
import com.openlattice.chronicle.storage.interactionSampleDao
import com.openlattice.chronicle.storage.notificationActivitySampleDao
import com.openlattice.chronicle.storage.sleepSampleDao

/** Research/Open-only operational contributions, excluded from minimal public source sets. */
internal object DistributionCollectionContributions {
    private const val TAG = "DistributionModules"

    val moduleSuppliers: Map<CollectionModuleId, (Context) -> DataCollectionModule> = buildMap {
        put(CollectionModuleId.APP_NETWORK_USAGE) { context -> AppNetworkUsageModuleHolder.get(context) }
        if (BuildConfig.HAS_HEALTH_CONNECT) {
            put(CollectionModuleId.HEALTH_CONNECT) { context -> HealthMetricModuleHolder.get(context) }
        }
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            put(CollectionModuleId.SLEEP) { context -> SleepModuleHolder.get(context) }
            put(CollectionModuleId.ACTIVITY_RECOGNITION) { context ->
                ActivityRecognitionModuleHolder.get(context)
            }
        }
    }

    fun collectAdditionalSamples(
        context: Context,
        schedule: ExpansionPullSchedule?,
        nowMs: Long,
    ) {
        pullExpansionModule(CollectionModuleId.APP_NETWORK_USAGE, schedule, nowMs) {
            AppNetworkUsageModuleHolder.get(context).sample()
        }
        if (BuildConfig.HAS_HEALTH_CONNECT) {
            pullExpansionModule(CollectionModuleId.HEALTH_CONNECT, schedule, nowMs) {
                HealthMetricModuleHolder.get(context).sample()
            }
        }
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            runCatching { ActivityRecognitionIntegration.ensureRegistration(context) }
                .onFailure { Log.w(TAG, "Sleep/activity registration refresh failed", it) }
        }
    }

    fun uploadAdditionalStreams(
        delegate: ExpansionUploadWorkerDelegate,
        servers: List<UploadServerEntity>,
    ): Int {
        var failures = 0
        failures += delegate.uploadStream(
            servers,
            getOldest = { delegate.db.appNetworkUsageSampleDao().getOldest(it) },
            idOf = { it.id },
            toDto = { it.toAndroidAppNetworkUsageEvent() },
            payloadType = EncryptedPayloadType.APP_NETWORK_USAGE,
            deleteByIds = { delegate.db.appNetworkUsageSampleDao().deleteByIds(it) },
            plainUpload = { studyId, server, events ->
                RestrictedUploadApiFactory.get(
                    server.url,
                    server.mobileSigningSecretOverride,
                ).uploadAndroidAppNetworkUsageData(
                    studyId,
                    server.participantId,
                    server.sourceDeviceId,
                    server.apiKey,
                    events,
                )
            },
            label = "app_network_usage",
        )
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            failures += delegate.uploadStream(
                servers,
                getOldest = { delegate.db.sleepSampleDao().getOldest(it) },
                idOf = { it.id },
                toDto = { it.toAndroidSleepEvent() },
                payloadType = EncryptedPayloadType.SLEEP,
                deleteByIds = { delegate.db.sleepSampleDao().deleteByIds(it) },
                plainUpload = { studyId, server, events ->
                    RestrictedUploadApiFactory.get(
                        server.url,
                        server.mobileSigningSecretOverride,
                    ).uploadAndroidSleepData(
                        studyId,
                        server.participantId,
                        server.sourceDeviceId,
                        server.apiKey,
                        events,
                    )
                },
                label = "sleep",
            )
            failures += delegate.uploadStream(
                servers,
                getOldest = { delegate.db.activityRecognitionSampleDao().getOldest(it) },
                idOf = { it.id },
                toDto = { it.toAndroidActivityRecognitionEvent() },
                payloadType = EncryptedPayloadType.ACTIVITY_RECOGNITION,
                deleteByIds = { delegate.db.activityRecognitionSampleDao().deleteByIds(it) },
                plainUpload = { studyId, server, events ->
                    RestrictedUploadApiFactory.get(
                        server.url,
                        server.mobileSigningSecretOverride,
                    ).uploadAndroidActivityRecognitionData(
                        studyId,
                        server.participantId,
                        server.sourceDeviceId,
                        server.apiKey,
                        events,
                    )
                },
                label = "activity_recognition",
            )
        }
        if (BuildConfig.HAS_HEALTH_CONNECT) {
            failures += delegate.uploadStream(
                servers,
                getOldest = { delegate.db.healthMetricSampleDao().getOldest(it) },
                idOf = { it.id },
                toDto = { it.toAndroidHealthMetricEvent() },
                payloadType = EncryptedPayloadType.HEALTH_CONNECT,
                deleteByIds = { delegate.db.healthMetricSampleDao().deleteByIds(it) },
                plainUpload = { studyId, server, events ->
                    RestrictedUploadApiFactory.get(
                        server.url,
                        server.mobileSigningSecretOverride,
                    ).uploadAndroidHealthMetricData(
                        studyId,
                        server.participantId,
                        server.sourceDeviceId,
                        server.apiKey,
                        events,
                    )
                },
                label = "health_connect",
            )
        }
        return failures
    }

    fun purgeAdditionalSamples(db: ChronicleDb, cutoff: String): Int {
        val activityPurged = if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            db.sleepSampleDao().deleteOlderThan(cutoff) +
                db.activityRecognitionSampleDao().deleteOlderThan(cutoff)
        } else {
            0
        }
        val healthPurged = if (BuildConfig.HAS_HEALTH_CONNECT) {
            db.healthMetricSampleDao().deleteOlderThan(cutoff)
        } else {
            0
        }
        return activityPurged + healthPurged + db.appNetworkUsageSampleDao().deleteOlderThan(cutoff)
    }

    fun pendingUploadCounts(db: ChronicleDb): RestrictedPendingUploadCounts =
        RestrictedPendingUploadCounts(
            interactionEvents = db.interactionSampleDao().count(),
            audioActivity = db.audioActivitySampleDao().count(),
            audioContent = db.audioContentSampleDao().count(),
            notificationActivity = db.notificationActivitySampleDao().count(),
            sleep = db.sleepSampleDao().count(),
            activityRecognition = db.activityRecognitionSampleDao().count(),
            healthMetrics = if (BuildConfig.HAS_HEALTH_CONNECT) {
                db.healthMetricSampleDao().count()
            } else {
                0
            },
        )
}
