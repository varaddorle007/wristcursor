package com.drift.airmouse.core

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class Transport(val label: String) {
    BLUETOOTH("Bluetooth"),
    WIFI("Wi-Fi")
}

/** What steering does. New modes go at the end so saved choices keep their meaning. */
enum class Mode(val label: String, val detail: String) {
    MOUSE("Mouse", "Tilt your wrist to move"),
    SLIDES("Slides", "Next and back for talks"),
    MEDIA("Media", "Play, skip and volume"),
    TOUCHPAD("Touchpad", "Drag your finger to move"),
    REMOTE("TV remote", "Arrows, OK, Back and Home"),
    KEYBOARD("Keyboard", "Speak or type into the computer"),
    GAME("Game", "Wheel & WASD controller");

    companion object {
        /** The order the mode picker shows them in. */
        val picker = listOf(MOUSE, GAME, TOUCHPAD, KEYBOARD, REMOTE, MEDIA, SLIDES)
    }
}

enum class ThemeId { DRIFT, WEB, INFERNO, REACTOR, SYNTH, TERMINAL }

/** What a pinch gesture does in Mouse mode. */
enum class PinchAction(val label: String) {
    CLICK("Click"),
    RIGHT_CLICK("Right-click"),
    DOUBLE_CLICK("Double-click"),
    HOLD("Hold button"),
    NOTHING("Nothing");

    companion object {
        /** Choices for one and two pinches. */
        val forTaps = listOf(RIGHT_CLICK, CLICK, DOUBLE_CLICK, NOTHING)

        /** Choices for pinch-and-hold: Hold keeps the button down until your fingers part, to drag. */
        val forHold = listOf(HOLD, CLICK, RIGHT_CLICK, NOTHING)

        fun next(current: PinchAction, choices: List<PinchAction>): PinchAction =
            choices[(choices.indexOf(current) + 1) % choices.size]
    }
}

/** What shaking your wrist does while steering. */
enum class ShakeAction(val label: String) {
    BACK("Go back"),
    PAUSE("Pause cursor"),
    STOP("Stop steering"),
    OFF("Off");

    val next: ShakeAction get() = entries[(ordinal + 1) % entries.size]
}

/** How often the gyroscope is read. Faster feels smoother; slower saves battery. */
enum class Power(val label: String, val detail: String, val samplingUs: Int) {
    SMOOTH("Smooth", "100 updates a second", 10_000),
    BALANCED("Balanced", "60 updates a second", 16_667),
    SAVER("Saver", "40 updates a second", 25_000)
}

/** A computer on the same Wi-Fi: a Mac with Screen Sharing on (nothing to install), or one running Drift Receiver. */
data class Computer(
    val host: String,
    val name: String,
    val kind: Kind = Kind.RECEIVER,
    val port: Int = Protocol.PORT,
) {
    /** OTHER is something on the Wi-Fi Drift can't control (a TV, speaker, printer, phone...). */
    enum class Kind { SCREEN_SHARING, RECEIVER, OTHER }
}

data class BluetoothHost(val address: String, val name: String, val kind: DeviceKind = DeviceKind.UNKNOWN)

/** Everything you can tune. Ranges match the Settings screen. */
data class Tuning(
    val speed: Int = 5,          // 1..10
    val acceleration: Int = 2,   // 0..5
    val steadiness: Int = 3,     // 1..5
    val scrollSpeed: Int = 5,    // 1..10
    val reverseScroll: Boolean = false,
    val rightWrist: Boolean = false,
    val vibration: Vibration = Vibration.MEDIUM,
    val dimScreen: Boolean = true,
    val autoStop: Boolean = true,
    val holdDrags: Boolean = false,
    val pinch: Boolean = true,
    val pinchSensitivity: Int = 2, // 1..3
    val pinchOnce: PinchAction = PinchAction.RIGHT_CLICK,
    val pinchTwice: PinchAction = PinchAction.CLICK,
    val pinchHold: PinchAction = PinchAction.HOLD,
    val shake: ShakeAction = ShakeAction.BACK,
    val shakeSensitivity: Int = 2, // 1..3
    val power: Power = Power.SMOOTH,
    val touchSpeed: Int = 5,     // 1..10
    val glide: Boolean = true,
    val hints: Boolean = true,
) {
    val haptics: Boolean get() = vibration != Vibration.OFF
}

