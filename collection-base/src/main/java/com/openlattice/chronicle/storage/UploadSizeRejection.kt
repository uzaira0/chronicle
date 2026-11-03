package com.openlattice.chronicle.storage

/** Typed reasons shared by persisted-row admission and request-size quarantine. */
enum class UploadSizeRejection {
    SINGLE_SAMPLE_EXCEEDS_REQUEST_BUDGET,
    SERVER_LIMIT_BELOW_CLIENT_CONTRACT,
    PERSISTED_ROW_EXCEEDS_PAYLOAD_BUDGET,
}
