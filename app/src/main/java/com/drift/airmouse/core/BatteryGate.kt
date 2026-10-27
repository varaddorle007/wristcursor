package com.drift.airmouse.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

const val MIN_BATTERY_PERCENT = 15

data class GateStatus(val allowed: Boolean, val reason: String?, val batteryPercent: Int)

/** Drift never runs under battery saver or below [MIN_BATTERY_PERCENT]. */
object BatteryGate {
    fun read(context: Context): GateStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val powerSave = context.getSystemService(PowerManager::class.java).isPowerSaveMode

        val reason = when {
            powerSave -> "Battery saver is on"
            percent < MIN_BATTERY_PERCENT -> "Battery below $MIN_BATTERY_PERCENT%"
            else -> null
        }
        return GateStatus(reason == null, reason, percent)
    }
}
