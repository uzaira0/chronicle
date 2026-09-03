package com.openlattice.chronicle.services.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.os.Build
import android.util.Log
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.NotificationEventType
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.audio.AudioCaptureController
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.NotificationActivitySampleEntry
import com.openlattice.chronicle.storage.notificationActivitySampleDao
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class NotificationListener : NotificationListenerService() {

    // Hosts the mic-free app-audio capture; alive only while notification access is granted.
    private val audioController by lazy { AudioCaptureController(applicationContext) }

    // Notification-activity Room writes must stay off the main thread (onNotification* run on it).
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { audioController.register() }
            .onFailure { Log.w(javaClass.name, "Failed to start audio capture", it) }
    }

    override fun onListenerDisconnected() {
        runCatching { audioController.unregister() }
            .onFailure { Log.w(javaClass.name, "Failed to stop audio capture", it) }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // When notification to identify user has been posted, unassign current user. If user ignores
        // notification, subsequent usage events will be assumed to belong to an 'unidentified user'.
        if (
            sbn.packageName == applicationContext.packageName &&
            sbn.tag == IDENTIFY_USER_NOTIFICATION_TAG &&
            sbn.id == applicationContext.resources.getInteger(R.integer.identify_user_notification_id)
        ) {
            Log.i(javaClass.name, "User identification notification posted.")
            executeIo("target-user reset") {
                val result = TargetUserRouter.setTargetUser(
                    applicationContext,
                    applicationContext.getString(R.string.user_unassigned),
                )
                if (result !is ModuleResult.Ok) {
                    Log.w(javaClass.name, "Target-user reset failed: ${result.label}")
                }
            }
        }

        recordNotificationActivity(sbn, NotificationEventType.POSTED)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        recordNotificationActivity(sbn, NotificationEventType.REMOVED)
    }

    /**
     * Content-free `notification_activity` capture: package + Android category + flags only — never
     * the notification's title, text, or any free-form payload. Gated; persists off the main thread.
     */
    private fun recordNotificationActivity(sbn: StatusBarNotification, eventType: NotificationEventType) {
        val pkg = sbn.packageName ?: return
        val category = sbn.notification?.category
        val ongoing = sbn.isOngoing
        val ranking = Ranking()
        val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                if (currentRanking.getRanking(sbn.key, ranking)) ranking.importance else null
            }.onFailure {
                Log.w(javaClass.name, "Notification ranking unavailable", it)
            }.getOrNull()
        } else {
            null
        }
        executeIo("notification-activity capture") {
            runCatching {
                val entry = NotificationActivitySampleEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                    timezone = ZoneId.systemDefault().id,
                    eventType = eventType.name,
                    packageName = pkg,
                    category = category,
                    ongoing = ongoing,
                    importance = importance,
                )
                ResearchPersistenceGate.persistIfCollecting(
                    applicationContext,
                    CollectionModuleId.NOTIFICATION_ACTIVITY,
                ) {
                    ChronicleDb.getInstance(applicationContext)
                        .notificationActivitySampleDao()
                        .insertAll(listOf(entry))
                }
            }.onFailure { Log.w(javaClass.name, "Notification-activity capture failed", it) }
        }
    }

    private fun executeIo(label: String, block: () -> Unit) {
        try {
            ioExecutor.execute {
                runCatching(block)
                    .onFailure { Log.w(javaClass.name, "$label failed", it) }
            }
        } catch (error: RejectedExecutionException) {
            Log.w(javaClass.name, "$label rejected because listener is stopping", error)
        }
    }

    override fun onDestroy() {
        runCatching { audioController.unregister() }
            .onFailure { Log.w(javaClass.name, "Failed to stop audio capture during destroy", it) }
        ioExecutor.shutdown()
        super.onDestroy()
    }
}

/** Private namespace for Chronicle's own identify-user notification; never match ID alone. */
