package com.drift.airmouse.core

/**
 * Detects a "pull-pull" gesture: two rapid pulls of the arm backwards along the forearm axis (Y-axis).
 * When two pulls occur within [MIN_GAP_NANOS]..[MAX_GAP_NANOS], a pull-pull gesture is reported.
 */
class PullDetector {
    private var gravityY = 0f
    private var primed = false
    private var firstPullNanos = 0L
    private var lastNanos = 0L
    private var cooldownUntilNanos = 0L
    private var inPull = false

    fun onAcceleration(x: Float, y: Float, z: Float, timestampNanos: Long): Boolean {
        if (!primed) {
            gravityY = y
            primed = true
            lastNanos = timestampNanos
            return false
        }
        // Track gravity component on Y
        gravityY += (y - gravityY) * 0.05f
        val linearY = y - gravityY // Acceleration along arm axis

        if (timestampNanos < cooldownUntilNanos) {
            return false
        }

        // Pulling arm backwards produces strong negative linearY
        if (linearY < -PULL_THRESHOLD) {
            if (!inPull) {
                inPull = true
                val gap = timestampNanos - firstPullNanos
                if (firstPullNanos != 0L && gap in MIN_GAP_NANOS..MAX_GAP_NANOS) {
                    firstPullNanos = 0L
                    inPull = false
                    cooldownUntilNanos = timestampNanos + COOLDOWN_NANOS
                    return true
                } else {
                    firstPullNanos = timestampNanos
                }
            }
        } else if (linearY > -1.5f) {
            inPull = false
        }

        if (firstPullNanos != 0L && timestampNanos - firstPullNanos > MAX_GAP_NANOS) {
            firstPullNanos = 0L
        }

        return false
    }

    fun reset() {
        primed = false
        inPull = false
        firstPullNanos = 0L
        cooldownUntilNanos = 0L
    }

    companion object {
        private const val PULL_THRESHOLD = 5.5f // m/s² pull back along arm
        private const val MIN_GAP_NANOS = 180_000_000L // 180ms
        private const val MAX_GAP_NANOS = 750_000_000L // 750ms
        private const val COOLDOWN_NANOS = 900_000_000L
    }
}
