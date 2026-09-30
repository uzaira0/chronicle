package com.openlattice.chronicle.preferences

/** Ciphertext is retained; only an explicit recovery may replace the secure store. */
class SecurePreferencesUnavailableException(cause: Throwable) :
    IllegalStateException("Secure preference storage is unavailable", cause)
