package com.drift.airmouse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.drift.airmouse.core.Bindings
import com.drift.airmouse.core.Haptics
import com.drift.airmouse.core.Pad
import com.drift.airmouse.core.PointerBus
import com.drift.airmouse.core.Remap
import com.drift.airmouse.core.RemoteKey
import com.drift.airmouse.core.Slot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val LONG_MS = 450L
private const val REPEAT_MS = 110L
private const val CENTER_RATIO = 0.40f

/**
 * A round pad: four buttons around the edge and one in the middle, like a TV remote's D-pad.
 * Each button does its short-press action on a tap and its long-press action on a hold;
 * buttons with no long-press action repeat while held if they're arrows or volume.
 * The small icon under each main icon shows what holding it does.
 */
@Composable
fun RingPad(
    pad: Pad,
    bindings: Bindings,
    onKey: (RemoteKey, Haptics.Kind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf<Slot?>(null) }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, maxHeight)
        Box(
            Modifier
                .size(diameter)
                .semantics { contentDescription = "${pad.title} pad" }
                .pointerInput(pad, bindings) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val slot = slotAt(down.position, Size(size.width.toFloat(), size.height.toFloat())) ?: return@awaitEachGesture
                        down.consume()
                        val binding = bindings.of(pad, slot)
                        PointerBus.touching(true)
                        pressed = slot
                        try {
                            val early = withTimeoutOrNull(LONG_MS) { waitForUpOrCancellation() != null }
                            when {
                                early != null -> if (early) onKey(binding.short, Haptics.Kind.CLICK)
                                binding.long != RemoteKey.NONE -> {
                                    onKey(binding.long, Haptics.Kind.HEAVY)
                                    waitForUpOrCancellation()
                                }
                                Remap.repeats(binding.short) -> {
                                    onKey(binding.short, Haptics.Kind.TICK)
                                    while (withTimeoutOrNull(REPEAT_MS) { waitForUpOrCancellation(); true } == null) {
                                        onKey(binding.short, Haptics.Kind.TICK)
                                    }
                                }
                                else -> {
                                    onKey(binding.short, Haptics.Kind.CLICK)
                                    waitForUpOrCancellation()
                                }
                            }
                        } finally {
                            pressed = null
                            PointerBus.touching(false)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val ring = colors.surfaceContainerHigh
            val ringPressed = colors.surfaceContainerHigh.copy(alpha = 1f).let { lerpTo(it, colors.onSurface, 0.18f) }
            val gap = colors.background
            val center = colors.primary
            val centerPressed = lerpTo(colors.primary, colors.onPrimary, 0.2f)
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                val inner = radius * CENTER_RATIO
                drawCircle(ring, radius)
                pressed?.takeIf { it != Slot.CENTER }?.let { slot ->
                    drawArc(
                        color = ringPressed,
                        startAngle = startAngle(slot),
                        sweepAngle = 90f,
                        useCenter = true,
                        topLeft = Offset.Zero,
                        size = size,
                    )
                }
                // Diagonal gaps between the four edge buttons.
                val stroke = 4.dp.toPx()
                listOf(45f, 135f, 225f, 315f).forEach { degrees ->
                    val r = Math.toRadians(degrees.toDouble())
                    val direction = Offset(cos(r).toFloat(), sin(r).toFloat())
                    drawLine(gap, this.center + direction * inner, this.center + direction * radius, strokeWidth = stroke)
                }
                drawCircle(gap, inner + stroke)
                drawCircle(if (pressed == Slot.CENTER) centerPressed else center, inner)
            }

            val half = diameter / 2
            val iconDistance = half * ((1f + CENTER_RATIO) / 2f)
            listOf(Slot.UP, Slot.RIGHT, Slot.DOWN, Slot.LEFT).forEach { slot ->
                val binding = bindings.of(pad, slot)
                val (dx, dy) = direction(slot)
                PadLabel(
                    key = binding.short,
                    hold = binding.long,
                    size = diameter * 0.19f,
                    tint = colors.onSurface,
                    modifier = Modifier.offset(x = iconDistance * dx, y = iconDistance * dy),
                )
            }
            val middle = bindings.of(pad, Slot.CENTER)
            PadLabel(
                key = middle.short,
                hold = middle.long,
                size = diameter * 0.2f,
                tint = colors.onPrimary,
            )
        }
    }
}

/** The main icon (or "OK") with a small icon underneath for the hold action. */
@Composable
private fun PadLabel(key: RemoteKey, hold: RemoteKey, size: Dp, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val icon = keyIcon(key)
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = key.label, modifier = Modifier.size(size), tint = tint)
        } else if (key != RemoteKey.NONE) {
            Text(
                if (key == RemoteKey.OK) "OK" else key.label,
                color = tint,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.62f).sp,
                maxLines = 1,
            )
        }
        keyIcon(hold)?.let {
            Icon(painterResource(it), contentDescription = "Hold: ${hold.label}", modifier = Modifier.size(size * 0.48f), tint = tint.copy(alpha = 0.55f))
        }
    }
}

private fun slotAt(at: Offset, size: Size): Slot? {
    val c = Offset(size.width / 2, size.height / 2)
    val d = at - c
    val radius = size.minDimension / 2
    val distance = d.getDistance()
    return when {
        distance <= radius * CENTER_RATIO -> Slot.CENTER
        distance > radius * 1.04f -> null
        abs(d.x) > abs(d.y) -> if (d.x > 0) Slot.RIGHT else Slot.LEFT
        else -> if (d.y > 0) Slot.DOWN else Slot.UP
    }
}

/** Drawing angle where each edge button's quarter starts (0° is 3 o'clock, clockwise). */
private fun startAngle(slot: Slot): Float = when (slot) {
    Slot.RIGHT -> -45f
    Slot.DOWN -> 45f
    Slot.LEFT -> 135f
    Slot.UP -> 225f
    Slot.CENTER -> 0f
}

private fun direction(slot: Slot): Pair<Float, Float> = when (slot) {
    Slot.UP -> 0f to -1f
    Slot.DOWN -> 0f to 1f
    Slot.LEFT -> -1f to 0f
    Slot.RIGHT -> 1f to 0f
    Slot.CENTER -> 0f to 0f
}

private fun lerpTo(from: androidx.compose.ui.graphics.Color, to: androidx.compose.ui.graphics.Color, amount: Float) =
    androidx.compose.ui.graphics.lerp(from, to, amount)
