package com.openlattice.chronicle.utils

import android.content.Context
import android.content.pm.PackageManager

/**
 * R/BuildConfig-free package-label lookup, extracted from `:app`'s `utils.Utils` so
 * `:collection-base` and the collection library modules can resolve app labels without a
 * `:app` dependency.
 *
 * Behaviour is identical to the historical `Utils.getAppFullName`: the human-readable
 * application label for [packageName], or [packageName] itself if it cannot be resolved.
 */
object PackageLabels {

    fun getAppFullName(context: Context, packageName: String): String {
        return try {
            val packageManager = context.applicationContext.packageManager
            val applicationInfo =
                packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
