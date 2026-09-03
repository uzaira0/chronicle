package com.openlattice.chronicle.collection.directboot

import android.content.Context
import android.os.Build
import java.io.File

internal const val DIRECT_BOOT_BUFFER_DIR_NAME = "direct_boot_sensor_buffer"
internal const val DIRECT_BOOT_LIVE_FILE_NAME = "buffer.bin"
internal const val DIRECT_BOOT_DRAINING_FILE_NAME = "buffer.draining.bin"
internal val DIRECT_BOOT_BUFFER_LOCK = Any()

internal fun directBootFilesDir(context: Context): File =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext().filesDir
    } else {
        context.filesDir
    }

/**
 * Erases the legacy pre-unlock sensor buffer without packaging its capture, encryption, or
 * replay implementation. Minimal Play and Amazon builds call this during update, withdrawal,
 * and recovery so data written by an older research build cannot survive an enrollment boundary.
 */
fun clearDirectBootSensorBuffer(context: Context): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
    val dir = File(directBootFilesDir(context), DIRECT_BOOT_BUFFER_DIR_NAME)
    val liveFile = File(dir, DIRECT_BOOT_LIVE_FILE_NAME)
    val drainingFile = File(dir, DIRECT_BOOT_DRAINING_FILE_NAME)
    val liveCleared = !liveFile.exists() || liveFile.delete()
    val drainingCleared = !drainingFile.exists() || drainingFile.delete()
    if (liveCleared && drainingCleared) dir.delete()
    liveCleared && drainingCleared
}
