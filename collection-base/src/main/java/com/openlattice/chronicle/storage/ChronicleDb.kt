package com.openlattice.chronicle.storage

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

private const val TAG = "ChronicleDb"
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
        SensorSampleDeadLetterEntity::class,
        UploadStatsEntity::class,
        UsagePollCheckpointEntity::class,
        CollectionModuleStateEntity::class,
        UploadDiagnosticEntity::class,
        LocalDataQuarantineEntity::class,
        LifecycleStateEntity::class,
        UploadQueueEvidence::class,
        PendingCollectionAckEntity::class,
    ],
    version = ChronicleDb.VERSION
)
abstract class ChronicleDb : RoomDatabase() {
    abstract fun queueEntryData(): StorageQueue
    abstract fun userQueueEntryData(): UserStorageQueue
    abstract fun restrictedArtifactErasureDao(): RestrictedArtifactErasureDao
    abstract fun sensorSampleDao(): SensorSampleDao
    abstract fun batterySampleDao(): BatterySampleDao
    abstract fun connectivityStateSampleDao(): ConnectivityStateSampleDao
    abstract fun appNetworkUsageSampleDao(): AppNetworkUsageSampleDao
    abstract fun deviceSettingsSampleDao(): DeviceSettingsSampleDao
    abstract fun uploadServerDao(): UploadServerDao
    abstract fun uploadStatsDao(): UploadStatsDao
    abstract fun usagePollCheckpointDao(): UsagePollCheckpointDao
    abstract fun lifecycleStateDao(): LifecycleStateDao
    abstract fun uploadQueueEvidenceDao(): UploadQueueEvidenceDao
    abstract fun collectionModuleStateDao(): CollectionModuleStateDao
    abstract fun uploadDiagnosticDao(): UploadDiagnosticDao
    abstract fun localDataQuarantineDao(): LocalDataQuarantineDao
    abstract fun pendingCollectionAckDao(): PendingCollectionAckDao

    companion object {
        /** The reset release is version 1; a pre-reset database is refused and kept for recovery. */
        const val VERSION = 4
        /** Every upgrade from version 1, in order; the builder and the migration test both read this list. */
        val ALL_MIGRATIONS: List<Migration> = listOf(DECISION_SEQ_MIGRATION, STORED_INSTANT_MIGRATION, BATTERY_PROVENANCE_MIGRATION)

        @Volatile
        private var INSTANCE: ChronicleDb? = null

        fun getInstance(context: Context): ChronicleDb {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: openEncryptedDatabase(context).also { INSTANCE = it }
            }
        }

        fun openEncryptedDatabase(context: Context, databaseName: String = ENCRYPTED_DB_NAME): ChronicleDb {
            System.loadLibrary("sqlcipher")

            val passphrase = DatabaseKeyManager.getPassphrase(
                context,
                allowCreate = recoverySourceFiles(context, databaseName).isEmpty()
            )
            val factory = SupportOpenHelperFactory(passphrase)

            var databaseOpened = false
            return try {
                val db = buildRoomInstance(context, factory, databaseName)
                db.openHelper.writableDatabase
                databaseOpened = true
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

        fun buildRoomInstance(context: Context, factory: SupportSQLiteOpenHelper.Factory, databaseName: String = ENCRYPTED_DB_NAME): ChronicleDb {
            return Room.databaseBuilder(
                context.applicationContext,
                ChronicleDb::class.java,
                databaseName
            )
                .openHelperFactory(SchemaRefusalFactory(factory))
                .apply { ALL_MIGRATIONS.forEach { addMigrations(it) } }
                .addCallback(UPLOAD_EVIDENCE_CALLBACK)
                .build()
        }

        internal fun recoverySourceFiles(context: Context, databaseName: String = ENCRYPTED_DB_NAME): List<File> {
            val dbFile = context.getDatabasePath(databaseName)
            return listOf("", "-wal", "-shm", "-journal")
                .map { suffix -> File(dbFile.absolutePath + suffix) }.filter(File::exists)
        }

        /** Called only after [LocalStoreRecoveryManager] has encrypted and verified every source. */
        internal fun resetAfterVerifiedRecoveryBundle(context: Context) = synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
            recoverySourceFiles(context).forEach { it.delete() }
            check(recoverySourceFiles(context).isEmpty()) {
                "Failed to remove the local database after preserving its recovery bundle"
            }
        }
    }
}

/**
 * Room refuses a store it decrypted when the store holds another build's schema: an identity hash it does not know or
 * a version with no migration path. It reports both as [IllegalStateException] from its open callback, which this
 * factory turns into [LocalStoreRecoveryReason.LOCAL_SCHEMA_REPLACED] so the participant is told the app was updated.
 */
private class SchemaRefusalFactory(private val delegate: SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val room = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(room.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = room.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = room.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                refused { room.onUpgrade(db, oldVersion, newVersion) }
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                refused { room.onDowngrade(db, oldVersion, newVersion) }
            override fun onOpen(db: SupportSQLiteDatabase) = refused { room.onOpen(db) }
            override fun onCorruption(db: SupportSQLiteDatabase) = room.onCorruption(db)
        }
        return delegate.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(callback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build(),
        )
    }

    private fun refused(open: () -> Unit) = try {
        open()
    } catch (e: LocalStoreRecoveryRequiredException) {
        throw e
    } catch (e: IllegalStateException) {
        throw LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.LOCAL_SCHEMA_REPLACED, e)
    }
}
