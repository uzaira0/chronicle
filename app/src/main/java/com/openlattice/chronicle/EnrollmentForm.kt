package com.openlattice.chronicle

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

internal sealed interface EnrollmentScreen {
    val message: Int?
    data class Editing(@param:StringRes override val message: Int? = null) : EnrollmentScreen
    data class Reviewing(val loading: Boolean = true, @param:StringRes override val message: Int? = null) : EnrollmentScreen
    data class Submitting(@param:StringRes override val message: Int? = null) : EnrollmentScreen
    data class Recovery(val kind: RecoveryKind, @param:StringRes override val message: Int = kind.message) : EnrollmentScreen
    data class Result(@param:StringRes override val message: Int) : EnrollmentScreen
    data class Failure(@param:StringRes override val message: Int) : EnrollmentScreen
}

internal enum class RecoveryKind(@param:StringRes val message: Int) {
    PENDING(R.string.enrollment_pending_retry),
    ISSUED(R.string.enrollment_issued_retry),
    STATUS_UNAVAILABLE(R.string.enrollment_recovery_unavailable),
    CANCELLING(R.string.enrollment_pending_retry),
    CANCELLATION_FAILED(R.string.enrollment_cancel_failed);

    val canCancel get() = this == PENDING || this == CANCELLING || this == CANCELLATION_FAILED

    /** Every recovery but a running cancellation offers a retry. */
    val canRetry get() = this != CANCELLING
}

internal val EnrollmentScreen.isBusy: Boolean
    get() = when (this) {
        is EnrollmentScreen.Reviewing -> loading
        is EnrollmentScreen.Submitting -> true
        is EnrollmentScreen.Recovery -> kind == RecoveryKind.CANCELLING
        is EnrollmentScreen.Editing, is EnrollmentScreen.Result, is EnrollmentScreen.Failure -> false
    }

internal fun EnrollmentScreen.withMessage(@StringRes message: Int): EnrollmentScreen = when (this) {
    is EnrollmentScreen.Editing -> copy(message = message)
    is EnrollmentScreen.Reviewing -> copy(message = message)
    is EnrollmentScreen.Submitting -> copy(message = message)
    is EnrollmentScreen.Recovery -> copy(message = message)
    is EnrollmentScreen.Result -> copy(message = message)
    is EnrollmentScreen.Failure -> copy(message = message)
}

internal class EnrollmentForm(
    private val activity: AppCompatActivity,
    onSubmit: () -> Unit,
    onDone: () -> Unit,
) {
    private val studyIdText = activity.findViewById<TextInputEditText>(R.id.studyIdText)
    private val participantIdText = activity.findViewById<TextInputEditText>(R.id.participantIdText)
    private val statusMessageText = activity.findViewById<TextView>(R.id.statusMessage)
    private val progressBar = activity.findViewById<ProgressBar>(R.id.enrollmentProgress)
    private val submitBtn = activity.findViewById<MaterialButton>(R.id.button)
    private val doneBtn = activity.findViewById<MaterialButton>(R.id.doneButton)
    private val participantIdTextView = activity.findViewById<TextView>(R.id.participantIdTextView)
    private val studyIdTextView = activity.findViewById<TextView>(R.id.studyIdTextView)
    private val studyIdTextLayout = activity.findViewById<TextInputLayout>(R.id.studyIdTextLayout)
    private val participantIdTextLayout = activity.findViewById<TextInputLayout>(R.id.participantIdTextLayout)
    private val serverUrlText = activity.findViewById<TextInputEditText>(R.id.serverUrlText)


    init {
        submitBtn.setOnClickListener { onSubmit() }
        doneBtn.setOnClickListener { onDone() }
    }

    fun prefill(link: EnrollmentLink.Prefill) {
        studyIdText.setText(link.studyId?.toString())
        participantIdText.setText(link.participantId)
        serverUrlText.setText(link.serverUrl)
    }

    fun render(state: EnrollmentScreen) {
        val result = state is EnrollmentScreen.Result
        val recovery = state as? EnrollmentScreen.Recovery
        listOf(studyIdTextLayout, participantIdTextLayout, studyIdTextView, participantIdTextView).forEach {
            it.visibility = if (result) View.GONE else View.VISIBLE
        }
        renderButtons(state, result, recovery)
        progressBar.visibility = when {
            result -> View.GONE
            state.isBusy -> View.VISIBLE
            else -> View.INVISIBLE
        }
        state.message?.let { statusMessageText.setText(it) }
        statusMessageText.visibility = if (state.message == null) View.INVISIBLE else View.VISIBLE
        if (state.isBusy && recovery == null) closeKeyBoard()
    }

    private fun renderButtons(state: EnrollmentScreen, result: Boolean, recovery: EnrollmentScreen.Recovery?) {
        val canCancel = recovery?.kind?.canCancel == true
        val canRetry = recovery?.kind?.canRetry == true
        submitBtn.visibility = when {
            result -> View.GONE
            canRetry -> View.VISIBLE
            recovery != null || state is EnrollmentScreen.Submitting -> View.INVISIBLE
            else -> View.VISIBLE
        }
        submitBtn.isEnabled = canRetry || state is EnrollmentScreen.Editing || state is EnrollmentScreen.Failure
        submitBtn.setText(if (canRetry) R.string.enrollment_retry else R.string.action_start_collection)
        doneBtn.visibility = if (result || canCancel) View.VISIBLE else View.INVISIBLE
        doneBtn.isEnabled = recovery?.kind != RecoveryKind.CANCELLING
        doneBtn.setText(if (canCancel) R.string.cancel_pending_enrollment else R.string.enrollment_done)
    }

    private fun closeKeyBoard() {
        val view = activity.currentFocus
        if (view != null) {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    fun enrollmentInvitation(accessCode: String?): EnrollmentInvitation? {
        listOf(studyIdText, participantIdText, serverUrlText).forEach { it.error = null }
        return when (val result = validateEnrollmentInput(studyIdText.text.toString(), participantIdText.text.toString(),
            serverUrlText.text.toString(), accessCode)) {
            is EnrollmentInputResult.Valid -> result.invitation
            is EnrollmentInputResult.Invalid -> {
                val field = when (result.field) {
                    EnrollmentField.STUDY -> studyIdText
                    EnrollmentField.PARTICIPANT -> participantIdText
                    EnrollmentField.SERVER -> serverUrlText
                    null -> null
                }
                field?.error = activity.getString(result.message)
                render(EnrollmentScreen.Editing(result.message))
                null
            }
        }
    }

}
