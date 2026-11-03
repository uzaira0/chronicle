package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * The one Robolectric shadow of [RestrictedUploadApiFactory]; each test installs its own [transport]. Two shadow classes
 * of the same class in different test classes leaked into each other's runs depending on the suite order.
 */
@Implements(value = RestrictedUploadApiFactory::class, isInAndroidSdk = false)
internal class RestrictedApiShadow {
    @Implementation fun get(url: String): RestrictedChronicleStudyApi = transport(url)

    companion object {
        @Volatile var transport: (String) -> RestrictedChronicleStudyApi = { error("Install a restricted study API transport") }
    }
}
