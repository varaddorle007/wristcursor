package com.drift.airmouse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TypingTest {

    @Test
    fun lettersDigitsAndSpaceMapToUsKeys() {
        assertEquals(0x04 to false, HidReports.keystroke('a'))
        assertEquals(0x1D to true, HidReports.keystroke('Z'))
        assertEquals(0x1E to false, HidReports.keystroke('1'))
        assertEquals(0x27 to false, HidReports.keystroke('0'))
        assertEquals(0x2C to false, HidReports.keystroke(' '))
        assertEquals(0x28 to false, HidReports.keystroke('\n'))
    }

    @Test
    fun shiftedSymbolsUseShift() {
        assertEquals(0x1E to true, HidReports.keystroke('!'))
        assertEquals(0x1F to true, HidReports.keystroke('@'))
        assertEquals(0x27 to true, HidReports.keystroke(')'))
        assertEquals(0x38 to true, HidReports.keystroke('?'))
        assertEquals(0x38 to false, HidReports.keystroke('/'))
        assertEquals(0x34 to true, HidReports.keystroke('"'))
        assertEquals(0x31 to false, HidReports.keystroke('\\'))
    }

    @Test
    fun charactersWithoutAUsKeyAreSkipped() {
        assertNull(HidReports.keystroke('é'))
        assertNull(HidReports.keystroke('क'))
    }

    @Test
    fun everyPrintableAsciiCharacterHasAKey() {
        (0x20..0x7E).map { it.toChar() }.forEach { c ->
            assertTrue("no key for '$c'", HidReports.keystroke(c) != null)
        }
    }

    @Test
    fun screenSharingKeysymsCoverAnyLanguage() {
        assertEquals('a'.code, VncLink.keysymFor('a'.code))
        assertEquals(0xE9, VncLink.keysymFor('é'.code))
        assertEquals(0x01000000 or 0x0915, VncLink.keysymFor(0x0915)) // Devanagari "क"
        assertEquals(0xFF0D, VncLink.keysymFor('\n'.code))
    }

    @Test
    fun receiverGetsTypedTextAsBase64AndMediaKeysByName() {
        val command = Protocol.type("hi नमस्ते 👋")
        assertTrue(command.startsWith("type "))
        assertEquals("hi नमस्ते 👋", String(Base64.getDecoder().decode(command.removePrefix("type ")), Charsets.UTF_8))
        assertEquals("media play", Protocol.keyCommand(RemoteKey.PLAY_PAUSE))
        assertEquals("media volup", Protocol.keyCommand(RemoteKey.VOLUME_UP))
        assertEquals("key backspace", Protocol.keyCommand(RemoteKey.BACKSPACE))
    }
}
