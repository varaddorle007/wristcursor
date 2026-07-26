/*
 * Copyright 2018 Google LLC All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wristcursor.app.ui.devices;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import androidx.annotation.Nullable;

/** Resolves human-readable Bluetooth device names. */
final class DeviceNames {
    private DeviceNames() {}

    static String resolve(BluetoothDevice device, @Nullable Intent intent) {
        if (device == null) {
            return "Unknown device";
        }

        String name = null;
        if (intent != null) {
            name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
        }

        if (TextUtils.isEmpty(name) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                name = device.getAlias();
            } catch (SecurityException ignored) {
            }
        }

        if (TextUtils.isEmpty(name)) {
            try {
                name = device.getName();
            } catch (SecurityException ignored) {
            }
        }

        if (TextUtils.isEmpty(name)) {
            String address = device.getAddress();
            if (address != null && address.length() >= 5) {
                return "Unknown (" + address.substring(address.length() - 5) + ")";
            }
            return "Unknown device";
        }
        return name.trim();
    }

    static String typeLabel(BluetoothDevice device) {
        BluetoothClass btClass = null;
        try {
            btClass = device.getBluetoothClass();
        } catch (SecurityException ignored) {
        }
        if (btClass == null) {
            return "Nearby device";
        }
        switch (btClass.getMajorDeviceClass()) {
            case BluetoothClass.Device.Major.PHONE:
                return "Phone";
            case BluetoothClass.Device.Major.COMPUTER:
                return "Computer";
            case BluetoothClass.Device.Major.AUDIO_VIDEO:
                return "Audio device";
            case BluetoothClass.Device.Major.WEARABLE:
                return "Wearable";
            case BluetoothClass.Device.Major.PERIPHERAL:
                return "Peripheral";
            default:
                return "Nearby device";
        }
    }

    static int iconRes(BluetoothDevice device) {
        BluetoothClass btClass = null;
        try {
            btClass = device.getBluetoothClass();
        } catch (SecurityException ignored) {
        }

        // Name hint first — MacBooks sometimes report odd classes.
        String name = resolve(device, null).toLowerCase();
        if (name.contains("macbook")
                || name.contains("laptop")
                || name.contains("notebook")
                || name.contains("imac")
                || name.contains("mac mini")
                || name.contains("desktop")
                || name.contains("pc")) {
            return com.wristcursor.app.R.drawable.ic_bt_laptop;
        }

        if (btClass == null) {
            return com.wristcursor.app.R.drawable.ic_bt_bluetooth;
        }
        switch (btClass.getMajorDeviceClass()) {
            case BluetoothClass.Device.Major.PHONE:
                return com.wristcursor.app.R.drawable.ic_bt_phone;
            case BluetoothClass.Device.Major.COMPUTER:
                return com.wristcursor.app.R.drawable.ic_bt_laptop;
            case BluetoothClass.Device.Major.AUDIO_VIDEO:
                return com.wristcursor.app.R.drawable.ic_bt_headset;
            case BluetoothClass.Device.Major.WEARABLE:
                return com.wristcursor.app.R.drawable.ic_bt_watch;
            default:
                return com.wristcursor.app.R.drawable.ic_bt_bluetooth;
        }
    }
}
