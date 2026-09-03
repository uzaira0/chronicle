package com.openlattice.chronicle.api

import java.util.UUID

/**
 * Response from a server's mobile enrollment endpoint.
 *
 * Current self-host servers return a JSON object: `{"chronicleId": "<uuid>", "apiKey": "ck_..."}`.
 * The upstream Chronicle server (backup) returns a bare UUID string: `"<uuid>"`.
 *
 * The Chronicle JSON adapter transparently handles both shapes so the same
 * client code can talk to either server without conditional logic at call sites.
 */
data class EnrollmentResponse(
    val chronicleId: UUID,
    val enrollmentId: UUID? = null,
    val apiKey: String? = null
)
