package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Buffered `connectivity_state` sample (ConnectivityManager / NetworkCapabilities), one row
 * per AndroidConnectivityStateEvent before upload. DEVICE_STATE_METADATA-class: transport +
 * metered/validated flags only — no SSID/BSSID/IP/cell identifiers.
 */
@Entity(tableName = "connectivity_state_samples")
data class ConnectivityStateSampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val eventType: String,
    val transport: String,
    val connected: Boolean,
    val metered: Boolean?,
    val validated: Boolean?,
)
