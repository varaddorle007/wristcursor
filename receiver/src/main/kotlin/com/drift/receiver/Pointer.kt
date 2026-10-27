package com.drift.receiver

import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/** Turns Drift commands into real mouse and keyboard input. */
class Pointer {
    private val robot = Robot().apply {
        autoDelay = 0
        isAutoWaitForIdle = false
    }

    // Track the cursor ourselves instead of asking the OS on every move; re-sync after a pause
    // in case the real mouse was used in between.
    private var x = 0
    private var y = 0
    private var lastMoveNanos = 0L
    private var screens = Rectangle()

    private var currentSteer = 0 // -1: Left, 0: Center, 1: Right
    private var currentGas = false
    private var currentBrake = false
    private var currentNitro = false

    /**
     * When true, steering also moves the mouse cursor horizontally across screen center,
     * providing 1:1 analog steering rack input for simulators (Assetto Corsa, BeamNG, ETS2).
     */
    @Volatile var mouseSteeringEnabled = false

    fun apply(command: String) {
        val parts = command.split(' ')
        when (parts[0]) {
            "m" -> move(parts[1].toInt(), parts[2].toInt())
            "click" -> click(InputEvent.BUTTON1_DOWN_MASK)
            "double" -> doubleClick()
            "right" -> click(InputEvent.BUTTON3_DOWN_MASK)
            "down" -> robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            "up" -> robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            "s" -> robot.mouseWheel(parts[1].toInt())
            "center" -> center()
            "wheel" -> {
                val steer = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                val throttle = parts.getOrNull(2) == "1"
                val brake = parts.getOrNull(3) == "1"
                val nitro = parts.getOrNull(4) == "1"
                applyWheel(steer, throttle, brake, nitro)
            }
            "steermode" -> {
                val mode = parts.getOrNull(1)
                mouseSteeringEnabled = (mode == "mouse" || mode == "1")
            }
            "media" -> parts.getOrNull(1)?.let(SystemKeys::media)
            "type" -> parts.getOrNull(1)?.let { encoded ->
                val text = runCatching { String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
                if (!text.isNullOrEmpty()) SystemKeys.type(robot, text)
            }
            "key" -> {
                val keyName = parts.getOrNull(1)
                if (keyName == "back") {
                    handleBack()
                } else {
                    keyToCode(keyName)?.let { press(it) }
                }
            }
            "keydown" -> keyToCode(parts.getOrNull(1))?.let { runCatching { robot.keyPress(it) } }
            "keyup" -> keyToCode(parts.getOrNull(1))?.let { runCatching { robot.keyRelease(it) } }
        }
    }

    private fun handleBack() {
        val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
        if (isMac) {
            runCatching {
                robot.keyPress(KeyEvent.VK_META)
                robot.keyPress(KeyEvent.VK_OPEN_BRACKET)
                robot.keyRelease(KeyEvent.VK_OPEN_BRACKET)
                robot.keyRelease(KeyEvent.VK_META)
            }
        } else {
            runCatching {
                robot.keyPress(KeyEvent.VK_ALT)
                robot.keyPress(KeyEvent.VK_LEFT)
                robot.keyRelease(KeyEvent.VK_LEFT)
                robot.keyRelease(KeyEvent.VK_ALT)
            }
        }
    }

    private fun keyToCode(name: String?): Int? = when (name) {
        "next", "right" -> KeyEvent.VK_RIGHT
        "prev", "left" -> KeyEvent.VK_LEFT
        "up" -> KeyEvent.VK_UP
        "down" -> KeyEvent.VK_DOWN
        "enter" -> KeyEvent.VK_ENTER
        "esc" -> KeyEvent.VK_ESCAPE
        "backspace" -> KeyEvent.VK_BACK_SPACE
        "space" -> KeyEvent.VK_SPACE
        "shift" -> KeyEvent.VK_SHIFT
        "w" -> KeyEvent.VK_W
        "a" -> KeyEvent.VK_A
        "s" -> KeyEvent.VK_S
        "d" -> KeyEvent.VK_D
        "e" -> KeyEvent.VK_E
        else -> null
    }

    fun resetDriving() {
        runCatching {
            robot.keyRelease(KeyEvent.VK_LEFT)
            robot.keyRelease(KeyEvent.VK_A)
            robot.keyRelease(KeyEvent.VK_RIGHT)
            robot.keyRelease(KeyEvent.VK_D)
        }
        currentSteer = 0
        if (currentGas) {
            runCatching { robot.keyRelease(KeyEvent.VK_UP); robot.keyRelease(KeyEvent.VK_W) }
        }
        if (currentBrake) {
            runCatching { robot.keyRelease(KeyEvent.VK_DOWN); robot.keyRelease(KeyEvent.VK_S) }
        }
        if (currentNitro) {
            runCatching { robot.keyRelease(KeyEvent.VK_SPACE) }
        }
        currentGas = false
        currentBrake = false
        currentNitro = false
        if (mouseSteeringEnabled) {
            center()
        }
    }

    private fun applyWheel(steer: Float, throttle: Boolean, brake: Boolean, nitro: Boolean) {
        // 1. Digital Keyboard Steering (WASD + Arrow keys):
        // Hold keys continuously without pulsing or spamming; release cleanly at center.
        val newSteer = when {
            steer < -0.04f -> -1
            steer > 0.04f -> 1
            else -> 0
        }

        if (newSteer != currentSteer) {
            when (newSteer) {
                -1 -> {
                    // Turn Left: release right keys, press and hold left keys
                    runCatching {
                        robot.keyRelease(KeyEvent.VK_RIGHT)
                        robot.keyRelease(KeyEvent.VK_D)
                        robot.keyPress(KeyEvent.VK_LEFT)
                        robot.keyPress(KeyEvent.VK_A)
                    }
                }
                1 -> {
                    // Turn Right: release left keys, press and hold right keys
                    runCatching {
                        robot.keyRelease(KeyEvent.VK_LEFT)
                        robot.keyRelease(KeyEvent.VK_A)
                        robot.keyPress(KeyEvent.VK_RIGHT)
                        robot.keyPress(KeyEvent.VK_D)
                    }
                }
                0 -> {
                    // Straight / Center: release all steering keys
                    runCatching {
                        robot.keyRelease(KeyEvent.VK_LEFT)
                        robot.keyRelease(KeyEvent.VK_A)
                        robot.keyRelease(KeyEvent.VK_RIGHT)
                        robot.keyRelease(KeyEvent.VK_D)
                    }
                }
            }
            currentSteer = newSteer
        }

        // 2. Throttle (Up / W)
        if (throttle != currentGas) {
            if (throttle) {
                runCatching { robot.keyPress(KeyEvent.VK_UP); robot.keyPress(KeyEvent.VK_W) }
            } else {
                runCatching { robot.keyRelease(KeyEvent.VK_UP); robot.keyRelease(KeyEvent.VK_W) }
            }
            currentGas = throttle
        }

        // 3. Brake (Down / S)
        if (brake != currentBrake) {
            if (brake) {
                runCatching { robot.keyPress(KeyEvent.VK_DOWN); robot.keyPress(KeyEvent.VK_S) }
            } else {
                runCatching { robot.keyRelease(KeyEvent.VK_DOWN); robot.keyRelease(KeyEvent.VK_S) }
            }
            currentBrake = brake
        }

        // 4. Nitro / Handbrake (Space)
        if (nitro != currentNitro) {
            if (nitro) {
                runCatching { robot.keyPress(KeyEvent.VK_SPACE) }
            } else {
                runCatching { robot.keyRelease(KeyEvent.VK_SPACE) }
            }
            currentNitro = nitro
        }

        // 5. 1:1 Analog Mouse Steering for racing simulators (Assetto Corsa, BeamNG, ETS2)
        if (mouseSteeringEnabled) {
            val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
            val centerX = bounds.x + bounds.width / 2
            val centerY = bounds.y + bounds.height / 2
            val maxRange = bounds.width / 2 - 40
            val targetX = (centerX + (steer * maxRange)).toInt().coerceIn(bounds.x + 20, bounds.x + bounds.width - 20)
            robot.mouseMove(targetX, centerY)
            x = targetX
            y = centerY
            lastMoveNanos = System.nanoTime()
        }
    }

    private fun move(dx: Int, dy: Int) {
        val now = System.nanoTime()
        if (now - lastMoveNanos > RESYNC_NANOS) {
            val at = MouseInfo.getPointerInfo()?.location ?: return
            x = at.x
            y = at.y
            screens = allScreens()
        }
        x = (x + dx).coerceIn(screens.x, screens.x + screens.width - 1)
        y = (y + dy).coerceIn(screens.y, screens.y + screens.height - 1)
        robot.mouseMove(x, y)
        lastMoveNanos = now
    }

    /** Middle of the main screen: where the watch starts, so your arm and the cursor line up. */
    private fun center() {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        x = bounds.x + bounds.width / 2
        y = bounds.y + bounds.height / 2
        screens = allScreens()
        robot.mouseMove(x, y)
        lastMoveNanos = System.nanoTime()
    }

    private fun click(button: Int) {
        runCatching {
            robot.mousePress(button)
            Thread.sleep(35)
            robot.mouseRelease(button)
        }
    }

    private fun doubleClick() {
        runCatching {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(35)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(60)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(35)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
    }

    private fun press(key: Int) {
        runCatching {
            robot.keyPress(key)
            Thread.sleep(25)
            robot.keyRelease(key)
        }
    }

    private fun allScreens(): Rectangle =
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
            .reduceOrNull { all, next -> all.union(next) }
            ?: Rectangle(0, 0, 1920, 1080)

    private companion object {
        const val RESYNC_NANOS = 250_000_000L
    }
}
