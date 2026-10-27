package com.drift.airmouse.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** How hard the watch buzzes on taps and clicks. */
enum class Vibration(val label: String, val percent: Int) {
    OFF("Off", 0),
    LIGHT("Light", 25),
    MEDIUM("Medium", 50),
    STRONG("Strong", 75),
    MAX("Max", 100);

    val next: Vibration get() = entries[(ordinal + 1) % entries.size]
}

/** One place that buzzes, at the strength picked in Settings. */
object Haptics {
    enum class Kind(val millis: Long, val fallback: Int) {
        // Watch motors need ~20 ms to spin up, so anything shorter is barely felt.
        TICK(22, VibrationEffect.EFFECT_TICK),
        CLICK(38, VibrationEffect.EFFECT_CLICK),
        HEAVY(65, VibrationEffect.EFFECT_HEAVY_CLICK),
    }

    @Volatile
    private var vibrator: Vibrator? = null

    /**
     * Sensor time (elapsed-realtime nanos) until which the motor may still be shaking the watch.
     * Pinch detection ignores that window, or each buzz would read as another pinch.
     */
    @Volatile
    var shakingUntilNanos = 0L
        private set

    fun buzz(context: Context, level: Vibration, kind: Kind = Kind.CLICK) {
        if (level == Vibration.OFF) return
        val motor = vibrator ?: find(context).also { vibrator = it } ?: return
        val effect = if (motor.hasAmplitudeControl()) {
            // Even Light must be clearly felt: strength runs from about half power up to full.
            VibrationEffect.createOneShot(kind.millis, (MIN_AMPLITUDE + (255 - MIN_AMPLITUDE) * level.percent / 100).coerceIn(1, 255))
        } else {
            VibrationEffect.createPredefined(kind.fallback)
        }
        shakingUntilNanos = SystemClock.elapsedRealtimeNanos() + (kind.millis + SETTLE_MS) * 1_000_000
        runCatching { motor.vibrate(effect) }
    }

    private const val MIN_AMPLITUDE = 90
    /** The watch keeps ringing a little after the motor stops. */
    private const val SETTLE_MS = 90L

    private fun find(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
}