/** Everything Drift remembers. Stored only on the watch. */
class AppPrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("drift", Context.MODE_PRIVATE)

    private val tuningState = MutableStateFlow(readTuning())
    val tuning: StateFlow<Tuning> = tuningState

    private val transportState = MutableStateFlow(
        Transport.entries.getOrElse(prefs.getInt(KEY_TRANSPORT, 0)) { Transport.BLUETOOTH }
    )
    val transport: StateFlow<Transport> = transportState

    private val themeState = MutableStateFlow(
        ThemeId.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ThemeId.DRIFT }
    )
    val theme: StateFlow<ThemeId> = themeState

    private val computerState = MutableStateFlow(readComputer())
    val computer: StateFlow<Computer?> = computerState

    private val calibrationState = MutableStateFlow(readCalibration())
    val calibration: StateFlow<Calibration?> = calibrationState

    private val bluetoothHostState = MutableStateFlow(readBluetoothHost())
    val bluetoothHost: StateFlow<BluetoothHost?> = bluetoothHostState

    private val bindingsState = MutableStateFlow(readBindings())
    val bindings: StateFlow<Bindings> = bindingsState

    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDED, value) }

    /** Calibration has been offered once (done or skipped), so first launch doesn't ask again. */
    var calibrationOffered: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATION_OFFERED, false)
        set(value) = prefs.edit { putBoolean(KEY_CALIBRATION_OFFERED, value) }

    var mode: Mode
        get() = Mode.entries.getOrElse(prefs.getInt(KEY_MODE, 0)) { Mode.MOUSE }
        set(value) = prefs.edit { putInt(KEY_MODE, value.ordinal) }

    /** Null until checked; then whether this watch can act as a Bluetooth mouse. */
    var bluetoothMouseSupported: Boolean?
        get() = if (prefs.contains(KEY_BT_SUPPORTED)) prefs.getBoolean(KEY_BT_SUPPORTED, false) else null
        set(value) = prefs.edit {
            if (value == null) remove(KEY_BT_SUPPORTED) else putBoolean(KEY_BT_SUPPORTED, value)
        }

    /** Random id so the computer can remember this watch without knowing who you are. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().take(8).also { prefs.edit { putString(KEY_DEVICE_ID, it) } }

    fun updateTuning(transform: (Tuning) -> Tuning) {
        val next = transform(tuningState.value)
        prefs.edit {
            putInt(KEY_SPEED, next.speed)
            putInt(KEY_ACCELERATION, next.acceleration)
            putInt(KEY_STEADINESS, next.steadiness)
            putInt(KEY_SCROLL, next.scrollSpeed)
            putBoolean(KEY_REVERSE_SCROLL, next.reverseScroll)
            putBoolean(KEY_RIGHT_WRIST, next.rightWrist)
            putInt(KEY_VIBRATION, next.vibration.ordinal)
            putBoolean(KEY_DIM, next.dimScreen)
            putBoolean(KEY_AUTO_STOP, next.autoStop)
            putBoolean(KEY_HOLD_DRAGS, next.holdDrags)
            putBoolean(KEY_PINCH, next.pinch)
            putInt(KEY_PINCH_SENSITIVITY, next.pinchSensitivity)
            putInt(KEY_PINCH_ONCE, next.pinchOnce.ordinal)
            putInt(KEY_PINCH_TWICE, next.pinchTwice.ordinal)
            putInt(KEY_PINCH_HOLD, next.pinchHold.ordinal)
            putInt(KEY_SHAKE, next.shake.ordinal)
            putInt(KEY_SHAKE_SENSITIVITY, next.shakeSensitivity)
            putInt(KEY_POWER, next.power.ordinal)
            putInt(KEY_TOUCH_SPEED, next.touchSpeed)
            putBoolean(KEY_GLIDE, next.glide)
            putBoolean(KEY_HINTS, next.hints)
        }
        tuningState.value = next
    }

    fun setTheme(theme: ThemeId) {
        prefs.edit { putInt(KEY_THEME, theme.ordinal) }
        themeState.value = theme
    }

    fun setTransport(transport: Transport) {
        prefs.edit { putInt(KEY_TRANSPORT, transport.ordinal) }
        transportState.value = transport
    }

    fun saveComputer(computer: Computer?) {
        prefs.edit {
            if (computer == null) {
                remove(KEY_HOST)
                remove(KEY_NAME)
            } else {
                putString(KEY_HOST, computer.host)
                putString(KEY_NAME, computer.name)
                putInt(KEY_KIND, computer.kind.ordinal)
                putInt(KEY_PORT, computer.port)
            }
        }
        computerState.value = computer
    }

    fun saveBinding(pad: Pad, slot: Slot, binding: Binding) {
        val next = bindingsState.value.with(pad, slot, binding)
        prefs.edit { putString(BINDING_PREFIX + Remap.key(pad, slot), Remap.encode(binding)) }
        bindingsState.value = next
    }

    fun resetBindings() {
        prefs.edit {
            prefs.all.keys.filter { it.startsWith(BINDING_PREFIX) }.forEach { remove(it) }
        }
        bindingsState.value = Bindings()
    }

    fun saveCalibration(calibration: Calibration) {
        prefs.edit {
            putFloat(KEY_PINCH_THRESHOLD, calibration.pinchThreshold)
            putFloat(KEY_BIAS_X, calibration.biasX)
            putFloat(KEY_BIAS_Y, calibration.biasY)
            putFloat(KEY_BIAS_Z, calibration.biasZ)
        }
        calibrationState.value = calibration
    }

    fun saveBluetoothHost(host: BluetoothHost?) {
        prefs.edit {
            if (host == null) {
                remove(KEY_BT_ADDRESS)
                remove(KEY_BT_NAME)
            } else {
                putString(KEY_BT_ADDRESS, host.address)
                putString(KEY_BT_NAME, host.name)
            }
        }
        bluetoothHostState.value = host
    }

    private fun readTuning(): Tuning {
        val defaults = Tuning()
        return Tuning(
            speed = prefs.getInt(KEY_SPEED, defaults.speed).coerceIn(1, 10),
            acceleration = prefs.getInt(KEY_ACCELERATION, defaults.acceleration).coerceIn(0, 5),
            steadiness = prefs.getInt(KEY_STEADINESS, defaults.steadiness).coerceIn(1, 5),
            scrollSpeed = prefs.getInt(KEY_SCROLL, defaults.scrollSpeed).coerceIn(1, 10),
            reverseScroll = prefs.getBoolean(KEY_REVERSE_SCROLL, defaults.reverseScroll),
            rightWrist = prefs.getBoolean(KEY_RIGHT_WRIST, defaults.rightWrist),
            vibration = readVibration(defaults.vibration),
            dimScreen = prefs.getBoolean(KEY_DIM, defaults.dimScreen),
            autoStop = prefs.getBoolean(KEY_AUTO_STOP, defaults.autoStop),
            holdDrags = prefs.getBoolean(KEY_HOLD_DRAGS, defaults.holdDrags),
            pinch = prefs.getBoolean(KEY_PINCH, defaults.pinch),
            pinchSensitivity = prefs.getInt(KEY_PINCH_SENSITIVITY, defaults.pinchSensitivity).coerceIn(1, 3),
            pinchOnce = readEnum(KEY_PINCH_ONCE, defaults.pinchOnce),
            pinchTwice = readEnum(KEY_PINCH_TWICE, defaults.pinchTwice),
            pinchHold = readEnum(KEY_PINCH_HOLD, defaults.pinchHold),
            shake = readEnum(KEY_SHAKE, defaults.shake),
            shakeSensitivity = prefs.getInt(KEY_SHAKE_SENSITIVITY, defaults.shakeSensitivity).coerceIn(1, 3),
            power = Power.entries.getOrElse(prefs.getInt(KEY_POWER, defaults.power.ordinal)) { defaults.power },
            touchSpeed = prefs.getInt(KEY_TOUCH_SPEED, defaults.touchSpeed).coerceIn(1, 10),
            glide = prefs.getBoolean(KEY_GLIDE, defaults.glide),
            hints = prefs.getBoolean(KEY_HINTS, defaults.hints),
        )
    }

    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E =
        enumValues<E>().getOrElse(prefs.getInt(key, default.ordinal)) { default }

    /** Older versions only had an on/off haptics switch. */
    private fun readVibration(default: Vibration): Vibration = when {
        prefs.contains(KEY_VIBRATION) -> Vibration.entries.getOrElse(prefs.getInt(KEY_VIBRATION, default.ordinal)) { default }
        !prefs.getBoolean(KEY_HAPTICS, true) -> Vibration.OFF
        else -> default
    }

    private fun readBindings(): Bindings {
        val custom = prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(BINDING_PREFIX)) return@mapNotNull null
            val binding = (value as? String)?.let { Remap.decode(it) } ?: return@mapNotNull null
            key.removePrefix(BINDING_PREFIX) to binding
        }.toMap()
        return Bindings(custom)
    }

    private fun readComputer(): Computer? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        return Computer(
            host = host,
            name = prefs.getString(KEY_NAME, null) ?: host,
            kind = Computer.Kind.entries.getOrElse(prefs.getInt(KEY_KIND, Computer.Kind.RECEIVER.ordinal)) { Computer.Kind.RECEIVER },
            port = prefs.getInt(KEY_PORT, Protocol.PORT),
        )
    }

    private fun readCalibration(): Calibration? {
        if (!prefs.contains(KEY_PINCH_THRESHOLD)) return null
        return Calibration(
            pinchThreshold = prefs.getFloat(KEY_PINCH_THRESHOLD, PinchDetector.DEFAULT_THRESHOLD),
            biasX = prefs.getFloat(KEY_BIAS_X, 0f),
            biasY = prefs.getFloat(KEY_BIAS_Y, 0f),
            biasZ = prefs.getFloat(KEY_BIAS_Z, 0f),
        )
    }

    private fun readBluetoothHost(): BluetoothHost? {
        val address = prefs.getString(KEY_BT_ADDRESS, null) ?: return null
        return BluetoothHost(address, prefs.getString(KEY_BT_NAME, null) ?: "Computer")
    }

    companion object {
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_MODE = "mode"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_HOST = "host"
        private const val KEY_NAME = "name"
        private const val KEY_KIND = "kind"
        private const val KEY_PORT = "port"
        private const val KEY_BT_ADDRESS = "bt_address"
        private const val KEY_BT_NAME = "bt_name"
        private const val KEY_BT_SUPPORTED = "bt_supported"
        private const val KEY_SPEED = "speed"
        private const val KEY_ACCELERATION = "acceleration"
        private const val KEY_STEADINESS = "steadiness"
        private const val KEY_SCROLL = "scroll_speed"
        private const val KEY_REVERSE_SCROLL = "reverse_scroll"
        private const val KEY_RIGHT_WRIST = "right_wrist"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_VIBRATION = "vibration"
        private const val KEY_TOUCH_SPEED = "touch_speed"
        private const val KEY_GLIDE = "glide"
        private const val KEY_HINTS = "hints"
        private const val BINDING_PREFIX = "bind."
        private const val KEY_DIM = "dim_screen"
        private const val KEY_AUTO_STOP = "auto_stop"
        private const val KEY_POWER = "power"
        private const val KEY_HOLD_DRAGS = "hold_drags"
        private const val KEY_THEME = "theme"
        private const val KEY_PINCH = "pinch"
        private const val KEY_PINCH_THRESHOLD = "pinch_threshold"
        private const val KEY_PINCH_ONCE = "pinch_once"
        private const val KEY_PINCH_TWICE = "pinch_twice"
        private const val KEY_PINCH_HOLD = "pinch_hold"
        private const val KEY_SHAKE = "shake"
        private const val KEY_SHAKE_SENSITIVITY = "shake_sensitivity"
        private const val KEY_CALIBRATION_OFFERED = "calibration_offered"
        private const val KEY_BIAS_X = "gyro_bias_x"
        private const val KEY_BIAS_Y = "gyro_bias_y"
        private const val KEY_BIAS_Z = "gyro_bias_z"
        private const val KEY_PINCH_SENSITIVITY = "pinch_sensitivity"

        @Volatile
        private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context.applicationContext).also { instance = it }
            }
    }
}
