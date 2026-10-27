package com.drift.airmouse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetProbesTest {

    @Test
    fun netbiosQueryAsksForNodeStatusOfStar() {
        val query = NetProbes.netbiosQuery(0x1234)
        assertEquals(50, query.size)
        assertEquals(0x20, query[12].toInt())
        assertEquals("CK", String(query, 13, 2, Charsets.US_ASCII)) // '*' first-level encoded
        assertEquals(0x21, query[query.size - 3].toInt()) // NBSTAT
    }

    @Test
    fun netbiosReplyGivesTheUniqueMachineName() {
        val reply = ByteArray(57 + 2 * 18)
        reply[56] = 2
        // First name: a group name (should be skipped).
        "WORKGROUP".toByteArray().copyInto(reply, 57)
        reply[57 + 15] = 0
        reply[57 + 16] = 0x80.toByte()
        // Second name: the computer itself.
        "GAMING-PC      ".toByteArray().copyInto(reply, 57 + 18)
        reply[57 + 18 + 15] = 0
        assertEquals("GAMING-PC", NetProbes.parseNetbiosName(reply))
    }

    @Test
    fun ptrQueryReversesTheAddress() {
        val query = NetProbes.ptrQuery(7, "192.168.1.5")
        val text = String(query, 12, query.size - 12 - 5, Charsets.US_ASCII)
        assertEquals("\u00015\u00011\u0003168\u0003192\u0007in-addr\u0004arpa", text)
    }

    @Test
    fun ptrAnswerIsRead() {
        val reply = buildPtrReply("Johns-iPhone.local")
        assertEquals("Johns-iPhone", NetProbes.parsePtrAnswer(reply))
        assertEquals("Johns iPhone", NetProbes.prettyHostName("Johns-iPhone"))
    }

    @Test
    fun ptrWithoutAnswerIsNull() {
        val query = NetProbes.ptrQuery(1, "10.0.0.1")
        assertNull(NetProbes.parsePtrAnswer(query))
    }

    @Test
    fun ssdpReplyAndDescription() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.20:8008/ssdp/device-desc.xml\r\n\r\n"
        assertEquals("http://192.168.1.20:8008/ssdp/device-desc.xml", NetProbes.ssdpLocation(reply))
        val xml = """<root><device><deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
            <friendlyName>Living Room TV</friendlyName><manufacturer>Google Inc.</manufacturer>
            <modelName>Chromecast</modelName></device></root>"""
        val description = NetProbes.parseDescription(xml)
        assertEquals("Living Room TV", description.friendlyName)
        assertEquals(DeviceKind.TV, NetProbes.kindFromDescription(description))
    }

    @Test
    fun routerDescription() {
        val xml = "<root><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>" +
            "<friendlyName>Archer C6</friendlyName><manufacturer>TP-Link</manufacturer></device></root>"
        assertEquals(DeviceKind.ROUTER, NetProbes.kindFromDescription(NetProbes.parseDescription(xml)))
    }

    @Test
    fun modelsAndHostNames() {
        assertEquals(DeviceKind.PHONE, NetProbes.kindFromModel("iPhone15,2"))
        assertEquals(DeviceKind.TABLET, NetProbes.kindFromModel("iPad13,4"))
        assertEquals(DeviceKind.LAPTOP, NetProbes.kindFromModel("MacBookPro18,1"))
        assertEquals(DeviceKind.TV, NetProbes.kindFromModel("AppleTV14,1"))
        assertEquals(DeviceKind.TV, NetProbes.kindFromModel("AFTMM"))
        assertEquals(DeviceKind.TV, NetProbes.kindFromModel("[TV] Samsung 7 Series"))
        assertEquals(DeviceKind.SPEAKER, NetProbes.kindFromModel("Google Nest Mini"))
        assertEquals(DeviceKind.PHONE, NetProbes.kindFromModel("Galaxy-S23"))
        assertEquals(DeviceKind.COMPUTER, NetProbes.kindFromModel("DESKTOP-4F2K9"))
        assertEquals(DeviceKind.LAPTOP, NetProbes.kindFromModel("LAPTOP-AB12"))
        assertNull(NetProbes.kindFromModel("xyz123"))
        assertEquals("MacBook Pro", NetProbes.prettyModel("MacBookPro18,1"))
        assertEquals("Chromecast", NetProbes.prettyModel("Chromecast"))
    }

    @Test
    fun portsAndServices() {
        assertEquals(DeviceKind.PHONE, NetProbes.kindFromPort(62078)?.first)
        assertEquals(DeviceKind.TV, NetProbes.kindFromPort(8001)?.first)
        assertEquals(DeviceKind.COMPUTER, NetProbes.kindFromPort(445)?.first)
        assertNull(NetProbes.kindFromPort(80))
        assertEquals(DeviceKind.TV, NetProbes.kindFromService("_androidtvremote2._tcp.")?.first)
        assertEquals(DeviceKind.PRINTER, NetProbes.kindFromService("_ipp._tcp")?.first)
    }

    @Test
    fun quickShareRecordGivesPhoneAndName() {
        val name = "Varad's S24".toByteArray()
        val info = byteArrayOf(0x02) + ByteArray(16) { 7 } + byteArrayOf(name.size.toByte()) + name
        val record = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(info)
        assertEquals(DeviceKind.PHONE to "Varad's S24", NetProbes.parseQuickShare(record))
    }

    @Test
    fun hiddenQuickShareDeviceHasNoName() {
        val info = byteArrayOf(0x06) + ByteArray(16) + byteArrayOf(0)
        val record = java.util.Base64.getUrlEncoder().encodeToString(info)
        assertEquals(DeviceKind.LAPTOP to null, NetProbes.parseQuickShare(record))
        assertNull(NetProbes.parseQuickShare("not base64 !!"))
    }

    @Test
    fun androidHostNames() {
        assertEquals(true, NetProbes.isAndroidHost("Android_8QSVP25B"))
        assertEquals(true, NetProbes.isAndroidHost("android-5f2a9c"))
        assertEquals(false, NetProbes.isAndroidHost("Macbooks-MacBook-Pro"))
        assertEquals(DeviceKind.PHONE, NetProbes.kindFromService("_nearbypresence._tcp")?.first)
    }

    /** A minimal DNS reply: one PTR question and one PTR answer using a compression pointer. */
    private fun buildPtrReply(host: String): ByteArray {
        val question = NetProbes.ptrQuery(1, "192.168.1.9")
        val out = java.io.ByteArrayOutputStream()
        out.write(question, 0, question.size)
        out.write(byteArrayOf(0xC0.toByte(), 12, 0, 12, 0, 1, 0, 0, 0, 10))
        val data = java.io.ByteArrayOutputStream()
        host.split('.').forEach { label ->
            data.write(label.length)
            data.write(label.toByteArray())
        }
        data.write(0)
        out.write(byteArrayOf(0, data.size().toByte()))
        out.write(data.toByteArray())
        val bytes = out.toByteArray()
        bytes[2] = 0x84.toByte() // response, authoritative
        bytes[7] = 1 // one answer
        return bytes
    }
}
