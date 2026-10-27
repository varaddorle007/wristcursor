package com.drift.airmouse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemapTest {

    @Test
    fun defaultsAreARemoteAndAMediaPad() {
        val bindings = Bindings()
        assertEquals(Binding(RemoteKey.OK, RemoteKey.MENU), bindings.of(Pad.REMOTE, Slot.CENTER))
        assertEquals(RemoteKey.UP, bindings.of(Pad.REMOTE, Slot.UP).short)
        assertEquals(Binding(RemoteKey.PLAY_PAUSE, RemoteKey.MUTE), bindings.of(Pad.MEDIA, Slot.CENTER))
    }

    @Test
    fun remappingOneButtonLeavesTheRest() {
        val changed = Bindings().with(Pad.REMOTE, Slot.UP, Binding(RemoteKey.UP, RemoteKey.HOME))
        assertEquals(RemoteKey.HOME, changed.of(Pad.REMOTE, Slot.UP).long)
        assertFalse(changed.isDefault(Pad.REMOTE, Slot.UP))
        assertTrue(changed.isDefault(Pad.REMOTE, Slot.DOWN))
    }

    @Test
    fun bindingsSurviveSaving() {
        val binding = Binding(RemoteKey.VOLUME_UP, RemoteKey.MUTE)
        assertEquals(binding, Remap.decode(Remap.encode(binding)))
        assertEquals(Binding(RemoteKey.OK, RemoteKey.NONE), Remap.decode("OK"))
        assertNull(Remap.decode("NOT_A_KEY|HOME"))
    }

    @Test
    fun onlyArrowsAndVolumeRepeat() {
        assertTrue(Remap.repeats(RemoteKey.LEFT))
        assertTrue(Remap.repeats(RemoteKey.VOLUME_DOWN))
        assertFalse(Remap.repeats(RemoteKey.OK))
        assertFalse(Remap.repeats(RemoteKey.HOME))
    }

    @Test
    fun receiverKeepsOldSlideCommands() {
        assertEquals("key next", Protocol.keyCommand(RemoteKey.RIGHT))
        assertEquals("key prev", Protocol.keyCommand(RemoteKey.LEFT))
        assertEquals("key enter", Protocol.keyCommand(RemoteKey.OK))
        // Media keys work over Wi-Fi now; a TV-only key like Home still has no computer equivalent.
        assertEquals("media volup", Protocol.keyCommand(RemoteKey.VOLUME_UP))
        assertNull(Protocol.keyCommand(RemoteKey.HOME))
    }
}
