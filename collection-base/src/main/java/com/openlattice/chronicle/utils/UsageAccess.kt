package com.openlattice.chronicle.utils

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.UserManager

/** Usage Access is an AppOp, not a runtime permission. Denied reads must not advance a cursor. */
object UsageAccess {
    @Suppress("DEPRECATION")
    fun isGranted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid, context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun canRead(context: Context): Boolean = isGranted(context) &&
        (Build.VERSION.SDK_INT < 30 || context.getSystemService(UserManager::class.java)?.isUserUnlocked == true)

    /** Null means the read was skipped; callers must retain their checkpoint. */
    fun queryEvents(context: Context, startMillis: Long, endMillis: Long): UsageEvents? {
        if (!canRead(context)) return null
        val events = context.getSystemService(UsageStatsManager::class.java)?.queryEvents(startMillis, endMillis)
        // A denied query returns empty, not an error; recheck so a revocation mid-query never reads as "no usage".
        return events.takeIf { canRead(context) }
    }

    fun queryUsageStats(context: Context, interval: Int, startMillis: Long, endMillis: Long): List<UsageStats>? {
        if (!canRead(context)) return null
        val stats = context.getSystemService(UsageStatsManager::class.java)?.queryUsageStats(interval, startMillis, endMillis)
        return stats.takeIf { canRead(context) }
    }
}
