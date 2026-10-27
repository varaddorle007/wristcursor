package com.drift.airmouse.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executor

/**
 * Bluetooth: the watch shows up on the computer as a regular mouse and keyboard,
 * so nothing needs installing there. Pair once from the computer's Bluetooth settings;
 * after that Drift reconnects by itself.
 */
@SuppressLint("MissingPermission") // checked in start()
class HidLink(
    private val context: Context,
    private val handler: Handler,
    private val prefs: AppPrefs,
    private val events: Events,
) : PointerLink {

    interface Events {
        fun onWaitingForHost(watchName: String)
        fun onConnected(hostName: String)
        fun onUnavailable(reason: String)
    }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val executor = Executor { handler.post(it) }
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var closed = false
    private var reconnectTries = 0
    private var retrying: BluetoothDevice? = null

    private var buttons = 0
    private var lastSentButtons = 0
    private var pendingX = 0
    private var pendingY = 0
    private var lastReportNanos = 0L
    private var flushScheduled = false
    private val mouseReport = ByteArray(HidReports.MOUSE_SIZE)
    private val keyReport = ByteArray(HidReports.KEYBOARD_SIZE)
    private val mediaReport = ByteArray(HidReports.MEDIA_SIZE)
    private val gamepadReport = ByteArray(HidReports.GAMEPAD_SIZE)

    private var lastWheelX = 0
    private var lastWheelButtons = 0
    private var lastWheelKeys = IntArray(6)
    private var lastWheelReportNanos = 0L

    private val flushRunnable = Runnable {
        flushScheduled = false
        if (pendingX != 0 || pendingY != 0) {
            sendMouse(0)
        }
    }

    fun start() {
        val bluetooth = adapter
        when {
            bluetooth == null -> events.onUnavailable("This watch has no Bluetooth")
            !hasPermission(context) -> events.onUnavailable("Allow Bluetooth for Drift first")
            !bluetooth.isEnabled -> events.onUnavailable("Turn on Bluetooth on the watch")
            !bluetooth.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE) ->
                unsupported()
            else -> handler.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS)
        }
    }

    override fun move(dx: Int, dy: Int) {
        pendingX += dx
        pendingY += dy
        val now = SystemClock.elapsedRealtimeNanos()
        val elapsed = now - lastReportNanos
        if (elapsed >= MIN_REPORT_GAP_NANOS) {
            handler.removeCallbacks(flushRunnable)
            flushScheduled = false
            sendMouse(0)
        } else if (!flushScheduled) {
            val delayMs = ((MIN_REPORT_GAP_NANOS - elapsed) / 1_000_000L).coerceAtLeast(1L)
            handler.postDelayed(flushRunnable, delayMs)
            flushScheduled = true
        }
    }

    override fun click(button: MouseButton) {
        press(button)
        handler.postDelayed({ release(button) }, PRESS_MS)
    }

    override fun press(button: MouseButton) {
        buttons = buttons or button.mask()
        sendMouse(0)
    }

    override fun release(button: MouseButton) {
        buttons = buttons and button.mask().inv()
        sendMouse(0)
    }

    override fun scroll(steps: Int) {
        // HID wheel is positive for "scroll up".
        sendMouse(-steps)
    }

    override fun wheel(steer: Float, throttle: Boolean, brake: Boolean, nitro: Boolean) {
        val device = host ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val x = (steer * HidReports.MAX_DELTA).toInt().coerceIn(-HidReports.MAX_DELTA, HidReports.MAX_DELTA)
        val y = when {
            throttle -> 32767
            brake -> -32767
            else -> 0
        }
        var btn = 0
        if (throttle) btn = btn or 0x01
        if (brake) btn = btn or 0x02
        if (nitro) btn = btn or 0x04

        // 1. Send Gamepad HID report (Report ID 4) rate-limited to 50 Hz (20ms gap) unless buttons changed
        val buttonsChanged = btn != lastWheelButtons
        if (buttonsChanged || now - lastWheelReportNanos >= 18_000_000L) {
            lastWheelX = x
            lastWheelButtons = btn
            lastWheelReportNanos = now
            HidReports.encodeGamepad(gamepadReport, x, y, btn)
            hid?.sendReport(device, HidReports.GAMEPAD_ID, gamepadReport)
        }

        // 2. Send keyboard arrow keys / space with instant low deadzone (0.05) so games expecting keyboard steer crisply
        val keySteer = when {
            steer < -0.05f -> HidReports.KEY_LEFT
            steer > 0.05f -> HidReports.KEY_RIGHT
            else -> 0
        }
        val keyThrottle = if (throttle) 0x52 else 0 // Up Arrow
        val keyBrake = if (brake) 0x51 else 0       // Down Arrow
        val keyNitro = if (nitro) 0x2C else 0       // Spacebar

        val targetKeys = intArrayOf(keySteer, keyThrottle, keyBrake, keyNitro, 0, 0)
        if (!targetKeys.contentEquals(lastWheelKeys)) {
            lastWheelKeys = targetKeys.clone()
            keyReport.fill(0)
            var idx = 2
            for (k in targetKeys) {
                if (k != 0 && idx < keyReport.size) {
                    keyReport[idx++] = k.toByte()
                }
            }
            hid?.sendReport(device, HidReports.KEYBOARD_ID, keyReport)
        }
    }

    override fun send(key: RemoteKey): Boolean {
        val device = host ?: return true
        val keyboard = HidReports.keyboardUsage(key, toComputer = isComputer(device))
        if (keyboard != 0) {
            HidReports.encodeKey(keyReport, keyboard)
            hid?.sendReport(device, HidReports.KEYBOARD_ID, keyReport)
            handler.postDelayed({
                HidReports.encodeKey(keyReport, 0)
                host?.let { hid?.sendReport(it, HidReports.KEYBOARD_ID, keyReport) }
            }, PRESS_MS)
            return true
        }
        val usage = HidReports.consumerUsage(key)
        if (usage == 0) return false
        HidReports.encodeMedia(mediaReport, usage)
        hid?.sendReport(device, HidReports.MEDIA_ID, mediaReport)
        handler.postDelayed({
            HidReports.encodeMedia(mediaReport, 0)
            host?.let { hid?.sendReport(it, HidReports.MEDIA_ID, mediaReport) }
        }, PRESS_MS)
        return true
    }

    override fun keyDown(key: RemoteKey): Boolean {
        val device = host ?: return true
        val keyboard = HidReports.keyboardUsage(key, toComputer = isComputer(device))
        if (keyboard != 0) {
            HidReports.encodeKey(keyReport, keyboard)
            hid?.sendReport(device, HidReports.KEYBOARD_ID, keyReport)
            return true
        }
        val usage = HidReports.consumerUsage(key)
        if (usage != 0) {
            HidReports.encodeMedia(mediaReport, usage)
            hid?.sendReport(device, HidReports.MEDIA_ID, mediaReport)
            return true
        }
        return false
    }

    override fun keyUp(key: RemoteKey): Boolean {
        val device = host ?: return true
        val keyboard = HidReports.keyboardUsage(key, toComputer = isComputer(device))
        if (keyboard != 0) {
            HidReports.encodeKey(keyReport, 0)
            hid?.sendReport(device, HidReports.KEYBOARD_ID, keyReport)
            return true
        }
        val usage = HidReports.consumerUsage(key)
        if (usage != 0) {
            HidReports.encodeMedia(mediaReport, 0)
            hid?.sendReport(device, HidReports.MEDIA_ID, mediaReport)
            return true
        }
        return false
    }

    // A Bluetooth mouse only ever moves relative to where the cursor is; it can't know the screen size.
    override fun center(): Boolean = false

    /**
     * Types like a US keyboard, one key at a time at a steady pace so nothing gets dropped.
     * Characters with no key on a US keyboard (accents, emoji) are skipped.
     */
    override fun type(text: String): Boolean {
        if (host == null) return true
        var at = 0L
        for (c in text.take(MAX_TYPED)) {
            val (usage, shift) = HidReports.keystroke(c) ?: continue
            handler.postDelayed({ sendKeyboard(usage, shift) }, at)
            handler.postDelayed({ sendKeyboard(0, false) }, at + PRESS_MS)
            at += TYPE_STEP_MS
        }
        return true
    }

    private fun sendKeyboard(usage: Int, shift: Boolean) {
        val device = host ?: return
        HidReports.encodeKey(keyReport, usage)
        keyReport[0] = if (shift) HidReports.LEFT_SHIFT else 0
        hid?.sendReport(device, HidReports.KEYBOARD_ID, keyReport)
    }

    /** Computers take Escape for Back; TVs, phones and tablets take Android's own Back key. */
    private fun isComputer(device: BluetoothDevice): Boolean =
        runCatching { device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }.getOrDefault(false)

    private fun MouseButton.mask() =
        if (this == MouseButton.LEFT) HidReports.LEFT_BUTTON else HidReports.RIGHT_BUTTON

    override fun close() {
        closed = true
        handler.removeCallbacks(proxyTimeout)
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(flushRunnable)
        flushScheduled = false
        hid?.let { device ->
            runCatching { device.unregisterApp() }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        }
        hid = null
        host = null
    }

    private fun sendMouse(wheel: Int) {
        val device = host ?: return
        if (pendingX == 0 && pendingY == 0 && wheel == 0 && buttons == lastSentButtons) return
        val x = pendingX.coerceIn(-HidReports.MAX_DELTA, HidReports.MAX_DELTA)
        val y = pendingY.coerceIn(-HidReports.MAX_DELTA, HidReports.MAX_DELTA)
        HidReports.encodeMouse(mouseReport, buttons, x, y, wheel)
        if (hid?.sendReport(device, HidReports.MOUSE_ID, mouseReport) == true) {
            pendingX -= x
            pendingY -= y
            lastSentButtons = buttons
            lastReportNanos = SystemClock.elapsedRealtimeNanos()
            if ((pendingX != 0 || pendingY != 0) && !flushScheduled) {
                handler.postDelayed(flushRunnable, 4L)
                flushScheduled = true
            }
        } else {
            // Buffer busy or temporary error -> retry shortly so no movement is lost
            if (!flushScheduled) {
                handler.postDelayed(flushRunnable, 3L)
                flushScheduled = true
            }
        }
    }

    private fun reconnect(device: BluetoothDevice) {
        if (closed || reconnectTries >= MAX_RECONNECT_TRIES) {
            retrying = null
            return
        }
        handler.removeCallbacks(flushRunnable)
        flushScheduled = false
        pendingX = 0
        pendingY = 0
        reconnectTries++
        retrying = device
        handler.postDelayed({
            if (!closed && host == null) hid?.connect(device)
        }, RECONNECT_DELAY_MS * reconnectTries)
    }

    private fun savedHost(): BluetoothDevice? {
        val saved = prefs.bluetoothHost.value ?: return null
        return adapter?.bondedDevices?.firstOrNull { it.address == saved.address }
    }

    /** Remember the answer and fall back to Wi-Fi so the next Go just works. */
    private fun unsupported() {
        prefs.bluetoothMouseSupported = false
        prefs.setTransport(Transport.WIFI)
        events.onUnavailable(NOT_SUPPORTED)
    }

    private fun watchName(): String = adapter?.name ?: "this watch"

    private val proxyTimeout = Runnable {
        if (hid == null && !closed) unsupported()
    }

    private val connectTimeout = Runnable {
        if (host == null && !closed) events.onWaitingForHost(watchName())
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            handler.post {
                handler.removeCallbacks(proxyTimeout)
                if (closed) {
                    adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                    return@post
                }
                val device = proxy as BluetoothHidDevice
                hid = device
                if (!device.registerApp(SDP, null, null, executor, callback)) unsupported()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            handler.post {
                hid = null
                host = null
            }
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (closed) return
            if (!registered) {
                events.onUnavailable("Another app is using Bluetooth mouse mode")
                return
            }
            // The computer you picked wins over whichever one the watch connected to last.
            val target = savedHost() ?: pluggedDevice
            if (target != null && hid?.connect(target) == true) {
                handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            } else {
                events.onWaitingForHost(watchName())
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (closed) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handler.removeCallbacks(connectTimeout)
                    host = device
                    reconnectTries = 0
                    retrying = null
                    val name = device.name ?: "Computer"
                    prefs.saveBluetoothHost(BluetoothHost(device.address, name))
                    events.onConnected(name)
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || device == host) {
                    val dropped = host ?: device.takeIf { it == retrying }
                    host = null
                    events.onWaitingForHost(watchName())
                    // The computer slept or walked out of range: try to get it back by ourselves.
                    if (dropped != null) reconnect(dropped)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val report = when (id.toInt()) {
                HidReports.KEYBOARD_ID -> keyReport.copyOf()
                HidReports.MEDIA_ID -> mediaReport.copyOf()
                HidReports.GAMEPAD_ID -> gamepadReport.copyOf()
                else -> mouseReport.copyOf()
            }
            hid?.replyReport(device, type, id, report)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (host == device) {
                host = null
                events.onWaitingForHost(watchName())
            }
        }
    }

    companion object {
        const val NOT_SUPPORTED = "This watch can't be a Bluetooth mouse, so Drift switched to Wi-Fi"
        private const val PROXY_TIMEOUT_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 6_000L
        private const val PRESS_MS = 15L
        private const val TYPE_STEP_MS = 28L
        private const val MAX_TYPED = 2_000
        private const val MAX_RECONNECT_TRIES = 5
        private const val RECONNECT_DELAY_MS = 1_500L
        private const val MIN_REPORT_GAP_NANOS = 7_000_000L

        private val SDP = BluetoothHidDeviceAppSdpSettings(
            "Drift", "Drift air mouse", "Drift", BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR
        )

        val PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                emptyArray()
            }

        fun hasPermission(context: Context): Boolean = PERMISSIONS.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

/** What the computer or TV sees: a 3-button mouse with a wheel, a keyboard, remote/media keys, and a gamepad. */
object HidReports {
    const val MOUSE_ID = 1
    const val KEYBOARD_ID = 2
    const val LEFT_SHIFT: Byte = 0x02
    const val MEDIA_ID = 3
    const val GAMEPAD_ID = 4
    const val MOUSE_SIZE = 6
    const val KEYBOARD_SIZE = 8
    const val MEDIA_SIZE = 2
    const val GAMEPAD_SIZE = 5
    const val MAX_DELTA = 32767
    const val LEFT_BUTTON = 0x01
    const val RIGHT_BUTTON = 0x02
    const val KEY_RIGHT = 0x4F
    const val KEY_LEFT = 0x50

    val DESCRIPTOR: ByteArray = intArrayOf(
        // Mouse: buttons, 16-bit X/Y, wheel
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, MOUSE_ID,
        0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,
        // Keyboard: modifiers, reserved, 6 keys
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, KEYBOARD_ID,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x75, 0x08, 0x95, 0x01, 0x81, 0x01,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x15, 0x00, 0x25, 0x65, 0x75, 0x08, 0x95, 0x06, 0x81, 0x00,
        0xC0,
        // Media keys: one 16-bit consumer usage
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, MEDIA_ID,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
        // Gamepad: Report ID 4, 16-bit X (Steering), 16-bit Y (Throttle/Brake), 8 Buttons
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, GAMEPAD_ID,
        0x09, 0x01, 0xA1, 0x00,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
        0xC0,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x08, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0xC0,
    ).map { it.toByte() }.toByteArray()

    fun encodeMouse(out: ByteArray, buttons: Int, x: Int, y: Int, wheel: Int) {
        out[0] = buttons.toByte()
        out[1] = x.toByte()
        out[2] = (x shr 8).toByte()
        out[3] = y.toByte()
        out[4] = (y shr 8).toByte()
        out[5] = wheel.coerceIn(-127, 127).toByte()
    }

    fun encodeGamepad(out: ByteArray, x: Int, y: Int, buttons: Int) {
        out[0] = (x and 0xFF).toByte()
        out[1] = ((x shr 8) and 0xFF).toByte()
        out[2] = (y and 0xFF).toByte()
        out[3] = ((y shr 8) and 0xFF).toByte()
        out[4] = (buttons and 0xFF).toByte()
    }

    fun encodeKey(out: ByteArray, keyCode: Int) {
        out.fill(0)
        out[2] = keyCode.toByte()
    }

    fun encodeMedia(out: ByteArray, usage: Int) {
        out[0] = usage.toByte()
        out[1] = (usage shr 8).toByte()
    }

    /** Keyboard usage for keys a keyboard has, or 0 to use [consumerUsage] instead. */
    fun keyboardUsage(key: RemoteKey, toComputer: Boolean): Int = when (key) {
        RemoteKey.UP -> 0x52
        RemoteKey.DOWN -> 0x51
        RemoteKey.LEFT -> KEY_LEFT
        RemoteKey.RIGHT -> KEY_RIGHT
        RemoteKey.OK -> 0x28 // Enter: Android TV treats it as the D-pad center
        RemoteKey.BACK -> if (toComputer) 0x29 else 0 // Escape
        RemoteKey.BACKSPACE -> 0x2A
        RemoteKey.SPACE -> 0x2C
        RemoteKey.SHIFT -> 0xE1
        RemoteKey.KEY_W -> 0x1A
        RemoteKey.KEY_A -> 0x04
        RemoteKey.KEY_S -> 0x16
        RemoteKey.KEY_D -> 0x07
        RemoteKey.KEY_E -> 0x08
        RemoteKey.ESCAPE -> 0x29
        else -> 0
    }

    /** A typed character as a US-keyboard key and whether Shift is held, or null if it has no key. */
    fun keystroke(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> (0x04 + (c - 'a')) to false
        in 'A'..'Z' -> (0x04 + (c - 'A')) to true
        in '1'..'9' -> (0x1E + (c - '1')) to false
        '0' -> 0x27 to false
        '\n' -> 0x28 to false
        '\t' -> 0x2B to false
        ' ' -> 0x2C to false
        else -> SHIFTED_DIGITS.indexOf(c).takeIf { it >= 0 }?.let { (0x1E + it) to true }
            ?: SYMBOLS.indexOf(c).takeIf { it >= 0 }?.let { SYMBOL_KEYS[it / 2] to (it % 2 == 1) }
    }

    // Each pair is the unshifted and shifted character on one key, matching SYMBOL_KEYS.
    private const val SYMBOLS = "-_=+[{]}\\|;:'\"`~,<.>/?"
    private val SYMBOL_KEYS = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
    private const val SHIFTED_DIGITS = "!@#\$%^&*()"

    /** Consumer-control usage (the remote and media keys every TV and computer understands), or 0. */
    fun consumerUsage(key: RemoteKey): Int = when (key) {
        RemoteKey.PLAY_PAUSE -> 0xCD
        RemoteKey.NEXT -> 0xB5
        RemoteKey.PREVIOUS -> 0xB6
        RemoteKey.FAST_FORWARD -> 0xB3
        RemoteKey.REWIND -> 0xB4
        RemoteKey.VOLUME_UP -> 0xE9
        RemoteKey.VOLUME_DOWN -> 0xEA
        RemoteKey.MUTE -> 0xE2
        RemoteKey.MENU -> 0x40
        RemoteKey.SEARCH -> 0x221
        RemoteKey.HOME -> 0x223
        RemoteKey.BACK -> 0x224
        else -> 0
    }
}
