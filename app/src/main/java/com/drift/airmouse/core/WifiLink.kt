package com.drift.airmouse.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Wear OS sends traffic through the phone over Bluetooth by default, which can't
 * reach a computer on your Wi-Fi. This asks the watch to bring Wi-Fi up and hands
 * back that network so sockets can be bound to it. Call [release] when done.
 */
class WifiLink(context: Context, private val onLost: () -> Unit = {}) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    var network: Network? = null
        private set

    suspend fun acquire(timeoutMs: Long = 15_000): Network? {
        release()
        val found = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (continuation.isActive) continuation.resume(network)
                    }

                    override fun onLost(network: Network) {
                        if (network == this@WifiLink.network) onLost()
                    }
                }
                callback = networkCallback
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                connectivity.requestNetwork(request, networkCallback)
            }
        }
        if (found == null) release() else network = found
        return found
    }

    /** The global broadcast address plus the subnet broadcast of the Wi-Fi network. */
    fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf(InetAddress.getByName("255.255.255.255"))
        val links = network?.let { connectivity.getLinkProperties(it) }?.linkAddresses ?: return result
        for (link in links) {
            val address = link.address as? Inet4Address ?: continue
            val ip = ByteBuffer.wrap(address.address).int
            val mask = if (link.prefixLength == 0) 0 else -1 shl (32 - link.prefixLength)
            val broadcast = ByteBuffer.allocate(4).putInt(ip or mask.inv()).array()
            result += InetAddress.getByAddress(broadcast)
        }
        return result
    }

    /** Addresses, routes and interface of the Wi-Fi network, once [acquire]d. */
    fun linkProperties(): LinkProperties? = network?.let { connectivity.getLinkProperties(it) }

    fun release() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }
}
