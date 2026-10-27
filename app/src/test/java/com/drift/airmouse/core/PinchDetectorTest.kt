package com.drift.airmouse.core

import com.drift.airmouse.core.PinchDetector.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class PinchDetectorTest {
    private val stepNanos = 10_000_000L // 100 Hz
    private fun detector(holdEnabled: Boolean = true) = PinchDetector(PinchDetector.thresholdFor(null, 2), holdEnabled)
    private fun at(i: Int) = 1_000_000_000L + i * stepNanos

    /** Fingers meet hard at [i] and part softly 120 ms later: one quick pinch. */
    private fun tap(i: Int) = mapOf(i to 6f, i + 12 to 3f)

    /**
     * Gravity on z plus a slow arm sway, with one-sample jolts (index to size).
     * [jitter] adds sample-to-sample shake, like a busy arm.
     */
    private fun feed(
        detector: PinchDetector,
        samples: Int,
        jolts: Map<Int, Float>,
        turn: Float = 0f,
        jitter: Float = 0f,
        from: Int = 0,
    ): List<Event> {
        val events = mutableListOf<Event>()
        detector.onTurnRate(turn)
        for (i in from until from + samples) {
            val sway = 0.8f * sin(i * 0.05f) + if (i % 2 == 0) jitter else 0f
            val spike = jolts[i] ?: 0f
            detector.onAcceleration(sway + spike, 0.2f, 9.81f + spike / 2, at(i))?.let(events::add)
        }
        return events
    }

    @Test
    fun onePinchIsOneSingle() {
        assertEquals(listOf(Event.SINGLE), feed(detector(), 150, tap(30)))
    }

    @Test
    fun onePinchNeverRepeats() {
        // A long quiet stretch afterwards: still exactly one event.
        assertEquals(listOf(Event.SINGLE), feed(detector(), 600, tap(30)))
    }

    @Test
    fun twoQuickPinchesAreOneDouble() {
        assertEquals(listOf(Event.DOUBLE), feed(detector(), 200, tap(30) + tap(60)))
    }

    @Test
    fun pinchesFarApartAreTwoSingles() {
        assertEquals(listOf(Event.SINGLE, Event.SINGLE), feed(detector(), 300, tap(30) + tap(150)))
    }

    @Test
    fun holdingThePinchHoldsUntilTheFingersPart() {
        val events = feed(detector(), 200, mapOf(30 to 6f, 110 to 4f))
        assertEquals(listOf(Event.HOLD_START, Event.HOLD_END), events)
    }

    @Test
    fun withHoldOffALongPinchIsStillOneSingle() {
        assertEquals(listOf(Event.SINGLE), feed(detector(holdEnabled = false), 200, mapOf(30 to 6f)))
    }

    @Test
    fun aHoldIsNeverLeftDown() {
        val events = feed(detector(), 1_200, mapOf(30 to 6f))
        assertEquals(listOf(Event.HOLD_START, Event.HOLD_END), events)
    }

    @Test
    fun armSwayIsNotAPinch() {
        assertTrue(feed(PinchDetector(PinchDetector.thresholdFor(null, 3)), 300, emptyMap()).isEmpty())
    }

    @Test
    fun aBusyArmNeverClicks() {
        // Shaky wrist all along, with jolts on top: the wrist was never calm, so nothing counts.
        val jolts = tap(40) + tap(80) + tap(120)
        assertTrue(feed(detector(), 200, jolts, jitter = 2f).isEmpty())
    }

    @Test
    fun theWatchsOwnBuzzIsIgnored() {
        val detector = detector()
        detector.muteUntil(at(60))
        assertTrue(feed(detector, 150, tap(30)).isEmpty())
    }

    @Test
    fun ringingRightAfterAPinchDoesNotCountTwice() {
        assertEquals(listOf(Event.SINGLE), feed(detector(), 150, tap(30) + mapOf(32 to 6f, 34 to 5f)))
    }

    @Test
    fun joltsWhileTurningFastAreIgnored() {
        assertTrue(feed(PinchDetector(PinchDetector.thresholdFor(null, 3)), 150, tap(30) + tap(60), turn = 3f).isEmpty())
    }

    @Test
    fun waitingCoversTheGestureOnly() {
        val detector = detector()
        feed(detector, 35, tap(30))
        assertTrue(detector.waiting)
        feed(detector, 100, emptyMap(), from = 35)
        assertFalse(detector.waiting)
    }

    @Test
    fun cancelLetsGoOfAHold() {
        val detector = detector()
        assertEquals(listOf(Event.HOLD_START), feed(detector, 80, mapOf(30 to 6f)))
        assertTrue(detector.holding)
        assertEquals(Event.HOLD_END, detector.cancel())
        assertFalse(detector.holding)
    }
}
