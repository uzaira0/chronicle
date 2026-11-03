package com.openlattice.chronicle.collection

import android.content.Context
import com.openlattice.chronicle.R
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.device.AppNetworkUsageModuleHolder
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate
import com.openlattice.chronicle.collection.device.pullExpansionModule
import com.openlattice.chronicle.collection.device.toAndroidAppNetworkUsageEvent
import com.openlattice.chronicle.collection.state.CollectionConsentCopy
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.services.upload.DedicatedUploadSource
import com.openlattice.chronicle.services.upload.RestrictedPendingUploadCounts
import com.openlattice.chronicle.services.upload.RestrictedUploadApiFactory
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.activityRecognitionSampleDao
import com.openlattice.chronicle.storage.audioActivitySampleDao
import com.openlattice.chronicle.storage.audioContentSampleDao
import com.openlattice.chronicle.storage.healthMetricSampleDao
import com.openlattice.chronicle.storage.interactionSampleDao
import com.openlattice.chronicle.storage.notificationActivitySampleDao
import com.openlattice.chronicle.storage.sleepSampleDao

/** Research and open operational contributions; the Amazon build has its own. */
internal object DistributionCollectionContributions {
    /** Consent copy for the restricted modules; their wording lives in `googleServices/res/values/consent_copy.xml`. */
    val consentResources: Map<CollectionModuleId, CollectionConsentCopy.Resources> = mapOf(
        CollectionModuleId.APP_NETWORK_USAGE to CollectionConsentCopy.Resources(
            R.string.consent_app_network_usage_label,
            R.string.consent_app_network_usage_privacy_class,
            R.array.consent_app_network_usage_collects,
            R.array.consent_app_network_usage_not_collects,
        ),
        CollectionModuleId.INTERACTION_EVENTS to CollectionConsentCopy.Resources(
            R.string.consent_interaction_events_label,
            R.string.consent_interaction_events_privacy_class,
            R.array.consent_interaction_events_collects,
            R.array.consent_interaction_events_not_collects,
        ),
        CollectionModuleId.AUDIO_ACTIVITY to CollectionConsentCopy.Resources(
            R.string.consent_audio_activity_label,
            R.string.consent_audio_activity_privacy_class,
            R.array.consent_audio_activity_collects,
            R.array.consent_audio_activity_not_collects,
        ),
        CollectionModuleId.AUDIO_CONTENT to CollectionConsentCopy.Resources(
            R.string.consent_audio_content_label,
            R.string.consent_audio_content_privacy_class,
            R.array.consent_audio_content_collects,
            R.array.consent_audio_content_not_collects,
        ),
        CollectionModuleId.NOTIFICATION_ACTIVITY to CollectionConsentCopy.Resources(
            R.string.consent_notification_activity_label,
            R.string.consent_notification_activity_privacy_class,
            R.array.consent_notification_activity_collects,
            R.array.consent_notification_activity_not_collects,
        ),
        CollectionModuleId.SLEEP to CollectionConsentCopy.Resources(
            R.string.consent_sleep_label,
            R.string.consent_sleep_privacy_class,
            R.array.consent_sleep_collects,
            R.array.consent_sleep_not_collects,
        ),
        CollectionModuleId.ACTIVITY_RECOGNITION to CollectionConsentCopy.Resources(
            R.string.consent_activity_recognition_label,
            R.string.consent_activity_recognition_privacy_class,
            R.array.consent_activity_recognition_collects,
            R.array.consent_activity_recognition_not_collects,
        ),
        CollectionModuleId.HEALTH_CONNECT to CollectionConsentCopy.Resources(
            R.string.consent_health_connect_label,
            R.string.consent_health_connect_privacy_class,
            R.array.consent_health_connect_collects,
            R.array.consent_health_connect_not_collects,
            R.array.consent_health_connect_caveats,
            R.string.consent_health_connect_collects_trailer,
        ),
    ) + mapOf(
        AndroidSensorType.accelerometer to (R.string.consent_sensor_accelerometer_label to R.array.consent_sensor_accelerometer_collects),
        AndroidSensorType.gyroscope to (R.string.consent_sensor_gyroscope_label to R.array.consent_sensor_gyroscope_collects),
        AndroidSensorType.magnetometer to (R.string.consent_sensor_magnetometer_label to R.array.consent_sensor_magnetometer_collects),
        AndroidSensorType.gravity to (R.string.consent_sensor_gravity_label to R.array.consent_sensor_gravity_collects),
        AndroidSensorType.linearAcceleration to
            (R.string.consent_sensor_linear_acceleration_label to R.array.consent_sensor_linear_acceleration_collects),
        AndroidSensorType.rotationVector to (R.string.consent_sensor_rotation_vector_label to R.array.consent_sensor_rotation_vector_collects),
        AndroidSensorType.stepCounter to (R.string.consent_sensor_step_counter_label to R.array.consent_sensor_step_counter_collects),
        AndroidSensorType.light to (R.string.consent_sensor_light_label to R.array.consent_sensor_light_collects),
        AndroidSensorType.proximity to (R.string.consent_sensor_proximity_label to R.array.consent_sensor_proximity_collects),
        AndroidSensorType.significantMotion to
            (R.string.consent_sensor_significant_motion_label to R.array.consent_sensor_significant_motion_collects),
        AndroidSensorType.tiltDetector to (R.string.consent_sensor_tilt_detector_label to R.array.consent_sensor_tilt_detector_collects),
        AndroidSensorType.screenOrientation to
            (R.string.consent_sensor_screen_orientation_label to R.array.consent_sensor_screen_orientation_collects),
    ).entries.associate { (sensor, copy) ->
        SensorCollectionModules.moduleFor(sensor) to CollectionConsentCopy.Resources(
            copy.first,
            R.string.consent_sensor_privacy_class,
            copy.second,
            R.array.consent_sensor_not_collects,
        )
    }

