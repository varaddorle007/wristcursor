package com.drift.airmouse.core

import kotlin.math.sqrt

/**
 * Detects a "knock-knock" gesture: two sharp taps/knocks on the wrist or watch casing.
 * Each knock produces a brief, high-acceleration jerk spike.
 * When two knocks occur within [MIN_GAP_NANOS]..[MAX_GAP_NANOS], a double knock is reported.
 */
class KnockDetector {
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastNanos = 0L
    private var firstKnockNanos = 0L
    private var cooldownUntilNanos = 0L

    fun onAcceleration(x: Float, y: Float, z: Float, timestampNanos: Long): Boolean {
        val dt = (timestampNanos - lastNanos) / 1e9f
        val primed = lastNanos != 0L && dt in 0.001f..0.08f
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

        if (!primed || timestampNanos < cooldownUntilNanos) return false

        // Check if jerk exceeds knock threshold (sharp tap on watch or wrist)
        if (jerk >= KNOCK_JERK_THRESHOLD) {
            val gap = timestampNanos - firstKnockNanos
            if (firstKnockNanos != 0L && gap in MIN_GAP_NANOS..MAX_GAP_NANOS) {
                // Second knock detected within window -> Double Knock!
                firstKnockNanos = 0L
                cooldownUntilNanos = timestampNanos + COOLDOWN_NANOS
                return true
            } else {
                // First knock detected
                firstKnockNanos = timestampNanos
            }
        } else if (firstKnockNanos != 0L && timestampNanos - firstKnockNanos > MAX_GAP_NANOS) {
            firstKnockNanos = 0L
        }

        return false
    }

    fun reset() {
        firstKnockNanos = 0L
        lastNanos = 0L
        cooldownUntilNanos = 0L
    }

    companion object {
        private const val KNOCK_JERK_THRESHOLD = 320f // Sharp distinct tap/knock
        private const val MIN_GAP_NANOS = 160_000_000L // Min 160ms between knocks
        private const val MAX_GAP_NANOS = 600_000_000L // Max 600ms between knocks
        private const val COOLDOWN_NANOS = 800_000_000L // 800ms cooldown after double knock
    }
}
