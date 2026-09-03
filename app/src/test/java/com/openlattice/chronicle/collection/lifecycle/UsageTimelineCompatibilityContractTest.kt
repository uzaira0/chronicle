package com.openlattice.chronicle.collection.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the original single UsageStats timeline against duplicate lifecycle injection. */
class UsageTimelineCompatibilityContractTest {

    @Test
    fun `device lifecycle receiver does not duplicate original UsageStats event types`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiverStart = manifest.indexOf(".receivers.lifecycle.DeviceLifecycleReceiver")
        val receiverEnd = manifest.indexOf("</receiver>", receiverStart)
        assertTrue(receiverStart >= 0 && receiverEnd > receiverStart)
        val receiver = manifest.substring(receiverStart, receiverEnd)

        listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.ACTION_SHUTDOWN",
            "android.intent.action.SCREEN_ON",
            "android.intent.action.SCREEN_OFF",
            "android.intent.action.USER_PRESENT",
        ).forEach { action -> assertFalse("duplicate receiver action: $action", receiver.contains(action)) }
    }

    @Test
    fun `usage pollers do not synthesize supplemental device state rows`() {
        listOf(
            "src/main/java/com/openlattice/chronicle/services/usage/UsageMonitoringWorker.kt",
            "src/main/java/com/openlattice/chronicle/services/usage/UsageModuleCollectionDelegate.kt",
        ).forEach { path ->
            val source = File(path).readText()
            assertFalse("$path must preserve the UsageStats sequence", source.contains("DeviceStateSampler(context).poll"))
        }

        val lifecycleSource = File(
            "src/main/java/com/openlattice/chronicle/services/lifecycle/DeviceLifecycleEvents.kt",
        ).readText()
        assertFalse(lifecycleSource.contains("addAction(Intent.ACTION_SHUTDOWN)"))
        assertFalse(lifecycleSource.contains("addAction(Intent.ACTION_SCREEN_ON)"))
        assertFalse(lifecycleSource.contains("addAction(Intent.ACTION_SCREEN_OFF)"))
        assertFalse(lifecycleSource.contains("addAction(Intent.ACTION_USER_PRESENT)"))
    }
}
