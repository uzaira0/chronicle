package com.openlattice.chronicle.services.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceUnlockMonitoringServiceContractTest {

    @Test
    fun `foreground notification creates and uses the canonical channel`() {
        val source = serviceSource()
        assertTrue(source.contains("createNotificationChannel(applicationContext)"))
        assertTrue(source.contains("NotificationCompat.Builder(applicationContext, UNLOCK_MONITORING_CHANNEL_ID)"))
        assertFalse(source.contains("val channelId = getString(R.string.channel_name)"))
    }

    @Test
    fun rebootIdentificationBroadcastHasTheCorrectActionAndExplicitPackage() {
        val source = serviceSource()

        assertTrue(
            source.contains(
                "Intent(applicationContext.getString(R.string.action_identify_after_reboot))",
            ),
        )
        assertTrue(source.contains(".setPackage(applicationContext.packageName)"))
        assertFalse(source.contains("intent.action ="))
    }

    @Test
    fun dynamicallyRegisteredInternalReceiversAreNotExported() {
        val source = serviceSource()

        assertTrue(source.contains("ContextCompat.RECEIVER_NOT_EXPORTED"))
        assertFalse(source.contains("registerReceiver(unlockDeviceReceiver, intentFilter, RECEIVER_EXPORTED)"))
        assertFalse(source.contains("notificationDismissedReceiver,\n                intentFilter,\n                RECEIVER_EXPORTED"))
    }

    @Test
    fun manifestUnlockReceiverIsNotExported() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        val receiver = manifest.substringAfter("android:name=\".receivers.lifecycle.UnlockDeviceReceiver\"")
            .substringBefore("</receiver>")

        assertTrue(receiver.contains("android:exported=\"false\""))
    }

    @Test
    fun receiversAndRebootPromptWaitForActiveAuthorizedStudyScope() {
        val source = serviceSource()
        val startEntry = source.substringAfter("fun startAuthorizedService(context: Context")
            .substringBefore("fun stopService")
        val serviceStart = source.substringAfter("override fun onStartCommand")
            .substringBefore("private fun startForeground")

        assertTrue(startEntry.contains("ContextCompat.startForegroundService(appContext, intent)"))
        assertFalse(startEntry.contains("AUTHORIZATION_EXECUTOR.execute"))
        assertTrue(serviceStart.contains("userIdentificationMayRun(applicationContext)"))
        assertTrue(serviceStart.indexOf("if (!authorized)") < serviceStart.indexOf("registerReceivers()"))
        assertTrue(
            serviceStart.indexOf("if (!authorized)") <
                serviceStart.indexOf("action_identify_after_reboot"),
        )
        assertTrue(source.contains("ResearchPersistenceGate.isActiveEnrollment"))
        assertTrue(source.contains("hasNotificationPermission(context.applicationContext, IDENTIFY_USER_CHANNEL_ID)"))
        assertTrue(source.contains("CollectionGate.collects("))
        assertTrue(source.contains("CollectionModuleId.USER_IDENTIFICATION"))
        assertTrue(source.contains("isUserIdentificationEnabled()"))
        assertTrue(source.contains("if (receiversRegistered)"))

        val receiver = moduleFile(
            "src/main/java/com/openlattice/chronicle/receivers/lifecycle/UnlockDeviceReceiver.kt",
        ).readText()
        assertTrue(receiver.contains("DeviceUnlockMonitoringService.stopService(context)"))
    }

    @Test
    fun notificationGateIncludesRuntimeAppAndChannelDeliveryState() {
        val source = moduleFile(
            "src/main/kotlin/com/openlattice/chronicle/services/notifications/" +
                "NotificationPermissionActivity.kt",
        ).readText()

        assertTrue(source.contains("POST_NOTIFICATIONS"))
        assertTrue(source.contains("areNotificationsEnabled()"))
        assertTrue(source.contains("getNotificationChannel(channelId)"))
        assertTrue(source.contains("NotificationManager.IMPORTANCE_NONE"))
        assertTrue(source.contains("!hasPostNotificationsRuntimePermission(this)"))

        val main = moduleFile(
            "src/main/java/com/openlattice/chronicle/MainActivity.kt",
        ).readText()
        assertTrue(main.contains("hasPostNotificationsRuntimePermission(this)"))
    }

    @Test
    fun identifyPromptAndSurveyReminderUseTheStandardTemplate() {
        // Android 12+ wraps custom RemoteViews in its own header and clips them to ~48dp, which hid
        // the prompt text on newer devices.
        val unlock = moduleFile(RECEIVERS + "UnlockDeviceReceiver.kt").readText()
        val survey = moduleFile(RECEIVERS + "SurveyNotificationsReceiver.kt").readText()
        for (source in listOf(unlock, survey)) {
            assertFalse(source.contains("RemoteViews("))
            assertFalse(source.contains(".setCustomContentView("))
            assertTrue(source.contains(".setContentTitle("))
            assertTrue(source.contains("NotificationCompat.BigTextStyle()"))
        }
        assertTrue(unlock.contains("NotificationCompat.Builder(context, IDENTIFY_USER_CHANNEL_ID)"))
        assertFalse(moduleFile("src/main/res/layout/notification.xml").exists())
    }

    @Test
    fun postedIdentifyPromptResetsTheUserInEveryBuild() {
        val unlock = moduleFile(RECEIVERS + "UnlockDeviceReceiver.kt").readText()
        val receive = unlock.substringAfter("private fun handleReceive").substringBefore("private fun postIdentifyNotification")
        val gateEnd = receive.indexOf("posted = postIdentifyNotification(context, intent)\n        }")
        val reset = receive.indexOf("TargetUserRouter.setTargetUser(context, context.getString(R.string.user_unassigned))")
        assertTrue(gateEnd >= 0)
        assertTrue(reset > gateEnd) // outside persistIfActive: the router takes the same barrier
        assertTrue(receive.substring(gateEnd, reset).contains("if (posted)"))

        val post = unlock.substringAfter("private fun postIdentifyNotification")
            .substringBefore("private fun createOnDismissedIntent")
        assertTrue(post.contains("suppressed (POST_NOTIFICATIONS not granted)\", e)\n            return false"))
        assertTrue(post.trimEnd().removeSuffix("}").trimEnd().endsWith("return true"))

        // The notification-access listener no longer owns the reset, so Play builds get it too.
        val listener = moduleFile(
            "src/googleServices/java/com/openlattice/chronicle/services/notifications/NotificationListener.kt",
        ).readText()
        assertFalse(listener.contains("setTargetUser"))
    }

    @Test
    fun identifyPromptsHaveTheirOwnChannelAndTheOngoingMonitorIsSilent() {
        val utils = moduleFile("src/main/java/com/openlattice/chronicle/utils/Utils.kt").readText()
            .replace(Regex("\\s+"), " ")
        assertTrue(utils.contains("NotificationChannel( IDENTIFY_USER_CHANNEL_ID,"))
        assertTrue(utils.contains("R.string.identify_user_channel_name), NotificationManager.IMPORTANCE_HIGH,"))
        assertTrue(utils.contains("R.string.unlock_monitoring_channel_name), NotificationManager.IMPORTANCE_LOW,"))
        assertTrue(utils.contains("createNotificationChannels(listOf(channel, identifyUser, unlockMonitoring))"))

        val settings = moduleFile("src/main/java/com/openlattice/chronicle/ui/SettingsHomeFragment.kt").readText()
        assertFalse(Regex("hasNotificationPermission\\((requireContext\\(\\)|appContext)\\)").containsMatchIn(settings))
        assertTrue(settings.contains("hasNotificationPermission(appContext, IDENTIFY_USER_CHANNEL_ID)"))
    }

    @Test
    fun backgroundStickyRestartDefersInsteadOfCrashing() {
        val onCreate = serviceSource().substringAfter("override fun onCreate()")
            .substringBefore("override fun onStartCommand")
        assertTrue(onCreate.indexOf("try {") < onCreate.indexOf("startForeground()"))
        assertTrue(onCreate.contains("} catch (error: IllegalStateException) {"))
        assertTrue(onCreate.contains("UnlockMonitoringRuntimeStatus.markDeferred(applicationContext, true)"))
        assertTrue(onCreate.contains("stopSelf()"))
    }

    private fun serviceSource(): String = moduleFile(
        "src/main/java/com/openlattice/chronicle/services/notifications/DeviceUnlockMonitoringService.kt",
    ).readText()

    private companion object {
        const val RECEIVERS = "src/main/java/com/openlattice/chronicle/receivers/lifecycle/"
    }

    private fun moduleFile(relativePath: String): File {
        val module = sequenceOf(File("."), File("app"))
            .map(File::getAbsoluteFile)
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Could not locate the app module")
        return File(module, relativePath)
    }
}
