package com.drift.airmouse.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Turns gyroscope rates (rad/s) into pointer movement in pixels.
 *
 * - A 1€ filter smooths each axis: heavy smoothing when you move slowly (no hand shake),
 *   almost none when you move fast (no lag).
 * - A dead zone keeps the cursor still when your hand is.
 * - Acceleration makes quick flicks travel further, so big jumps need less arm.
 *
 * Results are written to [dx] and [dy] to avoid allocating at 100 samples a second.
 */
class MotionFilter(tuning: Tuning) {
    private val gain = BASE_GAIN * 1.25f.pow(tuning.speed - 1)
    private val acceleration = tuning.acceleration * 0.3f
    private val deadZone = DEAD_ZONES[tuning.steadiness - 1]
    private val flipVertical = tuning.rightWrist
    private val yaw = OneEuroFilter(MIN_CUTOFFS[tuning.steadiness - 1], BETA)
    private val pitch = OneEuroFilter(MIN_CUTOFFS[tuning.steadiness - 1], BETA)
    private var lastNanos = 0L

    var dx = 0f
        private set
    var dy = 0f
        private set

    /** [yawRate] turns left/right, [pitchRate] tilts up/down. */
    fun step(yawRate: Float, pitchRate: Float, timestampNanos: Long) {
        val dt = if (lastNanos == 0L) 0f else ((timestampNanos - lastNanos) / 1e9f).coerceIn(0f, MAX_DT)
        lastNanos = timestampNanos
        if (dt == 0f) {
            dx = 0f
            dy = 0f
            return
        }
        val x = yaw.filter(-yawRate, dt)
        val y = pitch.filter(if (flipVertical) -pitchRate else pitchRate, dt)
        dx = shape(x) * gain * dt
        dy = shape(y) * gain * dt
    }

    fun reset() {
        lastNanos = 0L
        yaw.reset()
        pitch.reset()
        dx = 0f
        dy = 0f
    }

    private fun shape(rate: Float): Float {
        val live = abs(rate) - deadZone
        if (live <= 0f) return 0f
        // Precision hover damping: when moving slowly to aim at buttons/links,
        // scale down tremor smoothly (sub-linear easing) so the cursor glides onto targets and stays steady.
        val hoverScale = if (live < HOVER_THRESHOLD) {
            val progress = live / HOVER_THRESHOLD
            0.35f + 0.65f * (progress * progress)
        } else {
            1f
        }
        val shapedLive = live * hoverScale
        return sign(rate) * shapedLive * (1f + acceleration * live)
    }

    private companion object {
        const val BASE_GAIN = 750f
        const val BETA = 0.8f
        const val MAX_DT = 0.05f
        const val HOVER_THRESHOLD = 0.15f
        val DEAD_ZONES = floatArrayOf(0.015f, 0.025f, 0.035f, 0.045f, 0.06f)
        val MIN_CUTOFFS = floatArrayOf(5.5f, 4.2f, 3.0f, 2.0f, 1.0f)
    }
}

/** The 1€ filter (Casiez et al.): a low-pass whose cutoff rises with speed. */
class OneEuroFilter(private val minCutoff: Float, private val beta: Float, private val derivativeCutoff: Float = 1f) {
    private var value = 0f
    private var derivative = 0f
    private var primed = false

    fun filter(input: Float, dt: Float): Float {
        if (!primed) {
            value = input
            derivative = 0f
            primed = true
            return input
        }
        derivative += alpha(dt, derivativeCutoff) * ((input - value) / dt - derivative)
        val cutoff = minCutoff + beta * abs(derivative)
        value += alpha(dt, cutoff) * (input - value)
        return value
    }

    fun reset() {
        primed = false
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