    fun collectAdditionalSamples(
        context: Context,
        schedule: ExpansionPullSchedule?,
        nowMs: Long,
    ): Boolean {
        val network = pullExpansionModule(CollectionModuleId.APP_NETWORK_USAGE, schedule, nowMs) {
            AppNetworkUsageModuleHolder.get(context).sample()
        }
        val activityHealth = ActivityHealthCollectors.collect(context, schedule, nowMs)
        return network && activityHealth
    }

    fun uploadAdditionalStreams(
        delegate: ExpansionUploadWorkerDelegate,
        servers: List<UploadServerEntity>,
    ): Map<UploadQueueTable, () -> Int> {
        val streams = linkedMapOf<UploadQueueTable, () -> Int>()
        streams[UploadQueueTable.APP_NETWORK_USAGE] = {
            delegate.uploadStream(
                servers,
                source = DedicatedUploadSource(
                    elementType = AndroidAppNetworkUsageEvent::class.java,
                    getOldest = { delegate.db.appNetworkUsageSampleDao().getOldest(it) },
                    idOf = { it.id },
                    toDto = { it.toAndroidAppNetworkUsageEvent() },
                    table = UploadQueueTable.APP_NETWORK_USAGE,
                    deleteByIds = { delegate.db.appNetworkUsageSampleDao().deleteByIds(it) },
                    plainUpload = { studyId, server, headers, body ->
                        RestrictedUploadApiFactory.get(
                            server.url,
                        ).uploadAndroidAppNetworkUsageData(studyId, server.participantId, headers, body)
                    },
                ),
            )
        }
        streams.putAll(ActivityHealthCollectors.uploadStreams(delegate, servers))
        return streams
    }

    fun pendingUploadCounts(db: ChronicleDb): RestrictedPendingUploadCounts =
        RestrictedPendingUploadCounts(
            interactionEvents = db.interactionSampleDao().count(),
            audioActivity = db.audioActivitySampleDao().count(),
            audioContent = db.audioContentSampleDao().count(),
            notificationActivity = db.notificationActivitySampleDao().count(),
            sleep = db.sleepSampleDao().count(),
            activityRecognition = db.activityRecognitionSampleDao().count(),
            healthMetrics = db.healthMetricSampleDao().count(),
        )
}
