package com.drift.airmouse.core

import kotlin.math.max
import kotlin.math.sqrt

/** What calibration learns about this watch on this wrist. */
data class Calibration(
    /** Jerk (m/s³) that counts as a pinch for this person. */
    val pinchThreshold: Float,
    /** Gyroscope reading at rest (rad/s), subtracted so the cursor doesn't creep. */
    val biasX: Float,
    val biasY: Float,
    val biasZ: Float,
)

/**
 * Two short steps:
 * 1. Hold still: measures gyroscope drift and how much the wrist shakes at rest.
 * 2. Pinch [PINCHES] times: measures how hard this person's pinch is.
 * The pinch threshold lands between the two, so light pinches count and shake doesn't.
 */
class Calibrator {

    enum class Phase { STILL, PINCH, DONE }

    var phase = Phase.STILL
        private set
    var pinches = 0
        private set

    private var stillStartNanos = 0L
    private var gyroSum = FloatArray(3)
    private var gyroCount = 0
    private val restJerks = ArrayList<Float>()
    private var noise = MIN_NOISE
    private val peaks = ArrayList<Float>()

    private var lastAccel = FloatArray(3)
    private var lastAccelNanos = 0L
    private var quietUntilNanos = 0L
    private var spikeEndsNanos = 0L
    private var spikePeak = 0f

    /** 0..1 progress through the hold-still step. */
    fun stillProgress(nowNanos: Long): Float =
        if (stillStartNanos == 0L) 0f else ((nowNanos - stillStartNanos).toFloat() / STILL_NANOS).coerceIn(0f, 1f)

    fun onGyro(x: Float, y: Float, z: Float) {
        if (phase != Phase.STILL) return
        gyroSum[0] += x
        gyroSum[1] += y
        gyroSum[2] += z
        gyroCount++
    }

    /** Returns true when this sample completed a pinch (so the UI can buzz). */
    fun onAccel(x: Float, y: Float, z: Float, nanos: Long): Boolean {
        val dt = (nanos - lastAccelNanos) / 1e9f
        val jerk = if (lastAccelNanos != 0L && dt in 0.001f..0.1f) {
            val dx = x - lastAccel[0]
            val dy = y - lastAccel[1]
            val dz = z - lastAccel[2]
            sqrt(dx * dx + dy * dy + dz * dz) / dt
        } else {
            -1f
        }
        lastAccel[0] = x
        lastAccel[1] = y
        lastAccel[2] = z
        lastAccelNanos = nanos
        if (jerk < 0f) return false

        when (phase) {
            Phase.STILL -> {
                if (stillStartNanos == 0L) stillStartNanos = nanos
                restJerks += jerk
                if (nanos - stillStartNanos >= STILL_NANOS) finishStill()
            }
            Phase.PINCH -> return trackPinch(jerk, nanos)
            Phase.DONE -> Unit
        }
        return false
    }

    private fun finishStill() {
        restJerks.sort()
        val p95 = restJerks.getOrNull((restJerks.size * 0.95f).toInt().coerceAtMost(restJerks.lastIndex)) ?: MIN_NOISE
        noise = max(p95, MIN_NOISE)
        phase = Phase.PINCH
    }

    private fun trackPinch(jerk: Float, nanos: Long): Boolean {
        // Inside a spike: keep its highest point, then count it once it settles.
        if (spikeEndsNanos != 0L) {
            if (jerk > spikePeak) spikePeak = jerk
            if (nanos < spikeEndsNanos) return false
            peaks += spikePeak
            pinches = peaks.size
            spikeEndsNanos = 0L
            quietUntilNanos = nanos + REFRACTORY_NANOS
            if (pinches >= PINCHES) phase = Phase.DONE
            return true
        }
        if (nanos < quietUntilNanos || jerk < startLevel()) return false
        spikePeak = jerk
        spikeEndsNanos = nanos + SPIKE_NANOS
        return false
    }

    private fun startLevel() = max(noise * 1.8f, MIN_START)

    fun result(): Calibration? {
        if (phase != Phase.DONE || gyroCount == 0) return null
        val weakest = peaks.minOrNull() ?: return null
        val threshold = max(noise * 1.5f, weakest * 0.6f)
        return Calibration(
            pinchThreshold = threshold,
            biasX = gyroSum[0] / gyroCount,
            biasY = gyroSum[1] / gyroCount,
            biasZ = gyroSum[2] / gyroCount,
        )
    }

    companion object {
        const val PINCHES = 3
        private const val STILL_NANOS = 2_500_000_000L
        private const val SPIKE_NANOS = 80_000_000L
        private const val REFRACTORY_NANOS = 300_000_000L
        private const val MIN_NOISE = 60f
        private const val MIN_START = 120f
    }
}
