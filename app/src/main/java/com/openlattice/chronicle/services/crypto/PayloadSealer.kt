package com.openlattice.chronicle.services.crypto

import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.crypto.EnvelopeCipher
import com.openlattice.chronicle.crypto.PemKeys
import com.openlattice.chronicle.study.StudyEncryptionSetting
import java.util.UUID

/**
 * Envelope-encrypts an upload batch for a study (HIPAA-2028 W2, device side).
 *
 * Centralizes the two decisions the three upload paths share:
 *  - [isEncryptionEnabled]: is end-to-end payload encryption ON for this study? — the single
 *    source of truth each path consults to pick the encrypted endpoint vs. the existing
 *    plaintext one.
 *  - [seal]: turn the EXACT plaintext bytes a stream would otherwise post into an
 *    [EncryptedEnvelope] the backend can decrypt.
 *
 * Correctness contract (the server must be able to decrypt):
 *  1. [plaintext] MUST be the exact bytes the plaintext path would send — serialize the batch
 *     with the SAME JSON boundary the upload path uses ([JsonSerializer])
 *     BEFORE calling [seal]. This object does not serialize; it only seals raw bytes, so the
 *     caller controls (and the tests pin) the exact serialization.
 *  2. The AAD binds the ciphertext to `version|studyId|participantId|payloadType` via
 *     [EnvelopeCipher.aad], built from the SAME studyId + participantId the request is for.
 *     The server rebuilds the AAD from the trusted path values, so any mismatch fails decryption.
 *  3. [sampleCount] is the cleartext sample count (an ingest metric), carried alongside the
 *     ciphertext.
 *  4. The public key + keyId come from the cached [StudyEncryptionSetting].
 *
 * Pure JVM (no Android dependency) so the parity + routing tests run as plain unit tests.
 */
object PayloadSealer {

    /**
     * True when the study has e2ee turned on and usable public keys: the setting exists, is
     * [StudyEncryptionSetting.enabled], and carries BOTH a non-blank
     * [StudyEncryptionSetting.publicKeyPem] (RSA) and [StudyEncryptionSetting.mlkemPublicKey]
     * (ML-KEM) for the hybrid wrap. A null / disabled / key-less setting ⇒ false ⇒ the caller
     * stays on the plaintext path (unless e2ee is required for the study — see [routing]).
     */
    fun isEncryptionEnabled(setting: StudyEncryptionSetting?): Boolean =
        setting != null && setting.enabled &&
            setting.publicKeyPem.isNotBlank() && setting.mlkemPublicKey.isNotBlank()

    /** What an upload path should do for a study, given its cached setting + whether e2ee is required. */
    enum class EncryptionRouting { PLAINTEXT, ENCRYPT, FAIL_CLOSED }

    /**
     * The ONE place the encrypted / plaintext / fail-closed routing decision is made:
     *  - [EncryptionRouting.ENCRYPT] — e2ee is on with a usable key: seal + post to the encrypted endpoint.
     *  - [EncryptionRouting.FAIL_CLOSED] — the study REQUIRES e2ee ([encryptionRequired]) but no usable key
     *    is cached (sync/persistence momentarily failed): the caller MUST NOT send plaintext PHI — it skips
     *    the upload and retries (via [EncryptionRequiredButUnavailableException]).
     *  - [EncryptionRouting.PLAINTEXT] — the study does not use e2ee: the existing plaintext path, unchanged.
     */
    fun routing(setting: StudyEncryptionSetting?, encryptionRequired: Boolean): EncryptionRouting = when {
        isEncryptionEnabled(setting) -> EncryptionRouting.ENCRYPT
        encryptionRequired -> EncryptionRouting.FAIL_CLOSED
        else -> EncryptionRouting.PLAINTEXT
    }

    /**
     * Seals [plaintext] into an [EncryptedEnvelope] for the study described by [setting].
     *
     * @param plaintext the exact serialized batch bytes the plaintext path would have posted.
     * @param sampleCount number of samples in the batch (cleartext metric).
     * @throws IllegalStateException if called when [isEncryptionEnabled] is false (callers must
     *   gate on it first — this is a defensive guard, not a normal path).
     */
    fun seal(
        setting: StudyEncryptionSetting,
        studyId: UUID,
        participantId: String,
        payloadType: EncryptedPayloadType,
        plaintext: ByteArray,
        sampleCount: Int,
    ): EncryptedEnvelope {
        check(isEncryptionEnabled(setting)) {
            "PayloadSealer.seal called for a study without enabled encryption"
        }
        val rsaPublicKey = PemKeys.rsaPublicKey(setting.publicKeyPem)
        val mlkemPublicKey = EnvelopeCipher.decodeMlkemPublicKey(setting.mlkemPublicKey)
        val aad = EnvelopeCipher.aad(
            EnvelopeCipher.ENVELOPE_VERSION,
            studyId,
            participantId,
            payloadType,
        )
        return EnvelopeCipher.seal(
            rsaPublicKey = rsaPublicKey,
            mlkemPublicKey = mlkemPublicKey,
            keyId = setting.keyId,
            payloadType = payloadType,
            plaintext = plaintext,
            aad = aad,
            sampleCount = sampleCount,
        )
    }
}
