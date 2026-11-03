package com.openlattice.chronicle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.EnrollmentResponse
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.validateEnrollmentModulePartition
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.security.InvitedServerCa
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.study.EnrollmentWithdrawalResponse
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class InvitedServerCaLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val origin = EnrollmentTestStores.TEST_SERVER_URL
    private val root = HeldCertificate.Builder().certificateAuthority(1).build().certificate
    private lateinit var db: ChronicleDb
    private var controller: ActivityController<Enrollment>? = null
    private val executors = mutableListOf<PausedExecutorService>()

    @Before fun install() {
        db = EnrollmentTestStores.install(context)
        InvitedServerCa.replace(context, origin, root)
    }

    @After fun close() {
        (context as? FailingCaApplication)?.apply {
            failWrites = false; diskFull = false; throwOnCommit = false; dieBeforeCommit = false; dieAfterCommit = false
        }
        controller?.pause()?.stop()?.destroy()
        executors.forEach { it.runAll(); it.shutdown() }
        db.close()
    }

    @Test fun rootlessInvitationRevokesThePreviousRootAtTheSameOrigin() {
        openInvitation(origin)
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun rootlessInvitationToAnotherServerRevokesThePreviousRoot() {
        openInvitation("https://localhost:8443")
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun aNewRootReplacesTheOldOriginRatherThanAddingToIt() {
        val other = "https://localhost:8443"
        openInvitation(other, withRoot = true)
        assertNull(InvitedServerCa.rootFor(origin))
        assertEquals(root, InvitedServerCa.rootFor(other))
    }

    @Test fun anInvalidNewInvitationEndsThePreviousInvitationsTrust() {
        openInvitation(origin, withRoot = true)
        controller!!.newIntent(Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("chronicle://enroll?serverUrl=invalid")))
        await { InvitedServerCa.rootFor(origin) == null }
    }

    @Test fun editingTheServerAddressRevokesThePreviousOriginsRootBeforePreview() {
        val activity = openInvitation(origin, withRoot = true)
        activity.findViewById<TextView>(R.id.serverUrlText).text = "https://localhost:8443"
        EnrollmentTestStores.api = studyApi { _, _ ->
            assertNull(InvitedServerCa.rootFor(origin))
            throw IOException("preview unavailable")
        }
        EnrollmentTestStores.expectedApiRequest = EnrollmentTestStores.ApiRequest("https://localhost:8443")
        activity.findViewById<View>(R.id.button).performClick()
        await { activity.findViewById<View>(R.id.button).isEnabled }
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun cancellingDisclosureRevokesInvitationTrust() {
        val activity = openDisclosure()
        val disclosure = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(disclosure, Activity.RESULT_CANCELED, null)
        await { InvitedServerCa.rootFor(origin) == null }
        assertEquals(0, db.uploadServerDao().count())
    }

    @Test fun decliningOrientationRevokesInvitationTrust() {
        val activity = openDisclosure(setOf(CollectionModuleId.USAGE_EVENTS))
        val disclosure = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(disclosure, Activity.RESULT_OK, null)
        await { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val orientation = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(orientation, Activity.RESULT_CANCELED, null)
        await { InvitedServerCa.rootFor(origin) == null }
    }

    @Test fun leavingAnUnreservedInvitationRevokesItsTrust() {
        val activity = openInvitation(origin, withRoot = true)
        activity.finish()
        controller!!.pause().stop().destroy()
        controller = null
        await { InvitedServerCa.rootFor(origin) == null }
    }

    @Test fun clearingEnrollmentIdentityRevokesItsRoot() = onPersistenceWorker {
        EnrollmentSettings(context).clearEnrollment()
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun orphanedEnrollmentReconciliationRevokesItsRoot() = onPersistenceWorker {
        db.close()
        db = EnrollmentTestStores.install(context, enrolled = true)
        val settings = EnrollmentSettings(context)
        db.uploadServerDao().delete(db.uploadServerDao().getConfiguredServer()!!.id)
        assertFalse(settings.hasConfiguredEnrollmentOrThrow())
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun aRootLeftByAProcessThatDiedMidInvitationIsRetiredOnTheNextStart() = onPersistenceWorker {
        InvitedServerCa.simulateProcessStart()
        assertEquals(EnrollmentRecoveryResult.NONE, EnrollmentRecoveryManager.resumeIfNeeded(context))
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun recoveryKeepsTheRootOfAnInvitationOpenInThisProcess() = onPersistenceWorker {
        assertEquals(EnrollmentRecoveryResult.NONE, EnrollmentRecoveryManager.resumeIfNeeded(context))
        assertNotNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun recoveryKeepsTheRootOfAnEnrollmentStartedByAnEarlierProcess() = onPersistenceWorker {
        db.close()
        db = EnrollmentTestStores.install(context, enrolled = true)
        InvitedServerCa.simulateProcessStart()
        EnrollmentRecoveryManager.resumeIfNeeded(context)
        assertNotNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun explicitLocalStoreRecoveryRevokesItsRootWithoutOpeningRoom() {
        EnrollmentSettings.clearForLocalStoreRecovery(context)
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun allWithdrawalOutcomesRevokeTrustIncludingServerErasureAndCancellation() = onPersistenceWorker {
        for (ended in listOf(WithdrawalState.COMPLETE, WithdrawalState.CANCELLED, WithdrawalState.NEEDS_SUPPORT)) {
            db.close()
            db = EnrollmentTestStores.install(context, enrolled = true)
            InvitedServerCa.replace(context, origin, root)
            ResearchPersistenceGate.stop {
                ParticipantWithdrawalManager.clearLocalEnrollment(context, LocalUploadDiagnosticsStore.of(context),
                    db.uploadServerDao().getAll(), ended == WithdrawalState.NEEDS_SUPPORT, ended)
            }
            assertNull(ended.name, InvitedServerCa.rootFor(origin))
            assertEquals(0, db.uploadServerDao().count())
        }
    }

    @Test fun cancellingAnUnsentAttemptRevokesItsRoot() = onPersistenceWorker {
        reserve()
        assertTrue(EnrollmentRecoveryManager.cancelPendingAttempt(context))
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun cancellingACorruptUnsentAttemptRevokesItsRoot() = onPersistenceWorker {
        corruptPendingAttempt()
        assertTrue(EnrollmentRecoveryManager.cancelPendingAttempt(context))
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun recoveringACorruptUnsentAttemptRevokesItsRoot() = onPersistenceWorker {
        corruptPendingAttempt()
        assertEquals(EnrollmentRecoveryResult.TERMINAL_FAILURE, EnrollmentRecoveryManager.resumeIfNeeded(context))
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun aCorruptSentAttemptThatCannotBeDeletedRetainsItsTrust() = onPersistenceWorker {
        corruptPendingAttempt()
        val row = db.uploadServerDao().getRecoverablePendingEnrollment()!!
        db.uploadServerDao().update(row.copy(pendingEnrollmentFirstRequestAtEpochMillis = System.currentTimeMillis()))
        assertFalse(EnrollmentRecoveryManager.cancelPendingAttempt(context))
        assertNotNull(db.uploadServerDao().getRecoverablePendingEnrollment())
        assertEquals(root, InvitedServerCa.rootFor(origin))
    }

    @Test @Config(application = FailingCaApplication::class)
    fun aFailedRevocationAfterRemovalEndsTheAttemptAndTheRootIsRetiredOnTheNextStart() = onPersistenceWorker {
        val app = context as FailingCaApplication
        for (throwOnCommit in listOf(false, true)) {
            InvitedServerCa.replace(context, origin, root)
            reserve()
            app.failWrites = !throwOnCommit
            app.throwOnCommit = throwOnCommit
            assertTrue(EnrollmentRecoveryManager.cancelPendingAttempt(context))
            assertNull(db.uploadServerDao().getRecoverablePendingEnrollment())
            assertNull(InvitedServerCa.rootFor(origin))
            app.failWrites = false
            app.throwOnCommit = false
            assertRetiredOnTheNextStart(rootPersisted = true)
        }
    }

    @Test fun pendingRemovalDoesNotRevokeANewInvitationThatRacesItsClear() = onPersistenceWorker {
        reserve()
        val dao = db.uploadServerDao()
        val pending = dao.getRecoverablePendingEnrollment()!!
        assertTrue(InvitedServerCa.clearAfterPendingRemoval(context) {
            val removed = dao.deleteCorruptPendingEnrollment(pending.id) == 1
            assertTrue(removed)
            InvitedServerCa.replace(context, origin, root)
            reserve()
            removed
        })
        assertEquals(root, InvitedServerCa.rootFor(origin))
        assertNotNull(dao.getRecoverablePendingEnrollment())
        assertFalse(pending.reservationNonce == dao.getRecoverablePendingEnrollment()!!.reservationNonce)
    }

    /** The row's removal commits before its trust is revoked, so a death never strands a live attempt. */
    @Test @Config(application = FailingCaApplication::class)
    fun processDeathAroundRevocationNeverLeavesAPendingAttemptWithoutItsRoot() = onPersistenceWorker {
        val app = context as FailingCaApplication
        for (afterCommit in listOf(false, true)) {
            InvitedServerCa.replace(context, origin, root)
            reserve()
            app.dieBeforeCommit = !afterCommit
            app.dieAfterCommit = afterCommit
            assertThrows(ProcessDeath::class.java) { EnrollmentRecoveryManager.cancelPendingAttempt(context) }
            app.dieBeforeCommit = false
            app.dieAfterCommit = false
            assertNull("afterCommit=$afterCommit", db.uploadServerDao().getRecoverablePendingEnrollment())
            assertRetiredOnTheNextStart(rootPersisted = !afterCommit)
        }
    }

    private fun assertRetiredOnTheNextStart(rootPersisted: Boolean) {
        InvitedServerCa.simulateProcessStart()
        InvitedServerCa.attach(context)
        assertEquals(if (rootPersisted) root else null, InvitedServerCa.rootFor(origin))
        assertEquals(EnrollmentRecoveryResult.NONE, EnrollmentRecoveryManager.resumeIfNeeded(context))
        assertNull(db.uploadServerDao().getRecoverablePendingEnrollment())
        assertNull(InvitedServerCa.rootFor(origin))
        assertTrue(InvitedServerCa.attach(context).all.isEmpty())
    }

    @Test fun serverRefusalRevokesTrustAfterThePendingAttemptIsCleared() = onPersistenceWorker {
        reserve()
        EnrollmentTestStores.api = studyApi { _, _ -> throw ChronicleCallException("POST", "request", "", 400) }
        assertEquals(EnrollmentRecoveryResult.SERVER_INCOMPATIBLE, EnrollmentRecoveryManager.resumeIfNeeded(context))
        assertNull(db.uploadServerDao().getRecoverablePendingEnrollment())
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun anUncertainSentAttemptKeepsTrustUntilItsWithdrawalIsAcknowledged() = onPersistenceWorker {
        reserve()
        var online = false
        EnrollmentTestStores.api = studyApi { method, args ->
            assertEquals(root, InvitedServerCa.rootFor(origin))
            if (method != "withdrawCurrentEnrollment" || !online) throw IOException("offline")
            EnrollmentWithdrawalResponse(UUID.fromString(args!![2] as String))
        }
        assertEquals(EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED, EnrollmentRecoveryManager.resumeIfNeeded(context))
        assertFalse(EnrollmentRecoveryManager.cancelPendingAttempt(context))
        assertEquals(root, InvitedServerCa.rootFor(origin))
        online = true
        assertTrue(EnrollmentRecoveryManager.cancelPendingAttempt(context))
        assertNull(InvitedServerCa.rootFor(origin))
    }

    @Test fun successfulEnrollmentKeepsTheRootForUploads() = onPersistenceWorker {
        reserve()
        EnrollmentTestStores.api = studyApi { method, args ->
            when (method) {
                "enroll" -> EnrollmentResponse(UUID.randomUUID(), apiKey = args!![7] as String)
                "reportCollectionAck" -> OK()
                else -> error(method)
            }
        }
        assertEquals(EnrollmentRecoveryResult.COMPLETED, EnrollmentRecoveryManager.resumeIfNeeded(context))
        assertEquals(root, InvitedServerCa.rootFor(origin))
    }

    /**
     * A full-disk exception before the CA mutation is safe to retry. Both submitting again and re-opening the link
     * leave the storage-paused Editing screen; an unclassified false commit has its own recovery test.
     */
    @Test @Config(application = FailingCaApplication::class)
    fun fullDiskBeforeCaWriteAsksToFreeSpaceAndANewInvitationCanRetry() {
        val app = context as FailingCaApplication
        app.diskFull = true
        assertThrows(LocalStoreRecoveryRequiredException::class.java) { InvitedServerCa.replace(context, origin, root) }
        assertNull(InvitedServerCa.rootFor(origin))
        val activity = startActivity(invitation(origin, true))
        await { activity.findViewById<View>(R.id.button).isEnabled }
        assertEquals(activity.getString(R.string.settings_control_storage_paused), statusMessage(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)

        app.diskFull = false
        EnrollmentTestStores.api = studyApi { _, _ -> EnrollmentTestStores.enrollmentPreview() }
        controller!!.newIntent(invitation(origin, true))
        await { activity.findViewById<View>(R.id.button).isEnabled }
        assertNotEquals(activity.getString(R.string.enrollment_finish_current_first), statusMessage(activity))
        assertEquals(root, InvitedServerCa.rootFor(origin))
        assertEquals(EnrollmentTestStores.enrollmentPreview().manifest.studyId.toString(),
            activity.findViewById<TextView>(R.id.studyIdText).text.toString())
        assertFalse(activity.findViewById<View>(R.id.statusMessage).isShown)
        activity.findViewById<View>(R.id.button).performClick()
        await { shadowOf(activity).peekNextStartedActivityForResult() != null }
        assertEquals(0, db.uploadServerDao().count())
    }

    @Test @Config(application = FailingCaApplication::class)
    fun storagePausedReviewCanRetryWithoutReopeningTheInvitation() {
        val activity = openInvitation(origin, withRoot = true)
        val other = "https://localhost:8443"
        activity.findViewById<TextView>(R.id.serverUrlText).text = other
        val app = context as FailingCaApplication
        app.diskFull = true
        activity.findViewById<View>(R.id.button).performClick()
        await { activity.findViewById<View>(R.id.button).isEnabled }
        assertEquals(activity.getString(R.string.settings_control_storage_paused), statusMessage(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
        app.diskFull = false
        EnrollmentTestStores.expectedApiRequest = EnrollmentTestStores.ApiRequest(other)
        EnrollmentTestStores.api = studyApi { _, _ ->
            val preview = EnrollmentTestStores.enrollmentPreview()
            preview.copy(manifest = preview.manifest.copy(serverOrigin = other))
        }
        activity.findViewById<View>(R.id.button).performClick()
        await { shadowOf(activity).peekNextStartedActivityForResult() != null }
        assertFalse(activity.findViewById<View>(R.id.statusMessage).isShown)
        assertNull(InvitedServerCa.rootFor(origin))
        assertEquals(0, db.uploadServerDao().count())
    }

    @Test @Config(application = FailingCaApplication::class)
    fun falseCaCommitRequiresRecoveryAndNeverPublishesTrustOrEnrollment() {
        val app = context as FailingCaApplication
        app.failWrites = true
        val error = assertThrows(LocalStoreRecoveryRequiredException::class.java) { InvitedServerCa.replace(context, origin, root) }
        assertEquals(LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED, error.recoveryReason)
        assertNull(InvitedServerCa.rootFor(origin))
        val failed = startActivity(invitation(origin, true))
        await { shadowOf(failed).peekNextStartedActivity() != null }
        assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(failed).nextStartedActivity.component!!.className)
        assertTrue(failed.isFinishing)
        assertNull(InvitedServerCa.rootFor(origin))
        assertEquals(0, db.uploadServerDao().count())
        app.failWrites = false
        controller!!.pause().stop().destroy()
        controller = null
        openInvitation(origin, withRoot = true)
        assertEquals(root, InvitedServerCa.rootFor(origin))
    }

    /** A CA store whose encryption layer throws cannot be trusted: that one still opens the recovery screen. */
    @Test @Config(application = FailingCaApplication::class)
    fun unreadableCaStoreOpensVisibleStorageRecovery() {
        (context as FailingCaApplication).throwOnCommit = true
        val failed = startActivity(invitation(origin, true))
        await { shadowOf(failed).peekNextStartedActivity() != null }
        assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(failed).nextStartedActivity.component!!.className)
        assertTrue(failed.isFinishing)
        assertNull(InvitedServerCa.rootFor(origin))
        assertEquals(0, db.uploadServerDao().count())
    }

    private fun statusMessage(activity: Activity) = activity.findViewById<TextView>(R.id.statusMessage).text.toString()

    private fun reserve() = onPersistenceWorker {
        val preview = EnrollmentTestStores.enrollmentPreview()
        val settings = preview.manifest.collectionSettings
        reserveEnrollment(context, EnrollmentSubmission(preview,
            validateEnrollmentModulePartition(settings, settings.effectiveEnabledModuleIds(), emptySet(), emptySet()),
            origin, "a".repeat(40)))
    }

    private fun corruptPendingAttempt() {
        reserve()
        val row = db.uploadServerDao().getRecoverablePendingEnrollment()!!
        db.uploadServerDao().update(row.copy(studyDisclosureJson = null))
    }

    private fun invitation(server: String, withRoot: Boolean): Intent {
        val preview = EnrollmentTestStores.enrollmentPreview()
        val ca = if (withRoot) "&serverCa=" + Base64.getUrlEncoder().withoutPadding().encodeToString(root.encoded) else ""
        return Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(
            "chronicle://enroll?studyId=${preview.manifest.studyId}&participantId=${preview.manifest.participantId}" +
                "&serverUrl=${Uri.encode(server)}$ca#accessCode=${"a".repeat(40)}"))
    }

    private fun openInvitation(server: String, withRoot: Boolean = false): Enrollment {
        return startActivity(invitation(server, withRoot)).also { activity ->
            await { activity.findViewById<View>(R.id.button).isEnabled }
        }
    }

    private fun startActivity(intent: Intent): Enrollment {
        val executor = object : PausedExecutorService() {
            override fun shutdown() {
                // ExecutorService.shutdown keeps submitted work; the paused test executor drops it.
                runAll()
                super.shutdown()
            }
        }.also(executors::add)
        controller = ActivityController.of(Enrollment(executor), intent)
        val activity = controller!!.get()
        ViewModelProvider(activity, viewModelFactory {
            initializer { Enrollment.WizardState(executor.asCoroutineDispatcher()) }
        })[Enrollment.WizardState::class.java]
        controller!!.setup()
        return activity
    }

    private fun openDisclosure(modules: Set<CollectionModuleId> = emptySet()): Enrollment {
        EnrollmentTestStores.api = studyApi { _, _ ->
            EnrollmentTestStores.enrollmentPreview(enabled = modules + CollectionModuleId.UPLOAD_TELEMETRY)
        }
        val activity = openInvitation(origin, withRoot = true)
        activity.findViewById<View>(R.id.button).performClick()
        await { shadowOf(activity).peekNextStartedActivityForResult() != null }
        assertNotNull(InvitedServerCa.rootFor(origin))
        return activity
    }

    private fun await(condition: () -> Boolean) {
        do {
            executors.forEach { it.runAll() }
            shadowOf(Looper.getMainLooper()).idle()
        } while (executors.any { it.hasQueuedTasks() })
        assertTrue("UI did not settle: ${controller?.get()?.findViewById<TextView>(R.id.statusMessage)?.text}", condition())
    }

    /** Stands in for the process being killed: an Error no production catch handles. */
    class ProcessDeath : Error("process died")

    class FailingCaApplication : Application() {
        @Volatile var failWrites = false
        @Volatile var diskFull = false
        @Volatile var throwOnCommit = false
        @Volatile var dieBeforeCommit = false
        @Volatile var dieAfterCommit = false
        override fun createDeviceProtectedStorageContext(): Context {
            val device = super.createDeviceProtectedStorageContext()
            return object : ContextWrapper(device) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val prefs = super.getSharedPreferences(name, mode)
                    return object : SharedPreferences by prefs {
                        override fun edit(): SharedPreferences.Editor {
                            val editor = prefs.edit()
                            return object : SharedPreferences.Editor by editor {
                                override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                                    editor.putString(key, value); return this
                                }
                                override fun commit(): Boolean {
                                    val invitationCa = name == "invited_server_ca"
                                    if (invitationCa && dieBeforeCommit) throw ProcessDeath()
                                    if (invitationCa && diskFull) throw LocalStoreRecoveryRequiredException(
                                        LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED, SQLiteFullException("disk full before preference mutation"))
                                    check(!(invitationCa && throwOnCommit)) { "CA storage unavailable" }
                                    return (!failWrites && editor.commit()).also {
                                        if (invitationCa && dieAfterCommit) throw ProcessDeath()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
