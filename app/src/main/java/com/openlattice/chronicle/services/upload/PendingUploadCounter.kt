package com.openlattice.chronicle.services.upload

import android.content.Context
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.DistributionCollectionContributions
import com.openlattice.chronicle.storage.ChronicleDb
import java.time.LocalDate

data class PendingUploadCounts(
    val usageAndLifecycle: Int,
    val sensorSamples: Int,
    val batterySamples: Int,
    val auxiliary: AuxiliaryPendingUploadCounts,
    /** Local label history used while producing usage rows; it is not itself uploaded. */
    val localParticipantLabels: Int,
) {
    val total: Long
        get() = usageAndLifecycle.toLong() + sensorSamples + batterySamples + auxiliary.total
}

/** Counts for every Room table drained by the three auxiliary upload workers. */
data class AuxiliaryPendingUploadCounts(
    val connectivityState: Int,
    val appNetworkUsage: Int,
    val deviceSettings: Int,
    val restricted: RestrictedPendingUploadCounts? = null,
) {
    val total: Long
        get() = connectivityState.toLong() + appNetworkUsage + deviceSettings +
            (restricted?.total ?: 0L)
}

/** Research-only queue counts; unreachable and R8-removed from the Play artifact. */
data class RestrictedPendingUploadCounts(
    val interactionEvents: Int,
    val audioActivity: Int,
    val audioContent: Int,
    val notificationActivity: Int,
    val sleep: Int,
    val activityRecognition: Int,
    val healthMetrics: Int,
) {
    val total: Long
        get() = interactionEvents.toLong() + audioActivity + audioContent + notificationActivity +
            sleep + activityRecognition + healthMetrics
}

data class UploadSuccessCounts(
    val usageAndLifecycle: Int,
    val sensorSamples: Int,
    val batterySamples: Int,
)

data class UploadFailureCounts(
    val usageAttempts: Int,
    val sensorAttempts: Int,
    val batteryAttempts: Int,
) {
    val total: Int
        get() = usageAttempts + sensorAttempts + batteryAttempts
}

object PendingUploadCounter {
    fun snapshot(context: Context): PendingUploadCounts =
        snapshot(ChronicleDb.getInstance(context.applicationContext))

    fun snapshot(db: ChronicleDb): PendingUploadCounts =
        PendingUploadCounts(
            usageAndLifecycle = db.queueEntryData().getSize(),
            sensorSamples = db.sensorSampleDao().count(),
            batterySamples = db.batterySampleDao().count(),
            auxiliary = AuxiliaryPendingUploadCounts(
                connectivityState = db.connectivityStateSampleDao().count(),
                appNetworkUsage = if (BuildConfig.HAS_APP_NETWORK_USAGE) {
                    db.appNetworkUsageSampleDao().count()
                } else {
                    0
                },
                deviceSettings = db.deviceSettingsSampleDao().count(),
                restricted = DistributionCollectionContributions.pendingUploadCounts(db),
            ),
            localParticipantLabels = db.userQueueEntryData().count(),
        )

    fun dashboardSnapshot(context: Context, date: LocalDate = LocalDate.now()): UploadDashboardSnapshot =
        dashboardSnapshot(ChronicleDb.getInstance(context.applicationContext), date)

    fun dashboardSnapshot(db: ChronicleDb, date: LocalDate = LocalDate.now()): UploadDashboardSnapshot =
        UploadDashboardSnapshot(
            pending = snapshot(db),
            succeededToday = UploadSuccessCounts(
                usageAndLifecycle = db.uploadStatsDao().usageUploadedOn(date.toString()),
                sensorSamples = db.uploadStatsDao().sensorUploadedOn(date.toString()),
                batterySamples = db.uploadStatsDao().batteryUploadedOn(date.toString()),
            ),
            failedToday = UploadFailureCounts(
                usageAttempts = db.uploadStatsDao().usageFailuresOn(date.toString()),
                sensorAttempts = db.uploadStatsDao().sensorFailuresOn(date.toString()),
                batteryAttempts = db.uploadStatsDao().batteryFailuresOn(date.toString()),
            ),
        )
}

data class UploadDashboardSnapshot(
    val pending: PendingUploadCounts,
    val succeededToday: UploadSuccessCounts,
    val failedToday: UploadFailureCounts,
)
