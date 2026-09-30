package com.openlattice.chronicle.collection.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.storage.ChronicleDb
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lost and refused writes are counted only for the enrollment that produced them. */
@RunWith(RobolectricTestRunner::class)
class EnrollmentBoundLossTest {
    @get:org.junit.Rule val backgroundPersistence = com.openlattice.chronicle.collection.state.BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db get() = ChronicleDb.getInstance(context)

    @Before fun setUp() {
        TestStores.install(context, enrolled = true)
        ResearchPersistenceGate.initialize(context)
    }

    private fun diagnosticCount(issue: String) = db.openHelper.readableDatabase
        .query("SELECT coalesce(sum(count), 0) FROM upload_diagnostics WHERE issueCode = ?", arrayOf(issue))
        .use { it.moveToFirst(); it.getInt(0) }

    @Test fun failedWriteCountsEveryLostRecord() {
        val owner = ResearchPersistenceGate.captureOwner(context)
        assertNotNull("fixture must be an active enrollment", owner)

        val persisted = ResearchPersistenceGate.persistIfActive(context, records = 3, expectedOwner = owner!!) {
            throw IOException("disk full")
        }

        assertFalse(persisted)
        assertEquals(3, diagnosticCount("LOCAL_WRITE_FAILED"))
    }

    @Test fun refusalIsCountedForTheSameEnrollment() {
        val owner = ResearchPersistenceGate.captureOwner(context)!!
        val refused = mutableListOf<Int>()

        // Not among the fixture's enabled modules, so the module gate refuses.
        val persisted = ResearchPersistenceGate.persistIfCollecting(
            context, CollectionModuleId.NOTIFICATION_ACTIVITY, records = 2,
            expectedOwner = owner, onRefused = { refused += it },
        ) { error("must not persist") }

        assertFalse(persisted)
        assertEquals(listOf(2), refused)
    }

    @Test fun refusalFromAReplacedEnrollmentIsNotChargedToTheNewOne() {
        val oldOwner = ResearchPersistenceGate.captureOwner(context)!!
        // Withdraw and re-enroll: same study and participant, new enrollment row epoch.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE upload_servers SET createdAt = '2099-01-01T00:00:00Z' WHERE id = ${oldOwner.id}",
        )
        val refused = mutableListOf<Int>()

        val persisted = ResearchPersistenceGate.persistIfCollecting(
            context, CollectionModuleId.USAGE_EVENTS, records = 2,
            expectedOwner = oldOwner, onRefused = { refused += it },
        ) { error("must not persist") }

        assertFalse(persisted)
        assertEquals(emptyList<Int>(), refused)
    }
}
