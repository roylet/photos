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

/** [NetworkEnvironment] backed by ConnectivityManager and the process lifecycle. */
class AndroidNetworkEnvironment(context: Context) : NetworkEnvironment {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    override fun online(): Boolean =
        cm.activeNetwork?.let(cm::getNetworkCapabilities)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun lan(): LanInfo? = cm.lanInfo()

    override fun observe(onChange: () -> Unit) {
        // Not onCapabilitiesChanged: it fires on every signal-strength change. A new network or
        // new addresses are what matter here.
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onChange()
        })
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = onChange()
            })
        }
    }
}
