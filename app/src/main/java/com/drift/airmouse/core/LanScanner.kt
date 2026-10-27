package com.drift.airmouse.core

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Finds everything on this Wi-Fi: computers, laptops, phones, tablets, TVs, speakers, printers and the router.
 *
 * Wear OS normally sends traffic through the phone over Bluetooth, so first the watch brings up
 * its own Wi-Fi and every search below is pinned to that network. Then, all at once:
 * - every address on the network is pinged, and the ones that ignore pings get a quick connection
 *   attempt (a refused connection still proves the device is there, which catches most phones);
 * - Bonjour, UPnP, Windows names and reverse lookups ask devices what they're called;
 * - open ports tell a TV from a phone from a computer.
 * Devices show up in [scan]'s callback as they're found; the whole scan takes about ten seconds.
 */
object LanScanner {

    enum class Outcome { DONE, NO_WIFI }

    /**
     * [companionPhone] is the Bluetooth name of the phone this watch is paired with ("Varad's S24").
     * Phones rarely say their name on Wi-Fi, so when exactly one Android phone turns up it gets this name.
     */
    suspend fun scan(
        context: Context,
        companionPhone: String? = null,
        onUpdate: (List<NetDevice>) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val wifi = WifiLink(context)
        try {
            val network = wifi.acquire(timeoutMs = 10_000) ?: return@withContext Outcome.NO_WIFI
            val link = wifi.linkProperties() ?: return@withContext Outcome.NO_WIFI
            val ownAddress = link.linkAddresses.firstOrNull { it.address is Inet4Address }
                ?: return@withContext Outcome.NO_WIFI
            val self = ownAddress.address as Inet4Address
            val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway as? Inet4Address
            val targets = subnet(self, ownAddress.prefixLength)
            val book = Book(self.hostAddress.orEmpty(), gateway?.hostAddress, Build.MODEL, companionPhone, onUpdate)
            book.alive(self.hostAddress.orEmpty())
            gateway?.hostAddress?.let { book.alive(it) }

            coroutineScope {
                launch { Discovery.findAll(network, wifi.broadcastAddresses()).forEach { book.receiver(it) } }
                launch { browseBonjour(context, network, book) }
                launch { ssdp(context, network, book) }
                launch { netbiosSweep(network, targets, book) }
                launch { reverseLookups(network, targets, gateway, book) }
                launch {
                    pingSweep(network, targets).forEach { book.alive(it) }
                    val quiet = targets.map { it.hostAddress.orEmpty() }.filter { !book.isAlive(it) }
                    probeAll(network, quiet.map { it to LIVENESS_PORT }, book)
                    // Everything found so far (by any search) gets its ports checked.
                    val known = book.aliveIps().filter { it != book.selfIp }
                    probeAll(network, known.flatMap { ip -> FINGERPRINT_PORTS.map { ip to it } }, book)
                    askDevicesTheirNames(network, book)
                    // Phones on Wi-Fi power-save often miss the first round: ask the silent addresses once more.
                    val stillQuiet = targets.filter { !book.isAlive(it.hostAddress.orEmpty()) }
                    pingSweep(network, stillQuiet).forEach { book.alive(it) }
                }
            }
            book.flush()
            book.devices().forEach { Log.i(TAG, "found ${it.ip} ${it.kind} \"${it.name}\" ${it.detail.orEmpty()}${if (it.computer != null) " [controllable]" else ""}") }
            Outcome.DONE
        } finally {
            wifi.release()
        }
    }

    /** Looks for a computer by name again, after its Wi-Fi address changed. */
    suspend fun refind(context: Context, name: String): Computer? {
        var match: Computer? = null
        runCatching {
            scan(context) { devices ->
                if (match == null) {
                    match = devices.firstNotNullOfOrNull { it.computer?.takeIf { c -> c.name == name || sameDevice(c.name, name) } }
                }
            }
        }
        return match
    }

