package com.openlattice.chronicle.sensors

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE_PRE_26
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.content.Context
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.models.ExtractedActivities
import java.util.*


class ActivityManagerChronicleSensor(val context: Context) : ChronicleSensor {
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override fun poll(currentPollTimestamp:Long, users:NavigableMap<Long,String>): ChronicleData {
        return ChronicleData(activityManager.runningAppProcesses.map {
            ExtractedActivities(it.processName, mapImportance(it.importance))
        })
    }

    private fun mapImportance(importance: Int): String {
        return when (importance) {
            IMPORTANCE_FOREGROUND -> "Foreground UI"
            IMPORTANCE_FOREGROUND_SERVICE -> "Foreground Service"
            IMPORTANCE_PERCEPTIBLE -> "Perceptible"
            IMPORTANCE_PERCEPTIBLE_PRE_26 -> "Perceptible Pre-O"
            IMPORTANCE_TOP_SLEEPING -> "Foreground UI - Sleeping"
            IMPORTANCE_VISIBLE -> "Visible - Not In Foreground"
            else -> {
                return "Unknown importance: $importance"
            }
        }
    }


}
