package com.openlattice.chronicle.services.notifications

import com.openlattice.chronicle.preferences.IDENTIFY_USER
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ParticipantDecision
import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.Activity
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import androidx.core.app.ServiceCompat
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.UserIdentificationActivity
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.lifecycle.LIFECYCLE_DEDUPE_WINDOW_MS
import com.openlattice.chronicle.collection.lifecycle.lifecycleDedupeKey
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.receivers.lifecycle.DeviceLifecycleReceiver
import com.openlattice.chronicle.receivers.lifecycle.NotificationDismissedReceiver
import com.openlattice.chronicle.receivers.lifecycle.PowerSaveModeReceiver
import com.openlattice.chronicle.receivers.lifecycle.UnlockDeviceReceiver
import com.openlattice.chronicle.sensors.UsageEventsChronicleSensor
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.lifecycle.INTERACTION_POWER_SAVE_MODE_ON
import com.openlattice.chronicle.services.lifecycle.INTERACTION_POWER_SAVE_MODE_OFF
import com.openlattice.chronicle.services.lifecycle.ACTION_CONNECTIVITY_CHANGE
import com.openlattice.chronicle.services.lifecycle.INTERACTION_LOW_MEMORY
import com.openlattice.chronicle.utils.Utils
import java.time.Duration
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowUsageStatsManager
import org.robolectric.shadows.ShadowLog
import com.openlattice.chronicle.ui.awaitDashboardUi
import com.openlattice.chronicle.utils.registerSystemBroadcastReceiver
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder

internal fun startMonitoringCommand(service: DeviceUnlockMonitoringService, intent: Intent?, startId: Int): Int {
    val tag = DeviceUnlockMonitoringService::class.java.name
    val before = ShadowLog.getLogsForTag(tag).size
    val result = service.onStartCommand(intent, 0, startId)
    if (result == android.app.Service.START_STICKY) {
        awaitDashboardUi(attempts = 1000) { ShadowLog.getLogsForTag(tag).size > before }
    }
    return result
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], shadows = [EnrollmentTestStores.LifecycleModules::class])
class DeviceUnlockMonitoringServiceContractTest {
    // Resuming a study refreshes sensor availability through WorkManager.
    @get:Rule val workManagerRule = WorkManagerRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val previousUser = DeviceUser.STUDY_PARTICIPANT