    /** "Macbook's MacBook Pro" and "Macbooks-MacBook-Pro" are the same machine. */
    fun sameDevice(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    // region Who's there

    /** Every address on the watch's network (at most a /24, so the sweep stays quick), except the watch's own. */
    private fun subnet(self: Inet4Address, prefix: Int): List<Inet4Address> {
        val bits = prefix.coerceIn(24, 30)
        val ip = ByteBuffer.wrap(self.address).int
        val base = ip and (-1 shl (32 - bits))
        val count = (1 shl (32 - bits)) - 2
        return (1..count).map { base + it }.filter { it != ip }.map {
            InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(it).array()) as Inet4Address
        }
    }

    /**
     * One ICMP "ping" socket, one echo request per address, then collect replies.
     * The sequence number in each request says which address answered.
     */
    private fun pingSweep(network: Network, targets: List<Inet4Address>): Set<String> {
        val found = mutableSetOf<String>()
        val fd = try {
            Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
        } catch (_: ErrnoException) {
            return found
        }
        try {
            runCatching { network.bindSocket(fd) }
            val request = ByteArray(16)
            // Twice, a moment apart: a phone dozing on Wi-Fi power-save can sleep through the first one.
            repeat(2) { round ->
                if (round > 0) Thread.sleep(400)
                targets.forEachIndexed { index, address ->
                    request.fill(0)
                    request[0] = 8 // echo request; the kernel fills in the id and checksum
                    request[6] = (index shr 8).toByte()
                    request[7] = index.toByte()
                    runCatching { Os.sendto(fd, request, 0, request.size, 0, address, 0) }
                    if (index % 32 == 31) Thread.sleep(3)
                }
            }
            val reply = ByteArray(256)
            val poll = arrayOf(StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            })
            val deadline = SystemClock.elapsedRealtime() + PING_WINDOW_MS
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                poll[0].revents = 0
                if (Os.poll(poll, left.toInt()) <= 0) break
                val length = runCatching { Os.read(fd, reply, 0, reply.size) }.getOrDefault(-1)
                if (length < 8 || reply[0].toInt() != 0) continue // 0 = echo reply
                val index = ((reply[6].toInt() and 0xFF) shl 8) or (reply[7].toInt() and 0xFF)
                targets.getOrNull(index)?.hostAddress?.let { found += it }
            }
        } catch (_: Exception) {
            // Keep whatever answered.
        } finally {
            runCatching { Os.close(fd) }
        }
        return found
    }

    private enum class Probe { OPEN, REFUSED, SILENT }

    /** Tries each (address, port) with up to [PROBE_LANES] attempts at once. */
    private suspend fun probeAll(network: Network, work: List<Pair<String, Int>>, book: Book) = coroutineScope {
        val lanes = Dispatchers.IO.limitedParallelism(PROBE_LANES)
        work.forEach { (ip, port) ->
            launch(lanes) {
                when (probe(network, ip, port)) {
                    Probe.OPEN -> {
                        book.alive(ip)
                        book.port(ip, port)
                    }
                    // A refusal comes from the device itself, so it's there.
                    Probe.REFUSED -> book.alive(ip)
                    Probe.SILENT -> Unit
                }
            }
        }
    }

    private fun probe(network: Network, ip: String, port: Int): Probe {
        val socket = runCatching { network.socketFactory.createSocket() }.getOrNull() ?: return Probe.SILENT
        return try {
            socket.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS)
            Probe.OPEN
        } catch (e: ConnectException) {
            if (refused(e)) Probe.REFUSED else Probe.SILENT
        } catch (_: IOException) {
            Probe.SILENT
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun refused(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        (it as? ErrnoException)?.errno == OsConstants.ECONNREFUSED ||
            it.message?.contains("ECONNREFUSED") == true ||
            it.message?.contains("refused", ignoreCase = true) == true
    }

    // endregion

    // region What they're called

    private suspend fun browseBonjour(context: Context, network: Network, book: Book) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        // Callbacks are tiny, so run them right where the system delivers them. A thread pool here
        // would have to stay alive for late "stopped" callbacks, or the system crashes the app.
        val executor = Executor(Runnable::run)
        val finished = AtomicBoolean(false)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        val lookups = ConcurrentHashMap.newKeySet<NsdManager.ServiceInfoCallback>()
        // Before Android 14 the system resolves one service at a time, so queue them.
        val queue = LinkedBlockingQueue<Pair<String, NsdServiceInfo>>()

        fun resolved(type: String, info: NsdServiceInfo, addresses: List<InetAddress>) {
            if (finished.get()) return
            val ip = addresses.firstOrNull { it is Inet4Address }?.hostAddress ?: return
            if (ip == book.selfIp) return
            book.alive(ip)
            val txt = info.attributes.orEmpty().mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
            val hostname = if (Build.VERSION.SDK_INT >= 35) info.hostname else null
            book.service(ip, type, info.serviceName, info.port, txt, hostname)
        }

        for (type in BONJOUR_TYPES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (finished.get() || !seen.add("$type/${info.serviceName}")) return
                    if (Build.VERSION.SDK_INT >= 34) {
                        val lookup = object : NsdManager.ServiceInfoCallback {
                            override fun onServiceUpdated(updated: NsdServiceInfo) {
                                resolved(type, updated, updated.hostAddresses)
                                if (lookups.remove(this)) runCatching { nsd.unregisterServiceInfoCallback(this) }
                            }
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                lookups.remove(this)
                            }
                            override fun onServiceLost() = Unit
                            override fun onServiceInfoCallbackUnregistered() = Unit
                        }
                        lookups += lookup
                        if (runCatching { nsd.registerServiceInfoCallback(info, executor, lookup) }.isFailure) lookups.remove(lookup)
                    } else {
                        queue.offer(type to info)
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            val started = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, network, executor, listener)
                } else {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                }
            }.isSuccess
            if (started) listeners += listener
        }

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                delay(BONJOUR_MS)
            } else {
                val end = SystemClock.elapsedRealtime() + BONJOUR_MS
                while (SystemClock.elapsedRealtime() < end) {
                    val next = queue.poll()
                    if (next == null) {
                        delay(100)
                        continue
                    }
                    val info = resolveLegacy(nsd, next.second) ?: continue
                    @Suppress("DEPRECATION")
                    resolved(next.first, info, listOfNotNull(info.host))
                }
            }
        } finally {
            finished.set(true)
            withContext(Dispatchers.Main.immediate) {
                listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
                lookups.toList().forEach { lookup ->
                    if (lookups.remove(lookup)) runCatching { nsd.unregisterServiceInfoCallback(lookup) }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(1_500) {
            suspendCancellableCoroutine { done ->
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            if (done.isActive) done.resume(resolved)
                        }
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            if (done.isActive) done.resume(null)
                        }
                    })
                }.onFailure { if (done.isActive) done.resume(null) }
            }
        }

    /** UPnP: TVs, routers, speakers and media boxes answer a multicast search with a link to their description. */
    private suspend fun ssdp(context: Context, network: Network, book: Book) {
        val lock = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("drift:scan")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
        val locations = linkedMapOf<String, String>()
        try {
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.soTimeout = 250
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) {
                    SSDP_TARGETS.forEach { target ->
                        val bytes = NetProbes.ssdpSearch(target).toByteArray(Charsets.US_ASCII)
                        runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, 1900)) }
                    }
                }
                val buffer = ByteArray(2048)
                val deadline = SystemClock.elapsedRealtime() + SSDP_WINDOW_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val ip = packet.address?.hostAddress ?: continue
                    if (ip == book.selfIp) continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    book.alive(ip)
                    if (text.contains("dial-multiscreen", ignoreCase = true)) book.vote(ip, DeviceKind.TV, 6)
                    NetProbes.ssdpLocation(text)?.let { locations.putIfAbsent(ip, it) }
                }
            }
        } catch (_: Exception) {
            // Keep what came back.
        } finally {
            runCatching { lock?.release() }
        }
        coroutineScope {
            locations.forEach { (ip, url) ->
                launch {
                    val xml = HttpGet.fetch(network, url) ?: return@launch
                    val description = NetProbes.parseDescription(xml)
                    description.friendlyName?.let { book.name(ip, it, Book.RANK_FRIENDLY) }
                    NetProbes.kindFromDescription(description)?.let { book.vote(ip, it, 7) }
                    (description.modelName ?: description.manufacturer)?.let { book.detail(ip, it, 2) }
                }
            }
        }
    }

    /** Windows PCs and NAS boxes answer with their computer name. */
    private fun netbiosSweep(network: Network, targets: List<Inet4Address>, book: Book) {
        runCatching {
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.soTimeout = 200
                val query = NetProbes.netbiosQuery(0x4452)
                targets.forEachIndexed { index, target ->
                    runCatching { socket.send(DatagramPacket(query, query.size, target, 137)) }
                    if (index % 32 == 31) Thread.sleep(3)
                }
                val buffer = ByteArray(1024)
                val deadline = SystemClock.elapsedRealtime() + NAME_WINDOW_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val ip = packet.address?.hostAddress ?: continue
                    book.alive(ip)
                    val name = NetProbes.parseNetbiosName(buffer, packet.length) ?: continue
                    book.name(ip, name, Book.RANK_SYSTEM)
                    book.vote(ip, NetProbes.kindFromModel(name) ?: DeviceKind.COMPUTER, 4)
                }
            }
        }
    }

    /**
     * Reverse lookups, two ways at once: asking each device directly over multicast DNS
     * (Apple devices and Linux answer with their host name), and asking the router,
     * which often knows the names devices gave when they joined.
     */
    private fun reverseLookups(network: Network, targets: List<Inet4Address>, gateway: Inet4Address?, book: Book) {
        runCatching {
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.soTimeout = 200
                targets.forEachIndexed { index, target ->
                    val query = NetProbes.ptrQuery(index + 1, target.hostAddress.orEmpty())
                    runCatching { socket.send(DatagramPacket(query, query.size, target, 5353)) }
                    gateway?.let { runCatching { socket.send(DatagramPacket(query, query.size, it, 53)) } }
                    if (index % 32 == 31) Thread.sleep(3)
                }
                val buffer = ByteArray(1500)
                val deadline = SystemClock.elapsedRealtime() + NAME_WINDOW_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (packet.length < 2) continue
                    val id = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
                    val target = targets.getOrNull(id - 1)?.hostAddress ?: continue
                    val host = NetProbes.parsePtrAnswer(buffer, packet.length) ?: continue
                    val fromDevice = packet.address?.hostAddress == target
                    // The router may remember devices that have since left, so only a device's own answer proves it's here.
                    if (fromDevice) book.alive(target)
                    book.name(target, NetProbes.prettyHostName(host), if (fromDevice) Book.RANK_SYSTEM else Book.RANK_ROUTER)
                    NetProbes.kindFromModel(host)?.let { book.vote(target, it, 4) }
                }
            }
        }
    }

    /** TVs and media boxes with an open web service say their own name. */
    private suspend fun askDevicesTheirNames(network: Network, book: Book) = coroutineScope {
        book.aliveIps().forEach { ip ->
            val ports = book.portsOf(ip)
            if (8008 in ports) launch {
                val json = HttpGet.fetch(network, "http://$ip:8008/setup/eureka_info?params=name,device_info") ?: return@launch
                runCatching {
                    val root = JSONObject(json)
                    root.optString("name").takeIf { it.isNotBlank() }?.let { book.name(ip, it, Book.RANK_FRIENDLY) }
                    val model = root.optJSONObject("device_info")?.optString("model_name").orEmpty()
                    if (model.isNotBlank()) {
                        book.detail(ip, model, 0)
                        NetProbes.kindFromModel(model)?.let { book.vote(ip, it, 9) }
                    }
                }
            }
            if (8001 in ports) launch {
                val json = HttpGet.fetch(network, "http://$ip:8001/api/v2/") ?: return@launch
                runCatching {
                    val device = JSONObject(json).optJSONObject("device")
                    device?.optString("name")?.takeIf { it.isNotBlank() }?.let { book.name(ip, it.removePrefix("[TV] "), Book.RANK_FRIENDLY) }
                    device?.optString("modelName")?.takeIf { it.isNotBlank() }?.let { book.detail(ip, it, 0) }
                    book.vote(ip, DeviceKind.TV, 9)
                }
            }
            if (8060 in ports) launch {
                val xml = HttpGet.fetch(network, "http://$ip:8060/query/device-info") ?: return@launch
                (NetProbes.tag(xml, "user-device-name") ?: NetProbes.tag(xml, "friendly-device-name"))
                    ?.let { book.name(ip, it, Book.RANK_FRIENDLY) }
                NetProbes.tag(xml, "model-name")?.let { book.detail(ip, it, 0) }
                book.vote(ip, DeviceKind.TV, 9)
            }
        }
    }

    // endregion

    private const val TAG = "DriftScan"
    private const val PROBE_LANES = 64
    private const val PROBE_TIMEOUT_MS = 900
    private const val PING_WINDOW_MS = 2_000L
    private const val NAME_WINDOW_MS = 2_000L
    private const val SSDP_WINDOW_MS = 2_500L
    private const val BONJOUR_MS = 6_000L

    /** Almost every device either answers or refuses a connection here; either proves it's on the network. */
    private const val LIVENESS_PORT = 80

    /** Ports that say what something is (see [NetProbes.kindFromPort]), plus the web ports TVs name themselves on. */
    private val FINGERPRINT_PORTS = listOf(
        62078, 6466, 8001, 8060, 5555, 8008, 3001, 1400, 631, 9100, 554,
        445, 139, 3389, 548, 5900, 22,
    )

    private val SSDP_TARGETS = listOf("ssdp:all", "urn:dial-multiscreen-org:service:dial:1", "upnp:rootdevice")

    private val BONJOUR_TYPES = listOf(
        "_rfb._tcp",
        "_companion-link._tcp",
        "_airplay._tcp",
        "_raop._tcp",
        "_googlecast._tcp",
        "_androidtvremote2._tcp",
        "_amzn-wplay._tcp",
        "_mediaremotetv._tcp",
        "_spotify-connect._tcp",
        "_sonos._tcp",
        "_smb._tcp",
        "_workstation._tcp",
        "_device-info._tcp",
        "_ssh._tcp",
        "_apple-mobdev2._tcp",
        "_ipp._tcp",
        "_printer._tcp",
        "_pdl-datastream._tcp",
        "_hap._tcp",
        "_matter._tcp",
        "_hue._tcp",
        "_sleep-proxy._udp",
        "_adb-tls-connect._tcp",
        "_nearbypresence._tcp", // Android phones and tablets (Google Nearby)
        "_FC9F5ED42C8A._tcp", // Quick Share: phones, tablets and PCs, with their name when visible
    )
}

