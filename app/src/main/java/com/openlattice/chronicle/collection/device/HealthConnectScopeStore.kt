package com.openlattice.chronicle.collection.device

import android.content.Context
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper

/** Encrypted, fail-closed snapshot of the active study's approved Health Connect record types. */
public class HealthConnectScopeStore private constructor(context: Context) {
    private val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext)

    public fun read(): Set<HealthConnectRecordType> =
        prefs.getStringSet(KEY_RECORD_TYPES, emptySet())
            .orEmpty()
            .mapNotNullTo(LinkedHashSet()) { id ->
                runCatching { HealthConnectRecordType.fromId(id) }.getOrNull()
            }

    public fun replace(recordTypes: Set<HealthConnectRecordType>) {
        check(
            prefs.edit()
                .putStringSet(KEY_RECORD_TYPES, recordTypes.mapTo(LinkedHashSet()) { it.id })
                .commit(),
        ) { "Failed to persist the Health Connect study scope" }
    }

    public fun clear() {
        check(prefs.edit().remove(KEY_RECORD_TYPES).commit()) {
            "Failed to clear the Health Connect study scope"
        }
    }

    public companion object {
        private const val KEY_RECORD_TYPES = "health_connect_study_record_types"

        @JvmStatic
        public fun of(context: Context): HealthConnectScopeStore = HealthConnectScopeStore(context)
    }
}
