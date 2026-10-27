package com.drift.airmouse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WheelSteeringTest {

    private fun computeSteer(
        rawAngleDeg: Float,
        centerAngleDeg: Float,
        rightWrist: Boolean = false,
        deadzone: Float = 2.5f,
        maxAngle: Float = 60.0f,
    ): Float {
        var delta = rawAngleDeg - centerAngleDeg
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f

        if (rightWrist) delta = -delta

        val absDelta = abs(delta)
        return if (absDelta <= deadzone) {
            0f
        } else {
            val effective = (absDelta - deadzone) / (maxAngle - deadzone)
            val sign = if (delta > 0f) 1f else -1f
            (effective.coerceIn(0f, 1f) * sign)
        }
    }

    @Test
    fun zeroAtCenterAngle() {
        val steer = computeSteer(rawAngleDeg = 25f, centerAngleDeg = 25f)
        assertEquals(0f, steer, 0.001f)
    }

    @Test
    fun deadzoneSuppressesMicroTremor() {
        val steerSmallRight = computeSteer(rawAngleDeg = 26.5f, centerAngleDeg = 25f) // delta = 1.5 deg (< 2.5 deg)
        val steerSmallLeft = computeSteer(rawAngleDeg = 23.5f, centerAngleDeg = 25f)  // delta = -1.5 deg (< 2.5 deg)
        assertEquals(0f, steerSmallRight, 0.001f)
        assertEquals(0f, steerSmallLeft, 0.001f)
    }

    @Test
    fun fullLockAtMaxAngle() {
        val fullRight = computeSteer(rawAngleDeg = 85f, centerAngleDeg = 25f) // delta = +60 deg
        val fullLeft = computeSteer(rawAngleDeg = -35f, centerAngleDeg = 25f) // delta = -60 deg
        assertEquals(1.0f, fullRight, 0.001f)
        assertEquals(-1.0f, fullLeft, 0.001f)
    }

    @Test
    fun beyondMaxAngleClampedToUnitRange() {
        val extremeRight = computeSteer(rawAngleDeg = 110f, centerAngleDeg = 25f) // delta = +85 deg
        val extremeLeft = computeSteer(rawAngleDeg = -70f, centerAngleDeg = 25f)  // delta = -95 deg
        assertEquals(1.0f, extremeRight, 0.001f)
        assertEquals(-1.0f, extremeLeft, 0.001f)
    }

    @Test
    fun rightWristInversionPreservesNaturalSteering() {
        val leftWristRightTurn = computeSteer(rawAngleDeg = 55f, centerAngleDeg = 25f, rightWrist = false)
        val rightWristRightTurn = computeSteer(rawAngleDeg = -5f, centerAngleDeg = 25f, rightWrist = true)
        assertEquals(leftWristRightTurn, rightWristRightTurn, 0.001f)
    }

    @Test
    fun hidGamepadReportClamping() {
        val out = ByteArray(HidReports.GAMEPAD_SIZE)
        // Check full left (-32767) and full right (+32767)
        HidReports.encodeGamepad(out, x = -32767, y = 32767, buttons = 0x01)
        val xLittleEndian = (out[0].toInt() and 0xFF) or ((out[1].toInt() and 0xFF) shl 8)
        assertEquals(-32767, xLittleEndian.toShort().toInt())
        assertEquals(1, out[4].toInt())
    }
}
