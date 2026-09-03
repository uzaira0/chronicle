package com.openlattice.chronicle.services.crypto

import java.util.UUID

/**
 * Thrown by an upload path when a study REQUIRES end-to-end payload encryption (HIPAA-2028 W2) but
 * no usable public key is currently cached — e.g. the settings sync or its persistence momentarily
 * failed. The upload path throws this instead of falling back to a plaintext upload, so the batch
 * is retained and retried after the next successful settings sync rather than leaking PHI in
 * plaintext. The per-server upload loops treat it like any other upload failure (frozen cursor /
 * undeleted batch / WorkManager retry).
 */
class EncryptionRequiredButUnavailableException(studyId: UUID) : IllegalStateException(
    "Study $studyId requires end-to-end encryption but no usable public key is cached; " +
        "refusing to upload plaintext (will retry after the next successful settings sync).",
)
