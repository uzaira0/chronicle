package com.openlattice.chronicle.services.upload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadWithdrawalBoundaryContractTest {
    @Test
    fun withdrawalNetworkFailuresRemainDurableAndRetryable() {
        val source = appSource("services/withdrawal/ParticipantWithdrawalManager.kt")

        assertTrue(source.contains("Do not convert a temporary outage into an abandoned erasure request"))
        assertTrue(source.contains("return Result.retry()"))
        assertFalse(source.contains("MAX_RETRIES"))
    }

    @Test
    fun everyUploadWorkerTakesTheResearchLeaseBeforeQueueOwnershipAndNetworkWork() {
        val paths = listOf(
            "services/upload/CombinedUploadWorker.kt",
            "services/upload/UploadWorker.kt",
            "collection/battery/BatteryUploadWorker.kt",
            "collection/device/ExpansionUploadWorker.kt",
            "collection/interaction/InteractionUploadWorker.kt",
            "collection/audio/AudioUploadWorker.kt",
            "services/sensors/SensorUploadWorker.kt",
        )

        paths.forEach { relative ->
            val source = appSource(relative)
            val lease = source.indexOf("ResearchPersistenceGate.runIfActive")
            val queue = source.indexOf("UploadQueueSingleFlight.tryAcquire")
            assertTrue("$relative does not take the active-enrollment upload lease", lease >= 0)
            assertTrue("$relative acquires the queue before the stop barrier", queue > lease)
        }
    }

    @Test
    fun consentReportsShareTheWithdrawalLeaseAndUseKeyedQueueMutations() {
        val coordinator = appSource("collection/state/CollectionLoopCoordinator.kt")
        val queue = appSource("collection/state/CollectionAckRetryStore.kt")

        assertTrue(coordinator.contains("ResearchPersistenceGate.runIfActive(appContext, operation)"))
        assertTrue(coordinator.contains("ResearchPersistenceGate.runIfExpectedEnrollment("))
        assertTrue(coordinator.contains("queue.removeByStableKeys(result.removedStableKeys)"))
        assertTrue(queue.contains("private val mutationLock = Any()"))
        assertTrue(queue.contains("fun removeByStableKeys(stableKeys: Set<String>)"))
        assertTrue(queue.contains("fun clearForWithdrawal()"))
        assertTrue(!queue.contains("fun replace(records:"))
    }

    @Test
    fun pendingConsentQueueIsLoadedInsideTheWithdrawalLease() {
        val coordinator = appSource("collection/state/CollectionLoopCoordinator.kt")
        val retryMethod = coordinator.indexOf("private fun retryPendingCollectionAcks()")
        val lease = coordinator.indexOf("ResearchPersistenceGate.runIfActive(appContext)", retryMethod)
        val queueLoad = coordinator.indexOf("val pending = queue.load()", retryMethod)
        val networkDispatch = coordinator.indexOf("reportCollectionAck(", queueLoad)

        assertTrue(retryMethod >= 0)
        assertTrue("pending retry state is read before the withdrawal barrier", lease > retryMethod)
        assertTrue("pending retry state is not protected by the withdrawal barrier", queueLoad > lease)
        assertTrue("pending retry state is not bound before network dispatch", networkDispatch > queueLoad)
    }

    @Test
    fun reenrollmentSwitchesIdentityAndKeepsOldDiagnosticsScopedInsideTheStopBoundary() {
        val recovery = appSource("EnrollmentRecoveryManager.kt")
        val stop = recovery.indexOf("ResearchPersistenceGate.stop {")
        val activate = recovery.indexOf("dao.activateIssuedEnrollment", startIndex = stop)
        val clearAcks = recovery.indexOf("clearForWithdrawal()", startIndex = activate)
        val completeIdentity = recovery.indexOf("WithdrawalStateStore(context).completeReenrollment", startIndex = clearAcks)
        val stopEnd = recovery.indexOf("\n        }", startIndex = completeIdentity)

        assertTrue(stop >= 0)
        assertTrue(activate > stop)
        assertTrue(clearAcks > activate)
        assertFalse(recovery.contains("LocalUploadDiagnosticsStore.of(context).clear()"))
        assertTrue(completeIdentity > clearAcks)
        assertTrue(stopEnd > completeIdentity)
    }

    @Test
    fun distributionBoundaryReplaysJournalAndCountsCorruptFilesBeforeErasure() {
        val boundary = appSource("services/release/MinimalPlayBoundaryWorker.kt")
        val replay = boundary.indexOf("replayDirectBootJournalForErasure(applicationContext, db)")
        val purge = boundary.indexOf("purgeRestrictedPlayRows(db)")
        val countCorrupt = boundary.indexOf("inventory.corruptIncidentIds.forEach")
        val erase = boundary.indexOf("clearDirectBootSensorBuffer(applicationContext)")
        assertTrue(replay >= 0 && replay < purge)
        assertTrue(countCorrupt > purge && countCorrupt < erase)
    }

    @Test
    fun persistenceGateCountsDirectWriteFailuresAndPropagatesSinkFailures() {
        val gate = appSource("collection/state/ResearchPersistenceGate.kt")
        assertTrue(gate.contains("LocalOperationalIssue.LOCAL_WRITE_FAILED"))
        assertTrue(gate.contains("records,"))
        assertTrue(gate.contains("persistGuarded(context, null, persist)"))
    }

    private fun appSource(relative: String): String {
        val module = sequenceOf(File("."), File("app"))
            .map(File::getAbsoluteFile)
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Could not locate the app module")
        return sequenceOf("src/main/java", "src/googleServices/java")
            .map { sourceRoot -> File(module, "$sourceRoot/com/openlattice/chronicle/$relative") }
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Could not locate app source: $relative")
    }
}
