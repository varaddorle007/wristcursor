package com.drift.receiver

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.Robot
import java.awt.event.KeyEvent
import java.io.File
import kotlin.concurrent.thread

/**
 * Things plain Java can't do: media keys, system volume, and typing any text (any language,
 * emoji). Each OS gets its own built-in tool; nothing extra needs installing.
 */
object SystemKeys {
    private val os = System.getProperty("os.name").lowercase()
    private val isMac = os.startsWith("mac")
    private val isWindows = os.startsWith("windows")

    /** play, next, prev, volup, voldown, mute, ff, rw */
    fun media(name: String) {
        when {
            isMac -> mac(name)
            isWindows -> windows(name)
            else -> linux(name)
        }
    }

    /** Pastes [text] where the cursor is, then puts the old clipboard back. */
    fun type(robot: Robot, text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = runCatching { clipboard.getContents(null) }.getOrNull()
        clipboard.setContents(StringSelection(text), null)
        val modifier = if (isMac) KeyEvent.VK_META else KeyEvent.VK_CONTROL
        robot.keyPress(modifier)
        robot.keyPress(KeyEvent.VK_V)
        robot.keyRelease(KeyEvent.VK_V)
        robot.keyRelease(modifier)
        // Give the app a moment to read the clipboard before restoring it.
        thread(isDaemon = true) {
            Thread.sleep(400)
            if (previous != null) runCatching { clipboard.setContents(previous, null) }
        }
    }

    // macOS: volume through AppleScript (always allowed); play/next/previous as real media keys,
    // which reach Spotify, Music and browser video alike.
    private fun mac(name: String) {
        when (name) {
            "volup" -> osascript("set v to output volume of (get volume settings)", "set volume output volume (v + 6)")
            "voldown" -> osascript("set v to output volume of (get volume settings)", "set volume output volume (v - 6)")
            "mute" -> osascript("set volume output muted (not (output muted of (get volume settings)))")
            "play" -> macMediaKey(16)
            "next", "ff" -> macMediaKey(17)
            "prev", "rw" -> macMediaKey(18)
        }
    }

    private fun macMediaKey(code: Int) {
        run(
            "osascript", "-l", "JavaScript", "-e",
            """
            ObjC.import('Cocoa'); ObjC.import('CoreGraphics');
            [true, false].forEach(function (down) {
              var ev = $.NSEvent.otherEventWithTypeLocationModifierFlagsTimestampWindowNumberContextSubtypeData1Data2(
                14, $.NSMakePoint(0, 0), down ? 0xa00 : 0xb00, 0, 0, null, 8, ($code << 16) | ((down ? 0xa : 0xb) << 8), -1);
              $.CGEventPost($.kCGHIDEventTap, ev.CGEvent);
            });
            """.trimIndent(),
        )
    }

    private fun osascript(vararg lines: String) {
        run("osascript", *lines.flatMap { listOf("-e", it) }.toTypedArray())
    }

    // Windows: SendKeys understands the media virtual keys as characters 173–179.
    private fun windows(name: String) {
        val code = when (name) {
            "mute" -> 173
            "voldown" -> 174
            "volup" -> 175
            "next", "ff" -> 176
            "prev", "rw" -> 177
            "play" -> 179
            else -> return
        }
        run("powershell", "-NoProfile", "-Command", "(New-Object -ComObject WScript.Shell).SendKeys([char]$code)")
    }

    // Linux: xdotool when it's there.
    private fun linux(name: String) {
        val key = when (name) {
            "play" -> "XF86AudioPlay"
            "next", "ff" -> "XF86AudioNext"
            "prev", "rw" -> "XF86AudioPrev"
            "volup" -> "XF86AudioRaiseVolume"
            "voldown" -> "XF86AudioLowerVolume"
            "mute" -> "XF86AudioMute"
            else -> return
        }
        if (File("/usr/bin/xdotool").exists()) run("xdotool", "key", key)
    }

    /** Fire and forget, off the input thread so the mouse never waits on it. */
    private fun run(vararg command: String) {
        thread(isDaemon = true) {
            runCatching { ProcessBuilder(*command).redirectErrorStream(true).start().waitFor() }
        }
    }
}
