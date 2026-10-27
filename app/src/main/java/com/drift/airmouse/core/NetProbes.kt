package com.drift.airmouse.core

import java.io.ByteArrayOutputStream

/** What a device on the Wi-Fi is, for its icon and label. */
enum class DeviceKind(val label: String) {
    COMPUTER("Computer"),
    LAPTOP("Laptop"),
    PHONE("Phone"),
    TABLET("Tablet"),
    TV("TV"),
    SPEAKER("Speaker"),
    PRINTER("Printer"),
    ROUTER("Router"),
    WATCH("Watch"),
    CAMERA("Camera"),
    CONSOLE("Game console"),
    SMART_HOME("Smart home"),
    UNKNOWN("Device"),
}

/** One device on the Wi-Fi, as shown in the list. */
data class NetDevice(
    val ip: String,
    val name: String,
    val kind: DeviceKind,
    /** Model or maker when known, e.g. "Chromecast" or "TP-Link". */
    val detail: String? = null,
    /** Set when Drift can steer it over Wi-Fi. */
    val computer: Computer? = null,
    val self: Boolean = false,
)

/**
 * The small, pure pieces of network discovery: building and reading the packets that
 * ask a device its name, and guessing what kind of device something is.
 * Kept free of Android so they can be unit-tested.
 */
object NetProbes {

    // region NetBIOS: Windows PCs (and many NAS boxes) answer with their computer name.

    /** A NetBIOS "node status" query, sent to UDP 137. */
    fun netbiosQuery(id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        out.write(0x20)
        // "*" padded to 16 bytes, each byte split into two letters (first-level encoding).
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        raw.forEach { b ->
            val v = b.toInt() and 0xFF
            out.write('A'.code + (v shr 4))
            out.write('A'.code + (v and 0x0F))
        }
        out.write(byteArrayOf(0, 0, 0x21, 0, 1))
        return out.toByteArray()
    }

    /** The machine name from a node-status reply, or null. */
    fun parseNetbiosName(reply: ByteArray, length: Int = reply.size): String? {
        // Header (12) + echoed name (34) + type, class, TTL, data length (10) = 56, then the name count.
        val countAt = 56
        if (length <= countAt) return null
        val count = reply[countAt].toInt() and 0xFF
        for (i in 0 until count) {
            val at = countAt + 1 + i * 18
            if (at + 18 > length) return null
            val suffix = reply[at + 15].toInt() and 0xFF
            val flags = ((reply[at + 16].toInt() and 0xFF) shl 8) or (reply[at + 17].toInt() and 0xFF)
            val group = flags and 0x8000 != 0
            if (suffix == 0x00 && !group) {
                val name = String(reply, at, 15, Charsets.US_ASCII).trim { it <= ' ' }
                if (name.isNotEmpty() && name != "*") return name
            }
        }
        return null
    }

    // endregion

    // region DNS reverse lookup: routers (DHCP names) and mDNS responders (Apple, Linux) answer.

    /** A PTR query for [ip] ("192.168.1.5" asks for 5.1.168.192.in-addr.arpa). */
    fun ptrQuery(id: Int, ip: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        val labels = ip.split('.').reversed() + listOf("in-addr", "arpa")
        labels.forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(byteArrayOf(0, 0, 12, 0, 1))
        return out.toByteArray()
    }

