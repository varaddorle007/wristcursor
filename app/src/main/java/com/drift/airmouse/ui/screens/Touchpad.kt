package com.drift.airmouse.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.drift.airmouse.core.Action
import com.drift.airmouse.core.Haptics
import com.drift.airmouse.core.PointerBus
import com.drift.airmouse.core.Tuning
import com.drift.airmouse.ui.BrandFont
import com.drift.airmouse.ui.Caption
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

private const val HOLD_MS = 420L
private const val BASE_GAIN = 1.5f
private const val ACCELERATION = 0.55f // extra gain per (pixel per millisecond) of finger speed
private const val MAX_GAIN = 6f
private const val SCROLL_GAIN = 3f
private const val GLIDE_MIN_SPEED = 0.6f // pixels per millisecond
private const val GLIDE_MAX_SPEED = 4f
private const val GLIDE_FRICTION = 0.9f // per 16 ms frame

/**
 * Touchpad mode: the whole screen is a trackpad. Nothing else runs (no gyroscope, no pinch),
 * so the cursor only ever moves when your finger does.
 * - Drag to move. Faster swipes travel further; a quick flick keeps gliding for a moment.
 * - Tap to click (two quick taps double-click). Hold still to right-click.
 * - Hold, then drag: grab and move things, let go to drop.
 * - Two fingers: drag to scroll, tap to right-click. The bezel or crown scrolls too.
 */
@Composable
fun TouchpadSurface(tuning: Tuning, buzz: (Haptics.Kind) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var finger by remember { mutableStateOf<Offset?>(null) }
    var grabbing by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var used by remember { mutableStateOf(false) }
    val ripple = remember { Animatable(1f) }
    var rippleAt by remember { mutableStateOf(Offset.Zero) }
    val glide = remember { arrayOfNulls<Job>(1) }

    fun clicked(at: Offset?) {
        if (at == null) return
        rippleAt = at
        scope.launch {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(320))
        }
    }

    Box(
        modifier = modifier.pointerInput(tuning.touchSpeed, tuning.glide) {
            val slop = viewConfiguration.touchSlop
            val gain = BASE_GAIN * 1.2f.pow(tuning.touchSpeed - 5)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                glide[0]?.cancel()
                PointerBus.touching(true)
                used = true
                finger = down.position
                val downAt = SystemClock.uptimeMillis()
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var moved = false
                var held = false
                var multi = false
                var multiMoved = false
                var multiTravel = 0f
                var travel = Offset.Zero
                var last = down.position
                try {
                    while (true) {
                        val waitingForHold = !moved && !held && !multi
                        val left = HOLD_MS - (SystemClock.uptimeMillis() - downAt)
                        val event = when {
                            !waitingForHold -> awaitPointerEvent()
                            left <= 0 -> null
                            else -> withTimeoutOrNull(left) { awaitPointerEvent() }
                        }
                        if (event == null) {
                            // Held still: a right-click on release, or drag from here to grab.
                            held = true
                            holding = true
                            buzz(Haptics.Kind.HEAVY)
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) multi = true
                        if (pressed.isEmpty()) break

                        if (multi) {
                            val dy = pressed.map { it.position.y - it.previousPosition.y }.average().toFloat()
                            multiTravel += abs(dy)
                            if (multiTravel > slop) multiMoved = true
                            if (multiMoved && dy != 0f) PointerBus.perform(Action.Scroll(-dy * SCROLL_GAIN))
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val change = event.changes.firstOrNull { it.id == down.id && it.pressed } ?: pressed.first()
                        val delta = change.position - change.previousPosition
                        tracker.addPosition(change.uptimeMillis, change.position)
                        finger = change.position
                        last = change.position
                        if (!moved) {
                            travel += delta
                            if (travel.getDistance() > slop) {
                                moved = true
                                if (held) {
                                    PointerBus.perform(Action.DragStart)
                                    grabbing = true
                                    holding = false
                                    buzz(Haptics.Kind.CLICK)
                                }
                            }
                        }
                        if (moved) {
                            val millis = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1L)
                            val speed = delta.getDistance() / millis
                            val g = (gain * (1f + ACCELERATION * speed)).coerceAtMost(MAX_GAIN * gain / BASE_GAIN)
                            PointerBus.perform(Action.Move(delta.x * g, delta.y * g))
                        }
                        change.consume()
                    }

                    when {
                        multi -> if (!multiMoved) {
                            PointerBus.perform(Action.RightClick)
                            buzz(Haptics.Kind.CLICK)
                            clicked(last)
                        }
                        grabbing -> {
                            PointerBus.perform(Action.DragEnd)
                            buzz(Haptics.Kind.TICK)
                        }
                        held -> {
                            PointerBus.perform(Action.RightClick)
                            clicked(last)
                        }
                        !moved -> {
                            PointerBus.perform(Action.Click)
                            buzz(Haptics.Kind.CLICK)
                            clicked(last)
                        }
                        tuning.glide -> {
                            val velocity = tracker.calculateVelocity()
                            val vx = velocity.x / 1000f
                            val vy = velocity.y / 1000f
                            val speed = hypot(vx, vy)
                            if (speed > GLIDE_MIN_SPEED) {
                                val scale = (GLIDE_MAX_SPEED / speed).coerceAtMost(1f)
                                glide[0] = scope.launch { glide(vx * scale, vy * scale, gain) }
                            }
                        }
                    }
                } finally {
                    if (grabbing) PointerBus.perform(Action.DragEnd)
                    grabbing = false
                    holding = false
                    finger = null
                    PointerBus.touching(false)
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        val dotColor = colors.onSurface.copy(alpha = 0.1f)
        val fingerColor = if (grabbing) colors.secondary else colors.primary
        Canvas(Modifier.fillMaxSize()) {
            // A faint dot grid so it reads as a surface you can touch.
            val step = 22.dp.toPx()
            val area = size
            val points = buildList {
                var y = step / 2
                while (y < area.height) {
                    var x = step / 2
                    while (x < area.width) {
                        add(Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
            drawPoints(points, PointMode.Points, dotColor, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)

            finger?.let { at ->
                drawCircle(fingerColor.copy(alpha = 0.18f), radius = 26.dp.toPx(), center = at)
                drawCircle(fingerColor, radius = if (holding) 9.dp.toPx() else 6.dp.toPx(), center = at)
            }
            if (ripple.value < 1f) {
                drawCircle(
                    color = colors.primary.copy(alpha = 0.6f * (1f - ripple.value)),
                    radius = (10 + 34 * ripple.value).dp.toPx(),
                    center = rippleAt,
                    style = Stroke(2.dp.toPx()),
                )
            }
        }

        if (finger == null && (tuning.hints || !used)) {
            Column(
                modifier = Modifier.padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Touchpad", color = colors.primary, fontFamily = BrandFont, fontSize = 22.sp)
                if (tuning.hints) {
                    Caption(
                        "Drag to move\nTap to click\nHold to right-click\nHold, then drag to grab\nBezel scrolls",
                        color = colors.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** Keeps a flicked cursor moving, slowing like a puck on ice. Stops at the next touch. */
private suspend fun glide(startX: Float, startY: Float, gain: Float) {
    var vx = startX
    var vy = startY
    var last = withFrameMillis { it }
    while (true) {
        val now = withFrameMillis { it }
        val millis = (now - last).coerceIn(1L, 32L).toFloat()
        last = now
        val decay = GLIDE_FRICTION.pow(millis / 16f)
        vx *= decay
        vy *= decay
        if (hypot(vx, vy) < 0.05f) return
        PointerBus.perform(Action.Move(vx * millis * gain, vy * millis * gain))
    }
}
