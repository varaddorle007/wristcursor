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

    fun apply(command: String) {
        val parts = command.split(' ')
        when (parts[0]) {
            "m" -> move(parts[1].toInt(), parts[2].toInt())
            "click" -> click(InputEvent.BUTTON1_DOWN_MASK)
            "double" -> repeat(2) { click(InputEvent.BUTTON1_DOWN_MASK) }
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
            "media" -> parts.getOrNull(1)?.let(SystemKeys::media)
            "type" -> parts.getOrNull(1)?.let { encoded ->
                val text = runCatching { String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
                if (!text.isNullOrEmpty()) SystemKeys.type(robot, text)
            }
            "key" -> when (parts.getOrNull(1)) {
                "next" -> press(KeyEvent.VK_RIGHT)
                "prev" -> press(KeyEvent.VK_LEFT)
                "up" -> press(KeyEvent.VK_UP)
                "down" -> press(KeyEvent.VK_DOWN)
                "enter" -> press(KeyEvent.VK_ENTER)
                "esc" -> press(KeyEvent.VK_ESCAPE)
                "backspace" -> press(KeyEvent.VK_BACK_SPACE)
            }
        }
    }

    fun resetDriving() {
        if (currentSteer == -1) {
            runCatching { robot.keyRelease(KeyEvent.VK_LEFT); robot.keyRelease(KeyEvent.VK_A) }
        } else if (currentSteer == 1) {
            runCatching { robot.keyRelease(KeyEvent.VK_RIGHT); robot.keyRelease(KeyEvent.VK_D) }
        }
        if (currentGas) {
            runCatching { robot.keyRelease(KeyEvent.VK_UP); robot.keyRelease(KeyEvent.VK_W) }
        }
        if (currentBrake) {
            runCatching { robot.keyRelease(KeyEvent.VK_DOWN); robot.keyRelease(KeyEvent.VK_S) }
        }
        if (currentNitro) {
            runCatching { robot.keyRelease(KeyEvent.VK_SPACE) }
        }
        currentSteer = 0
        currentGas = false
        currentBrake = false
        currentNitro = false
    }

    private fun applyWheel(steer: Float, throttle: Boolean, brake: Boolean, nitro: Boolean) {
        val steerDir = when {
            steer < -0.15f -> -1
            steer > 0.15f -> 1
            else -> 0
        }

        // Steer Left / Right (Arrow keys and A/D keys)
        if (steerDir != currentSteer) {
            if (currentSteer == -1) {
                runCatching { robot.keyRelease(KeyEvent.VK_LEFT); robot.keyRelease(KeyEvent.VK_A) }
            } else if (currentSteer == 1) {
                runCatching { robot.keyRelease(KeyEvent.VK_RIGHT); robot.keyRelease(KeyEvent.VK_D) }
            }
            if (steerDir == -1) {
                runCatching { robot.keyPress(KeyEvent.VK_LEFT); robot.keyPress(KeyEvent.VK_A) }
            } else if (steerDir == 1) {
                runCatching { robot.keyPress(KeyEvent.VK_RIGHT); robot.keyPress(KeyEvent.VK_D) }
            }
            currentSteer = steerDir
        }

        // Throttle (Up / W)
        if (throttle != currentGas) {
            if (throttle) {
                runCatching { robot.keyPress(KeyEvent.VK_UP); robot.keyPress(KeyEvent.VK_W) }
            } else {
                runCatching { robot.keyRelease(KeyEvent.VK_UP); robot.keyRelease(KeyEvent.VK_W) }
            }
            currentGas = throttle
        }

        // Brake (Down / S)
        if (brake != currentBrake) {
            if (brake) {
                runCatching { robot.keyPress(KeyEvent.VK_DOWN); robot.keyPress(KeyEvent.VK_S) }
            } else {
                runCatching { robot.keyRelease(KeyEvent.VK_DOWN); robot.keyRelease(KeyEvent.VK_S) }
            }
            currentBrake = brake
        }

        // Nitro / Handbrake (Space)
        if (nitro != currentNitro) {
            if (nitro) {
                runCatching { robot.keyPress(KeyEvent.VK_SPACE) }
            } else {
                runCatching { robot.keyRelease(KeyEvent.VK_SPACE) }
            }
            currentNitro = nitro
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
        robot.mousePress(button)
        robot.mouseRelease(button)
    }

    private fun press(key: Int) {
        robot.keyPress(key)
        robot.keyRelease(key)
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
