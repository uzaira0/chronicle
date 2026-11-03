package com.openlattice.chronicle

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.state.CollectionHardStop
import com.openlattice.chronicle.collection.state.CollectionSettingsSyncWorker
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.services.enrollment.scheduleEnrollmentMonitoringWork
import com.openlattice.chronicle.services.notifications.scheduleNotificationsWorker
import com.openlattice.chronicle.services.sync.scheduleChronicleSyncWork
import com.openlattice.chronicle.services.enrollment.ENROLLMENT_MONITOR_WORK_NAME
import com.openlattice.chronicle.services.notifications.NOTIFICATIONS_WORK_NAME
import com.openlattice.chronicle.services.sync.AUXILIARY_UPLOAD_IMMEDIATE_WORK_NAME
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_IMMEDIATE_WORK_NAME
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_WORK_NAME
import com.openlattice.chronicle.collection.battery.BATTERY_WORK_NAME
import com.openlattice.chronicle.collection.battery.BATTERY_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.device.EXPANSION_COLLECTION_WORK_NAME
import com.openlattice.chronicle.collection.device.EXPANSION_UPLOAD_WORK_NAME
import com.openlattice.chronicle.services.sync.restrictedPeriodicWorkNames

/** [collects]: collection, upload or reminder work, which a hard stop cancels (CollectionHardStop). */
private data class EnrolledWorkSchedule(val names: Set<String>, val collects: Boolean = false, val enqueue: (Context) -> Unit)

internal object EnrolledWork {
    fun uniqueWorkNames(): Set<String> = schedules().flatMapTo(linkedSetOf()) { it.names }

    /** Reads Room and secure preferences ([CollectionHardStop.applies]): never on the main thread. */
    @WorkerThread
    fun reconcile(context: Context, refreshSettings: Boolean = true) = ResearchPersistenceGate.withReadLease {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) SensorSettings(context).clear()
        val stopped = CollectionHardStop.applies(context)
        schedules().filterNot { stopped && it.collects }.forEach { it.enqueue(context) }
        if (refreshSettings) {
            CollectionSettingsSyncWorker.enqueueNow(context, ExistingWorkPolicy.KEEP)
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
                DistributionRestrictedRuntime.enqueueSensorSettingsRefresh(context, ExistingWorkPolicy.KEEP)
            }
        }
    }

    fun collectionWorkNames(): Set<String> = schedules().filter { it.collects }.flatMapTo(linkedSetOf()) { it.names } +
        setOf(CHRONICLE_SYNC_IMMEDIATE_WORK_NAME, AUXILIARY_UPLOAD_IMMEDIATE_WORK_NAME)

    fun rescheduleSync(context: Context) = scheduleChronicleSyncWork(context)
    fun rescheduleNotifications(context: Context) = scheduleNotificationsWorker(context, ExistingPeriodicWorkPolicy.REPLACE)
    fun refreshCollectionSettings(context: Context) = CollectionSettingsSyncWorker.enqueueNow(context, ExistingWorkPolicy.REPLACE)
    fun refreshSensorAvailability(context: Context) = DistributionRestrictedRuntime.enqueueSensorSettingsRefresh(context, ExistingWorkPolicy.REPLACE)

    private fun schedules(): List<EnrolledWorkSchedule> = buildList {
        add(EnrolledWorkSchedule(syncWorkNames(), collects = true, enqueue = ::rescheduleSync))
        add(EnrolledWorkSchedule(setOf(ENROLLMENT_MONITOR_WORK_NAME), enqueue = ::scheduleEnrollmentMonitoringWork))
        if (BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
            add(EnrolledWorkSchedule(setOf(NOTIFICATIONS_WORK_NAME), collects = true) {
                scheduleNotificationsWorker(it, ExistingPeriodicWorkPolicy.KEEP)
            })
        }
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            add(EnrolledWorkSchedule(DistributionRestrictedRuntime.sensorSettingsWorkNames) { target ->
                DistributionRestrictedRuntime.scheduleSensorSettingsRefresh(target)
            })
        }
        add(EnrolledWorkSchedule(setOf(CollectionSettingsSyncWorker.PERIODIC_NAME, CollectionSettingsSyncWorker.ONESHOT_NAME)) { target ->
            CollectionSettingsSyncWorker.schedulePeriodic(target)
        })
    }

    private fun syncWorkNames(): Set<String> = buildSet {
        add(BATTERY_WORK_NAME)
        add(BATTERY_UPLOAD_WORK_NAME)
        add(EXPANSION_COLLECTION_WORK_NAME)
        add(EXPANSION_UPLOAD_WORK_NAME)
        addAll(restrictedPeriodicWorkNames())
        add(CHRONICLE_SYNC_WORK_NAME)
    }
}
