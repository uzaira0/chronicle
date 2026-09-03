package com.openlattice.chronicle

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.sync.scheduleChronicleSyncWork
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserIdentificationActivity : AppCompatActivity() {
    private lateinit var childUserBtn: Button
    private lateinit var otherUserBtn: Button

    private lateinit var settings: EnrollmentSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_identification)
        padViewForSystemBars(R.id.user_identification_scroll)

        try {
            settings = EnrollmentSettings(this)
        } catch (error: LocalStoreRecoveryRequiredException) {
            startActivity(LocalStoreRecoveryActivity.intent(this, error.recoveryReason))
            finish()
            return
        }
        childUserBtn = findViewById(R.id.child_user_btn)
        otherUserBtn = findViewById(R.id.other_user_btn)

        // listeners
        otherUserBtn.setOnClickListener {
            handleOnSave(it.id)
        }

        childUserBtn.setOnClickListener {
            handleOnSave(it.id)
        }
    }

    private fun handleOnSave(buttonId: Int) {
        val targetUser = if (buttonId == R.id.other_user_btn) getString(R.string.user_other) else getString(R.string.user_target_child)
        childUserBtn.isEnabled = false
        otherUserBtn.isEnabled = false

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    TargetUserRouter.setTargetUser(this@UserIdentificationActivity, targetUser, settings)
                }
            } catch (error: LocalStoreRecoveryRequiredException) {
                startActivity(LocalStoreRecoveryActivity.intent(this@UserIdentificationActivity, error.recoveryReason))
                finish()
                return@launch
            }

            if (result is ModuleResult.Ok) {
                scheduleChronicleSyncWork(this@UserIdentificationActivity)
                Toast.makeText(
                    this@UserIdentificationActivity,
                    getString(R.string.user_set_toast, targetUser),
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            } else {
                childUserBtn.isEnabled = true
                otherUserBtn.isEnabled = true
                Toast.makeText(
                    this@UserIdentificationActivity,
                    getString(R.string.user_save_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
