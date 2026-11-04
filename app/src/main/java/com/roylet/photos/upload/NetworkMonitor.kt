package com.roylet.photos.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NetworkStatus(val online: Boolean, val unmetered: Boolean, val wifi: Boolean)

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(read(cm.activeNetwork?.let(cm::getNetworkCapabilities)))
    val status: StateFlow<NetworkStatus> = _status

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _status.value = read(caps)
            }
            override fun onLost(network: Network) {
                _status.value = NetworkStatus(online = false, unmetered = false, wifi = false)
            }
        })
    }

    private fun read(caps: NetworkCapabilities?) = NetworkStatus(
        // Not requiring VALIDATED on purpose: a LAN-only Wi-Fi with no internet is still a fine
        // way to reach a home Immich server.
        online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
        wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
    )
}
