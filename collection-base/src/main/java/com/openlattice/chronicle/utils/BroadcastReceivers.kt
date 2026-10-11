package com.openlattice.chronicle.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler

/** Only framework system broadcasts belong in this filter; keep app actions in a private receiver. */
fun registerSystemBroadcastReceiver(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter,
                                    handler: Handler? = null): Intent? {
    // Privileged app UIDs require exported system receivers: https://developer.android.com/develop/background-work/background-tasks/broadcasts#context-registered-receivers
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
    }
    return context.registerReceiver(receiver, filter, null, handler)
}

/** Use private receiver flags, with Core's signature permission on older Android versions. */
fun registerPrivateReceiver(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter,
                            handler: Handler? = null): Intent? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
    }
    // AndroidX Core declares this signature permission in the merged app manifest.
    // This library has no Core dependency, so use the native permission overload.
    val permission = context.packageName + PRIVATE_RECEIVER_PERMISSION_SUFFIX
    if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        throw SecurityException("Private broadcast permission is unavailable")
    }
    return context.registerReceiver(receiver, filter, permission, handler)
}

private const val PRIVATE_RECEIVER_PERMISSION_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
