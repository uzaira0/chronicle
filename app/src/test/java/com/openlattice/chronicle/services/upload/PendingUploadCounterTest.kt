package com.openlattice.chronicle.services.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PendingUploadCounterTest {
    @Test
    fun `pending total includes every upload table and excludes local participant labels`() {
        val auxiliary = AuxiliaryPendingUploadCounts(
            connectivityState = 11,
            appNetworkUsage = 12,
            deviceSettings = 13,
            restricted = RestrictedPendingUploadCounts(
                interactionEvents = 4,
                audioActivity = 5,
                audioContent = 6,
                notificationActivity = 7,
                sleep = 8,
                activityRecognition = 9,
                healthMetrics = 10,
            ),
        )
        val pending = PendingUploadCounts(
            usageAndLifecycle = 1,
            sensorSamples = 2,
            batterySamples = 3,
            auxiliary = auxiliary,
            localParticipantLabels = 10_000,
        )

        assertEquals(85L, auxiliary.total)
        assertEquals(91L, pending.total)
    }

    @Test
    fun `zero auxiliary counts do not change the core pending total`() {
        val pending = PendingUploadCounts(
            usageAndLifecycle = 3,
            sensorSamples = 5,
            batterySamples = 7,
            auxiliary = AuxiliaryPendingUploadCounts(
                connectivityState = 0,
                appNetworkUsage = 0,
                deviceSettings = 0,
            ),
            localParticipantLabels = 99,
        )

        assertEquals(15L, pending.total)
    }

    @Test
    fun `production snapshot keeps restricted DAO access out of the public source graph`() {
        val source = File(
            "src/main/java/com/openlattice/chronicle/services/upload/PendingUploadCounter.kt",
        ).readText()
        listOf(
            "connectivityStateSampleDao().count()",
            "appNetworkUsageSampleDao().count()",
            "deviceSettingsSampleDao().count()",
        ).forEach { expected ->
            assertTrue("Missing pending count for $expected", source.contains(expected))
        }
        assertTrue(source.contains("DistributionCollectionContributions.pendingUploadCounts(db)"))

        val minimal = File(
            "src/minimal/java/com/openlattice/chronicle/collection/DistributionCollectionContributions.kt",
        ).readText()
        assertTrue(minimal.contains("pendingUploadCounts(db: ChronicleDb): RestrictedPendingUploadCounts? = null"))

        val research = File(
            "src/googleServices/java/com/openlattice/chronicle/collection/DistributionCollectionContributions.kt",
        ).readText()
        listOf(
            "interactionSampleDao().count()",
            "audioActivitySampleDao().count()",
            "audioContentSampleDao().count()",
            "notificationActivitySampleDao().count()",
            "sleepSampleDao().count()",
            "activityRecognitionSampleDao().count()",
            "healthMetricSampleDao().count()",
        ).forEach { expected ->
            assertTrue("Missing research-only pending count for $expected", research.contains(expected))
            assertTrue("Restricted DAO leaked into the public counter: $expected", !source.contains(expected))
        }

        val uploadsUi = File("src/main/java/com/openlattice/chronicle/ui/UploadsFragment.kt").readText()
        val uploadsCopy = File("src/main/res/values/strings.xml").readText()
        assertTrue(uploadsUi.contains("pending.localParticipantLabels"))
        assertTrue(uploadsCopy.contains("Local participant labels"))
        assertTrue(uploadsCopy.contains("not an upload queue"))
    }
}
