package com.openlattice.chronicle.api

import com.openlattice.chronicle.api.ChronicleStudyApi.Companion.API_KEY_HEADER
import com.openlattice.chronicle.api.ChronicleStudyApi.Companion.RANDOM_DEVICE_ID_HEADER
import com.openlattice.chronicle.collection.CollectionUploadEvidence

/** Named upload metadata; Retrofit omits absent optional credentials and settings evidence. */
class CollectedUploadHeaders(private val randomDeviceId: String, private val apiKey: String?, val settingsVersion: Int? = null,
                             val decisionSeq: Long? = null) {
    fun toMap(): Map<String, String> = buildMap {
        put(RANDOM_DEVICE_ID_HEADER, randomDeviceId)
        apiKey?.let { put(API_KEY_HEADER, it) }
        settingsVersion?.let { put(CollectionUploadEvidence.SETTINGS_VERSION_HEADER, it.toString()) }
        decisionSeq?.let { put(CollectionUploadEvidence.DECISION_SEQ_HEADER, it.toString()) }
    }

    override fun toString(): String = "CollectedUploadHeaders(redacted)"
}
