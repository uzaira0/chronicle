package com.openlattice.chronicle.receivers.lifecycle

import com.openlattice.chronicle.EnrolledWork
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class NotificationDismissedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }
        val notificationId = intent.let {
            it.extras?.getInt(context.getString(R.string.notification_id))
        }

        val dismissNotificationId =
            context.resources?.getInteger(R.integer.dismiss_target_user_notification_id)
        if (dismissNotificationId == notificationId) {
            val pendingResult = goAsync()
            try {
                IO_EXECUTOR.execute {
                    try {
                        val result = TargetUserRouter.setTargetUser(
                            context.applicationContext,
                            DeviceUser.NOT_SET,
                        )
                        if (result is ModuleResult.Ok) {
                            EnrolledWork.rescheduleSync(context.applicationContext)
                        } else {
                            Log.w(TAG, "Target-user reset failed: ${result.label}")
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Target-user reset after notification dismissal failed", error)
                    } finally {
                        pendingResult.finish()
                    }
                }
            } catch (error: RejectedExecutionException) {
                pendingResult.finish()
                Log.e(TAG, "Target-user reset executor rejected work", error)
            }
        }
    }

    private companion object {
        const val TAG = "NotificationDismissedReceiver"
        val IO_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
