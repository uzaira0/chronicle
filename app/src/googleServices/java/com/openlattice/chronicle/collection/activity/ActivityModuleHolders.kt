package com.openlattice.chronicle.collection.activity

import android.content.Context
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.activityRecognitionSampleDao
import com.openlattice.chronicle.storage.sleepSampleDao

/**
 * App-scoped holders for the two Play Services push modules (sleep, activity_recognition). They are
 * registry citizens whose diagnostics report the pending-row depth of their buffer table; capture
 * itself is driven by [SleepActivityCaptureController] (GMS registration) and persisted by
 * [SleepActivityReceiver]. Each holder builds lazily from the application context and holds no
 * `Context`.
 */
public object SleepModuleHolder {
    @Volatile private var instance: SleepCollectionModule? = null

    public fun get(context: Context): SleepCollectionModule {
        instance?.let { return it }
        val appContext = context.applicationContext
        return synchronized(this) {
            instance ?: SleepCollectionModule(
                queueDepth = { ChronicleDb.getInstance(appContext).sleepSampleDao().count() },
            ).also { instance = it }
        }
    }
}

public object ActivityRecognitionModuleHolder {
    @Volatile private var instance: ActivityRecognitionCollectionModule? = null

    public fun get(context: Context): ActivityRecognitionCollectionModule {
        instance?.let { return it }
        val appContext = context.applicationContext
        return synchronized(this) {
            instance ?: ActivityRecognitionCollectionModule(
                queueDepth = { ChronicleDb.getInstance(appContext).activityRecognitionSampleDao().count() },
            ).also { instance = it }
        }
    }
}
