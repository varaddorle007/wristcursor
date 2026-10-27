package com.drift.airmouse.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Spots finger pinches (thumb meets index finger) from the watch's accelerometer.
 *
 * Pinching jolts the wrist twice: sharply when the fingers meet, more softly when they part.
 * We look for those jolts as sudden jumps between samples ("jerk"). A jolt only counts as
 * fingers meeting when the wrist was calm just before it, so moving or shaking the arm, or the
 * watch's own vibration, never clicks.
 *
 * - Fingers meet and part quickly: one pinch. A second pinch soon after reports [Event.DOUBLE]
 *   at once; a lone one reports [Event.SINGLE] after a short wait in case a second follows.
 * - Fingers meet and stay together: [Event.HOLD_START], then [Event.HOLD_END] when they part.
 *
 * Every gesture reports exactly once.
 */
class PinchDetector(private val threshold: Float, private val holdEnabled: Boolean = true) {

    enum class Event { SINGLE, DOUBLE, HOLD_START, HOLD_END }

    private enum class State { IDLE, DOWN, HOLDING }

    private var state = State.IDLE
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastNanos = 0L
    private var background = 0f
    private var quietUntilNanos = 0L
    private var downNanos = 0L
    private var holdNanos = 0L
    private var taps = 0
    private var lastTapNanos = 0L
    private var turning = 0f

    /** Largest jerk seen since the last read, for tuning. */
    var peak = 0f
        private set

    /** Latest wrist turn rate (rad/s) from the gyroscope, used to ignore big arm moves. */
    fun onTurnRate(radPerSecond: Float) {
        turning = radPerSecond
    }

    /** Ignore jolts until this sensor time, e.g. while the watch vibrates. */
    fun muteUntil(nanos: Long) {
        if (nanos > quietUntilNanos) quietUntilNanos = nanos
    }

    /** Feed one accelerometer sample. Returns an event when a gesture completes. */
    fun onAcceleration(x: Float, y: Float, z: Float, timestampNanos: Long): Event? {
        val dt = (timestampNanos - lastNanos) / 1e9f
        val primed = lastNanos != 0L && dt in 0.001f..0.1f
        val jerk = if (primed) {
            val dx = x - lastX
            val dy = y - lastY
            val dz = z - lastZ
            sqrt(dx * dx + dy * dy + dz * dz) / dt
        } else {
            0f
        }
        lastX = x
        lastY = y
        lastZ = z
        lastNanos = timestampNanos
        if (jerk > peak) peak = jerk
        // How busy the wrist was just before this sample.
        val calmBefore = background < threshold * CALM_RATIO
        if (primed) background += (jerk - background) * BACKGROUND_ALPHA

        expire(timestampNanos)?.let { return it }
        if (!primed || timestampNanos < quietUntilNanos || turning > MAX_TURN) return null

        when (state) {
            State.IDLE -> if (jerk >= threshold && calmBefore) {
                state = State.DOWN
                downNanos = timestampNanos
                quietUntilNanos = timestampNanos + RING_NANOS
            }
            // The softer jolt of the fingers parting ends a quick pinch.
            State.DOWN -> if (jerk >= threshold * RELEASE_RATIO) {
                state = State.IDLE
                quietUntilNanos = timestampNanos + AFTER_RELEASE_NANOS
                return tapped(timestampNanos)
            }
            State.HOLDING -> if (jerk >= threshold * HOLD_RELEASE_RATIO) {
                state = State.IDLE
                quietUntilNanos = timestampNanos + AFTER_RELEASE_NANOS
                return Event.HOLD_END
            }
        }
        return null
    }

    /** Call regularly (each sample does it): turns waiting into a single, a hold, or a hold's end. */
    fun expire(nowNanos: Long): Event? {
        when {
            state == State.DOWN && nowNanos - downNanos > TAP_MAX_NANOS -> {
                // Fingers still together: a hold, unless this was the second pinch of a double.
                if (holdEnabled && taps == 0) {
                    state = State.HOLDING
                    holdNanos = nowNanos
                    return Event.HOLD_START
                }
                state = State.IDLE
                return tapped(nowNanos)
            }
            // Safety net: never hold the button forever if the fingers parting went unnoticed.
            state == State.HOLDING && nowNanos - holdNanos > MAX_HOLD_NANOS -> {
                state = State.IDLE
                return Event.HOLD_END
            }
            state == State.IDLE && taps == 1 && nowNanos - lastTapNanos > TAP_GAP_NANOS -> {
                taps = 0
                return Event.SINGLE
            }
        }
        return null
    }

    private fun tapped(nowNanos: Long): Event? {
        taps++
        lastTapNanos = nowNanos
        if (taps < 2) return null
        taps = 0
        return Event.DOUBLE
    }

    /**
     * Drops whatever is being read (the arm swung hard, so it wasn't a pinch).
     * Returns [Event.HOLD_END] if a hold was in progress, so a held button gets let go.
     */
    fun cancel(): Event? {
        val wasHolding = state == State.HOLDING
        state = State.IDLE
        taps = 0
        return if (wasHolding) Event.HOLD_END else null
    }

    /** True while a quick pinch is being read, so the cursor can hold still for the click. */
    val waiting: Boolean get() = state == State.DOWN || taps > 0

    val holding: Boolean get() = state == State.HOLDING

    val thresholdValue: Float get() = threshold

    fun readPeak(): Float = peak.also { peak = 0f }

    fun reset() {
        state = State.IDLE
        lastNanos = 0L
        background = 0f
        taps = 0
        lastTapNanos = 0L
        quietUntilNanos = 0L
        peak = 0f
    }

    companion object {
        /** Used until the person calibrates. */
        const val DEFAULT_THRESHOLD = 300f
        private const val MIN_THRESHOLD = 140f
        private val SENSITIVITY_SCALE = floatArrayOf(1.25f, 1f, 0.8f)

        /** Jerk (m/s³) that counts as a pinch: calibrated to this wrist, nudged by the sensitivity setting. */
        fun thresholdFor(calibrated: Float?, sensitivity: Int): Float =
            max(MIN_THRESHOLD, (calibrated ?: DEFAULT_THRESHOLD) * SENSITIVITY_SCALE[(sensitivity - 1).coerceIn(0, SENSITIVITY_SCALE.lastIndex)])

        private const val MAX_TURN = 1.2f
        /** Fingers meeting only counts when the wrist was calmer than this (share of the threshold) just before. */
        private const val CALM_RATIO = 0.4f
        private const val BACKGROUND_ALPHA = 0.25f
        /** Fingers parting is a softer jolt than fingers meeting. */
        private const val RELEASE_RATIO = 0.4f
        /** While holding, the arm moves (dragging), so letting go needs a clearer jolt. */
        private const val HOLD_RELEASE_RATIO = 0.55f
        /** Ignore the wrist ringing right after the fingers meet. */
        private const val RING_NANOS = 70_000_000L
        private const val AFTER_RELEASE_NANOS = 120_000_000L
        /** Fingers together longer than this is a hold. */
        private const val TAP_MAX_NANOS = 350_000_000L
        /** Longest wait for a second pinch. */
        private const val TAP_GAP_NANOS = 380_000_000L
        private const val MAX_HOLD_NANOS = 10_000_000_000L
    }
}
