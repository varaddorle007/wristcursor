package com.drift.airmouse.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.drift.airmouse.MainActivity
import com.drift.airmouse.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.sqrt

sealed interface LinkState {
    /** Not running. [note] explains why it stopped, if it stopped on its own. */
    data class Idle(val note: String? = null) : LinkState
    data object Connecting : LinkState
    data object WaitingForApproval : LinkState
    data class WaitingForHost(val watchName: String) : LinkState
    data object Steering : LinkState
}

sealed interface Action {
    data object Click : Action
    data object DoubleClick : Action
    data object RightClick : Action
    data class Scroll(val crownPixels: Float) : Action
    data object NextSlide : Action
    data object PreviousSlide : Action
    data object DragStart : Action
    data object DragEnd : Action

    /** Press and let go of a remote, media or keyboard key. */
    data class Press(val key: RemoteKey) : Action
    data class Volume(val crownPixels: Float) : Action

    /** A finger dragging on the touchpad, in pointer pixels. */
    data class Move(val dx: Float, val dy: Float) : Action

    /** Text from the watch keyboard or voice, typed where the computer's cursor is. */
    data class Type(val text: String) : Action

    /** Cursor to the middle of the screen (Wi-Fi only). */
    data object Center : Action

    /** Continuous key down (press and hold) for games. */
    data class KeyDown(val key: RemoteKey) : Action

    /** Continuous key up (release) for games. */
    data class KeyUp(val key: RemoteKey) : Action

    /** Pedal states for car controller mode: throttle, brake, nitro. */
    data class WheelPedals(val throttle: Boolean, val brake: Boolean, val nitro: Boolean) : Action

    /** Recalibrates zero steering angle to current wrist position. */
    data object WheelCenter : Action

    /** Turning physical rotating bezel to steer. */
    data class WheelBezelDelta(val deltaAngle: Float) : Action
}

/** Shared state between the screens and the service. */
object PointerBus {
    val state = MutableStateFlow<LinkState>(LinkState.Idle())
    val mode = MutableStateFlow(Mode.MOUSE)
    val connectedTo = MutableStateFlow<String?>(null)

    /** Live car steering wheel metrics. */
    val wheelAngle = MutableStateFlow(0f)
    val wheelPitch = MutableStateFlow(0f)
    val wheelSteer = MutableStateFlow(0f)
    val wheelThrottle = MutableStateFlow(false)
    val wheelBrake = MutableStateFlow(false)
    val wheelNitro = MutableStateFlow(false)
    val wheelGyroGas = MutableStateFlow(false)
    val wheelGyroBrake = MutableStateFlow(false)

    /** The last key the current connection couldn't send (Wi-Fi can't press media keys), if any. */
    val unsupported = MutableStateFlow<RemoteKey?>(null)

    /** Tap the mouse to pause: the cursor holds still and pinches do nothing, but the connection stays up. */
    val paused = MutableStateFlow(false)

    /** Goes up by one on every click (and pinch-hold), so the screen can flash. */
    val clickPulse = MutableStateFlow(0)

    /** True while a pinch-and-hold keeps the mouse button down. */
    val holding = MutableStateFlow(false)

    /** Active wrist tilt scroll in mouse mode: -1 = up, 0 = neutral, 1 = down. */
    val mouseScrollState = MutableStateFlow(0)

    /** A wrist shake, with what it should do; the steering screen acts on it. */
    val shakes = MutableSharedFlow<ShakeAction>(extraBufferCapacity = 1)

    /** True while a finger is on the pad, so tapping doesn't nudge the cursor. */
    @Volatile
    var frozen = false

    @Volatile
    var freezeUntilMs = 0L

    fun freezeFor(ms: Long = 320L) {
        freezeUntilMs = SystemClock.uptimeMillis() + ms
    }

    val isFrozen: Boolean
        get() = frozen || SystemClock.uptimeMillis() < freezeUntilMs

    /**
     * Tapping the screen jolts the watch like a pinch does. Pinches are ignored until this
     * time (uptime ms); screens push it forward while a finger is down and just after.
     */
    @Volatile
    var touchQuietUntil = 0L

    fun touching(down: Boolean) {
        touchQuietUntil = if (down) Long.MAX_VALUE else SystemClock.uptimeMillis() + TOUCH_QUIET_MS
    }

    private const val TOUCH_QUIET_MS = 350L

    @Volatile
    internal var service: PointerService? = null

    fun perform(action: Action) {
        if (state.value == LinkState.Steering) service?.perform(action)
    }
}

/**
 * Foreground service that turns wrist motion into pointer moves.
 * Everything time-critical runs on one high-priority thread, driven by the gyroscope:
 * each sample is filtered and sent the moment it arrives, and nothing runs when you're still.
 */
class PointerService : Service(), SensorEventListener {

