package com.roylet.photos.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.net.Inet4Address

/** [NetworkEnvironment] backed by ConnectivityManager and the process lifecycle. */
class AndroidNetworkEnvironment(context: Context) : NetworkEnvironment {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    override fun online(): Boolean =
        cm.activeNetwork?.let(cm::getNetworkCapabilities)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun lan(): LanInfo? = cm.lanInfo()

    override fun describe(): String = cm.describeActive()

    override fun observe(onNetworkChange: () -> Unit, onForeground: () -> Unit) {
        // Not onCapabilitiesChanged: it fires on every signal-strength change. A new network or
        // new addresses are what matter here.
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChange()
            override fun onLost(network: Network) = onNetworkChange()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onNetworkChange()
        })
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = onForeground()
            })
        }
    }
}

/**
 * Reads the Wi-Fi/ethernet network's IPv4 address, prefix and gateway from [ConnectivityManager]
 * link properties (no location permission, unlike WifiManager's connection info). Mobile data
 * never counts. When a VPN is the default network (Tailscale, a work VPN, an ad blocker), the
 * Wi-Fi underneath is used instead of giving up: LAN addresses are usually routed outside the
 * tunnel, and if they aren't the probes simply fail and the diagnostics say so.
 */
@Suppress("DEPRECATION") // allNetworks: the only way to find the Wi-Fi under a VPN without a callback.
fun ConnectivityManager.lanInfo(): LanInfo? {
    val active = activeNetwork ?: return null
    val activeCaps = getNetworkCapabilities(active) ?: return null
    val viaVpn = activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    val candidates = if (!viaVpn) listOf(active) else listOf(active) + allNetworks.filter { it != active }
    for (network in candidates) {
        val caps = getNetworkCapabilities(network) ?: continue
        val label = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> continue
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
        val props = getLinkProperties(network) ?: continue
        // IPv4 only: a link usually lists IPv6 addresses first.
        val link = props.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: continue
        val gateway = props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val full = buildString {
            append(label)
            if (metered) append(", metered")
            if (viaVpn) append(" (VPN active)")
        }
        return LanInfo(link.address.hostAddress ?: continue, link.prefixLength, gateway?.takeIf { it != "0.0.0.0" }, full)
    }
    return null
}

/** "Mobile data", "Wi-Fi (VPN active)", ... for diagnostics when [lanInfo] finds nothing. */
private fun ConnectivityManager.describeActive(): String {
    val caps = activeNetwork?.let(::getNetworkCapabilities) ?: return "No network"
    val parts = buildList {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Mobile data")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
    }
    return parts.joinToString(" + ").ifEmpty { "Other network" }
}
