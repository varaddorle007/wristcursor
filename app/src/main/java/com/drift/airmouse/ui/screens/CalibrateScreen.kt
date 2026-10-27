package com.drift.airmouse.ui.screens

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.drift.airmouse.core.Action
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.PointerBus
import com.drift.airmouse.core.Calibrator
import com.drift.airmouse.core.Haptics
import com.drift.airmouse.ui.BrandFont
import com.drift.airmouse.ui.Caption
import com.drift.airmouse.ui.LocalLook
import com.drift.airmouse.ui.themeArt

/**
 * Tunes Drift to this wrist: hold still (gyro drift and resting shake), then pinch three
 * times (how hard you pinch). Runs once after setup and any time from Settings.
 */
@Composable
fun CalibrateScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember { AppPrefs.get(context) }
    var phase by remember { mutableStateOf(Calibrator.Phase.STILL) }
    var stillProgress by remember { mutableFloatStateOf(0f) }
    var pinches by remember { mutableIntStateOf(0) }

    // Sensors run only while this screen is showing.
    DisposableEffect(Unit) {
        val calibrator = Calibrator()
        phase = calibrator.phase
        pinches = 0
        stillProgress = 0f
        val sensors = context.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    calibrator.onGyro(v[0], v[1], v[2])
                    return
                }
                val counted = calibrator.onAccel(v[0], v[1], v[2], event.timestamp)
                stillProgress = calibrator.stillProgress(event.timestamp)
                if (counted) {
                    pinches = calibrator.pinches
                    Haptics.buzz(context, prefs.tuning.value.vibration, Haptics.Kind.CLICK)
                }
                if (calibrator.phase != phase) {
                    phase = calibrator.phase
                    if (phase == Calibrator.Phase.DONE) {
                        calibrator.result()?.let(prefs::saveCalibration)
                        // You're pointing at the middle: put the cursor there too (Wi-Fi only).
                        PointerBus.perform(Action.Center)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(listener, it, 10_000) }
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(listener, it, 10_000) }
        onDispose { sensors.unregisterListener(listener) }
    }

    ScreenScaffold {
        Box(Modifier.fillMaxSize().themeArt(LocalLook.current, strength = 0.5f)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                when (phase) {
                    Calibrator.Phase.STILL -> {
                        Title("Aim at center")
                        CircularProgressIndicator(progress = { stillProgress }, modifier = Modifier.size(44.dp))
                        Caption("Point your arm at the middle of your screen and hold still.")
                    }
                    Calibrator.Phase.PINCH -> {
                        Title("Pinch 3×")
                        Dots(pinches)
                        Caption("Tap your thumb and index finger together, like a click.")
                    }
                    Calibrator.Phase.DONE -> {
                        Title("All set")
                        Dots(Calibrator.PINCHES)
                        Caption("Drift now knows your pinch. Redo this anytime in Settings.")
                    }
                }
            }
            EdgeButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.BottomCenter),
                buttonSize = EdgeButtonSize.Small,
            ) {
                Text(if (phase == Calibrator.Phase.DONE) "Done" else "Skip", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, fontFamily = BrandFont, fontSize = 26.sp)
}

@Composable
private fun Dots(filled: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(Calibrator.PINCHES) { i ->
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (i < filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            )
        }
    }
}
