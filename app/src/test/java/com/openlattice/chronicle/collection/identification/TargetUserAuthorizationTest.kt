package com.openlattice.chronicle.collection.identification

import com.openlattice.chronicle.collection.state.ResearchPersistenceBarrier
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetUserAuthorizationTest {

    @Test
    fun disabledManifestRejectsTargetUserWritesEvenWhenLegacyPreferenceIsOn() {
        assertEquals(
            TargetUserWriteRoute.REJECT,
            targetUserWriteRoute(
                studyAuthorized = false,
                participantEnabled = true,
                resettingToUnassigned = false,
            ),
        )
    }

    @Test
    fun authorizedScopeAllowsEnabledChoiceAndOnlyTheDisabledCleanupWrite() {
        assertEquals(
            TargetUserWriteRoute.MODULE,
            targetUserWriteRoute(true, participantEnabled = true, resettingToUnassigned = false),
        )
        assertEquals(
            TargetUserWriteRoute.RESET_TO_UNASSIGNED,
            targetUserWriteRoute(true, participantEnabled = false, resettingToUnassigned = true),
        )
        assertEquals(
            TargetUserWriteRoute.REJECT,
            targetUserWriteRoute(true, participantEnabled = false, resettingToUnassigned = false),
        )
    }

    @Test
    fun postWithdrawalBoundaryRejectsAnOtherwiseAuthorizedLateWrite() {
        val barrier = ResearchPersistenceBarrier()
        val activeEnrollment = AtomicBoolean(true)
        val writes = AtomicInteger()
        barrier.stop { activeEnrollment.set(false) }

        val persisted = barrier.persistIf(activeEnrollment::get) {
            if (
                targetUserWriteRoute(true, participantEnabled = true, resettingToUnassigned = false) ==
                TargetUserWriteRoute.MODULE
            ) {
                writes.incrementAndGet()
            }
        }

        assertFalse(persisted)
        assertEquals(0, writes.get())
    }

    @Test
    fun productionRouterKeepsAuthorizationInsideTheWithdrawalWriteChokepoint() {
        val source = locateAppSource(
            "com/openlattice/chronicle/collection/identification/TargetUserRouter.kt",
        )
        val guarded = source.substringAfter("ResearchPersistenceGate.persistIfActive(context) {")

        assertTrue(guarded.contains("settings.isUserIdentificationStudyAuthorized()"))
        assertTrue(guarded.contains("CollectionGate.collects(context, CollectionModuleId.USER_IDENTIFICATION)"))
        assertTrue(guarded.contains("targetUserWriteRoute("))
        assertTrue(guarded.contains("TargetUserWriteRoute.REJECT"))
    }

    @Test
    fun appEntryPointsAndUsageFieldFailClosedOnStudyAuthorization() {
        val main = locateAppSource("com/openlattice/chronicle/MainActivity.kt")
        val boot = locateAppSource("com/openlattice/chronicle/receivers/lifecycle/StartOnBoot.kt")
        val unlock = locateAppSource(
            "com/openlattice/chronicle/receivers/lifecycle/UnlockDeviceReceiver.kt",
        )
        val usageDelegate = locateAppSource(
            "com/openlattice/chronicle/services/usage/UsageModuleCollectionDelegate.kt",
        )
        val legacyUsage = locateAppSource(
            "com/openlattice/chronicle/services/usage/UsageMonitoringWorker.kt",
        )
        val holder = locateAppSource(
            "com/openlattice/chronicle/collection/identification/UserIdentificationModuleHolder.kt",
        )

        assertTrue(main.contains("if (userIdentificationMayRun(applicationContext))"))
        assertTrue(boot.contains("if (userIdentificationMayRun(context))"))
        assertTrue(unlock.contains("if (!userIdentificationMayRun(context))"))
        assertTrue(unlock.contains("DeviceUnlockMonitoringService.stopService(context)"))
        assertTrue(usageDelegate.contains("CollectionModuleId.USER_IDENTIFICATION"))
        assertTrue(legacyUsage.contains("CollectionModuleId.USER_IDENTIFICATION"))
        assertTrue(holder.contains("configuredStudyModuleEnabled("))
    }

    private fun locateAppSource(path: String): String {
        val module = sequenceOf(File("."), File("app"))
            .map(File::getAbsoluteFile)
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Could not locate the app module")
        return File(module, "src/main/java/$path").readText()
    }
}