    /** The host name in a PTR answer ("Johns-iPhone.local" becomes "Johns-iPhone"), or null. */
    fun parsePtrAnswer(reply: ByteArray, length: Int = reply.size): String? {
        if (length < 12) return null
        val questions = u16(reply, 4)
        val answers = u16(reply, 6) + u16(reply, 8) + u16(reply, 10)
        var at = 12
        repeat(questions) {
            at = skipName(reply, at, length) ?: return null
            at += 4
        }
        repeat(answers) {
            at = skipName(reply, at, length) ?: return null
            if (at + 10 > length) return null
            val type = u16(reply, at)
            val dataLength = u16(reply, at + 8)
            val data = at + 10
            if (type == 12) {
                val name = readName(reply, data, length) ?: return null
                return name.removeSuffix(".").removeSuffix(".local").removeSuffix(".lan").removeSuffix(".home")
                    .takeIf { it.isNotBlank() && !it.endsWith("in-addr.arpa") }
            }
            at = data + dataLength
        }
        return null
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun skipName(b: ByteArray, start: Int, length: Int): Int? {
        var at = start
        while (at < length) {
            val len = b[at].toInt() and 0xFF
            when {
                len == 0 -> return at + 1
                len and 0xC0 == 0xC0 -> return at + 2
                else -> at += 1 + len
            }
        }
        return null
    }

    private fun readName(b: ByteArray, start: Int, length: Int): String? {
        val parts = mutableListOf<String>()
        var at = start
        var jumps = 0
        while (at < length) {
            val len = b[at].toInt() and 0xFF
            when {
                len == 0 -> return parts.joinToString(".")
                len and 0xC0 == 0xC0 -> {
                    if (at + 1 >= length || ++jumps > 16) return null
                    at = ((len and 0x3F) shl 8) or (b[at + 1].toInt() and 0xFF)
                }
                else -> {
                    if (at + 1 + len > length) return null
                    parts += String(b, at + 1, len, Charsets.UTF_8)
                    at += 1 + len
                }
            }
        }
        return null
    }

    // endregion

    // region UPnP / SSDP: TVs, routers, speakers and media boxes describe themselves in XML.

    fun ssdpSearch(target: String) =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $target\r\n\r\n"

    /** The LOCATION header of an SSDP reply. */
    fun ssdpLocation(reply: String): String? =
        reply.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
            ?.takeIf { it.startsWith("http") }

    data class Description(
        val friendlyName: String?,
        val manufacturer: String?,
        val modelName: String?,
        val deviceType: String?,
    )

    fun parseDescription(xml: String): Description = Description(
        friendlyName = tag(xml, "friendlyName"),
        manufacturer = tag(xml, "manufacturer"),
        modelName = tag(xml, "modelName"),
        deviceType = tag(xml, "deviceType"),
    )

    /** First `<name>…</name>` value, unescaped. Good enough for UPnP descriptions. */
    fun tag(xml: String, name: String): String? {
        val match = Regex("<(?:\\w+:)?$name>([^<]*)</(?:\\w+:)?$name>").find(xml) ?: return null
        return match.groupValues[1]
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    fun kindFromDescription(d: Description): DeviceKind? {
        val type = d.deviceType.orEmpty()
        val all = listOfNotNull(d.friendlyName, d.manufacturer, d.modelName).joinToString(" ")
        return when {
            type.contains("InternetGatewayDevice") || type.contains("WANDevice") || type.contains("WLANAccessPoint") -> DeviceKind.ROUTER
            type.contains("Printer", ignoreCase = true) -> DeviceKind.PRINTER
            else -> kindFromModel(all) ?: if (type.contains("MediaRenderer")) DeviceKind.TV else null
        }
    }

    // endregion

    // region Guessing what something is

    /** From a model or product name such as "iPhone15,2", "MacBookPro18,1", "Chromecast" or "[TV] Samsung". */
    fun kindFromModel(model: String): DeviceKind? {
        val m = model.lowercase()
        return when {
            m.startsWith("iphone") || m.startsWith("ipod") -> DeviceKind.PHONE
            m.startsWith("ipad") -> DeviceKind.TABLET
            m.startsWith("macbook") -> DeviceKind.LAPTOP
            m.startsWith("imac") || m.startsWith("macmini") || m.startsWith("macpro") ||
                m.startsWith("macstudio") || m.startsWith("mac1") || m.startsWith("mac ") -> DeviceKind.COMPUTER
            m.startsWith("appletv") -> DeviceKind.TV
            m.startsWith("aft") -> DeviceKind.TV // Fire TV models: AFTMM, AFTKA, ...
            m.startsWith("audioaccessory") || m.contains("homepod") -> DeviceKind.SPEAKER
            m.startsWith("watch") -> DeviceKind.WATCH
            SPEAKER_WORDS.any { it in m } -> DeviceKind.SPEAKER
            TV_WORDS.any { it in m } -> DeviceKind.TV
            PRINTER_WORDS.any { it in m } -> DeviceKind.PRINTER
            CONSOLE_WORDS.any { it in m } -> DeviceKind.CONSOLE
            CAMERA_WORDS.any { it in m } -> DeviceKind.CAMERA
            ROUTER_WORDS.any { it in m } -> DeviceKind.ROUTER
            m.contains("laptop") || m.contains("notebook") || m.contains("thinkpad") -> DeviceKind.LAPTOP
            m.contains("desktop") || m.contains("-pc") || m.endsWith(" pc") -> DeviceKind.COMPUTER
            m.contains("galaxy-tab") || m.contains("galaxy tab") || m.contains("tablet") -> DeviceKind.TABLET
            PHONE_WORDS.any { it in m } -> DeviceKind.PHONE
            else -> null
        }
    }

    /** What an open TCP port says about a device, with how sure that is (higher is surer). */
    fun kindFromPort(port: Int): Pair<DeviceKind, Int>? = when (port) {
        62078 -> DeviceKind.PHONE to 6 // iPhone and iPad sync service
        6466, 6467 -> DeviceKind.TV to 8 // Android TV remote service
        8001, 8002 -> DeviceKind.TV to 8 // Samsung TV
        8060 -> DeviceKind.TV to 8 // Roku
        5555 -> DeviceKind.TV to 4 // Fire TV and Android TV network debugging
        8008, 8009 -> DeviceKind.TV to 4 // Google Cast
        3001 -> DeviceKind.TV to 3 // LG webOS
        1400 -> DeviceKind.SPEAKER to 8 // Sonos
        631, 9100, 515 -> DeviceKind.PRINTER to 8
        554 -> DeviceKind.CAMERA to 5
        445, 139, 3389, 135 -> DeviceKind.COMPUTER to 5 // Windows file sharing / remote desktop
        548, 5900 -> DeviceKind.COMPUTER to 5 // Mac file sharing / screen sharing
        22 -> DeviceKind.COMPUTER to 3
        else -> null
    }

    /** Bonjour service types and what they say about a device. */
    fun kindFromService(type: String): Pair<DeviceKind, Int>? = when (type.removeSuffix(".").removeSuffix(".local").trimStart('.')) {
        "_androidtvremote2._tcp", "_amzn-wplay._tcp", "_mediaremotetv._tcp" -> DeviceKind.TV to 10
        "_googlecast._tcp" -> DeviceKind.TV to 5
        "_airplay._tcp" -> DeviceKind.TV to 2
        "_raop._tcp", "_spotify-connect._tcp", "_sonos._tcp" -> DeviceKind.SPEAKER to 3
        "_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp" -> DeviceKind.PRINTER to 10
        "_hap._tcp", "_matter._tcp", "_hue._tcp" -> DeviceKind.SMART_HOME to 6
        "_rfb._tcp" -> DeviceKind.COMPUTER to 6
        "_smb._tcp", "_workstation._tcp", "_ssh._tcp", "_sftp-ssh._tcp", "_device-info._tcp" -> DeviceKind.COMPUTER to 3
        "_apple-mobdev2._tcp" -> DeviceKind.PHONE to 6
        "_adb-tls-connect._tcp" -> DeviceKind.PHONE to 2
        "_nearbypresence._tcp", "_FC9F5ED42C8A._tcp" -> DeviceKind.PHONE to 5 // Google Nearby / Quick Share
        "_sleep-proxy._udp" -> DeviceKind.TV to 3
        else -> null
    }

    /**
     * Quick Share (Nearby Share) puts the device's type and, when it's visible to everyone, its name
     * in the Bonjour text record "n": URL-safe base64 of [flags, 16 bytes of keys, name length, name].
     * Bits 1-3 of the first byte are the type: 1 phone, 2 tablet, 3 computer.
     */
    fun parseQuickShare(record: String): Pair<DeviceKind?, String?>? {
        val data = runCatching { java.util.Base64.getUrlDecoder().decode(record.trim()) }.getOrNull() ?: return null
        if (data.size < 18) return null
        val kind = when ((data[0].toInt() shr 1) and 7) {
            1 -> DeviceKind.PHONE
            2 -> DeviceKind.TABLET
            3 -> DeviceKind.LAPTOP
            else -> null
        }
        val length = data[17].toInt() and 0xFF
        val name = if (length > 0 && data.size >= 18 + length) String(data, 18, length, Charsets.UTF_8).trim() else null
        return kind to name?.takeIf { it.isNotEmpty() }
    }

    /** Android phones and tablets announce themselves as "Android_8QSVP25B" or "android-1a2b3c". */
    fun isAndroidHost(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return n.startsWith("android_") || n.startsWith("android-")
    }

    /** Apple's model codes ("MacBookPro18,1", "iPhone15,2") as people say them; anything else unchanged. */
    fun prettyModel(model: String): String {
        val apple = APPLE_MODEL.find(model) ?: return model
        return when (val family = apple.groupValues[1]) {
            "MacBookPro" -> "MacBook Pro"
            "MacBookAir" -> "MacBook Air"
            "Macmini" -> "Mac mini"
            "MacPro" -> "Mac Pro"
            "MacStudio" -> "Mac Studio"
            "AppleTV" -> "Apple TV"
            "AudioAccessory" -> "HomePod"
            "Watch" -> "Apple Watch"
            else -> family
        }
    }

    private val APPLE_MODEL =
        Regex("^(iPhone|iPad|iPod|MacBookPro|MacBookAir|MacBook|iMac|Macmini|MacPro|MacStudio|Mac|AppleTV|AudioAccessory|Watch)\\d+,\\d+$")

    /** "Macbooks-MacBook-Pro" reads better as "Macbooks MacBook Pro". */
    fun prettyHostName(name: String): String =
        name.substringBefore('.').replace('-', ' ').replace('_', ' ').trim()

    private val TV_WORDS = listOf(
        "[tv]", " tv", "tv ", "-tv", "television", "chromecast", "google tv", "streamer", "shield", "bravia",
        "fire tv", "firetv", "roku", "webos", "tizen", "smarttv", "smart tv", "android tv", "mi box", "apple tv",
    )
    private val SPEAKER_WORDS = listOf(
        "speaker", "sonos", "home mini", "nest mini", "nest audio", "google home", "echo", "soundbar", "bose", "jbl", "homepod",
    )
    private val PRINTER_WORDS = listOf("printer", "laserjet", "officejet", "deskjet", "pixma", "epson", "ecotank", "brother", "canon mf")
    private val CONSOLE_WORDS = listOf("playstation", "xbox", "nintendo")
    private val CAMERA_WORDS = listOf("camera", "cam ", "ipcam", "doorbell", "ring ", "wyze", "arlo")
    private val ROUTER_WORDS = listOf("router", "archer", "deco", "tp-link", "netgear", "asus rt", "linksys", "mesh", "gateway", "access point")
    private val PHONE_WORDS = listOf("iphone", "galaxy", "pixel", "redmi", "oneplus", "phone", "android", "xiaomi", "oppo", "vivo", "realme", "motorola", "moto ")

    // endregion
}
