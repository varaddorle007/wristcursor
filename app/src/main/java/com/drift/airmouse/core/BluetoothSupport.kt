package com.drift.airmouse.core

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Checks once whether this watch can act as a Bluetooth mouse, and remembers the answer. */
object BluetoothSupport {
    suspend fun canBeMouse(context: Context): Boolean {
        val prefs = AppPrefs.get(context)
        prefs.bluetoothMouseSupported?.let { return it }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false

        val supported = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onServiceDisconnected(profile: Int) = Unit
                }
                if (!adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)) {
                    continuation.resume(false)
                }
            }
        } ?: false

        prefs.bluetoothMouseSupported = supported
        return supported
    }
}
