package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import com.openlattice.chronicle.utils.Utils
import com.openlattice.chronicle.utils.Utils.createRetrofitAdapter
import java.util.concurrent.ConcurrentHashMap

/** Retrofit surface for collectors that are intentionally absent from public artifacts. */
internal object RestrictedUploadApiFactory {
    private val cache = ConcurrentHashMap<String, RestrictedChronicleStudyApi>()

    fun get(url: String): RestrictedChronicleStudyApi {
        val trustedUrl = Utils.normalizeTrustedServerUrl(url)
            ?: throw IllegalArgumentException("Untrusted Chronicle server URL")
        return cache.getOrPut(trustedUrl) {
            createRetrofitAdapter(trustedUrl)
                .create(RestrictedChronicleStudyApi::class.java)
        }
    }
}
