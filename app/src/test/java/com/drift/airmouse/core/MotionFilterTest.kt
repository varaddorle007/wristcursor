package com.drift.airmouse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class MotionFilterTest {

    /** Feeds [samples] gyro readings at 100 Hz and returns the total movement. */
    private fun run(
        filter: MotionFilter,
        samples: Int = 50,
        rates: (Int) -> Pair<Float, Float>,
    ): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        repeat(samples) { i ->
            val (yaw, pitch) = rates(i)
            filter.step(yaw, pitch, timestampNanos = 1_000_000_000L + i * 10_000_000L)
            x += filter.dx
            y += filter.dy
        }
        return x to y
    }

    @Test
    fun handAtRestDoesNotMoveCursor() {
        val (x, y) = run(MotionFilter(Tuning())) { 0.02f to -0.02f }
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    @Test
    fun turningLeftMovesLeftAndRaisingMovesUp() {
        val (x, y) = run(MotionFilter(Tuning())) { 1f to -1f }
        assertTrue("x=$x", x < -50f)
        assertTrue("y=$y", y < -50f)
    }

    @Test
    fun rightWristFlipsVerticalOnly() {
        val left = run(MotionFilter(Tuning(rightWrist = false))) { 1f to 1f }
        val right = run(MotionFilter(Tuning(rightWrist = true))) { 1f to 1f }
        assertEquals(left.first, right.first, 0.01f)
        assertEquals(left.second, -right.second, 0.01f)
    }

    @Test
    fun higherSpeedTravelsFurther() {
        val slow = abs(run(MotionFilter(Tuning(speed = 2))) { 1f to 0f }.first)
        val fast = abs(run(MotionFilter(Tuning(speed = 8))) { 1f to 0f }.first)
        assertTrue("slow=$slow fast=$fast", fast > slow * 3)
    }

    @Test
    fun accelerationMakesFastFlicksTravelMoreThanProportionally() {
        val tuning = Tuning(acceleration = 4)
        val gentle = abs(run(MotionFilter(tuning)) { -0.5f to 0f }.first)
        val flick = abs(run(MotionFilter(tuning)) { -2f to 0f }.first)
        assertTrue("gentle=$gentle flick=$flick", flick > gentle * 5)
    }

    @Test
    fun steadinessCalmsTremor() {
        // A slow move with a 10 Hz shake on top, like a real hand.
        val shaky: (Int) -> Pair<Float, Float> = { i -> (0.4f + 0.3f * sin(i * 2 * Math.PI * 0.1).toFloat()) to 0f }
        fun jitter(steadiness: Int): Float {
            val filter = MotionFilter(Tuning(steadiness = steadiness, acceleration = 0))
            var last = 0f
            var total = 0f
            repeat(100) { i ->
                val (yaw, pitch) = shaky(i)
                filter.step(yaw, pitch, 1_000_000_000L + i * 10_000_000L)
                if (i > 20) total += abs(filter.dx - last)
                last = filter.dx
            }
            return total
        }
        assertTrue(jitter(5) < jitter(1) * 0.6f)
    }

    @Test
    fun precisionHoverCalmsMicroMovementNearButtons() {
        val filter = MotionFilter(Tuning(steadiness = 3, acceleration = 0))
        // Small rate just above deadzone (0.035f + 0.03f = 0.065f)
        val (hoverX, _) = run(filter, samples = 30) { 0.065f to 0f }
        // Faster rate (0.035f + 0.30f = 0.335f, 10x higher live delta)
        val filterFast = MotionFilter(Tuning(steadiness = 3, acceleration = 0))
        val (fastX, _) = run(filterFast, samples = 30) { 0.335f to 0f }
        // Because of hover progressive damping, fastX / hoverX > 15 (sub-linear fine targeting)
        assertTrue("hoverX=$hoverX fastX=$fastX", abs(fastX) > abs(hoverX) * 15f)
    }

    @Test
    fun oneEuroFollowsFastChangesQuickly() {
        val filter = OneEuroFilter(minCutoff = 4.5f, beta = 0.5f)
        filter.filter(0f, 0.01f)
        var out = 0f
        repeat(5) { out = filter.filter(3f, 0.01f) }
        assertTrue("out=$out", out > 2f)
    }
}
