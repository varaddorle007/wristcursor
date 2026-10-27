package com.drift.receiver

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Listens for the watch over UDP.
 * Only watches you've allowed can move the mouse; everyone else gets asked about once.
 */
class Server(
    private val pointer: Pointer,
    private val trust: TrustStore,
    private val ui: ReceiverWindow,
) {
    private class Request(val name: String, val address: InetAddress, val port: Int)

    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(PORT))
    }
    private val computerName: String by lazy {
        runCatching { InetAddress.getLocalHost().hostName.removeSuffix(".local") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Computer"
    }

    private val connected = mutableSetOf<InetAddress>()
    private val pending = mutableMapOf<String, Request>()
    private val denied = mutableSetOf<String>()

    fun run() {
        val buffer = ByteArray(16 * 1024) // room for typed text
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
            runCatching { handle(text, packet.address, packet.port) }
        }
    }

    @Synchronized
    private fun handle(text: String, from: InetAddress, port: Int) {
        when {
            text.startsWith("DRIFT?") -> reply("DRIFT! $computerName", from, port)
            text.startsWith("HELLO ") -> hello(text, from, port)
            text == "BYE" -> if (connected.remove(from)) {
                pointer.resetDriving()
                ui.waiting()
            }
            from in connected -> pointer.apply(text)
        }
    }

    private fun hello(text: String, from: InetAddress, port: Int) {
        val parts = text.split(' ', limit = 3)
        val id = parts.getOrNull(1) ?: return
        val name = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "Watch"
        when {
            id in denied -> reply("NO", from, port)
            trust.isTrusted(id) -> {
                reply("OK", from, port)
                if (connected.add(from)) ui.connected(name, from)
            }
            else -> {
                reply("WAIT", from, port)
                if (pending.put(id, Request(name, from, port)) == null) ui.askToAllow(id, name, from)
            }
        }
    }

    @Synchronized
    fun allow(id: String) {
        val request = pending.remove(id) ?: return
        trust.add(id, request.name)
        connected += request.address
        reply("OK", request.address, request.port)
        ui.connected(request.name, request.address)
    }

    @Synchronized
    fun deny(id: String) {
        val request = pending.remove(id) ?: return
        denied += id
        reply("NO", request.address, request.port)
        ui.waiting()
    }

    @Synchronized
    fun disconnectAll() {
        connected.clear()
        pending.clear()
        denied.clear()
        pointer.resetDriving()
        ui.waiting()
    }

    private fun reply(message: String, to: InetAddress, port: Int) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, to, port)) }
    }
}