    @Before fun setUp() = onPersistenceWorker {
        AuditStores.install(app, enrolled = true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(app)
        Utils.createNotificationChannel(app)
        assertTrue(TargetUserRouter.setTargetUser(app, previousUser) is ModuleResult.Ok)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
    }

    @After fun tearDown() = onPersistenceWorker {
        ChronicleDb.getInstance(app).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    @Test
    fun foregroundNotificationCreatesAndUsesTheCanonicalSilentChannel() {
        manager.deleteNotificationChannel(UNLOCK_MONITORING_CHANNEL_ID)
        val controller = startForReboot()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertEquals(UNLOCK_MONITORING_CHANNEL_ID, notification.channelId)
            assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(notification.channelId).importance)
            assertEquals(app.getString(R.string.interactivity_monitoring_notification_title),
                notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally { controller.destroy() }
    }

    /** Android can demote a running monitor (background usage toggled off and on); the next start must promote it again. */
    @Test
    fun everyStartPromotesAgainAfterAndroidDemotesTheMonitor() {
        val controller = startForReboot()
        try {
            val service = controller.get()
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            assertTrue(DeviceUnlockMonitoringService.startAuthorizedService(app))
            startMonitoringCommand(service, requireNotNull(shadowOf(app).nextStartedService), 2)
            assertFalse(shadowOf(service).isForegroundStopped)
            assertEquals(UNLOCK_MONITORING_CHANNEL_ID, shadowOf(service).lastForegroundNotification?.channelId)
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            assertEquals(android.app.Service.START_STICKY, startMonitoringCommand(service, null, 3))
            assertFalse(shadowOf(service).isForegroundStopped)
            assertEquals(5, monitoringReceivers().size)
        } finally { controller.destroy() }
    }

    @Test
    fun rebootIdentificationBroadcastHasTheCorrectActionAndExplicitPackage() {
        val controller = startForReboot()
        try {
            val action = app.getString(R.string.action_identify_after_reboot)
            val broadcast = shadowOf(app).broadcastIntents.single { it.action == action }
            assertEquals(app.packageName, broadcast.`package`)
            assertEquals(5, monitoringReceivers().size)
        } finally { controller.destroy() }
        assertTrue(monitoringReceivers().isEmpty())
    }

    @Test
    fun receiversAndRebootPromptWaitForAuthorization() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = startForReboot()
        try {
            assertLifecycleOnly(controller)
        } finally { controller.destroy() }
    }

    @Test fun lifecycleMonitoringSurvivesDeclinedIdentification() {
        onPersistenceWorker {
            val dao = ChronicleDb.getInstance(app).collectionModuleStateDao()
            dao.upsert(dao.get(CollectionModuleId.USER_IDENTIFICATION.id)!!.copy(decision = ParticipantDecision.DECLINED.name))
            ResearchPersistenceGate.initialize(app)
        }
        val controller = startForReboot()
        try {
            assertLifecycleOnly(controller)
        } finally { controller.destroy() }
    }

    @Test fun authorizedLifecycleRegistersAndDeliversPowerSaveBroadcastsUntilStopped() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = startForReboot()
        try {
            val registration = shadowOf(app).registeredReceivers.singleOrNull {
                it.broadcastReceiver is PowerSaveModeReceiver
            }
            assertTrue("Battery Saver needs a context-registered receiver", registration != null)
            assertTrue(registration!!.intentFilter.hasAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            val power = shadowOf(app.getSystemService(PowerManager::class.java))
            for (enabled in listOf(true, false)) {
                power.setIsPowerSaveMode(enabled)
                app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
                shadowOf(Looper.getMainLooper()).idle()
                awaitLifecycleWrites()
            }
            assertEquals(listOf(INTERACTION_POWER_SAVE_MODE_ON, INTERACTION_POWER_SAVE_MODE_OFF),
                lifecycleEvents().map { it.interactionType })
            assertTrue(onPersistenceWorker {
                val db = ChronicleDb.getInstance(app)
                ResearchPersistenceGate.applyParticipationStatus(app,
                    db.uploadServerDao().getConfiguredServer()!!,
                    ResearchErasureFence(app).settingsGeneration(), ParticipationStatus.PAUSED, emptyList())
            })
            startMonitoringCommand(controller.get(), Intent(), 2)
            // A pause keeps the receivers for a background resume; they record nothing while paused.
            assertEquals(listOf(DeviceLifecycleReceiver::class.java, PowerSaveModeReceiver::class.java),
                monitoringReceivers().map { it.broadcastReceiver.javaClass })
            power.setIsPowerSaveMode(true)
            app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            setParticipation(ParticipationStatus.ENROLLED)
            awaitLifecycleWrites()
            assertEquals(listOf(INTERACTION_POWER_SAVE_MODE_ON, INTERACTION_POWER_SAVE_MODE_OFF),
                lifecycleEvents().map { it.interactionType })
        } finally { controller.destroy() }
        assertTrue(monitoringReceivers().isEmpty())
    }

    @Test fun batterySaverBroadcastAndTheNextStateSnapshotRecordOneTransition() {
        val controller = startForReboot()
        try {
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
            app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            awaitLifecycleWrites()
            // Past the time-window dedupe, so only the shared Battery Saver state can suppress the snapshot.
            Thread.sleep(LIFECYCLE_DEDUPE_WINDOW_MS + 1)
            app.sendBroadcast(Intent(ACTION_CONNECTIVITY_CHANGE))
            shadowOf(Looper.getMainLooper()).idle()
            awaitLifecycleWrites()
            assertEquals(listOf(INTERACTION_POWER_SAVE_MODE_ON), lifecycleEvents().map { it.interactionType }
                .filter { it == INTERACTION_POWER_SAVE_MODE_ON || it == INTERACTION_POWER_SAVE_MODE_OFF })
        } finally { controller.destroy() }
    }

    @Test fun delayedLifecycleExecutorPreservesReceiptTimesAndRepeatedTransitions() {
        val controller = startForReboot()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        DeviceLifecycleEventRecorder.recordObserved(app) {
            blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)); emptyList()
        }
        assertTrue(blocked.await(10, TimeUnit.SECONDS))
        try {
            val receipts = mutableListOf<LongRange>()
            for (action in listOf(Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED, Intent.ACTION_POWER_CONNECTED)) {
                val receivedAt = System.currentTimeMillis()
                app.sendBroadcast(Intent(action))
                shadowOf(Looper.getMainLooper()).idle()
                controller.get().onTrimMemory(10)
                receipts += receivedAt..System.currentTimeMillis()
                Thread.sleep(LIFECYCLE_DEDUPE_WINDOW_MS + 1)
            }
            release.countDown()
            awaitLifecycleWrites()
            val events = lifecycleEvents()
            assertReceiptTimes(events.filter { it.interactionType != INTERACTION_LOW_MEMORY }, receipts)
            assertReceiptTimes(events.filter { it.interactionType == INTERACTION_LOW_MEMORY }, receipts)
        } finally { release.countDown(); awaitLifecycleWrites(); controller.destroy() }
    }

    /** U2: the identification switch and current-user mirror keep the preference keys earlier builds wrote. */
    @Test fun identificationPreferencesUseTheKeysEarlierBuildsWrote() = onPersistenceWorker {
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(app)
        prefs.edit().putBoolean("identify_user", false).commit()
        assertFalse(EnrollmentSettings(app).isUserIdentificationEnabled())
        prefs.edit().putBoolean("identify_user", true).commit()
        assertTrue(EnrollmentSettings(app).isUserIdentificationEnabled())
        EnrollmentSettings(app).setTargetUser("alice")
        assertEquals("alice", prefs.getString("current_user", null))
    }

    /** Leaving Chronicle (UI_HIDDEN) or being moved to the cached list (BACKGROUND) is not device memory pressure. */
    @Test fun leavingTheAppRecordsNoLowMemory() {
        val controller = startForReboot()
        try {
            controller.get().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            controller.get().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            awaitLifecycleWrites()
            assertEquals(emptyList<String?>(), lifecycleEvents().filter { it.interactionType == INTERACTION_LOW_MEMORY }
                .map { it.activityClass })
        } finally { controller.destroy() }
    }

    /**
     * A remote pause stops prompts and collection but not the monitor: Android may refuse a background
     * foreground-service start at resume, so a stopped monitor would stay stopped until Chronicle is opened.
     */
    @Test fun remotePauseResumeKeepsUnlockMonitoringRecoverable() {
        val controller = startForReboot()
        var stopped = false
        val refusingStarts = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? =
                throw ForegroundServiceStartNotAllowedException("Test background start refusal")
        }
        fun reconcileInBackground(startId: Int) {
            onPersistenceWorker { DeviceUnlockMonitoringService.reconcile(refusingStarts) }
            if (monitorStopped()) {
                stopped = true; controller.destroy()
            }
            if (stopped) return
            // The redelivered start a sticky restart or settings sync produces.
            startMonitoringCommand(controller.get(), Intent(), startId)
        }
        try {
            manager.cancelAll()
            setParticipation(ParticipationStatus.PAUSED)
            reconcileInBackground(2)
            assertFalse("a pause keeps the monitor", stopped)
            assertEquals("a pause keeps every receiver", 5, monitoringReceivers().size)
            val collected = lifecycleEvents()
            unlockThroughRegisteredReceivers()
            app.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("no prompt while paused", 0, identifyPrompts())

            setParticipation(ParticipationStatus.ENROLLED)
            reconcileInBackground(3)
            awaitLifecycleWrites()
            assertEquals("no collection while paused", collected, lifecycleEvents())
            assertEquals("resume keeps every receiver", 5, monitoringReceivers().size)
            unlockThroughRegisteredReceivers()
            assertEquals("the next unlock after resume prompts", 1, identifyPrompts())

            setParticipation(ParticipationStatus.COLLECTION_COMPLETED)
            reconcileInBackground(4)
            assertTrue("a terminal state stops the monitor", stopped)
        } finally { if (!stopped) controller.destroy() }
    }

    /** A remote terminal status stops a lifecycle-only monitor itself: no unlock prompt is left to notice it. */
    @Test fun aRemoteTerminalStatusStopsALifecycleOnlyMonitor() {
        onPersistenceWorker {
            val dao = ChronicleDb.getInstance(app).collectionModuleStateDao()
            dao.upsert(dao.get(CollectionModuleId.USER_IDENTIFICATION.id)!!.copy(decision = ParticipantDecision.DECLINED.name))
            ResearchPersistenceGate.initialize(app)
        }
        val controller = startForReboot()
        try {
            assertLifecycleOnly(controller)
            setParticipation(ParticipationStatus.PAUSED)
            assertFalse("a pause keeps the monitor", monitorStopped())
            setParticipation(ParticipationStatus.COLLECTION_COMPLETED)
            assertTrue("completion stops the monitor", monitorStopped())
        } finally { controller.destroy() }
    }

    /** A full disk refuses research writes; it does not end the shared-device monitor. */
    @Test fun aStorageRefusalKeepsTheMonitorAndItsReceivers() {
        val controller = startForReboot()
        try {
            onPersistenceWorker { assertFalse(StorageAdmission.allowed(app, 0L)) }
            unlockThroughRegisteredReceivers()
            assertFalse("a storage refusal keeps the monitor", monitorStopped())
            assertEquals(5, monitoringReceivers().size)
        } finally { onPersistenceWorker { StorageAdmission.clear(app) }; controller.destroy() }
    }

    /** A status the monitor does not survive stops it; ENROLLED after it asks Android to start it again. */
    @Test fun enrolledAfterAStoppingStatusStartsTheMonitorAgain() {
        for (stopping in listOf(ParticipationStatus.COLLECTION_COMPLETED, ParticipationStatus.NOT_ENROLLED)) {
            val controller = startForReboot()
            setParticipation(stopping)
            assertTrue("$stopping stops the monitor", monitorStopped())
            controller.destroy()
            generateSequence { shadowOf(app).nextStartedService }.toList()
            setParticipation(ParticipationStatus.ENROLLED)
            assertEquals("ENROLLED after $stopping", DeviceUnlockMonitoringService::class.java.name,
                shadowOf(app).nextStartedService?.component?.className)
        }
    }

    /** Other services (the sensor service on a pause) may be stopped too; only this monitor counts. */
    private fun monitorStopped() = generateSequence { shadowOf(app).nextStoppedService }.toList()
        .any { it.component?.className == DeviceUnlockMonitoringService::class.java.name }

    private fun setParticipation(status: ParticipationStatus) = assertTrue(onPersistenceWorker {
        ResearchPersistenceGate.applyParticipationStatus(app, ChronicleDb.getInstance(app).uploadServerDao().getConfiguredServer()!!,
            ResearchErasureFence(app).settingsGeneration(), status, emptyList())
    })

    private fun unlockThroughRegisteredReceivers() = deliverThroughRegisteredReceivers(Intent.ACTION_USER_PRESENT)

    private fun deliverThroughRegisteredReceivers(action: String) {
        val delivered = CountDownLatch(1)
        app.sendOrderedBroadcast(Intent(action), null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { delivered.countDown() }
        }, null, Activity.RESULT_OK, null, null)
        awaitDashboardUi(attempts = 1000) { delivered.count == 0L }
    }

    private fun identifyPrompts() = shadowOf(manager).allNotifications.count { it.channelId == IDENTIFY_USER_CHANNEL_ID }

    private fun awaitLifecycleWrites() {
        // An observation can enqueue its persistence after the first callback.
        repeat(2) {
            val delivered = CountDownLatch(1)
            DeviceLifecycleEventRecorder.recordObserved(app) { delivered.countDown(); emptyList() }
            assertTrue("lifecycle observations reached persistence", delivered.await(10, TimeUnit.SECONDS))
        }
    }

    private fun lifecycleEvents() = ChronicleDb.getInstance(app).queueEntryData().getNextEntries(Int.MAX_VALUE)
        .flatMap { JsonSerializer.deserializeQueueEntry(it.data) }.filterIsInstance<ExtractedUsageEvent>()

    private fun assertReceiptTimes(events: List<ExtractedUsageEvent>, receipts: List<LongRange>) {
        assertEquals(receipts.size, events.size)
        events.zip(receipts).forEach { (event, receipt) ->
            assertTrue("${event.timestamp} must fall within receipt $receipt", event.timestamp.toInstant().toEpochMilli() in receipt)
        }
        val latest = events.last()
        assertEquals(latest.timestamp.toInstant().toEpochMilli(),
            ChronicleDb.getInstance(app).lifecycleStateDao().get(lifecycleDedupeKey(latest))?.observedAt)
    }

    @Test fun delayedConnectivitySnapshotsKeepTheStateAndTimeObservedAtReceipt() {
        val controller = startForReboot()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        DeviceLifecycleEventRecorder.recordObserved(app) {
            blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)); emptyList()
        }
        assertTrue(blocked.await(10, TimeUnit.SECONDS))
        try {
            val receipts = mutableListOf<LongRange>()
            val power = shadowOf(app.getSystemService(PowerManager::class.java))
            // Battery and network never change and Battery Saver repeats once: only state changes persist.
            for (enabled in listOf(true, true, false, true)) {
                power.setIsPowerSaveMode(enabled)
                val receivedAt = System.currentTimeMillis()
                app.sendBroadcast(Intent(ACTION_CONNECTIVITY_CHANGE))
                shadowOf(Looper.getMainLooper()).idle()
                receipts += receivedAt..System.currentTimeMillis()
                Thread.sleep(LIFECYCLE_DEDUPE_WINDOW_MS + 1)
            }
            release.countDown()
            awaitLifecycleWrites()
            val events = lifecycleEvents()
            assertEquals(1, events.count { it.activityClass?.startsWith("network:") == true })
            // Robolectric publishes no ACTION_BATTERY_CHANGED: an unknown battery is not recorded as discharging.
            assertEquals(0, events.count { it.activityClass?.startsWith("battery:") == true })
            val powerEvents = events.filter { it.activityClass?.startsWith("power-save:") == true }
            assertEquals(listOf("power-save:on", "power-save:off", "power-save:on"), powerEvents.map { it.activityClass })
            assertReceiptTimes(powerEvents, receipts.filterIndexed { index, _ -> index != 1 })
        } finally { release.countDown(); awaitLifecycleWrites(); controller.destroy() }
    }

    @Test fun disabledIdentificationNeverPromptsWhileAuthorizedLifecycleKeepsRunning() {
        onPersistenceWorker {
            val dao = ChronicleDb.getInstance(app).collectionModuleStateDao()
            dao.upsert(dao.get(CollectionModuleId.USER_IDENTIFICATION.id)!!.copy(serverEnabled = false))
            ResearchPersistenceGate.initialize(app)
        }
        receiveUnlock()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        val controller = startForReboot()
        try {
            assertLifecycleOnly(controller)
        } finally { controller.destroy() }
    }

    @Test fun targetUserWritesCannotCrossTheEnrollmentBoundary() = onPersistenceWorker {
        val queue = ChronicleDb.getInstance(app).userQueueEntryData()
        val before = queue.getUserTimestamps()
        ResearchPersistenceGate.stop {
            EncryptedPrefsHelper.getEncryptedPrefs(app).edit().remove(PARTICIPANT_ID).commit()
        }
        assertTrue(TargetUserRouter.setTargetUser(app, DeviceUser.SOMEONE_ELSE) is ModuleResult.Skipped)
        assertEquals(before, queue.getUserTimestamps())
    }

    @Test
    fun identificationRequiresActiveEnrollmentLocalOptInAndNotificationDelivery() = onPersistenceWorker {
        assertTrue(userIdentificationMayRun(app))
        manager.deleteNotificationChannel(IDENTIFY_USER_CHANNEL_ID)
        assertFalse(userIdentificationMayRun(app))
        Utils.createNotificationChannel(app)
        assertTrue(userIdentificationMayRun(app))
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(app)
        prefs.edit().putBoolean(IDENTIFY_USER, false).commit()
        assertFalse(userIdentificationMayRun(app))
        prefs.edit().putBoolean(IDENTIFY_USER, true).commit()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(userIdentificationMayRun(app))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(userIdentificationMayRun(app))
        shadowOf(manager).setNotificationsEnabled(true)
        assertTrue(userIdentificationMayRun(app))
        ResearchPersistenceGate.stop { prefs.edit().remove(PARTICIPANT_ID).commit() }
        assertFalse(userIdentificationMayRun(app))
    }

    @Test
    fun postedIdentifyPromptResetsTheUserInEveryBuild() {
        receiveUnlock()
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(IDENTIFY_USER_CHANNEL_ID, notification.channelId)
        assertEquals(app.getString(R.string.on_wake_notification_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(app.getString(R.string.on_wake_notification_message),
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(DeviceUser.NOT_SET.stored, EnrollmentSettings(app).getCurrentUser())
    }

    /**
     * One prompt per unlock, posted at unlock: the screen turning on behind the lock screen posts
     * nothing, so the first copy a participant sees is the heads-up after unlock, and one tap on it
     * opens the device-user chooser itself. Receivers are registered exactly as the monitor does.
     */
    @Test
    fun screenOnBehindTheLockScreenPostsNothingAndUnlockPostsOnePromptThatOpensTheChooser() {
        val receiver = UnlockDeviceReceiver()
        val filter = IntentFilter().apply {
            (UnlockDeviceReceiver.getValidReceiverActions(app) - app.getString(R.string.action_identify_after_reboot))
                .forEach(::addAction)
        }
        registerSystemBroadcastReceiver(app, receiver, filter)
        try {
            deliverThroughRegisteredReceivers(Intent.ACTION_SCREEN_ON)
            assertEquals("screen-on behind the lock screen posted a prompt", 0, identifyPrompts())
            deliverThroughRegisteredReceivers(Intent.ACTION_USER_PRESENT)
            assertEquals(1, identifyPrompts())
        } finally { app.unregisterReceiver(receiver) }
        val prompt = shadowOf(manager).allNotifications.single { it.channelId == IDENTIFY_USER_CHANNEL_ID }
        assertEquals(UserIdentificationActivity::class.java.name,
            shadowOf(prompt.contentIntent).savedIntent.component?.className)
        assertTrue(shadowOf(prompt.contentIntent).isActivity)
    }

    @Test
    fun suppressedIdentifyPromptPreservesHistoricalUsageAndLeavesLaterUsageUnassigned() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        receiveUnlock()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(DeviceUser.NOT_SET.stored, EnrollmentSettings(app).getCurrentUser())
        assertUsageHistoryEndsUnassigned()
    }

    @Test
    fun losingMonitoringDeliveryResetsAttributionWithoutAnUnlock() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        val controller = startForReboot()
        try { assertUsageHistoryEndsUnassigned() } finally { controller.destroy() }
    }

    private fun assertUsageHistoryEndsUnassigned() = onPersistenceWorker {
        val labels = ChronicleDb.getInstance(app).userQueueEntryData().getUserTimestamps().sortedBy { it.writeTimestamp }
        assertEquals(previousUser.stored, labels.first().user)
        assertEquals(DeviceUser.NOT_SET.stored, labels.last().user)
        val usage = shadowOf(app.getSystemService(UsageStatsManager::class.java))
        val times = listOf(labels.first().writeTimestamp + 1, labels.last().writeTimestamp + 1)
        times.forEach { timestamp -> usage.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
            .setPackage(app.packageName).setEventType(UsageEvents.Event.ACTIVITY_RESUMED)
            .setTimeStamp(timestamp).build()) }
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, app.applicationInfo.uid, app.packageName,
            AppOpsManager.MODE_ALLOWED)
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        val users = labels.associateTo(TreeMap<Long, String>()) { it.writeTimestamp to it.user }
        val rows = UsageEventsChronicleSensor(app)
            .poll(times.first() - 1, times.last() + 1, users)!!
            .filterIsInstance<ExtractedUsageEvent>()
        assertEquals(listOf(previousUser.stored, ""), rows.map { it.user })
    }

    @Test
    fun backgroundStickyRestartDefersInsteadOfCrashing() {
        val controller = Robolectric.buildService(DeviceUnlockMonitoringService::class.java)
        val service = controller.get()
        shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("Test refusal"))
        try {
            controller.create().startCommand(0, 1)
            onPersistenceWorker { ResearchPersistenceGate.stop { assertTrue(UnlockMonitoringRuntimeStatus.isDeferred(app)) } }
            assertTrue(shadowOf(service).isStoppedBySelf)
            // Stops with its own start token, so a start Android queued since still runs and promotes.
            assertEquals(1, shadowOf(service).stopSelfResultId)
            assertTrue(monitoringReceivers().isEmpty())
        } finally { controller.destroy() }
    }

    @Test
    fun laterPromotionRefusalDefersAndUnregistersExistingReceivers() {
        val controller = startForReboot()
        val service = controller.get()
        try {
            assertEquals(5, monitoringReceivers().size)
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            shadowOf(service).setThrowInStartForeground(SecurityException("Test permission refusal"))
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(2, shadowOf(service).stopSelfResultId)
            onPersistenceWorker { ResearchPersistenceGate.stop { assertTrue(UnlockMonitoringRuntimeStatus.isDeferred(app)) } }
        } finally { controller.destroy() }
        assertTrue(monitoringReceivers().isEmpty())
    }

    /** A main-thread write queued behind busy work must not land after a later background write (the hint is the last outcome). */
    @Test
    fun deferredStatusWritesKeepCallOrderAcrossThreads() {
        val busy = CountDownLatch(1)
        ResearchPersistenceGate.executeAsync { check(busy.await(10, TimeUnit.SECONDS)) }
        UnlockMonitoringRuntimeStatus.markDeferred(app, true)
        onPersistenceWorker { UnlockMonitoringRuntimeStatus.markDeferred(app, false) }
        busy.countDown()
        onPersistenceWorker { ResearchPersistenceGate.stop { assertFalse(UnlockMonitoringRuntimeStatus.isDeferred(app)) } }
    }

    private fun assertLifecycleOnly(controller: ServiceController<DeviceUnlockMonitoringService>) {
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(listOf(DeviceLifecycleReceiver::class.java, PowerSaveModeReceiver::class.java),
            monitoringReceivers().map { it.broadcastReceiver.javaClass })
        assertTrue(shadowOf(app).broadcastIntents.none { it.action == app.getString(R.string.action_identify_after_reboot) })
    }

    private fun monitoringReceivers() = shadowOf(app).registeredReceivers.filter {
        it.broadcastReceiver is UnlockDeviceReceiver ||
            it.broadcastReceiver is NotificationDismissedReceiver ||
            it.broadcastReceiver is DeviceLifecycleReceiver || it.broadcastReceiver is PowerSaveModeReceiver
    }

    private fun receiveUnlock() {
        val receiver = UnlockDeviceReceiver()
        registerSystemBroadcastReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        try { unlockThroughRegisteredReceivers() } finally { app.unregisterReceiver(receiver) }
    }

    private fun startForReboot(): ServiceController<DeviceUnlockMonitoringService> {
        assertTrue(DeviceUnlockMonitoringService.startAuthorizedService(app, restartOnBoot = true))
        val intent = requireNotNull(shadowOf(app).nextStartedService)
        val controller = Robolectric.buildService(DeviceUnlockMonitoringService::class.java).create()
        try {
            startMonitoringCommand(controller.get(), intent, 1)
            if (onPersistenceWorker { userIdentificationMayRun(app) }) {
                awaitDashboardUi(attempts = 1000) { identifyPrompts() == 1 }
            }
            return controller
        } catch (error: Throwable) {
            controller.destroy()
            throw error
        }
    }
}
