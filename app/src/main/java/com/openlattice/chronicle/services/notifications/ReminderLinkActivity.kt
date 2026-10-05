package com.openlattice.chronicle.services.notifications

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.security.CallDeadline
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.completeServerForIdentity
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.utils.Utils.createNotificationTargetUrl
import java.util.UUID

/**
 * Opens a reminder's form with a fresh one-time code. The server revokes a device's earlier
 * unused codes whenever reminders are re-synced (every 15 minutes), so the code a reminder was
 * posted with is usually dead by the time the participant taps it.
 */
class ReminderLinkActivity : Activity() {
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val details = intent.getStringExtra(NOTIFICATION_DETAILS)
            ?.let { runCatching { Gson().fromJson(it, NotificationDetails::class.java) }.getOrNull() }
        // The enrollment scope the receiver admitted this reminder under.
        val scope = intent.getStringExtra(SURVEY_ENROLLMENT_SCOPE)
        // A second tap (or a rotation) while this reminder's code is being fetched is ignored.
        if (details == null || !inFlight.add(details.requestCode())) {
            finish()
            return
        }
        val appContext = applicationContext
        Thread {
            // A reminder admitted under another enrollment scope never reaches the server.
            val owner = runCatching {
                ResearchPersistenceGate.awaitAuthorization(appContext, AUTHORIZATION_WAIT_MS)
                ResearchPersistenceGate.captureOwner(appContext)?.takeIf { scope != null && scope == currentScope(appContext) }
            }.getOrNull()
            val result = if (owner == null) Result.failure<String>(IllegalStateException("No active enrollment"))
                else runCatching { freshReminderUrlFor(appContext, owner, details) }
            val url = result
                .onFailure { Log.w(TAG, "Reminder link unavailable", it) }
                .getOrNull()
            runOnUiThread {
                try {
                    openOrKeep(appContext, details, owner, scope, url, result.exceptionOrNull())
                } finally {
                    inFlight.remove(details.requestCode())
                    finish()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private fun openOrKeep(
        appContext: Context,
        details: NotificationDetails,
        owner: UploadServerEntity?,
        scope: String?,
        url: String?,
        error: Throwable?,
    ) {
        // Withdrawn or replaced since the tap: the link belongs to an enrollment that is gone.
        // Both reads are of the published snapshot, so MAIN never blocks here.
        val current = ResearchPersistenceGate.captureOwner(appContext)
        if (owner == null || current == null || !sameEnrollment(owner, current) || currentScope(appContext) != scope) {
            NotificationManagerCompat.from(appContext).cancel(SURVEY_NOTIFICATION_TAG, details.requestCode())
            Toast.makeText(appContext, R.string.reminder_form_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        if (url != null) {
            // Left meanwhile: Android drops a background launch without an error, so keep the
            // reminder for another foreground tap instead of cancelling it unopened.
            if (!resumed || isFinishing) return
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(appContext, R.string.reminder_link_unavailable, Toast.LENGTH_LONG).show()
                return
            }
            NotificationManagerCompat.from(appContext).cancel(SURVEY_NOTIFICATION_TAG, details.requestCode())
        } else if (error is ReminderFormUnavailableException) {
            NotificationManagerCompat.from(appContext).cancel(SURVEY_NOTIFICATION_TAG, details.requestCode())
            Toast.makeText(appContext, R.string.reminder_form_unavailable, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(appContext, R.string.reminder_link_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        val inFlight: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        const val AUTHORIZATION_WAIT_MS = 5_000L

        fun currentScope(context: Context) =
            ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)?.first

        fun sameEnrollment(a: UploadServerEntity, b: UploadServerEntity) =
            a.id == b.id && a.createdAt == b.createdAt && a.studyId == b.studyId &&
                a.participantId == b.participantId && a.sourceDeviceId == b.sourceDeviceId &&
                a.enrollmentIssuedAtEpochMillis == b.enrollmentIssuedAtEpochMillis
    }
}

/** Servers without the route: 404/405, or 500 from the catch-all exception handler of releases through 2026.10.3. */
private val LEGACY_REMINDER_SERVER_CODES = setOf(404, 405, 500)

/** Overall bound on a tap's server calls, which run under [ResearchPersistenceGate]'s read lease. */
internal const val REMINDER_LINK_DEADLINE_MS = 20_000L

/**
 * Bound to the captured owner: a withdrawal waits for this call instead of racing it. The deadline
 * cancels a server that trickles its answer, so the lease (and the withdrawal behind it) is never held
 * longer than [deadlineMs].
 */
internal fun freshReminderUrlFor(
    context: Context,
    owner: UploadServerEntity,
    details: NotificationDetails,
    deadlineMs: Long = REMINDER_LINK_DEADLINE_MS,
): String = ResearchPersistenceGate.runIfExpectedOwner(context, owner) {
    CallDeadline.within(deadlineMs) { freshReminderUrl(context, details) }
} ?: throw IllegalStateException("Enrollment changed before the reminder link was fetched")

internal fun freshReminderUrl(context: Context, details: NotificationDetails): String {
    val settings = EnrollmentSettings(context)
    val studyId = settings.getStudyId()
    val participantId = settings.getParticipantId()
    val server = requireNotNull(
        completeServerForIdentity(ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer(), studyId, participantId),
    ) { "No study server for the enrolled participant" }
    require(server.authMode == AUTH_MODE_API_KEY) { "Reminder links need a device-bound enrollment" }
    val apiKey = requireNotNull(server.apiKey)
    val api = UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
    val kind = if (details.type == NotificationType.QUESTIONNAIRE) ParticipantFormKind.QUESTIONNAIRE else ParticipantFormKind.APP_USAGE
    fun tapped(configuration: MobileReminderConfiguration) = configuration.forms.firstOrNull {
        it.formKind == kind && (it.resourceId?.toString() ?: studyId.toString()) == details.id
    } ?: throw ReminderFormUnavailableException()
    // The schedule read is the availability check. Releases through 2026.10.3 answer it with 500
    // but already mint single forms, so only the check is skipped there.
    val resourceId = try {
        tapped(api.getMobileReminderSchedule(studyId, participantId, server.sourceDeviceId, apiKey)).resourceId
    } catch (error: ChronicleCallException) {
        if (error.code !in LEGACY_REMINDER_SERVER_CODES) throw error
        if (kind == ParticipantFormKind.QUESTIONNAIRE) UUID.fromString(details.id) else null
    }
    // Mint only the tapped form: the manifest re-issues every form's code and so revokes a code
    // another tap already handed to the browser. It is the fallback for servers without the mint.
    val accessCode = try {
        api.createParticipantFormAccessCode(
            studyId, participantId, server.sourceDeviceId, apiKey,
            CreateParticipantFormAccessCodeRequest(kind, resourceId),
        ).accessCode
    } catch (error: ChronicleCallException) {
        if (error.code !in LEGACY_REMINDER_SERVER_CODES) throw error
        tapped(api.getMobileReminderConfiguration(studyId, participantId, server.sourceDeviceId, apiKey)).accessCode
    }
    return createNotificationTargetUrl(details.copy(serverUrl = server.url, accessCode = accessCode), studyId.toString(), participantId)
}

internal class ReminderFormUnavailableException : IllegalStateException("Reminder form is no longer available")
