package com.drift.airmouse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class CalibratorTest {
    private val step = 10_000_000L // 100 Hz
    private var t = 1_000_000_000L
    private var i = 0

    /** One sample: gravity, a little resting shake, plus an optional jolt. */
    private fun sample(c: Calibrator, jolt: Float = 0f): Boolean {
        val shake = 0.05f * sin(i++ * 1.3f)
        c.onGyro(0.01f, -0.02f, 0.005f)
        val counted = c.onAccel(shake + jolt, 0.1f, 9.81f, t)
        t += step
        return counted
    }

    @Test
    fun holdStillThenThreePinchesGivesACalibration() {
        val c = Calibrator()
        repeat(260) { sample(c) }
        assertEquals(Calibrator.Phase.PINCH, c.phase)
        assertNull(c.result())

        var counted = 0
        repeat(3) {
            if (sample(c, jolt = 4f)) counted++
            repeat(40) { if (sample(c)) counted++ }
        }
        assertEquals(3, counted)
        assertEquals(Calibrator.Phase.DONE, c.phase)

        val result = assertNotNull(c.result()).let { c.result()!! }
        assertEquals(0.01f, result.biasX, 0.001f)
        assertEquals(-0.02f, result.biasY, 0.001f)
        // A 4 m/s² jolt in one 10 ms sample is ~400 m/s³; the threshold sits well below it.
        assertTrue("threshold=${result.pinchThreshold}", result.pinchThreshold in 150f..300f)
    }

    @Test
    fun restingShakeIsNotCountedAsPinches() {
        val c = Calibrator()
        repeat(260) { sample(c) }
        repeat(300) { assertTrue(!sample(c)) }
        assertEquals(0, c.pinches)
    }

    @Test
    fun sensitivityScalesTheCalibratedThreshold() {
        assertEquals(250f, PinchDetector.thresholdFor(200f, 1), 0.01f)
        assertEquals(200f, PinchDetector.thresholdFor(200f, 2), 0.01f)
        assertEquals(160f, PinchDetector.thresholdFor(200f, 3), 0.01f)
        assertEquals(PinchDetector.DEFAULT_THRESHOLD, PinchDetector.thresholdFor(null, 2), 0.01f)
    }
}
