package com.openlattice.chronicle.storage

import android.content.Context
import android.util.Log
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

private const val TAG = "ChronicleDb"
private const val DB_NAME = "chronicle"
private const val ENCRYPTED_DB_NAME = "chronicle_encrypted"

@Database(
    entities = [
        QueueEntry::class,
        UserQueueEntry::class,
        SensorSampleEntry::class,
        BatterySampleEntry::class,
        InteractionSampleEntry::class,
        AudioActivitySampleEntry::class,
        AudioContentSampleEntry::class,
        NotificationActivitySampleEntry::class,
        SleepSampleEntry::class,
        ActivityRecognitionSampleEntry::class,
        HealthMetricSampleEntry::class,
        ConnectivityStateSampleEntry::class,
        AppNetworkUsageSampleEntry::class,
        DeviceSettingsSampleEntry::class,
        UploadServerEntity::class,
        SensorSampleDeliveryEntity::class,
        SensorSampleDeadLetterEntity::class,
        UploadStatsEntity::class,
        UsagePollCheckpointEntity::class,
        CollectionModuleStateEntity::class
    ],
    version = 28,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3)
    ]
)
abstract class ChronicleDb : RoomDatabase() {
    abstract fun queueEntryData(): StorageQueue
    abstract fun userQueueEntryData(): UserStorageQueue
    abstract fun sensorSampleDao(): SensorSampleDao
    abstract fun batterySampleDao(): BatterySampleDao
    abstract fun connectivityStateSampleDao(): ConnectivityStateSampleDao
    abstract fun appNetworkUsageSampleDao(): AppNetworkUsageSampleDao
    abstract fun deviceSettingsSampleDao(): DeviceSettingsSampleDao
    abstract fun uploadServerDao(): UploadServerDao
    abstract fun uploadStatsDao(): UploadStatsDao
    abstract fun usagePollCheckpointDao(): UsagePollCheckpointDao
    abstract fun collectionModuleStateDao(): CollectionModuleStateDao

    companion object {
        @Volatile
        private var INSTANCE: ChronicleDb? = null

        fun getInstance(context: Context): ChronicleDb {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildEncryptedDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildEncryptedDatabase(context: Context): ChronicleDb {
            System.loadLibrary("sqlcipher")
            migrateUnencryptedDb(context)

            val encryptedDbFile = context.getDatabasePath(ENCRYPTED_DB_NAME)
            val passphrase = DatabaseKeyManager.getPassphrase(
                context,
                allowCreate = !databaseBundleExists(encryptedDbFile)
            )
            val factory = SupportOpenHelperFactory(passphrase)

            var databaseOpened = false
            return try {
                val db = buildRoomInstance(context, factory)
                db.openHelper.writableDatabase
                databaseOpened = true
                val oldDbFile = context.getDatabasePath(DB_NAME)
                if (oldDbFile.exists()) {
                    deleteDbFiles(oldDbFile)
                }
                db
            } catch (e: LocalStoreRecoveryRequiredException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted database open failed; preserving the database bundle for recovery.")
                throw LocalStoreRecoveryRequiredException(
                    LocalStoreRecoveryReason.DATABASE_OPEN_FAILED,
                    e
                )
            } finally {
                // SupportOpenHelperFactory/SQLiteOpenHelper retain this exact byte array and
                // need it if the connection pool reopens the database later in the process.
                // Clearing it after the first writableDatabase call makes that later open use
                // an all-zero key and fail with SQLiteNotADatabaseException. On success the
                // helper owns the passphrase for the database lifetime; failed builds clear it.
                if (!databaseOpened) passphrase.fill(0)
            }
        }

        private fun buildRoomInstance(context: Context, factory: SupportOpenHelperFactory): ChronicleDb {
            return Room.databaseBuilder(
                context.applicationContext,
                ChronicleDb::class.java,
                ENCRYPTED_DB_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                    MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
                    MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
                    MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27,
                    MIGRATION_27_28
                )
                .build()
        }

        private fun migrateUnencryptedDb(context: Context) {
            val oldDbFile = context.getDatabasePath(DB_NAME)
            if (!oldDbFile.exists()) return

            val encryptedDbFile = context.getDatabasePath(ENCRYPTED_DB_NAME)
            if (databaseBundleExists(encryptedDbFile)) {
                if (!encryptedDbFile.exists()) {
                    throw LocalStoreRecoveryRequiredException(
                        LocalStoreRecoveryReason.DATABASE_OPEN_FAILED
                    )
                }
                Log.i(TAG, "Encrypted DB already exists; plaintext cleanup waits for a verified open")
                return
            }

            Log.i(TAG, "Migrating unencrypted database to SQLCipher")
            val passphrase = DatabaseKeyManager.getPassphrase(context, allowCreate = true)
            val temporaryDbFile = File(encryptedDbFile.absolutePath + ".migration")
            try {
                encryptedDbFile.parentFile?.mkdirs()
                deleteDbFiles(temporaryDbFile)

                val encryptedDb = SQLiteDatabase.openOrCreateDatabase(
                    temporaryDbFile, passphrase, null, null
                )
                try {
                    encryptedDb.execSQL("ATTACH DATABASE '${oldDbFile.absolutePath}' AS plaintext KEY ''")
                    encryptedDb.execSQL("SELECT sqlcipher_export('main', 'plaintext')")
                    encryptedDb.execSQL("DETACH DATABASE plaintext")
                    encryptedDb.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                        check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                            "Encrypted database integrity check failed"
                        }
                    }
                } finally {
                    encryptedDb.close()
                }

                check(temporaryDbFile.renameTo(encryptedDbFile)) {
                    "Unable to atomically install encrypted database"
                }
                Log.i(TAG, "Database migration to SQLCipher verified; plaintext cleanup follows verified open")
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted database migration failed; preserving the plaintext database.")
                deleteDbFiles(temporaryDbFile)
                throw LocalStoreRecoveryRequiredException(
                    LocalStoreRecoveryReason.DATABASE_MIGRATION_FAILED,
                    e
                )
            } finally {
                passphrase.fill(0)
            }
        }

        private fun databaseBundleExists(dbFile: File): Boolean =
            listOf("", "-wal", "-shm", "-journal").any { suffix ->
                File(dbFile.absolutePath + suffix).exists()
            }

        internal fun recoverySourceFiles(context: Context): List<File> = buildList {
            listOf(DB_NAME, ENCRYPTED_DB_NAME, "$ENCRYPTED_DB_NAME.migration").forEach { name ->
                val dbFile = context.getDatabasePath(name)
                listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                    File(dbFile.absolutePath + suffix).takeIf(File::exists)?.let(::add)
                }
            }
        }

        /** Called only after [LocalStoreRecoveryManager] has encrypted and verified every source. */
        internal fun resetAfterVerifiedRecoveryBundle(context: Context) = synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
            listOf(DB_NAME, ENCRYPTED_DB_NAME, "$ENCRYPTED_DB_NAME.migration").forEach { name ->
                deleteDbFiles(context.getDatabasePath(name))
            }
            check(recoverySourceFiles(context).isEmpty()) {
                "Failed to remove the local database after preserving its recovery bundle"
            }
        }

        private fun deleteDbFiles(dbFile: File) {
            dbFile.delete()
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
            File(dbFile.absolutePath + "-journal").delete()
        }
    }
}
