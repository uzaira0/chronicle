package com.openlattice.chronicle.services.notifications

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.security.CallDeadline
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.completeServerForIdentity
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.utils.Utils.createNotificationTargetUrl
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens a reminder's form with a fresh one-time code. A posted reminder carries no code: each tap
 * mints one for its own form, after the current schedule confirms the form is still offered.
 */
class ReminderLinkActivity : ComponentActivity() {
    private var resumed = false
    private lateinit var details: NotificationDetails
    private var scope: String? = null
    private val request: LinkRequest by viewModels()

    /** [settled] is false when the enrollment was not published before the wait ended: no answer yet, not a retirement. */
    internal class ReminderResult(val owner: UploadServerEntity?, val url: Result<String>, val settled: Boolean)

    internal class LinkRequest : ViewModel() {
        private var started = false
        val outcome = MutableLiveData<ReminderResult>()

        /**
         * False when another tap on this reminder already has a request in flight: a second code would
         * revoke the first tab's code. The claim lives as long as the request, so recreation keeps it.
         */
        fun start(context: Context, details: NotificationDetails, scope: String?): Boolean {
            if (started) return true
            if (!inFlight.add(details.requestCode())) return false
            started = true
            val appContext = context.applicationContext
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val settled = runCatching { ResearchPersistenceGate.awaitAuthorization(appContext, AUTHORIZATION_WAIT_MS) }
                        .getOrDefault(false)
                    val owner = if (!settled) null else runCatching {
                        ResearchPersistenceGate.captureOwner(appContext)
                            ?.takeIf { scope != null && scope == currentScope(appContext) }
                    }.getOrNull()
                    val url = if (owner == null) Result.failure<String>(IllegalStateException("No active enrollment"))
                        else runCatching { freshReminderUrlFor(appContext, owner, details) }
                    url.onFailure { Log.w(TAG, "Reminder link unavailable", it) }
                    ReminderResult(owner, url, settled)
                }
                outcome.value = result
            }.invokeOnCompletion { inFlight.remove(details.requestCode()) }
            return true
        }

        private companion object {
            val inFlight: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notification = intent.getStringExtra(NOTIFICATION_DETAILS)
            ?.let { runCatching { Gson().fromJson(it, NotificationDetails::class.java) }.getOrNull() }
        if (notification == null) {
            Toast.makeText(applicationContext, R.string.reminder_form_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        details = notification
        scope = intent.getStringExtra(SURVEY_ENROLLMENT_SCOPE)
        Toast.makeText(applicationContext, R.string.dashboard_loading, Toast.LENGTH_SHORT).show()
        if (!request.start(applicationContext, notification, scope)) {
            finish()
            return
        }
        request.outcome.observe(this) { openResultIfResumed() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        openResultIfResumed()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private fun openResultIfResumed() {
        if (!resumed || isFinishing) return
        val result = request.outcome.value ?: return
        try {
            if (result.settled) openOrKeep(applicationContext, details, result.owner, scope, result.url)
            // Unsettled is no answer yet; the reminder stays so the participant can tap it again.
            else Toast.makeText(applicationContext, R.string.reminder_link_unavailable, Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }

    private fun openOrKeep(
        appContext: Context,
        details: NotificationDetails,
        owner: UploadServerEntity?,
        scope: String?,
        result: Result<String>,
    ) {
        // The published scope binds both enrollment identity and module generation without MAIN-thread IO.
        if (owner == null || currentScope(appContext) != scope) {
            NotificationManagerCompat.from(appContext).cancel(SURVEY_NOTIFICATION_TAG, details.requestCode())
            Toast.makeText(appContext, R.string.reminder_form_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val url = result.getOrNull()
        if (url != null) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(appContext, R.string.reminder_browser_unavailable, Toast.LENGTH_LONG).show()
                return
            }
            NotificationManagerCompat.from(appContext).cancel(SURVEY_NOTIFICATION_TAG, details.requestCode())
        } else if (result.exceptionOrNull() is ReminderFormUnavailableException) {
            NotificationManagerCompat.from(appContext).cancel(SURVEY_NOTIFICATION_TAG, details.requestCode())
            Toast.makeText(appContext, R.string.reminder_form_unavailable, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(appContext, R.string.reminder_link_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val AUTHORIZATION_WAIT_MS = 5_000L

        fun currentScope(context: Context) =
            ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)?.key
    }
}

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
} ?: error("Enrollment changed before the reminder link was fetched")

internal fun freshReminderUrl(context: Context, details: NotificationDetails): String {
    val settings = EnrollmentSettings(context)
    val studyId = settings.getStudyId()
    val participantId = settings.getParticipantId()
    val server = requireNotNull(
        completeServerForIdentity(ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer(), studyId, participantId),
    ) { "No study server for the enrolled participant" }
    val apiKey = requireNotNull(server.apiKey)
    val api = UploadWorker.getChronicleStudyApi(server.url)
    val kind = if (details.type == NotificationType.QUESTIONNAIRE) ParticipantFormKind.QUESTIONNAIRE else ParticipantFormKind.APP_USAGE
    fun tapped(configuration: MobileReminderConfiguration) = configuration.forms.firstOrNull {
        it.formKind == kind && (it.resourceId?.toString() ?: studyId.toString()) == details.id
    } ?: throw ReminderFormUnavailableException()
    val resourceId = tapped(api.getMobileReminderSchedule(studyId, participantId, server.sourceRandomDeviceId, apiKey)).resourceId
    val accessCode = api.createParticipantFormAccessCode(
        studyId, participantId, server.sourceRandomDeviceId, apiKey,
        CreateParticipantFormAccessCodeRequest(kind, resourceId),
    ).accessCode
    return createNotificationTargetUrl(details.copy(serverUrl = server.url, accessCode = accessCode), studyId.toString(), participantId)
}

internal class ReminderFormUnavailableException : IllegalStateException("Reminder form is no longer available")
