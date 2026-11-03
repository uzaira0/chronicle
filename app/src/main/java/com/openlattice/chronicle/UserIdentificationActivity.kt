package com.openlattice.chronicle

import android.os.Bundle
import android.content.Context
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.ui.storageFailureHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserIdentificationActivity : AppCompatActivity() {
    private lateinit var participantUserBtn: Button
    private lateinit var someoneElseUserBtn: Button

    private val saveState: SaveState by viewModels()

    internal class SaveState : ViewModel() {
        var saving = false
            private set
        lateinit var targetUser: DeviceUser
            private set
        val outcome = MutableLiveData<Result<ModuleResult>?>()

        fun save(context: Context, user: DeviceUser) {
            if (saving) return
            saving = true
            targetUser = user
            val appContext = context.applicationContext
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    TargetUserRouter.setTargetUser(appContext, user).also {
                        if (it is ModuleResult.Ok) EnrolledWork.rescheduleSync(appContext)
                    }
                } }
                saving = false
                outcome.value = result
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_identification)

        participantUserBtn = findViewById(R.id.participant_user_btn)
        someoneElseUserBtn = findViewById(R.id.someone_else_user_btn)
        participantUserBtn.isEnabled = false
        someoneElseUserBtn.isEnabled = false
        lifecycleScope.launch(storageFailureHandler()) {
            withContext(Dispatchers.IO) { EnrollmentSettings(applicationContext) }
            participantUserBtn.isEnabled = !saveState.saving
            someoneElseUserBtn.isEnabled = !saveState.saving
        }
        saveState.outcome.observe(this) { outcome ->
            if (outcome == null) return@observe
            saveState.outcome.value = null
            participantUserBtn.isEnabled = true
            someoneElseUserBtn.isEnabled = true
            lifecycleScope.launch(storageFailureHandler()) {
                val result = outcome.getOrThrow()
                if (result is ModuleResult.Ok) {
                    Toast.makeText(this@UserIdentificationActivity,
                        getString(R.string.user_set_toast, getString(TargetUserRouter.label(saveState.targetUser))), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@UserIdentificationActivity,
                        getString(TargetUserRouter.failureMessage(result)), Toast.LENGTH_LONG).show()
                }
            }
        }

        // listeners
        someoneElseUserBtn.setOnClickListener {
            handleOnSave(it.id)
        }

        participantUserBtn.setOnClickListener {
            handleOnSave(it.id)
        }
    }

    private fun handleOnSave(buttonId: Int) {
        val targetUser = if (buttonId == R.id.someone_else_user_btn) DeviceUser.SOMEONE_ELSE else DeviceUser.STUDY_PARTICIPANT
        participantUserBtn.isEnabled = false
        someoneElseUserBtn.isEnabled = false

        saveState.save(applicationContext, targetUser)
    }
}
