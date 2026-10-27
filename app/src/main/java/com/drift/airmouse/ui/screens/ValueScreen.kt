package com.drift.airmouse.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.drift.airmouse.R
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.Haptics
import com.drift.airmouse.core.Tuning
import com.drift.airmouse.ui.BackEdgeButton
import com.drift.airmouse.ui.BrandFont
import com.drift.airmouse.ui.Caption
import com.drift.airmouse.ui.LevelBar
import com.drift.airmouse.ui.RoundIcon
import com.drift.airmouse.ui.LocalLook
import com.drift.airmouse.ui.themeArt

private const val CROWN_STEP_PX = 60f

/** A number you can tune. Changes apply instantly, even while steering. */
enum class Knob(
    val title: String,
    val hint: String,
    val range: IntRange,
    val read: (Tuning) -> Int,
    val write: (Tuning, Int) -> Tuning,
) {
    SPEED("Speed", "How far the cursor travels", 1..10, { it.speed }, { t, v -> t.copy(speed = v) }),
    ACCELERATION("Acceleration", "Quick flicks go further", 0..5, { it.acceleration }, { t, v -> t.copy(acceleration = v) }),
    STEADINESS("Steadiness", "Higher hides hand shake", 1..5, { it.steadiness }, { t, v -> t.copy(steadiness = v) }),
    SCROLL("Scroll speed", "Lines per crown turn", 1..10, { it.scrollSpeed }, { t, v -> t.copy(scrollSpeed = v) }),
    PINCH("Pinch sensitivity", "Higher catches lighter pinches", 1..3, { it.pinchSensitivity }, { t, v -> t.copy(pinchSensitivity = v) }),
    SHAKE("Shake sensitivity", "Higher needs a gentler shake", 1..3, { it.shakeSensitivity }, { t, v -> t.copy(shakeSensitivity = v) }),
    TOUCH_SPEED("Touchpad speed", "How far a swipe moves the cursor", 1..10, { it.touchSpeed }, { t, v -> t.copy(touchSpeed = v) }),
}

/** Big number, − and + buttons, and the bezel or crown turns it. */
@Composable
fun ValueScreen(knob: Knob, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val tuning by prefs.tuning.collectAsState()
    val value = knob.read(tuning)
    val focus = remember { FocusRequester() }
    val crown = remember { floatArrayOf(0f) }

    fun change(by: Int) {
        val before = knob.read(prefs.tuning.value)
        prefs.updateTuning { knob.write(it, (knob.read(it) + by).coerceIn(knob.range)) }
        if (knob.read(prefs.tuning.value) != before) {
            Haptics.buzz(context, prefs.tuning.value.vibration, Haptics.Kind.TICK)
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .themeArt(LocalLook.current, strength = 0.5f)
                .onRotaryScrollEvent { event ->
                    crown[0] += event.verticalScrollPixels
                    val steps = (crown[0] / CROWN_STEP_PX).toInt()
                    if (steps != 0) {
                        crown[0] -= steps * CROWN_STEP_PX
                        change(steps)
                    }
                    true
                }
                .focusRequester(focus)
                .focusable()
                // Room at the bottom for the Back button.
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(knob.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundIcon(R.drawable.ic_remove, "Less", { change(-1) }, enabled = value > knob.range.first)
                Text(
                    text = "$value",
                    modifier = Modifier.width(76.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = BrandFont,
                    fontSize = 46.sp,
                    textAlign = TextAlign.Center,
                )
                RoundIcon(R.drawable.ic_add, "More", { change(1) }, enabled = value < knob.range.last)
            }
            Spacer(Modifier.height(8.dp))
            LevelBar(value, knob.range.last)
            Spacer(Modifier.height(8.dp))
            Caption(knob.hint)
            Caption("Turn the bezel to adjust", color = MaterialTheme.colorScheme.outline)
        }
        BackEdgeButton(onBack, Modifier.align(Alignment.BottomCenter))
        }
    }
}
