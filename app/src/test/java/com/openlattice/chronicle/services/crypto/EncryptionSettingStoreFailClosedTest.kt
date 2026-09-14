package com.openlattice.chronicle.services.crypto

import com.openlattice.chronicle.collection.directboot.InMemorySharedPreferences
import com.openlattice.chronicle.crypto.EnvelopeCipher
import com.openlattice.chronicle.study.StudyEncryptionSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The e2ee routing gate must never read "we don't know" as "plaintext is fine".
 *
 * The failing scenario this pins: a freshly enrolled device has no cached encryption policy;
 * `CollectionLoopCoordinator.syncEncryptionSetting` swallows a timeout/500/403 from the settings
 * read, and `ChronicleSyncWorker` only *enqueues* the settings refresh before collecting and
 * uploading in the same run. Before this, `isEncryptionRequired` was false for that study, so
 * `PayloadSealer.routing` returned PLAINTEXT and the batch went out unencrypted for a study whose
 * server-side policy is e2ee. It now returns FAIL_CLOSED, and the upload paths retain + retry.
 */
class EncryptionSettingStoreFailClosedTest {

    private val prefs = InMemorySharedPreferences()
    private val store = EncryptionSettingStore(prefs)

    @Test
    fun unfetchedPolicyIsTreatedAsEncryptionRequired() {
        val studyId = UUID.randomUUID()

        assertFalse("no fetch has landed", store.isPolicyKnown(studyId))
        assertTrue("an unknown policy must fail closed", store.isEncryptionRequired(studyId))
        assertEquals(
            PayloadSealer.EncryptionRouting.FAIL_CLOSED,
            PayloadSealer.routing(store.get(studyId), store.isEncryptionRequired(studyId)),
        )
    }

    @Test
    fun onlyAnAuthoritativeDisabledFetchPermitsPlaintext() {
        val studyId = UUID.randomUUID()

        store.put(studyId, StudyEncryptionSetting())

        assertTrue(store.isPolicyKnown(studyId))
        assertFalse(store.isEncryptionRequired(studyId))
        assertEquals(
            PayloadSealer.EncryptionRouting.PLAINTEXT,
            PayloadSealer.routing(store.get(studyId), store.isEncryptionRequired(studyId)),
        )
    }

    @Test
    fun anEnabledSettingWithoutUsableKeyMaterialStaysRequired() {
        val studyId = UUID.randomUUID()

        // Legacy RSA-only setting: enabled, but PayloadSealer cannot seal without the ML-KEM half.
        store.put(
            studyId,
            StudyEncryptionSetting(
                enabled = true,
                keyId = "k1",
                algorithm = EnvelopeCipher.LEGACY_ALG,
                publicKeyPem = RSA_PUBLIC_KEY_PEM,
            ),
        )

        assertTrue(store.isEncryptionRequired(studyId))
        assertEquals(
            PayloadSealer.EncryptionRouting.FAIL_CLOSED,
            PayloadSealer.routing(store.get(studyId), store.isEncryptionRequired(studyId)),
        )
    }

    @Test
    fun evictionReturnsTheStudyToTheFailClosedUnknownState() {
        val studyId = UUID.randomUUID()
        store.put(studyId, StudyEncryptionSetting())
        assertFalse(store.isEncryptionRequired(studyId))

        store.evict(studyId)

        assertFalse(store.isPolicyKnown(studyId))
        assertTrue(store.isEncryptionRequired(studyId))
    }

    @Test
    fun anUnparseableStudyIdFailsClosed() {
        assertTrue(store.isEncryptionRequired("not-a-uuid"))
    }

    private companion object {
        /** Shape-only public key: the store never parses it, it only records the policy. */
        const val RSA_PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAO=\n-----END PUBLIC KEY-----"
    }
}
