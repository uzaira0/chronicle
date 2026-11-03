package com.openlattice.chronicle.collection.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.PendingCollectionAckEntity
import java.time.OffsetDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CollectionAckCorruptionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    @Before fun installStore() { AuditStores.install(context, enrolled = true) }
    @After fun closeStore() {
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
    }

    @Test fun mutationsCannotOverwriteUnreadableAcknowledgments() {
        val db = requireNotNull(EnrollmentTestStores.db)
        val server = db.uploadServerDao().getConfiguredServer()!!
        val record = PendingCollectionAckRecord.from(server, setOf(CollectionModuleId.USAGE_EVENTS), emptySet(),
            trigger = ConsentTrigger.PARTICIPANT_TOGGLE, acknowledgedAt = OffsetDateTime.now())
        val queue = CollectionAckRetryQueue.of(context)
        for (corrupt in listOf("{", "null")) {
            val row = PendingCollectionAckEntity(stableKey = "corrupt", record = corrupt)
            db.pendingCollectionAckDao().insertAll(listOf(row))
            assertThrows(LocalStoreRecoveryRequiredException::class.java) { queue.enqueue(listOf(record)) }
            assertThrows(LocalStoreRecoveryRequiredException::class.java) { queue.removeByStableKeys(setOf(record.stableKey())) }
            assertEquals(listOf(corrupt), db.pendingCollectionAckDao().getAll().map { it.record })
            db.pendingCollectionAckDao().clear()
        }
        queue.enqueue(listOf(record))
        assertEquals(listOf(record), queue.load())
    }
}
