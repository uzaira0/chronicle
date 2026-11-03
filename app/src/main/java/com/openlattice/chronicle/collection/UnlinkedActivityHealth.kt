package com.openlattice.chronicle.collection

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.openlattice.chronicle.collection.activity.ACTIVITY_REGISTRATION_PREFS
import com.openlattice.chronicle.collection.device.HEALTH_CONNECT_PREFS
import com.openlattice.chronicle.storage.checkLocalStoreWrite

/** Builds without the sleep/activity and Health Connect SDKs: erasure clears their local state, requests do nothing. */
internal object UnlinkedActivityHealth {
    fun eraseActivityRegistration(context: Context, module: CollectionModuleId) {
        checkLocalStoreWrite(context.getSharedPreferences(ACTIVITY_REGISTRATION_PREFS, Context.MODE_PRIVATE)
            .edit().remove(module.id).commit())
    }

    fun eraseHealthSource(context: Context) {
        checkLocalStoreWrite(context.getSharedPreferences(HEALTH_CONNECT_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit())
    }

    val permissionContract: ActivityResultContract<Set<String>, Set<String>> =
        object : ActivityResultContract<Set<String>, Set<String>>() {
            override fun createIntent(context: Context, input: Set<String>): Intent = Intent()
            override fun parseResult(resultCode: Int, intent: Intent?): Set<String> = emptySet()
        }
}
