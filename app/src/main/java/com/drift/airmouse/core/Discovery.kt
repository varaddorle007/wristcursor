package com.drift.airmouse.core

import android.content.Context
import android.net.Network
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Finds Drift Receiver on the local network by broadcasting a short probe. */
object Discovery {

    sealed interface Result {
        data class Found(val computer: Computer) : Result
        data object NotFound : Result
        data object NoWifi : Result
    }

    suspend fun find(context: Context): Result = withContext(Dispatchers.IO) {
        val wifi = WifiLink(context)
        try {
            val network = wifi.acquire() ?: return@withContext Result.NoWifi
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                search(socket, wifi.broadcastAddresses())?.let { Result.Found(it) } ?: Result.NotFound
            }
        } catch (_: Exception) {
            Result.NotFound
        } finally {
            wifi.release()
        }
    }

    /**
     * Broadcasts a probe on an already-bound [socket] and returns the first receiver that answers.
     * Also used to re-find a paired computer whose address changed. Blocks for up to ~3 s.
     */
    fun search(socket: DatagramSocket, targets: List<InetAddress>, wanted: String? = null): Computer? {
        val oldTimeout = socket.soTimeout
        socket.broadcast = true
        socket.soTimeout = REPLY_WINDOW_MS
        val probe = "${Protocol.DISCOVER} ${Build.MODEL}".toByteArray(Charsets.UTF_8)
        val buffer = ByteArray(256)
        try {
            repeat(ATTEMPTS) {
                for (target in targets) {
                    runCatching { socket.send(DatagramPacket(probe, probe.size, target, Protocol.PORT)) }
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    return@repeat
                }
                val reply = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val host = packet.address?.hostAddress
                if (reply.startsWith(Protocol.DISCOVER_REPLY) && host != null) {
                    val name = reply.removePrefix(Protocol.DISCOVER_REPLY).trim().ifEmpty { host }
                    // When looking for one particular computer, ignore any other that answers.
                    if (wanted == null || name == wanted) return Computer(host, name)
                }
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            socket.soTimeout = oldTimeout
        }
    }

    /** Every receiver that answers within a couple of seconds, on a Wi-Fi [network] already held. */
    fun findAll(network: Network, targets: List<InetAddress>): List<Computer> {
        val found = linkedMapOf<String, Computer>()
        try {
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.broadcast = true
                socket.soTimeout = REPLY_WINDOW_MS
                val probe = "${Protocol.DISCOVER} ${Build.MODEL}".toByteArray(Charsets.UTF_8)
                val buffer = ByteArray(256)
                repeat(3) {
                    for (target in targets) {
                        runCatching { socket.send(DatagramPacket(probe, probe.size, target, Protocol.PORT)) }
                    }
                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        val reply = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val host = packet.address?.hostAddress ?: continue
                        if (reply.startsWith(Protocol.DISCOVER_REPLY)) {
                            val name = reply.removePrefix(Protocol.DISCOVER_REPLY).trim().ifEmpty { host }
                            found[name] = Computer(host, name)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Whatever answered before the error still counts.
        }
        return found.values.toList()
    }

    private const val ATTEMPTS = 5
    private const val REPLY_WINDOW_MS = 600
}
