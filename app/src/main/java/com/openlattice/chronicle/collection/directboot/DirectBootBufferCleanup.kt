package com.openlattice.chronicle.collection.directboot

import android.content.Context
import java.io.File

internal val DIRECT_BOOT_BUFFER_LOCK = Any()

internal fun directBootBufferDir(context: Context): File =
    File(context.createDeviceProtectedStorageContext().filesDir, "direct_boot_sensor_buffer")

/** Erases every pre-unlock sample file; withdrawal, recovery and module erasure call this. */
fun clearDirectBootSensorBuffer(context: Context): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
    val dir = directBootBufferDir(context)
    !dir.exists() || dir.deleteRecursively()
}

/** Unreadable archives cannot prove that an erased sensor is absent. */
internal fun clearCorruptDirectBootSensorBuffers(context: Context): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
    val dir = directBootBufferDir(context)
    if (!dir.exists()) return@synchronized true
    val files = dir.listFiles() ?: return@synchronized false
    files.filter { it.name.startsWith("corrupt-") }.all { it.deleteRecursively() && !it.exists() }
}
