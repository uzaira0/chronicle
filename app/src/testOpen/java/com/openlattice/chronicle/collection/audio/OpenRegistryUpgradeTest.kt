package com.openlattice.chronicle.collection.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.directboot.KeystoreDirectBootRecordCipher
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.upload.DistributionUploadPolicy
import com.openlattice.chronicle.storage.AudioContentSampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.audioContentSampleDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [OpenRegistryUpgradeTest.TestCipher::class, OpenRegistryUpgradeTest.TestCipherKeys::class])
class OpenRegistryUpgradeTest {
    @Implements(value = KeystoreDirectBootRecordCipher::class, isInAndroidSdk = false)
    class TestCipher {
        @Implementation fun encrypt(plaintext: ByteArray): ByteArray = plaintext.reversedArray()
        @Implementation fun decrypt(blob: ByteArray): ByteArray = blob.reversedArray()
    }

    @Implements(value = KeystoreDirectBootRecordCipher.Companion::class, isInAndroidSdk = false)
    class TestCipherKeys { @Implementation fun ensureKey() = Unit }

    @get:Rule val persistence = BackgroundPersistenceRule()

    @Test fun savedAudioContentAcceptanceIsGatedAndKeptWhileApprovedModulesContinue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AuditStores.install(context, true, mapOf(CollectionModuleId.AUDIO_CONTENT to CollectionModuleSetting(enabled = true)))
        val db = ChronicleDb.getInstance(context)
        val approvedState = db.collectionModuleStateDao().get(CollectionModuleId.USAGE_EVENTS.id)
        db.queueEntryData().insertEntry(QueueEntry(1, 1, byteArrayOf(1)))
        val row = AudioContentSampleEntry("saved-media", "2026-10-01T00:00:00Z", "UTC", "com.example.player",
            "title", "artist", "album", null, null)
        db.audioContentSampleDao().insertAll(listOf(row))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(context)
        assertFalse(CollectionLoopStore.of(context).collects(CollectionModuleId.AUDIO_CONTENT))
        assertFalse(ResearchPersistenceGate.captureObservation(context, CollectionModuleId.AUDIO_CONTENT).persist {
            db.audioContentSampleDao().insertAll(listOf(row.copy(id = "late-media")))
        })
        assertEquals(1, db.audioContentSampleDao().count())
        assertFalse(DistributionUploadPolicy.allows(UploadQueueTable.AUDIO_CONTENT))
        assertEquals("an excluded queue is never acknowledged as uploaded", 1, db.audioContentSampleDao().count())
        assertEquals("an app update erases nothing", approvedState,
            db.collectionModuleStateDao().get(CollectionModuleId.USAGE_EVENTS.id))
        assertEquals("approved queue kept", 1, db.queueEntryData().getSize())

        ResearchPersistenceGate.initialize(context)
        assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.USAGE_EVENTS))
        assertFalse(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.AUDIO_CONTENT))
    }
}
