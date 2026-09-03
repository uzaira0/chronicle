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
        assertTrue(source.contains("NotificationCompat.Builder(applicationContext, CHANNEL_ID)"))
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
        assertTrue(source.contains("hasNotificationPermission(context.applicationContext)"))
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
        assertTrue(source.contains("getNotificationChannel(CHANNEL_ID)"))
        assertTrue(source.contains("NotificationManager.IMPORTANCE_NONE"))
        assertTrue(source.contains("!hasPostNotificationsRuntimePermission(this)"))

        val main = moduleFile(
            "src/main/java/com/openlattice/chronicle/MainActivity.kt",
        ).readText()
        assertTrue(main.contains("hasPostNotificationsRuntimePermission(this)"))
    }

    private fun serviceSource(): String = moduleFile(
        "src/main/java/com/openlattice/chronicle/services/notifications/DeviceUnlockMonitoringService.kt",
    ).readText()

    private fun moduleFile(relativePath: String): File {
        val module = sequenceOf(File("."), File("app"))
            .map(File::getAbsoluteFile)
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Could not locate the app module")
        return File(module, relativePath)
    }
}
