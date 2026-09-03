package com.openlattice.chronicle

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.MIGRATION_10_11
import com.openlattice.chronicle.storage.MIGRATION_11_12
import com.openlattice.chronicle.storage.MIGRATION_12_13
import com.openlattice.chronicle.storage.MIGRATION_13_14
import com.openlattice.chronicle.storage.MIGRATION_14_15
import com.openlattice.chronicle.storage.MIGRATION_15_16
import com.openlattice.chronicle.storage.MIGRATION_16_17
import com.openlattice.chronicle.storage.MIGRATION_17_18
import com.openlattice.chronicle.storage.MIGRATION_18_19
import com.openlattice.chronicle.storage.MIGRATION_19_20
import com.openlattice.chronicle.storage.MIGRATION_20_21
import com.openlattice.chronicle.storage.MIGRATION_21_22
import com.openlattice.chronicle.storage.MIGRATION_3_4
import com.openlattice.chronicle.storage.MIGRATION_4_5
import com.openlattice.chronicle.storage.MIGRATION_5_6
import com.openlattice.chronicle.storage.MIGRATION_6_7
import com.openlattice.chronicle.storage.MIGRATION_7_8
import com.openlattice.chronicle.storage.MIGRATION_8_9
import com.openlattice.chronicle.storage.MIGRATION_9_10
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

internal class IsolatedChronicleTestDb private constructor(
    val db: ChronicleDb,
    private val context: Context,
    private val name: String,
) : AutoCloseable {

    override fun close() {
        db.close()
        deleteDbFiles(context.getDatabasePath(name))
    }

    companion object {
        fun create(prefix: String): IsolatedChronicleTestDb {
            System.loadLibrary("sqlcipher")
            val context = InstrumentationRegistry.getInstrumentation()
                .targetContext
                .applicationContext
            val name = "${prefix}_${System.nanoTime()}.db"
            val passphrase = "chronicle-android-test-${System.nanoTime()}".toByteArray(Charsets.UTF_8)
            val factory = SupportOpenHelperFactory(passphrase)
            val db = Room.databaseBuilder(context, ChronicleDb::class.java, name)
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                    MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
                    MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                )
                .allowMainThreadQueries()
                .build()
            return IsolatedChronicleTestDb(db, context, name)
        }

        private fun deleteDbFiles(dbFile: File) {
            dbFile.delete()
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
            File(dbFile.absolutePath + "-journal").delete()
        }
    }
}
