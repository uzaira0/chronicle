package com.openlattice.chronicle

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Extracts the short-lived enrollment capability from a URL fragment without persisting it. */
internal object EnrollmentLinkCredential {
    private const val MIN_TOKEN_CHARS = 32
    private const val MAX_TOKEN_CHARS = 256

    fun fromFragment(fragment: String?): String? {
        val encoded = fragment
            ?.split('&')
            ?.mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
            ?.firstOrNull { (name) -> name == "accessCode" }
            ?.get(1)
            ?: return null
        val token = runCatching {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null
        return token.takeIf {
            it.length in MIN_TOKEN_CHARS..MAX_TOKEN_CHARS &&
                it.all { character -> character.isLetterOrDigit() || character == '-' || character == '_' }
        }
    }
}
