package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one interaction-salience event, stored in the `interaction_samples` table.
 *
 * The structured analogue of [BatterySampleEntry] for the `interaction_events` collection
 * module (see `docs/SENSING-EXPANSION-DESIGN.md` §6). The enum-valued [eventType] is persisted
 * as its enum `name` string (mirroring how [BatterySampleEntry] stores its enums), so the table
 * needs no Room `TypeConverter`. [timestamp] is an ISO-8601 UTC string, ordered like the other
 * sample tables. Content-free by construction: [elementRole] is the interacted view's class
 * name — never element text or contentDescription. [scrollDeltaX]/[scrollDeltaY] are populated
 * only for SCROLL events (and may be null when unknown). [nodeBoundsLeft]/[nodeBoundsTop]/
 * [nodeBoundsRight]/[nodeBoundsBottom] plus [positionSource] and display context are the raw
 * position observation. [rawX]/[rawY] and normalized position are legacy nullable columns kept
 * only so historical buffered rows remain readable; new collection leaves them null because
 * they were derived node centers, not raw finger/pointer coordinates. Grid fields remain required
 * by the legacy wire contract and are also derived from the node bounds.
 */
@Entity(tableName = "interaction_samples")
data class InteractionSampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val eventType: String,
    val gridRows: Int,
    val gridCols: Int,
    val gridRow: Int,
    val gridCol: Int,
    val elementRole: String,
    val foregroundPackage: String,
    val positionSource: String? = null,
    val nodeBoundsLeft: Int? = null,
    val nodeBoundsTop: Int? = null,
    val nodeBoundsRight: Int? = null,
    val nodeBoundsBottom: Int? = null,
    val displayId: Int? = null,
    val rawX: Int?,
    val rawY: Int?,
    val screenWidth: Int?,
    val screenHeight: Int?,
    val normalizedX: Double?,
    val normalizedY: Double?,
    val scrollDeltaX: Int?,
    val scrollDeltaY: Int?,
    // Salience kinematics + context. eventTimeMillis = monotonic uptime clock (ordering +
    // kinematics basis); episodeId groups an interaction burst; dwell + scroll velocity/reversal
    // are derived; orientation/screenDensityDpi let the raw position be interpreted.
    val eventTimeMillis: Long?,
    val episodeId: String?,
    val dwellMillisSincePrev: Long?,
    val orientation: Int?,
    val screenDensityDpi: Int?,
    val scrollVelocityX: Double?,
    val scrollVelocityY: Double?,
    val scrollReversed: Boolean?,
)
