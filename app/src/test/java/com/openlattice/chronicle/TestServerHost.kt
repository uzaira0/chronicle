package com.openlattice.chronicle

import com.openlattice.chronicle.utils.Utils
import org.junit.Assume.assumeTrue

/**
 * An upload-server host the flavor under test accepts. Public flavors accept any https host;
 * research accepts only its configured CHRONICLE_PRODUCTION_HOST.
 */
internal val TEST_SERVER_HOST: String =
    BuildConfig.CHRONICLE_PRODUCTION_HOST.ifBlank { "research.example.org" }

/** Research built without CHRONICLE_PRODUCTION_HOST trusts no https host: skip, do not fail. */
internal fun assumeTestServerHostTrusted() = assumeTrue(
    "flavor trusts no https host (research without CHRONICLE_PRODUCTION_HOST)",
    Utils.normalizeTrustedServerUrl("https://$TEST_SERVER_HOST") != null,
)