/**
 * Everything learned about each address, merged into one row per device.
 * Each fact comes with a confidence; the best name and the strongest kind win.
 */
private class Book(
    val selfIp: String,
    private val gateway: String?,
    private val selfModel: String,
    private val companionPhone: String?,
    private val publish: (List<NetDevice>) -> Unit,
) {
    private class Host(val ip: String) {
        var alive = false
        var android = false
        val names = sortedMapOf<Int, String>()
        val votes = mutableMapOf<DeviceKind, Int>()
        var detail: String? = null
        var detailRank = Int.MAX_VALUE
        val ports = mutableSetOf<Int>()
        var receiver: Computer? = null
        var screenSharing: Computer? = null
    }

    private val hosts = linkedMapOf<String, Host>()
    private var dirty = false
    private var lastPublish = 0L

    @Synchronized fun alive(ip: String) {
        if (ip.isEmpty()) return
        val host = host(ip)
        if (!host.alive) {
            host.alive = true
            changed()
        }
    }

    @Synchronized fun isAlive(ip: String) = hosts[ip]?.alive == true

    @Synchronized fun aliveIps(): List<String> = hosts.values.filter { it.alive }.map { it.ip }

    @Synchronized fun portsOf(ip: String): Set<Int> = hosts[ip]?.ports?.toSet().orEmpty()

    @Synchronized fun name(ip: String, name: String, rank: Int) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.equals(ip, ignoreCase = true)) return
        val host = host(ip)
        if (host.names.putIfAbsent(rank, clean) == null) changed()
    }

    @Synchronized fun vote(ip: String, kind: DeviceKind, weight: Int) {
        val host = host(ip)
        host.votes[kind] = (host.votes[kind] ?: 0) + weight
        changed()
    }

    @Synchronized fun detail(ip: String, text: String, rank: Int) {
        val host = host(ip)
        if (rank < host.detailRank && text.isNotBlank()) {
            host.detail = NetProbes.prettyModel(text.trim())
            host.detailRank = rank
            changed()
        }
    }

    @Synchronized fun port(ip: String, port: Int) {
        val host = host(ip)
        if (host.ports.add(port)) NetProbes.kindFromPort(port)?.let { (kind, weight) -> vote(ip, kind, weight) }
    }

    @Synchronized fun receiver(computer: Computer) {
        val host = host(computer.host)
        host.alive = true
        host.receiver = computer
        host.names[RANK_FRIENDLY] = computer.name
        changed()
    }

    /** A Bonjour service, resolved: its type, name, text record and host name say a lot. */
    @Synchronized fun service(
        ip: String,
        type: String,
        serviceName: String,
        port: Int,
        txt: Map<String, String>,
        hostname: String?,
    ) {
        val kind = type.trimEnd('.').removeSuffix(".local").trimStart('.')
        NetProbes.kindFromService(kind)?.let { (k, w) -> vote(ip, k, w) }
        if (NetProbes.isAndroidHost(hostname) || kind == "_nearbypresence._tcp") {
            host(ip).android = true
            vote(ip, DeviceKind.PHONE, 6)
        }
        if (kind.equals("_FC9F5ED42C8A._tcp", ignoreCase = true)) {
            txt["n"]?.let(NetProbes::parseQuickShare)?.let { (k, name) ->
                k?.let { vote(ip, it, 9) }
                name?.let { name(ip, it, RANK_FRIENDLY) }
                if (k == DeviceKind.PHONE || k == DeviceKind.TABLET) host(ip).android = true
            }
        }
        val model = txt["model"] ?: txt["rpMd"] ?: txt["md"] ?: txt["am"] ?: txt["ty"] ?: txt["usb_MDL"]
        model?.takeIf { it.isNotBlank() }?.let { m ->
            NetProbes.kindFromModel(m)?.let { vote(ip, it, 9) }
            detail(ip, m, 1)
        }
        val shown = serviceName.substringBefore(" [").trim() // _workstation adds " [mac address]"
        when (kind) {
            "_googlecast._tcp" -> txt["fn"]?.let { name(ip, it, RANK_FRIENDLY) }
            "_amzn-wplay._tcp" -> name(ip, txt["n"] ?: shown, RANK_FRIENDLY)
            "_companion-link._tcp", "_androidtvremote2._tcp", "_ipp._tcp", "_printer._tcp", "_mediaremotetv._tcp" ->
                name(ip, shown, RANK_FRIENDLY)
            "_rfb._tcp" -> {
                name(ip, shown, RANK_SYSTEM)
                host(ip).screenSharing = Computer(ip, serviceName, Computer.Kind.SCREEN_SHARING, port)
            }
            "_airplay._tcp", "_device-info._tcp", "_smb._tcp", "_workstation._tcp", "_hap._tcp", "_hue._tcp" ->
                name(ip, shown, RANK_SYSTEM)
            "_raop._tcp" -> name(ip, shown.substringAfter('@'), RANK_ROUTER)
            "_adb-tls-connect._tcp", "_sleep-proxy._udp", "_matter._tcp", "_nearbypresence._tcp" -> Unit // machine ids, not names
            else -> if (!kind.equals("_FC9F5ED42C8A._tcp", ignoreCase = true)) name(ip, shown, RANK_OTHER)
        }
        changed()
    }

    @Synchronized fun devices(): List<NetDevice> = snapshot()

    @Synchronized fun flush() {
        if (!dirty) return
        dirty = false
        lastPublish = SystemClock.elapsedRealtime()
        publish(snapshot())
    }

    private fun changed() {
        dirty = true
        if (SystemClock.elapsedRealtime() - lastPublish >= PUBLISH_EVERY_MS) flush()
    }

    private fun host(ip: String) = hosts.getOrPut(ip) { Host(ip) }

    private fun snapshot(): List<NetDevice> {
        val live = hosts.values.filter { it.alive }
        // The watch's own phone almost never says its name on Wi-Fi. If exactly one unnamed
        // Android phone is here, it's the one this watch is paired with.
        val unnamedAndroid = live.filter { it.android && it.names.isEmpty() && it.ip != selfIp }
        val yours = if (companionPhone != null && unnamedAndroid.size == 1) unnamedAndroid.first().ip else null
        return live.map { host ->
            val device = host.toDevice()
            if (host.ip == yours) device.copy(name = companionPhone!!, detail = "Your phone") else device
        }.sortedWith(compareBy<NetDevice>({ it.computer == null }, { ORDER.indexOf(it.kind) }, { it.name.lowercase() }))
    }

    private fun Host.toDevice(): NetDevice {
        val self = ip == selfIp
        val router = ip == gateway
        val computer = receiver ?: screenSharing
        val voted = votes.maxByOrNull { it.value }?.key
        val kind = when {
            self -> DeviceKind.WATCH
            router -> DeviceKind.ROUTER
            computer != null -> if (voted == DeviceKind.LAPTOP) DeviceKind.LAPTOP else DeviceKind.COMPUTER
            else -> voted ?: DeviceKind.UNKNOWN
        }
        val name = when {
            self -> "This watch"
            else -> names.values.firstOrNull() ?: when {
                android && kind == DeviceKind.TABLET -> "Android tablet"
                android -> "Android phone"
                kind == DeviceKind.UNKNOWN -> "Unknown device"
                else -> kind.label
            }
        }
        return NetDevice(
            ip = ip,
            name = name,
            kind = kind,
            detail = if (self) selfModel else detail,
            computer = computer,
            self = self,
        )
    }

    companion object {
        const val RANK_FRIENDLY = 0 // a name the owner gave it ("Living Room TV")
        const val RANK_SYSTEM = 1 // the computer's own name
        const val RANK_ROUTER = 2 // what the router or a host name says
        const val RANK_OTHER = 3
        private const val PUBLISH_EVERY_MS = 300L

        /** Controllable computers first, then the things people look for most. */
        private val ORDER = listOf(
            DeviceKind.TV, DeviceKind.LAPTOP, DeviceKind.COMPUTER, DeviceKind.PHONE, DeviceKind.TABLET,
            DeviceKind.SPEAKER, DeviceKind.CONSOLE, DeviceKind.PRINTER, DeviceKind.CAMERA, DeviceKind.SMART_HOME,
            DeviceKind.UNKNOWN, DeviceKind.ROUTER, DeviceKind.WATCH,
        )
    }
}

/**
 * A tiny HTTP/1.0 GET over a socket pinned to the Wi-Fi network. Device descriptions are plain
 * HTTP on the local network; going through a raw socket keeps Android's cleartext rules out of it.
 */
private object HttpGet {
    fun fetch(network: Network, url: String, maxBytes: Int = 64_000): String? = runCatching {
        val parsed = java.net.URI(url)
        if (parsed.scheme != "http") return null
        val host = parsed.host ?: return null
        val port = if (parsed.port > 0) parsed.port else 80
        val path = (parsed.rawPath?.ifEmpty { "/" } ?: "/") + (parsed.rawQuery?.let { "?$it" } ?: "")
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 1_500)
            socket.soTimeout = 1_500
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.0\r\nHost: $host:$port\r\nConnection: close\r\nUser-Agent: Drift\r\n\r\n".toByteArray())
                flush()
            }
            val input = socket.getInputStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() < maxBytes) {
                val read = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    break
                }
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            val text = out.toString(Charsets.UTF_8.name())
            val status = text.substringBefore("\r\n")
            if (!status.contains(" 200")) return null
            text.substringAfter("\r\n\r\n", "")
        }
    }.getOrNull()
}