    private lateinit var prefs: AppPrefs
    private lateinit var sensors: SensorManager
    private val motionThread = HandlerThread("drift-motion", Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var motion: Handler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    // Owned by the motion thread.
    private var link: PointerLink? = null
    private var tuning = Tuning()
    private var filter = MotionFilter(tuning)
    private var gyroOn = false
    private var accelOn = false
    private var wheelSensor: Sensor? = null
    private var wheelSensorOn = false
    private var wheelCenterRoll = 0f
    private var wheelCenterPitch = 0f
    private var wheelCalibrated = false
    private val calRotMatrix = FloatArray(9)
    private var bezelSteerAngle = 0f
    private val rotMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val wheelFilter = OneEuroFilter(minCutoff = 12.0f, beta = 1.0f, derivativeCutoff = 20.0f)
    private var lastWheelNanos = 0L
    private var pinch = PinchDetector(PinchDetector.DEFAULT_THRESHOLD)
    private var shake = ShakeDetector(2)
    private var knock = KnockDetector()
    private var pull = PullDetector()
    private var mouseGravX = 0f
    private var mouseGravY = 0f
    private var mouseGravZ = 9.8f
    private var lastMouseScrollMs = 0L
    private var lastWheelSentMs = 0L
    private var lastSentSteer = 0f
    private var lastSentThrottle = false
    private var lastSentBrake = false
    private var lastSentNitro = false
    private var calibration: Calibration? = null
    private var lastPeakLogMs = 0L
    private var connected = false
    private var pendingX = 0f
    private var pendingY = 0f
    private var scrollRemainder = 0f
    private var volumeRemainder = 0f
    private var lastActivityMs = 0L

    private var wifi: WifiLink? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var handshakeSocket: DatagramSocket? = null
    private var batteryReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs.get(this)
        sensors = getSystemService(SensorManager::class.java)
        motionThread.start()
        motion = Handler(motionThread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Steering", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true

        // Always enter the foreground first; Android requires it after startForegroundService.
        startForeground(NOTIFICATION_ID, buildNotification())

        val gate = BatteryGate.read(this)
        val transport = prefs.transport.value
        val computer = prefs.computer.value
        val blocked = gate.reason
            ?: if (transport == Transport.WIFI && computer == null) "Pair a computer first" else null
        if (blocked != null) {
            stopWithNote(blocked)
            return START_NOT_STICKY
        }

        PointerBus.service = this
        PointerBus.state.value = LinkState.Connecting
        PointerBus.mode.value = prefs.mode
        registerBatteryWatch()
        val initialTuning = prefs.tuning.value
        motion.post { applyTuning(initialTuning) }
        scope.launch { prefs.tuning.collect { next -> motion.post { applyTuning(next) } } }
        scope.launch { PointerBus.mode.collect { motion.post { refreshSensing() } } }
        scope.launch { prefs.calibration.collect { motion.post { applyTuning(tuning) } } }

        when (transport) {
            Transport.BLUETOOTH -> motion.post { startBluetooth() }
            Transport.WIFI -> scope.launch { startWifi(computer!!) }
        }
        return START_NOT_STICKY
    }

    // region Connections

    private fun startBluetooth() {
        val hid = HidLink(this, motion, prefs, object : HidLink.Events {
            override fun onWaitingForHost(watchName: String) {
                connected = false
                refreshSensing()
                PointerBus.connectedTo.value = null
                PointerBus.state.value = LinkState.WaitingForHost(watchName)
            }

            override fun onConnected(hostName: String) {
                PointerBus.connectedTo.value = hostName
                connected = true
                refreshSensing()
                PointerBus.state.value = LinkState.Steering
            }

            override fun onUnavailable(reason: String) = stopWithNote(reason)
        })
        link = hid
        hid.start()
    }

    private suspend fun startWifi(computer: Computer) {
        val wifiLink = WifiLink(this) { stopWithNote("Wi-Fi dropped") }
        wifi = wifiLink
        val network = wifiLink.acquire() ?: return stopWithNote("Couldn't connect to Wi-Fi")
        if (computer.kind == Computer.Kind.SCREEN_SHARING) return startScreenSharing(network, computer)
        var target = computer
        var address = runCatching { InetAddress.getByName(computer.host) }.getOrNull()
            ?: return stopWithNote("Computer address looks wrong")

        val socket = DatagramSocket()
        handshakeSocket = socket
        network.bindSocket(socket)
        socket.soTimeout = 500
        var problem = handshake(socket, address)
        if (problem == NO_ANSWER) {
            // Routers hand out new addresses now and then: look for the computer again and retry.
            PointerBus.state.value = LinkState.Connecting
            val found = Discovery.search(socket, wifiLink.broadcastAddresses(), wanted = computer.name)
            val foundAddress = found?.let { runCatching { InetAddress.getByName(it.host) }.getOrNull() }
            if (found != null && foundAddress != null) {
                prefs.saveComputer(found)
                target = found
                address = foundAddress
                problem = handshake(socket, address)
            }
        }
        problem?.let {
            socket.close()
            return stopWithNote(it)
        }
        socket.soTimeout = 0
        handshakeSocket = null
        holdLowLatencyWifi()

        val finalAddress = address
        val handedOver = motion.post {
            link = UdpLink(socket, finalAddress).also { it.center() } // start in the middle, where you're pointing
            PointerBus.connectedTo.value = target.name
            connected = true
            refreshSensing()
            PointerBus.state.value = LinkState.Steering
        }
        if (!handedOver) socket.close()
    }

    /** Mac with Screen Sharing on: sign in with the saved Mac login and drive it directly. */
    private suspend fun startScreenSharing(network: Network, computer: Computer) {
        val login = Credentials.load(this, computer.name)
            ?: return stopWithNote("Sign in to ${computer.name} in Connection first")
        var target = computer
        val vnc = try {
            VncLink.open(network, computer.host, computer.port, login.user, login.password)
        } catch (first: VncException) {
            // Its Wi-Fi address may have changed: look for it by name and try once more.
            val moved = if (first.unreachable) LanScanner.refind(this, computer.name)?.takeIf { it.host != computer.host } else null
            if (moved == null) return stopWithNote(first.message)
            prefs.saveComputer(moved)
            target = moved
            try {
                VncLink.open(network, moved.host, moved.port, login.user, login.password)
            } catch (second: VncException) {
                return stopWithNote(second.message)
            }
        }
        holdLowLatencyWifi()
        val name = target.name
        val handedOver = motion.post {
            link = vnc.also { it.center() } // start in the middle, where you're pointing
            PointerBus.connectedTo.value = name
            connected = true
            refreshSensing()
            PointerBus.state.value = LinkState.Steering
        }
        if (!handedOver) vnc.close()
    }

    /**
     * Says hello until the computer answers. Returns null once allowed, or a note saying why not.
     * While it answers WAIT, the user still has to click Allow on the computer.
     */
    private suspend fun handshake(socket: DatagramSocket, address: InetAddress): String? {
        val hello = "${Protocol.HELLO} ${prefs.deviceId} ${Build.MODEL}".toByteArray(Charsets.UTF_8)
        val buffer = ByteArray(64)
        val startedAt = SystemClock.elapsedRealtime()
        var heardBack = false

        while (true) {
            currentCoroutineContext().ensureActive()
            runCatching { socket.send(DatagramPacket(hello, hello.size, address, Protocol.PORT)) }
            val reply = try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
            } catch (_: IOException) {
                null
            }
            when (reply) {
                Protocol.OK -> return null
                Protocol.NO -> return "Your computer said no"
                Protocol.WAIT -> {
                    heardBack = true
                    PointerBus.state.value = LinkState.WaitingForApproval
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (!heardBack && elapsed > NO_ANSWER_TIMEOUT_MS) return NO_ANSWER
            if (elapsed > APPROVAL_TIMEOUT_MS) return "Nobody clicked Allow on the computer"
        }
    }

    /** Stops Wi-Fi power-save from batching packets, which feels like stutter. Released on stop. */
    private fun holdLowLatencyWifi() {
        val manager = applicationContext.getSystemService(WifiManager::class.java) ?: return
        wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "drift:steer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // endregion

    // region Motion (motion thread)

    /**
     * The gyroscope runs only while connected in Mouse mode; the accelerometer runs while
     * connected with pinch on in the modes where pinching does something, or with shake on.
     * Nothing runs otherwise.
     */
    private fun refreshSensing() {
        val mode = PointerBus.mode.value
        setGyro(connected && mode == Mode.MOUSE)
        setWheelSensing(connected && mode == Mode.GAME)
        val needAccel = pinchActive() ||
                        tuning.shake != ShakeAction.OFF ||
                        (mode == Mode.MOUSE && tuning.wristScroll) ||
                        tuning.doubleKnock != KnockAction.OFF ||
                        tuning.pullPull != PullAction.OFF
        setAccelerometer(connected && needAccel)
    }

    private fun pinchActive() = tuning.pinch && PointerBus.mode.value in PINCH_MODES

    private fun setGyro(on: Boolean) {
        if (on == gyroOn) return
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: return stopWithNote("This watch has no gyroscope")
        if (on) {
            filter.reset()
            pendingX = 0f
            pendingY = 0f
            lastActivityMs = SystemClock.uptimeMillis()
            sensors.registerListener(this, gyro, tuning.power.samplingUs, 0, motion)
        } else {
            sensors.unregisterListener(this, gyro)
        }
        gyroOn = on
    }

    private fun setWheelSensing(on: Boolean) {
        if (on == wheelSensorOn) return
        if (on) {
            val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                ?: return stopWithNote("This watch has no rotation sensor")
            wheelSensor = sensor
            wheelCalibrated = false
            bezelSteerAngle = 0f
            wheelFilter.reset()
            lastWheelNanos = 0L
            PointerBus.wheelAngle.value = 0f
            PointerBus.wheelPitch.value = 0f
            PointerBus.wheelSteer.value = 0f
            PointerBus.wheelThrottle.value = false
            PointerBus.wheelBrake.value = false
            PointerBus.wheelNitro.value = false
            PointerBus.wheelGyroGas.value = false
            PointerBus.wheelGyroBrake.value = false
            lastActivityMs = SystemClock.uptimeMillis()
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, 0, motion)
        } else {
            wheelSensor?.let { sensors.unregisterListener(this, it) }
            wheelSensor = null
            wheelCalibrated = false
            bezelSteerAngle = 0f
            wheelFilter.reset()
            lastWheelNanos = 0L
            PointerBus.wheelAngle.value = 0f
            PointerBus.wheelPitch.value = 0f
            PointerBus.wheelSteer.value = 0f
            PointerBus.wheelGyroGas.value = false
            PointerBus.wheelGyroBrake.value = false
            link?.wheel(0f, throttle = false, brake = false, nitro = false)
        }
        wheelSensorOn = on
    }

    private fun setAccelerometer(on: Boolean) {
        if (on == accelOn) return
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (on) {
            pinch.reset()
            shake.reset()
            knock.reset()
            pull.reset()
            mouseGravX = 0f
            mouseGravY = 0f
            mouseGravZ = 9.8f
            // Pinches are short jolts: always sample fast enough to catch them.
            sensors.registerListener(this, accel, PINCH_SAMPLING_US, 0, motion)
        } else {
            sensors.unregisterListener(this, accel)
        }
        accelOn = on
    }

    private fun stopSensing() {
        setGyro(false)
        setWheelSensing(false)
        setAccelerometer(false)
    }

    private fun applyTuning(next: Tuning) {
        val rateChanged = next.power != tuning.power
        tuning = next
        filter = MotionFilter(next)
        pendingX = 0f
        pendingY = 0f
        calibration = prefs.calibration.value
        // Never leave the button down while the detector is swapped out.
        if (pinch.holding) releaseHold()
        pinch = PinchDetector(
            PinchDetector.thresholdFor(calibration?.pinchThreshold, next.pinchSensitivity),
            holdEnabled = next.pinchHold != PinchAction.NOTHING,
        )
        shake = ShakeDetector(next.shakeSensitivity)
        knock.reset()
        pull.reset()
        if (gyroOn && rateChanged) setGyro(false)
        refreshSensing()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAcceleration(event)
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> onWheelTurn(event)
            else -> if (PointerBus.mode.value == Mode.GAME) onWheelGyroFallback(event) else onTurn(event)
        }
    }

    private fun onWheelTurn(event: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

        if (!wheelCalibrated) {
            System.arraycopy(rotMatrix, 0, calRotMatrix, 0, 9)
            SensorManager.getOrientation(rotMatrix, orientationAngles)
            wheelCenterPitch = orientationAngles[1]
            wheelCenterRoll = orientationAngles[2]
            wheelFilter.reset()
            lastWheelNanos = event.timestamp
            wheelCalibrated = true
        }

        // Calibrated 3D vectors in world coordinate space:
        // Normal to watch face (pointing straight out of the glass):
        val nCalX = calRotMatrix[2]
        val nCalY = calRotMatrix[5]
        val nCalZ = calRotMatrix[8]

        // 12 o'clock vector at calibration (pointing along forearm toward hand):
        val uCalX = calRotMatrix[1]
        val uCalY = calRotMatrix[4]
        val uCalZ = calRotMatrix[7]

        // Current 12 o'clock vector:
        val uNowX = rotMatrix[1]
        val uNowY = rotMatrix[4]
        val uNowZ = rotMatrix[7]

        // Cross product (uCal x uNow):
        val crossX = uCalY * uNowZ - uCalZ * uNowY
        val crossY = uCalZ * uNowX - uCalX * uNowZ
        val crossZ = uCalX * uNowY - uCalY * uNowX

        // Dot with calibrated normal (sin of steering rotation on the driver's steering plane):
        val sinAngle = crossX * nCalX + crossY * nCalY + crossZ * nCalZ
        // Dot product (uCal . uNow) (cos of steering rotation):
        val cosAngle = uCalX * uNowX + uCalY * uNowY + uCalZ * uNowZ

        // Invert sinAngle so that clockwise rotation (turning right) yields positive angle (+deg)
        var rawAngleDeg = Math.toDegrees(kotlin.math.atan2(-sinAngle.toDouble(), cosAngle.toDouble())).toFloat()

        if (tuning.rightWrist) {
            rawAngleDeg = -rawAngleDeg
        }

        // 1€ Filter: zero lag during fast cornering, ultra steady on straights
        val nowNanos = event.timestamp
        val dt = if (lastWheelNanos == 0L) 0.01f else ((nowNanos - lastWheelNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastWheelNanos = nowNanos
        val smoothWristDelta = wheelFilter.filter(rawAngleDeg, dt)

        // Include physical rotating bezel steering contribution
        val totalDelta = smoothWristDelta + bezelSteerAngle

        // Sensitivity: 1 = wide (80° lock), 5 = standard (55° lock), 10 = snap (30° lock)
        val maxAngle = 30f + (10 - tuning.wheelSensitivity) * 5f
        val deadzone = 1.5f
        val absDelta = kotlin.math.abs(totalDelta)
        val steer = if (absDelta <= deadzone) {
            0f
        } else {
            val u = ((absDelta - deadzone) / (maxAngle - deadzone)).coerceIn(0f, 1f)
            // 80% linear + 20% progressive curve for high precision
            val curved = 0.80f * u + 0.20f * u * u
            val sign = if (totalDelta > 0f) 1f else -1f
            curved * sign
        }

        // Pitch tilt driving (ONLY active if explicitly enabled in Settings):
        SensorManager.getOrientation(rotMatrix, orientationAngles)
        val rawPitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        var deltaPitch = rawPitchDeg - Math.toDegrees(wheelCenterPitch.toDouble()).toFloat()
        while (deltaPitch > 180f) deltaPitch -= 360f
        while (deltaPitch < -180f) deltaPitch += 360f

        val gyroGas = tuning.wheelTiltPedals && deltaPitch < -24f
        val gyroBrake = tuning.wheelTiltPedals && deltaPitch > 24f

        PointerBus.wheelAngle.value = totalDelta.coerceIn(-maxAngle, maxAngle)
        PointerBus.wheelPitch.value = deltaPitch
        PointerBus.wheelSteer.value = steer
        PointerBus.wheelGyroGas.value = gyroGas
        PointerBus.wheelGyroBrake.value = gyroBrake

        val activeThrottle = gyroGas || PointerBus.wheelThrottle.value
        val activeBrake = gyroBrake || PointerBus.wheelBrake.value
        val activeNitro = PointerBus.wheelNitro.value

        val pedalChanged = activeThrottle != lastSentThrottle || activeBrake != lastSentBrake || activeNitro != lastSentNitro
        val steerChanged = kotlin.math.abs(steer - lastSentSteer) >= 0.005f
        val timeElapsed = now - lastWheelSentMs >= 20L

        if (pedalChanged || (steerChanged && now - lastWheelSentMs >= 10L) || timeElapsed) {
            link?.wheel(
                steer = steer,
                throttle = activeThrottle,
                brake = activeBrake,
                nitro = activeNitro
            )
            lastWheelSentMs = now
            lastSentSteer = steer
            lastSentThrottle = activeThrottle
            lastSentBrake = activeBrake
            lastSentNitro = activeNitro
        }
        lastActivityMs = now
    }

    private fun onWheelGyroFallback(event: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        val rz = -event.values[2]
        val delta = (rz * 50f).coerceIn(-60f, 60f)
        val steer = (delta / 60f).coerceIn(-1f, 1f)
        PointerBus.wheelAngle.value = delta
        PointerBus.wheelSteer.value = steer
        link?.wheel(
            steer = steer,
            throttle = PointerBus.wheelThrottle.value,
            brake = PointerBus.wheelBrake.value,
            nitro = PointerBus.wheelNitro.value
        )
        lastActivityMs = now
    }

    private fun onTurn(event: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        // Remove the drift measured at calibration so a still wrist means a still cursor.
        val bias = calibration
        val rx = event.values[0] - (bias?.biasX ?: 0f)
        val ry = event.values[1] - (bias?.biasY ?: 0f)
        val rz = event.values[2] - (bias?.biasZ ?: 0f)
        pinch.onTurnRate(sqrt(rx * rx + ry * ry + rz * rz))
        // Hold the cursor still while a finger is on the pad, right after a click, while a pinch is read, or wrist is tilt-scrolling.
        if (PointerBus.isFrozen || pinch.waiting || PointerBus.paused.value || PointerBus.mouseScrollState.value != 0) {
            filter.reset()
            pendingX = 0f
            pendingY = 0f
            lastActivityMs = now
            return
        }
        filter.step(yawRate = rz, pitchRate = ry, timestampNanos = event.timestamp)
        pendingX += filter.dx
        pendingY += filter.dy
        val x = pendingX.toInt()
        val y = pendingY.toInt()
        if (x == 0 && y == 0) {
            checkIdle(now)
            return
        }
        pendingX -= x
        pendingY -= y
        link?.move(x, y)
        lastActivityMs = now
    }

    private fun onAcceleration(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val now = SystemClock.uptimeMillis()
        if (shake.onAcceleration(x, y, z, event.timestamp) && tuning.shake != ShakeAction.OFF) {
            Log.d(TAG, "shake: ${tuning.shake.name.lowercase()}")
            pinch.cancel()?.let { releaseHold() }
            lastActivityMs = now
            Haptics.buzz(this, tuning.vibration, Haptics.Kind.HEAVY)
            PointerBus.shakes.tryEmit(tuning.shake)
            if (tuning.shake == ShakeAction.BACK && PointerBus.mode.value == Mode.MOUSE) {
                perform(Action.Press(RemoteKey.BACK))
            }
            return
        }

        // Double Knock gesture
        if (tuning.doubleKnock != KnockAction.OFF && knock.onAcceleration(x, y, z, event.timestamp)) {
            Log.d(TAG, "double knock: ${tuning.doubleKnock.name.lowercase()}")
            lastActivityMs = now
            Haptics.buzz(this, tuning.vibration, Haptics.Kind.CLICK)
            when (tuning.doubleKnock) {
                KnockAction.CLICK -> perform(Action.Click)
                KnockAction.RIGHT_CLICK -> perform(Action.RightClick)
                KnockAction.DOUBLE_CLICK -> perform(Action.DoubleClick)
                KnockAction.PAUSE -> PointerBus.paused.value = !PointerBus.paused.value
                KnockAction.BACK -> perform(Action.Press(RemoteKey.BACK))
                KnockAction.PLAY_PAUSE -> perform(Action.Press(RemoteKey.PLAY_PAUSE))
                KnockAction.ENTER -> perform(Action.Press(RemoteKey.OK))
                KnockAction.OFF -> Unit
            }
            return
        }

        // Pull-pull gesture
        if (tuning.pullPull != PullAction.OFF && pull.onAcceleration(x, y, z, event.timestamp)) {
            Log.d(TAG, "pull-pull: ${tuning.pullPull.name.lowercase()}")
            lastActivityMs = now
            Haptics.buzz(this, tuning.vibration, Haptics.Kind.CLICK)
            when (tuning.pullPull) {
                PullAction.BACK -> perform(Action.Press(RemoteKey.BACK))
                PullAction.SCROLL_TOP -> perform(Action.Scroll(if (tuning.reverseScroll) 400f else -400f))
                PullAction.PAUSE -> PointerBus.paused.value = !PointerBus.paused.value
                PullAction.CLICK -> perform(Action.Click)
                PullAction.STOP -> stopWithNote("Stopped by pull-pull gesture")
                PullAction.OFF -> Unit
            }
            return
        }

        // Wrist Tilt Scroll in Mouse Mode
        if (PointerBus.mode.value == Mode.MOUSE && tuning.wristScroll && !PointerBus.paused.value) {
            mouseGravX += (x - mouseGravX) * 0.12f
            mouseGravY += (y - mouseGravY) * 0.12f
            mouseGravZ += (z - mouseGravZ) * 0.12f
            val pitchDeg = Math.toDegrees(kotlin.math.atan2(-mouseGravY.toDouble(), kotlin.math.sqrt((mouseGravX * mouseGravX + mouseGravZ * mouseGravZ).toDouble()))).toFloat()

            if (pitchDeg > 15f) {
                PointerBus.mouseScrollState.value = -1
                val excess = (pitchDeg - 15f).coerceIn(0f, 30f)
                val intervalMs = (210 - (tuning.scrollSpeed * 14) - excess * 2.5f).toLong().coerceIn(35L, 220L)
                if (now - lastMouseScrollMs >= intervalMs) {
                    val step = if (tuning.reverseScroll) 1 else -1
                    link?.scroll(step)
                    Haptics.buzz(this, tuning.vibration, Haptics.Kind.TICK)
                    lastMouseScrollMs = now
                }
            } else if (pitchDeg < -15f) {
                PointerBus.mouseScrollState.value = 1
                val excess = (kotlin.math.abs(pitchDeg) - 15f).coerceIn(0f, 30f)
                val intervalMs = (210 - (tuning.scrollSpeed * 14) - excess * 2.5f).toLong().coerceIn(35L, 220L)
                if (now - lastMouseScrollMs >= intervalMs) {
                    val step = if (tuning.reverseScroll) -1 else 1
                    link?.scroll(step)
                    Haptics.buzz(this, tuning.vibration, Haptics.Kind.TICK)
                    lastMouseScrollMs = now
                }
            } else {
                PointerBus.mouseScrollState.value = 0
            }
        } else if (PointerBus.mouseScrollState.value != 0) {
            PointerBus.mouseScrollState.value = 0
        }
        // The watch's own buzz jolts the wrist like a pinch: skip it, or one pinch clicks again and again.
        pinch.muteUntil(Haptics.shakingUntilNanos)
        var gesture = pinch.onAcceleration(x, y, z, event.timestamp)
        // A big swing (maybe the start of a shake) is never a pinch.
        if (shake.agitated && gesture != PinchDetector.Event.HOLD_END) gesture = pinch.cancel()
        if (now - lastPeakLogMs > PEAK_LOG_MS) {
            lastPeakLogMs = now
            val peak = pinch.readPeak()
            if (peak > PEAK_LOG_FLOOR) Log.d(TAG, "pinch peak ${peak.toInt()} (threshold ${pinch.thresholdValue.toInt()})")
        }
        if (gesture != null) onPinch(gesture, now)
    }

    private fun onPinch(gesture: PinchDetector.Event, now: Long) {
        if (gesture == PinchDetector.Event.HOLD_END) {
            Log.d(TAG, "pinch released")
            releaseHold()
            return
        }
        if (link == null || !pinchActive() || PointerBus.paused.value) return
        // A finger on the screen (or one that just lifted) shook the watch: that wasn't a pinch.
        if (now < PointerBus.touchQuietUntil) {
            Log.d(TAG, "pinch ignored: screen touch")
            return
        }
        val action = pinchAction(gesture) ?: return
        Log.d(TAG, "pinch ${gesture.name.lowercase()} -> $action")
        lastActivityMs = now
        Haptics.buzz(this, tuning.vibration, if (action == Action.DragStart) Haptics.Kind.CLICK else Haptics.Kind.TICK)
        if (action == Action.DragStart) PointerBus.holding.value = true
        perform(action)
    }

    /** Mouse uses the gestures picked in Settings; Slides and Media have fixed, simple ones. */
    private fun pinchAction(gesture: PinchDetector.Event): Action? = when (PointerBus.mode.value) {
        Mode.MOUSE -> when (gesture) {
            PinchDetector.Event.SINGLE -> tuning.pinchOnce.toAction()
            PinchDetector.Event.DOUBLE -> tuning.pinchTwice.toAction()
            PinchDetector.Event.HOLD_START -> tuning.pinchHold.toAction()
            PinchDetector.Event.HOLD_END -> null
        }
        Mode.SLIDES -> when (gesture) {
            PinchDetector.Event.SINGLE -> Action.NextSlide
            PinchDetector.Event.DOUBLE -> Action.PreviousSlide
            else -> null
        }
        Mode.MEDIA -> when (gesture) {
            PinchDetector.Event.SINGLE -> Action.Press(RemoteKey.PLAY_PAUSE)
            PinchDetector.Event.DOUBLE -> Action.Press(RemoteKey.NEXT)
            else -> null
        }
        Mode.TOUCHPAD, Mode.REMOTE, Mode.KEYBOARD, Mode.GAME -> null
    }

    private fun PinchAction.toAction(): Action? = when (this) {
        PinchAction.CLICK -> Action.Click
        PinchAction.RIGHT_CLICK -> Action.RightClick
        PinchAction.DOUBLE_CLICK -> Action.DoubleClick
        PinchAction.HOLD -> Action.DragStart
        PinchAction.NOTHING -> null
    }

    /** Fingers parted after a pinch-and-hold: let go of the button. */
    private fun releaseHold() {
        if (!PointerBus.holding.value) return
        PointerBus.holding.value = false
        link?.release(MouseButton.LEFT)
        Haptics.buzz(this, tuning.vibration, Haptics.Kind.TICK)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun checkIdle(now: Long) {
        if (tuning.autoStop && now - lastActivityMs > IDLE_LIMIT_MS) {
            lastActivityMs = now
            stopSensing()
            stopWithNote("Stopped after 5 quiet minutes")
        }
    }

    fun perform(action: Action) {
        motion.post {
            val target = link ?: return@post
            lastActivityMs = SystemClock.uptimeMillis()
            when (action) {
                Action.Click, Action.DoubleClick, Action.RightClick, Action.DragStart -> {
                    PointerBus.clickPulse.value++
                    PointerBus.freezeFor(350L)
                }
                else -> Unit
            }
            when (action) {
                is Action.WheelPedals -> {
                    PointerBus.wheelThrottle.value = action.throttle
                    PointerBus.wheelBrake.value = action.brake
                    PointerBus.wheelNitro.value = action.nitro
                    target.wheel(
                        steer = PointerBus.wheelSteer.value,
                        throttle = action.throttle,
                        brake = action.brake,
                        nitro = action.nitro
                    )
                }
                Action.WheelCenter -> {
                    wheelCalibrated = false
                    bezelSteerAngle = 0f
                    wheelFilter.reset()
                    lastWheelNanos = 0L
                }
                is Action.WheelBezelDelta -> {
                    val maxAngle = 30f + (10 - tuning.wheelSensitivity) * 5f
                    bezelSteerAngle = (bezelSteerAngle + action.deltaAngle).coerceIn(-maxAngle, maxAngle)
                    val absDelta = kotlin.math.abs(bezelSteerAngle)
                    val steer = if (absDelta <= 1.5f) {
                        0f
                    } else {
                        val u = ((absDelta - 1.5f) / (maxAngle - 1.5f)).coerceIn(0f, 1f)
                        val curved = 0.80f * u + 0.20f * u * u
                        val sign = if (bezelSteerAngle > 0f) 1f else -1f
                        curved * sign
                    }
                    PointerBus.wheelAngle.value = bezelSteerAngle
                    PointerBus.wheelSteer.value = steer
                    target.wheel(
                        steer = steer,
                        throttle = PointerBus.wheelThrottle.value,
                        brake = PointerBus.wheelBrake.value,
                        nitro = PointerBus.wheelNitro.value
                    )
                }
                is Action.KeyDown -> {
                    if (!target.keyDown(action.key)) PointerBus.unsupported.value = action.key
                }
                is Action.KeyUp -> {
                    target.keyUp(action.key)
                }
                Action.Click -> target.click(MouseButton.LEFT)
                Action.DoubleClick -> target.doubleClick()
                Action.RightClick -> target.click(MouseButton.RIGHT)
                is Action.Scroll -> scroll(target, action.crownPixels)
                Action.NextSlide -> press(target, RemoteKey.RIGHT)
                Action.PreviousSlide -> press(target, RemoteKey.LEFT)
                Action.DragStart -> target.press(MouseButton.LEFT)
                Action.DragEnd -> target.release(MouseButton.LEFT)
                is Action.Press -> press(target, action.key)
                is Action.Volume -> volume(target, action.crownPixels)
                is Action.Type -> target.type(action.text)
                Action.Center -> target.center()
                is Action.Move -> {
                    pendingX += action.dx
                    pendingY += action.dy
                    val x = pendingX.toInt()
                    val y = pendingY.toInt()
                    if (x != 0 || y != 0) {
                        pendingX -= x
                        pendingY -= y
                        target.move(x, y)
                    }
                }
            }
        }
    }

    private fun press(target: PointerLink, key: RemoteKey) {
        if (key == RemoteKey.NONE) return
        if (!target.send(key)) PointerBus.unsupported.value = key
    }

    private fun volume(target: PointerLink, crownPixels: Float) {
        volumeRemainder += crownPixels
        while (volumeRemainder >= VOLUME_STEP_PX) {
            volumeRemainder -= VOLUME_STEP_PX
            press(target, RemoteKey.VOLUME_UP)
        }
        while (volumeRemainder <= -VOLUME_STEP_PX) {
            volumeRemainder += VOLUME_STEP_PX
            press(target, RemoteKey.VOLUME_DOWN)
        }
    }

    private fun scroll(target: PointerLink, crownPixels: Float) {
        val pixelsPerStep = 120f / (1f + (tuning.scrollSpeed - 1) * 0.3f)
        scrollRemainder += if (tuning.reverseScroll) -crownPixels else crownPixels
        val steps = (scrollRemainder / pixelsPerStep).toInt()
        if (steps != 0) {
            scrollRemainder -= steps * pixelsPerStep
            target.scroll(steps)
        }
    }

    // endregion

    private fun registerBatteryWatch() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                BatteryGate.read(context).reason?.let { stopWithNote(it) }
            }
        }
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        })
        batteryReceiver = receiver
    }

    private fun stopWithNote(note: String?) {
        PointerBus.state.value = LinkState.Idle(note)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PointerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_drift)
            .setContentTitle("Drift")
            .setContentText("Steering your computer")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // Shows Drift's icon on the watch face while it runs; tap it to come back.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_stat_drift)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("Steering").build())
            .build()
            .apply(applicationContext)

        return builder.build()
    }

    override fun onDestroy() {
        PointerBus.service = null
        batteryReceiver?.let { unregisterReceiver(it) }
        batteryReceiver = null
        scope.cancel()
        handshakeSocket?.close()
        wifiLock?.takeIf { it.isHeld }?.release()
        // Finish on the motion thread so the last packet and Bluetooth teardown happen in order.
        motion.post {
            stopSensing()
            link?.close()
            link = null
            wifi?.release()
        }
        motionThread.quitSafely()
        if (PointerBus.state.value !is LinkState.Idle) PointerBus.state.value = LinkState.Idle()
        PointerBus.connectedTo.value = null
        PointerBus.unsupported.value = null
        PointerBus.paused.value = false
        PointerBus.holding.value = false
        PointerBus.frozen = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.drift.airmouse.STOP"
        private const val CHANNEL_ID = "drift_steering"
        private const val NOTIFICATION_ID = 7
        private const val NO_ANSWER_TIMEOUT_MS = 6_000L
        private const val NO_ANSWER = "Drift Receiver didn't answer. Is it open on your computer?"
        private const val APPROVAL_TIMEOUT_MS = 90_000L
        private const val IDLE_LIMIT_MS = 5 * 60_000L
        private const val DOUBLE_CLICK_GAP_MS = 90L
        private const val VOLUME_STEP_PX = 70f
        private const val RESTART_DELAY_MS = 700L
        private const val PINCH_SAMPLING_US = 10_000
        private const val PEAK_LOG_MS = 500L
        private const val PEAK_LOG_FLOOR = 150f
        private const val TAG = "Drift"
        private val PINCH_MODES = setOf(Mode.MOUSE, Mode.SLIDES, Mode.MEDIA)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PointerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PointerService::class.java))
        }

        /** Reconnect to a newly chosen computer: stop, then start fresh once the old session is gone. */
        fun restart(context: Context) {
            stop(context)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ start(context) }, RESTART_DELAY_MS)
        }
    }
}
