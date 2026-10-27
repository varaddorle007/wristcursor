package com.drift.airmouse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ShakeDetectorTest {
    private val stepNanos = 10_000_000L // 100 Hz

    /** Gravity on z plus a side-to-side swing of [amplitude] m/s² at [hertz]; counts shakes. */
    private fun shakes(detector: ShakeDetector, samples: Int, amplitude: Float, hertz: Float = 4f, jolts: Map<Int, Float> = emptyMap()): Int {
        var count = 0
        repeat(samples) { i ->
            val swing = amplitude * sin(2 * PI * hertz * i / 100).toFloat() + (jolts[i] ?: 0f)
            if (detector.onAcceleration(swing, 0.2f, 9.81f, 1_000_000_000L + i * stepNanos)) count++
        }
        return count
    }

    @Test
    fun aHardShakeCountsOnce() {
        assertEquals(1, shakes(ShakeDetector(2), 100, amplitude = 22f))
    }

    @Test
    fun aLongShakeDoesNotRepeatRightAway() {
        // 1.1 s of shaking: one shake, then the cooldown.
        assertEquals(1, shakes(ShakeDetector(2), 110, amplitude = 22f))
    }

    @Test
    fun pointingIsNotAShake() {
        assertEquals(0, shakes(ShakeDetector(3), 400, amplitude = 4f, hertz = 1f))
    }

    @Test
    fun pinchJoltsAreNotAShake() {
        val jolts = mapOf(50 to 6f, 62 to 3f, 90 to 6f, 102 to 3f)
        val detector = ShakeDetector(3)
        assertEquals(0, shakes(detector, 200, amplitude = 0f, jolts = jolts))
        assertFalse(detector.agitated)
    }

    @Test
    fun aSwingMarksTheWristAsBusy() {
        val detector = ShakeDetector(2)
        shakes(detector, 20, amplitude = 22f)
        assertTrue(detector.agitated)
    }
}
