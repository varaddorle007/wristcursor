package com.drift.airmouse.core

import kotlin.math.sqrt

/**
 * Spots a deliberate wrist shake: [SWINGS] hard swings back and forth within [WINDOW_NANOS].
 * Gravity is tracked with a slow filter and taken away, so only the swinging is measured.
 * Pointing and pinching move far less than a shake, so neither sets it off.
 */
class ShakeDetector(sensitivity: Int) {

    /** Linear acceleration (m/s²) a swing must reach. Higher sensitivity needs a gentler shake. */
    private val strength = STRENGTHS[(sensitivity - 1).coerceIn(0, STRENGTHS.lastIndex)]

    private val gravity = FloatArray(3)
    private var primed = false
    private val lastSwing = FloatArray(3)
    private var swings = 0
    private var firstSwingNanos = 0L
    private var lastSwingNanos = 0L
    private var lastStrongNanos = Long.MIN_VALUE / 2
    private var cooldownUntilNanos = 0L
    private var nowNanos = 0L

    /** True for a moment after any big swing: whatever the wrist did then wasn't a pinch. */
    val agitated: Boolean get() = nowNanos - lastStrongNanos < AGITATED_NANOS

    /** Feed one accelerometer sample. Returns true once when a shake completes. */
    fun onAcceleration(x: Float, y: Float, z: Float, timestampNanos: Long): Boolean {
        nowNanos = timestampNanos
        if (!primed) {
            gravity[0] = x
            gravity[1] = y
            gravity[2] = z
            primed = true
            return false
        }
        gravity[0] += (x - gravity[0]) * GRAVITY_ALPHA
        gravity[1] += (y - gravity[1]) * GRAVITY_ALPHA
        gravity[2] += (z - gravity[2]) * GRAVITY_ALPHA
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        val size = sqrt(lx * lx + ly * ly + lz * lz)
        if (size >= strength * AGITATED_SHARE) lastStrongNanos = timestampNanos
        if (timestampNanos < cooldownUntilNanos || size < strength) return false

        if (swings > 0 && timestampNanos - firstSwingNanos > WINDOW_NANOS) swings = 0
        // A new swing points the other way from the last one.
        val reversed = swings == 0 || lx * lastSwing[0] + ly * lastSwing[1] + lz * lastSwing[2] < 0f
        if (!reversed || timestampNanos - lastSwingNanos < MIN_SWING_GAP_NANOS) return false
        if (swings == 0) firstSwingNanos = timestampNanos
        swings++
        lastSwingNanos = timestampNanos
        lastSwing[0] = lx
        lastSwing[1] = ly
        lastSwing[2] = lz
        if (swings < SWINGS) return false
        swings = 0
        cooldownUntilNanos = timestampNanos + COOLDOWN_NANOS
        return true
    }

    fun reset() {
        primed = false
        swings = 0
        lastStrongNanos = Long.MIN_VALUE / 2
    }

    companion object {
        private val STRENGTHS = floatArrayOf(17f, 13f, 10f)
        const val SWINGS = 4
        private const val GRAVITY_ALPHA = 0.03f
        private const val AGITATED_SHARE = 0.8f
        private const val AGITATED_NANOS = 300_000_000L
        private const val WINDOW_NANOS = 1_000_000_000L
        private const val MIN_SWING_GAP_NANOS = 60_000_000L
        private const val COOLDOWN_NANOS = 1_200_000_000L
    }
}
