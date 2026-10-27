package com.drift.airmouse.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class VncLinkTest {

    @Test
    fun vncPasswordResponseMatchesReferenceDes() {
        // Reference computed with OpenSSL: key "password" with each byte bit-reversed (0E86CECEEEF64E26).
        val challenge = ByteArray(16) { it.toByte() }
        val expected = "b866924125c8eebb9debc1db61c538e2".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, VncLink.vncResponse(challenge, "password"))
    }

    @Test
    fun keysArePaddedOrTrimmedToTheirLength() {
        assertArrayEquals(byteArrayOf(0, 0, 1, 0), VncLink.fixed(BigInteger.valueOf(256), 4))
        // BigInteger adds a sign byte for 0xFF.. values; it must be dropped.
        assertEquals(2, VncLink.fixed(BigInteger(1, byteArrayOf(0xFF.toByte(), 0xFE.toByte())), 2).size)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), VncLink.fixed(BigInteger(1, byteArrayOf(0xFF.toByte(), 0xFE.toByte())), 2))
    }
}
