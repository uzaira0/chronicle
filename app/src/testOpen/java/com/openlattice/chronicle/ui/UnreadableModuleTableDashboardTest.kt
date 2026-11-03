package com.openlattice.chronicle.ui

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.storage.ChronicleDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A module table that cannot be read must fail the dashboard load, not render as a study with no modules. */
@RunWith(RobolectricTestRunner::class)
class UnreadableModuleTableDashboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun unreadableModuleStateFailsTheLoad() {
        AuditStores.install(context, enrolled = true)
        ChronicleDb.getInstance(context).openHelper.writableDatabase.execSQL("DROP TABLE collection_module_state")
        assertThrows(SQLiteException::class.java) { runBlocking { DashboardDataRepository.load(context) } }
    }
}
