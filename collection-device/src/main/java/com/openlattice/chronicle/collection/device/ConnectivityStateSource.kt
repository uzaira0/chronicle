package com.openlattice.chronicle.collection.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.openlattice.chronicle.collection.NetworkTransport

/**
 * A point-in-time connectivity reading produced by a [ConnectivityStateSource]. Carries no id
 * or timestamp — [ConnectivityStateCollectionModule] adds those from its injected clock.
 * Content-free: transport + metered/validated flags only, never SSID/BSSID/IP/cell id.
 */
public data class ConnectivityStateReading(
    public val transport: NetworkTransport,
    public val connected: Boolean,
    public val metered: Boolean?,
    public val validated: Boolean?,
)

/**
 * Dependency-inversion seam for reading the device connectivity state. The module depends on
 * this interface, never on `ConnectivityManager` directly, so it is a `Context`-free class JVM
 * tests can drive with a lambda. Production impl: [AndroidConnectivityStateSource].
 */
public fun interface ConnectivityStateSource {
    /** Reads the current connectivity state, or `null` if it is unavailable. */
    public fun read(): ConnectivityStateReading?
}

/**
 * Production [ConnectivityStateSource] over `ConnectivityManager` / `NetworkCapabilities`.
 * Keeps only an application-`Context` handle. Reads the active transport plus metered and
 * validated-internet flags — deliberately never the SSID, BSSID, IP, or cell identifiers
 * (those would be a location proxy).
 */
public class AndroidConnectivityStateSource(context: Context) : ConnectivityStateSource {

    private val appContext = context.applicationContext

    override fun read(): ConnectivityStateReading? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (active == null || caps == null) {
            return ConnectivityStateReading(
                transport = NetworkTransport.NONE,
                connected = false,
                metered = runCatching { cm.isActiveNetworkMetered }.getOrNull(),
                validated = null,
            )
        }
        return ConnectivityStateReading(
            transport = transportOf(caps),
            connected = true,
            metered = runCatching { cm.isActiveNetworkMetered }.getOrNull(),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    private fun transportOf(caps: NetworkCapabilities): NetworkTransport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkTransport.BLUETOOTH
        else -> NetworkTransport.OTHER
    }
}
