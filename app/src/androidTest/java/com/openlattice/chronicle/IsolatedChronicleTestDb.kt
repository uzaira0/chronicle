package com.openlattice.chronicle

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.openlattice.chronicle.storage.ChronicleDb
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
                .addMigrations(*ChronicleDb.ALL_MIGRATIONS)
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
