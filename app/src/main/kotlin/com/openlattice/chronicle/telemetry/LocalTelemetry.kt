package com.openlattice.chronicle.telemetry

import android.os.Bundle
import android.util.Log

/**
 * Local-only operational telemetry for the dogfood build.
 *
 * Nothing leaves the device. Events and exceptions are available through Logcat
 * during tester-assisted debugging.
 */
object LocalTelemetry {
    private const val TAG = "ChronicleTelemetry"

    fun logEvent(name: String, parameters: Bundle?) {
        val details = parameters?.keySet()
            ?.sorted()
            ?.joinToString(prefix = " fields=[", postfix = "]")
            .orEmpty()
        Log.d(TAG, "$name$details")
    }

    fun recordException(error: Throwable) {
        Log.w(TAG, error.message ?: error::class.java.simpleName, error)
    }

    fun log(message: String) {
        Log.d(TAG, message)
    }
}
