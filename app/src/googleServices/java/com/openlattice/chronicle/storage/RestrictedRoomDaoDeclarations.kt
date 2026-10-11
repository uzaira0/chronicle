package com.openlattice.chronicle.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import java.util.WeakHashMap

/**
 * Compile-time Room declaration for research-only DAO implementations. It is never opened as a
 * second database; the generated DAO implementations are bound to the encrypted [ChronicleDb]
 * below. Keeping this declaration in googleServices is what makes the implementations absent from
 * the minimal public source graph.
 */
@Database(
    entities = [
        InteractionSampleEntry::class,
        AudioActivitySampleEntry::class,
        AudioContentSampleEntry::class,
        NotificationActivitySampleEntry::class,
        SleepSampleEntry::class,
        ActivityRecognitionSampleEntry::class,
        HealthMetricSampleEntry::class,
    ],
    version = 1,
    exportSchema = false,
)
public abstract class RestrictedRoomDaoDeclarations : RoomDatabase() {
    abstract fun interaction(): InteractionSampleDao
    abstract fun audioActivity(): AudioActivitySampleDao
    abstract fun audioContent(): AudioContentSampleDao
    abstract fun notificationActivity(): NotificationActivitySampleDao
    abstract fun sleep(): SleepSampleDao
    abstract fun activityRecognition(): ActivityRecognitionSampleDao
    abstract fun healthMetric(): HealthMetricSampleDao
}

/**
 * Research/Open-only access to Room DAOs for restricted collection tables.
 *
 * [ChronicleDb] deliberately exposes only public-release DAOs. Keeping these generated DAO
 * constructors behind the googleServices source boundary prevents Play/Amazon from retaining
 * audio, notification, accessibility-interaction, activity/sleep, or Health Connect persistence
 * implementations while preserving the historical database schema and research variants.
 */
private class RestrictedRoomDaos(db: ChronicleDb) {
    val interaction: InteractionSampleDao by lazy { InteractionSampleDao_Impl(db) }
    val audioActivity: AudioActivitySampleDao by lazy { AudioActivitySampleDao_Impl(db) }
    val audioContent: AudioContentSampleDao by lazy { AudioContentSampleDao_Impl(db) }
    val notificationActivity: NotificationActivitySampleDao by lazy {
        NotificationActivitySampleDao_Impl(db)
    }
    val sleep: SleepSampleDao by lazy { SleepSampleDao_Impl(db) }
    val activityRecognition: ActivityRecognitionSampleDao by lazy {
        ActivityRecognitionSampleDao_Impl(db)
    }
    val healthMetric: HealthMetricSampleDao by lazy { HealthMetricSampleDao_Impl(db) }
}

private val restrictedDaos = WeakHashMap<ChronicleDb, RestrictedRoomDaos>()

private fun ChronicleDb.restrictedDaos(): RestrictedRoomDaos = synchronized(restrictedDaos) {
    restrictedDaos.getOrPut(this) { RestrictedRoomDaos(this) }
}

internal fun ChronicleDb.interactionSampleDao(): InteractionSampleDao = restrictedDaos().interaction

internal fun ChronicleDb.audioActivitySampleDao(): AudioActivitySampleDao = restrictedDaos().audioActivity

internal fun ChronicleDb.audioContentSampleDao(): AudioContentSampleDao = restrictedDaos().audioContent

internal fun ChronicleDb.notificationActivitySampleDao(): NotificationActivitySampleDao =
    restrictedDaos().notificationActivity

internal fun ChronicleDb.sleepSampleDao(): SleepSampleDao = restrictedDaos().sleep

internal fun ChronicleDb.activityRecognitionSampleDao(): ActivityRecognitionSampleDao =
    restrictedDaos().activityRecognition

internal fun ChronicleDb.healthMetricSampleDao(): HealthMetricSampleDao = restrictedDaos().healthMetric
