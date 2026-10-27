package com.drift.airmouse.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportsTest {

    @Test
    fun mouseReportIsLittleEndianSixteenBit() {
        val out = ByteArray(HidReports.MOUSE_SIZE)
        HidReports.encodeMouse(out, HidReports.LEFT_BUTTON, x = 300, y = -2, wheel = -1)
        assertArrayEquals(byteArrayOf(1, 0x2C, 0x01, 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte()), out)
    }

    @Test
    fun wheelIsClampedToOneByte() {
        val out = ByteArray(HidReports.MOUSE_SIZE)
        HidReports.encodeMouse(out, 0, 0, 0, wheel = 500)
        assertEquals(127.toByte(), out[5])
    }

    @Test
    fun keyReportPutsKeyInFirstSlot() {
        val out = ByteArray(HidReports.KEYBOARD_SIZE) { 9 }
        HidReports.encodeKey(out, HidReports.KEY_RIGHT)
        assertArrayEquals(byteArrayOf(0, 0, 0x4F, 0, 0, 0, 0, 0), out)
    }

    @Test
    fun mediaReportIsLittleEndianUsage() {
        val out = ByteArray(HidReports.MEDIA_SIZE)
        HidReports.encodeMedia(out, HidReports.consumerUsage(RemoteKey.VOLUME_UP))
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0), out)
    }

    @Test
    fun tvKeysUseAndroidConsumerUsages() {
        // Android maps these consumer usages to KEYCODE_HOME and KEYCODE_BACK.
        assertEquals(0x223, HidReports.consumerUsage(RemoteKey.HOME))
        assertEquals(0x224, HidReports.consumerUsage(RemoteKey.BACK))
        assertEquals(0, HidReports.keyboardUsage(RemoteKey.BACK, toComputer = false))
    }

    @Test
    fun backIsEscapeOnComputers() {
        assertEquals(0x29, HidReports.keyboardUsage(RemoteKey.BACK, toComputer = true))
    }

    @Test
    fun everyKeyExceptNothingHasAUsage() {
        RemoteKey.entries.filter { it != RemoteKey.NONE }.forEach { key ->
            val usage = HidReports.keyboardUsage(key, toComputer = false).takeIf { it != 0 } ?: HidReports.consumerUsage(key)
            assertTrue("$key has no HID usage", usage != 0)
            assertTrue("$key usage $usage outside the descriptor's range", usage <= 0x3FF)
        }
    }

    @Test
    fun descriptorCollectionsAreBalanced() {
        val bytes = HidReports.DESCRIPTOR.map { it.toInt() and 0xFF }
        var depth = 0
        var i = 0
        while (i < bytes.size) {
            val prefix = bytes[i]
            when (prefix) {
                0xA1 -> depth++
                0xC0 -> depth--
            }
            // Short items: low two bits give the data size (3 means 4 bytes).
            val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            i += 1 + size
        }
        assertEquals(bytes.size, i)
        assertEquals(0, depth)
    }
}
