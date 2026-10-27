package com.drift.airmouse.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.Haptics
import com.drift.airmouse.core.PinchAction
import com.drift.airmouse.core.ShakeAction
import com.drift.airmouse.core.Vibration
import com.drift.airmouse.core.Power
import com.drift.airmouse.core.Tuning
import com.drift.airmouse.ui.BackEdgeButton
import com.drift.airmouse.ui.Caption
import com.drift.airmouse.ui.ListScreen
import com.drift.airmouse.ui.RowButton
import com.drift.airmouse.ui.lookFor

/** Every setting applies instantly, even while you're steering. */
@Composable
fun SettingsScreen(
    onKnob: (Knob) -> Unit,
    onThemes: () -> Unit,
    onDevices: () -> Unit,
    onCalibrate: () -> Unit,
    onRemap: () -> Unit,
    onPairWifi: () -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val tuning by prefs.tuning.collectAsState()
    val computer by prefs.computer.collectAsState()
    val bluetoothHost by prefs.bluetoothHost.collectAsState()
    val theme by prefs.theme.collectAsState()
    val calibration by prefs.calibration.collectAsState()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    fun update(transform: (Tuning) -> Tuning) = prefs.updateTuning(transform)

    ListScreen(edgeButton = { BackEdgeButton(onBack) }) {
        item { ListHeader { Text("Settings") } }
        item { RowButton("Theme", onThemes, secondary = lookFor(theme).label) }
        item {
            val transport by prefs.transport.collectAsState()
            RowButton("Connection", onDevices, secondary = transport.label)
        }

        section("Gestures")
        item { RowButton("Calibrate", onCalibrate, secondary = if (calibration != null) "Tuned to your wrist" else "Not done yet") }
        item {
            Toggle("Pinch gestures", "Tap finger and thumb to click", tuning.pinch) { on ->
                update { it.copy(pinch = on) }
            }
        }
        if (tuning.pinch) {
            // Tap a row to cycle through what that pinch does (Mouse mode).
            item {
                RowButton("1 pinch", { update { it.copy(pinchOnce = PinchAction.next(it.pinchOnce, PinchAction.forTaps)) } }, secondary = tuning.pinchOnce.label)
            }
            item {
                RowButton("2 pinches", { update { it.copy(pinchTwice = PinchAction.next(it.pinchTwice, PinchAction.forTaps)) } }, secondary = tuning.pinchTwice.label)
            }
            item {
                RowButton(
                    "Pinch & hold",
                    { update { it.copy(pinchHold = PinchAction.next(it.pinchHold, PinchAction.forHold)) } },
                    secondary = if (tuning.pinchHold == PinchAction.HOLD) "Hold button to drag" else tuning.pinchHold.label,
                )
            }
            knobRow(Knob.PINCH, tuning, onKnob)
            item { Caption("Slides: 1 pinch next, 2 back. Media: 1 pinch play, 2 next.") }
        }
        item {
            RowButton("Shake wrist", { update { it.copy(shake = it.shake.next) } }, secondary = tuning.shake.label)
        }
        if (tuning.shake != ShakeAction.OFF) knobRow(Knob.SHAKE, tuning, onKnob)

        section("Touchpad")
        knobRow(Knob.TOUCH_SPEED, tuning, onKnob)
        item {
            Toggle("Glide", "A quick flick keeps the cursor moving", tuning.glide) { on -> update { it.copy(glide = on) } }
        }
        item {
            Toggle("Gesture hints", "Show tips on the touchpad", tuning.hints) { on -> update { it.copy(hints = on) } }
        }

        section("TV remote & media")
        item { RowButton("Remap buttons", onRemap, secondary = "Customize button actions") }

        section("Motion")
        knobRow(Knob.SPEED, tuning, onKnob)
        knobRow(Knob.ACCELERATION, tuning, onKnob)
        knobRow(Knob.STEADINESS, tuning, onKnob)
        item {
            val next = Power.entries[(tuning.power.ordinal + 1) % Power.entries.size]
            RowButton("Power", { update { it.copy(power = next) } }, secondary = "${tuning.power.label} · ${tuning.power.detail}")
        }
        item {
            Toggle("Right wrist", "Flips up and down", tuning.rightWrist) { on -> update { it.copy(rightWrist = on) } }
        }

        section("Clicks & scroll")
        knobRow(Knob.SCROLL, tuning, onKnob)
        item {
            Toggle("Reverse scroll", "Turn the other way", tuning.reverseScroll) { on ->
                update { it.copy(reverseScroll = on) }
            }
        }
        item {
            Toggle("Hold to drag", "Instead of right-click", tuning.holdDrags) { on ->
                update { it.copy(holdDrags = on) }
            }
        }
        item {
            val level = tuning.vibration
            RowButton(
                "Vibration",
                {
                    val next = level.next
                    update { it.copy(vibration = next) }
                    Haptics.buzz(context, next, Haptics.Kind.HEAVY)
                },
                secondary = if (level == Vibration.OFF) "Off" else "${level.label} · ${level.percent}%",
            )
        }

        section("Battery")
        item {
            Toggle("Dim while steering", "Saves the most battery", tuning.dimScreen) { on ->
                update { it.copy(dimScreen = on) }
            }
        }
        item {
            Toggle("Auto-stop", "After 5 quiet minutes", tuning.autoStop) { on -> update { it.copy(autoStop = on) } }
        }

        section("Computers")
        item { RowButton("Wi-Fi computer", onPairWifi, secondary = computer?.name ?: "Not paired") }
        bluetoothHost?.let { host ->
            item { RowButton("Forget ${host.name}", { prefs.saveBluetoothHost(null) }, secondary = "Bluetooth computer") }
        }

        section("Drift")
        item { RowButton("Reset settings", { update { Tuning() } }) }
        item { RowButton("Terms", onTerms) }
        item { RowButton("Privacy", onPrivacy) }
        item { RowButton("Close Drift", onClose) }
        item { Caption("Version ${version.orEmpty()}") }
    }
}

private fun ScalingLazyListScope.section(title: String) {
    item {
        Text(
            text = title.uppercase(),
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

private fun ScalingLazyListScope.knobRow(knob: Knob, tuning: Tuning, onKnob: (Knob) -> Unit) {
    item { RowButton(knob.title, { onKnob(knob) }, secondary = "${knob.read(tuning)} of ${knob.range.last}") }
}

@Composable
private fun Toggle(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontWeight = FontWeight.Bold) },
        secondaryLabel = { Text(detail) },
    )
}
