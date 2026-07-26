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

import static android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP;
import static android.os.PowerManager.FULL_WAKE_LOCK;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.MainThread;
import com.wristcursor.app.R;
import com.wristcursor.app.bluetooth.HidDataSender;
import com.wristcursor.app.bluetooth.HidDeviceProfile;
import com.wristcursor.app.ui.input.InputActivity;
import com.wristcursor.app.ui.input.InputActivity.InputMode;
import android.support.wearable.preference.WearablePreferenceActivity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Find screen with Paired + Nearby sections and reliable pairing. */
public class FindComputerFragment extends Fragment {
    private static final String TAG = "FindComputer";
    private static final int PERMISSION_REQUEST = 42;
    private static final int DISCOVERABLE_REQUEST = 43;
    private static final int ENABLE_BT_REQUEST = 44;
    /** Stay discoverable while Connect screen is open (OEM may still cap dialog text). */
    private static final int DISCOVERABLE_DURATION_SEC = 300;

    private BluetoothAdapter bluetoothAdapter;
    private HidDeviceProfile hidDeviceProfile;
    private HidDataSender hidDataSender;

    private LinearLayout pairedList;
    private LinearLayout nearbyList;
    private TextView pairedEmpty;
    private TextView nearbyEmpty;
    private TextView statusBluetooth;
    private TextView statusMessage;
    private View scanRow;
    private View enableBtRow;
    private TextView scanTitle;

    private BluetoothStateReceiver stateReceiver;
    private BluetoothScanReceiver scanReceiver;
    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean discoverableRequested;

    private final Map<String, BluetoothDevice> nearbyDevices = new LinkedHashMap<>();
    private final Map<String, String> deviceNames = new LinkedHashMap<>();
    private boolean scanning;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_find_computer, container, false);
        pairedList = root.findViewById(R.id.paired_list);
        nearbyList = root.findViewById(R.id.nearby_list);
        pairedEmpty = root.findViewById(R.id.paired_empty);
        nearbyEmpty = root.findViewById(R.id.nearby_empty);
        statusBluetooth = root.findViewById(R.id.status_bluetooth);
        statusMessage = root.findViewById(R.id.status_message);
        scanRow = root.findViewById(R.id.row_scan);
        enableBtRow = root.findViewById(R.id.row_enable_bt);

        ImageView scanIcon = scanRow.findViewById(R.id.row_icon);
        scanTitle = scanRow.findViewById(R.id.row_title);
        TextView scanSubtitle = scanRow.findViewById(R.id.row_subtitle);
        scanIcon.setImageResource(R.drawable.ic_bt_search);
        scanTitle.setText(R.string.pref_bluetoothScan);
        scanSubtitle.setText(R.string.pref_bluetoothScan_summary);
        scanRow.setOnClickListener(v -> onScanClicked());

        ImageView btIcon = enableBtRow.findViewById(R.id.row_icon);
        TextView btTitle = enableBtRow.findViewById(R.id.row_title);
        TextView btSub = enableBtRow.findViewById(R.id.row_subtitle);
        btIcon.setImageResource(R.drawable.ic_bt_bluetooth);
        btTitle.setText(R.string.pref_enable_bluetooth);
        btSub.setText(R.string.pref_enable_bluetooth_summary);
        enableBtRow.setOnClickListener(v -> requestEnableBluetooth());

        root.findViewById(R.id.btn_connect_help).setOnClickListener(v -> openHelp());

        return root;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getContext();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        hidDataSender = HidDataSender.getInstance();
        hidDeviceProfile = hidDataSender.register(context, profileListener);
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        wakeLock =
                powerManager.newWakeLock(
                        FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP, "WristCursor:PokeScreen");
    }

    @Override
    public void onStart() {
        super.onStart();
        // Keep HID SDP registered while pairing (Android requires a visible FGS/activity).
        keepHidVisible(null, BluetoothProfile.STATE_CONNECTING);
        registerStateReceiver();
        updateConnectivityStatus();
        refreshPaired();
        if (!hasAllPermissions()) {
            showMessage(getString(R.string.status_need_permission));
            requestAllPermissions();
        } else {
            ensureBluetoothAndScan();
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateConnectivityStatus();
        refreshPaired();
        View view = getView();
        if (view != null) {
            view.requestFocus();
        }
    }

    @Override
    public void onStop() {
        // Keep bond / discovery receivers alive across system dialogs (discoverable + pair).
        // Unregistering here was the main reason pair→connect never completed on Wear.
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onStop();
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        stopDiscovery();
        unregisterScanReceiver();
        unregisterStateReceiver();
        if (getContext() != null) {
            hidDataSender.unregister(getContext(), profileListener);
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        if (hasAllPermissions()) {
            showMessage(null);
            ensureBluetoothAndScan();
        } else {
            showMessage(getString(R.string.status_need_permission));
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ENABLE_BT_REQUEST || requestCode == DISCOVERABLE_REQUEST) {
            if (requestCode == DISCOVERABLE_REQUEST && resultCode == Activity.RESULT_CANCELED) {
                discoverableRequested = false;
                showMessage(getString(R.string.status_need_discoverable));
            } else if (requestCode == DISCOVERABLE_REQUEST) {
                discoverableRequested = true;
                showMessage(getString(R.string.status_discoverable_ok));
            }
            updateConnectivityStatus();
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                beginScan(true);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void onScanClicked() {
        if (!hasAllPermissions()) {
            showMessage(getString(R.string.status_need_permission));
            requestAllPermissions();
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            showMessage(getString(R.string.status_bt_off));
            requestEnableBluetooth();
            return;
        }
        beginScan(true);
    }

    private void ensureBluetoothAndScan() {
        updateConnectivityStatus();
        refreshPaired();
        if (bluetoothAdapter == null) {
            showMessage(getString(R.string.status_scan_failed));
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            enableBtRow.setVisibility(View.VISIBLE);
            showMessage(getString(R.string.status_bt_off));
            return;
        }
        enableBtRow.setVisibility(View.GONE);
        beginScan(false);
    }

    private void beginScan(boolean clearFirst) {
        if (!hasAllPermissions()) {
            requestAllPermissions();
            return;
        }
        registerScanReceiver();
        if (clearFirst) {
            nearbyDevices.clear();
            refreshNearby();
        }
        // Seed nearby list with unpaired bonded? No — paired section handles bonded.
        // Also add currently bonded names cache
        seedBondedNames();

        stopDiscovery();
        boolean started;
        try {
            started = bluetoothAdapter.startDiscovery();
        } catch (SecurityException e) {
            showMessage(getString(R.string.status_need_permission));
            requestAllPermissions();
            return;
        }
        if (!started) {
            showMessage(getString(R.string.status_scan_failed));
            scanning = false;
            scanTitle.setText(R.string.pref_bluetoothScan);
            return;
        }
        scanning = true;
        scanTitle.setText(R.string.pref_bluetoothScan_scanning);
        showMessage(getString(R.string.status_scanning));
        scanRow.setAlpha(0.75f);
        scanRow.postDelayed(
                () -> {
                    if (scanRow != null) {
                        scanRow.setAlpha(1f);
                    }
                },
                2000);
    }

    private void seedBondedNames() {
        if (bluetoothAdapter == null) {
            return;
        }
        try {
            for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
                deviceNames.put(device.getAddress(), DeviceNames.resolve(device, null));
            }
        } catch (SecurityException ignored) {
        }
    }

    private void requestEnableBluetooth() {
        try {
            startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BT_REQUEST);
        } catch (SecurityException e) {
            requestAllPermissions();
        }
    }

    private void updateConnectivityStatus() {
        if (statusBluetooth == null) {
            return;
        }
        boolean btOn = bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        statusBluetooth.setText(btOn ? R.string.status_bt_on : R.string.status_bt_off);
        statusBluetooth.setTextColor(
                getResources().getColor(btOn ? R.color.accent : R.color.danger, null));
        enableBtRow.setVisibility(btOn ? View.GONE : View.VISIBLE);

    }

    private void openHelp() {
        WearablePreferenceActivity activity = (WearablePreferenceActivity) getActivity();
        if (activity != null) {
            activity.startPreferenceFragment(new HelpFragment(), true);
        }
    }

    private void showMessage(String message) {
        if (statusMessage == null) {
            return;
        }
        if (TextUtils.isEmpty(message)) {
            statusMessage.setVisibility(View.GONE);
            return;
        }
        statusMessage.setVisibility(View.VISIBLE);
        statusMessage.setText(message);
    }

    /** WowMouse-style: jump straight into the air-mouse screen after connect. */
    private void openAirMouse() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        activity.startActivity(
                new Intent(activity, InputActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(InputActivity.EXTRA_INPUT_MODE, InputMode.MOUSE));
    }

    private void refreshPaired() {
        if (pairedList == null || bluetoothAdapter == null || getContext() == null) {
            return;
        }
        pairedList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        Set<BluetoothDevice> bonded;
        try {
            bonded = bluetoothAdapter.getBondedDevices();
        } catch (SecurityException e) {
            pairedEmpty.setVisibility(View.VISIBLE);
            return;
        }
        for (BluetoothDevice device : bonded) {
            String name = DeviceNames.resolve(device, null);
            deviceNames.put(device.getAddress(), name);
            // Remove from nearby if paired
            nearbyDevices.remove(device.getAddress());

            View row = inflater.inflate(R.layout.row_compact_menu_item, pairedList, false);
            ImageView icon = row.findViewById(R.id.row_icon);
            TextView title = row.findViewById(R.id.row_title);
            TextView subtitle = row.findViewById(R.id.row_subtitle);
            icon.setImageResource(DeviceNames.iconRes(device));
            title.setText(name);
            int state = BluetoothProfile.STATE_DISCONNECTED;
            try {
                state = hidDeviceProfile.getConnectionState(device);
            } catch (SecurityException ignored) {
            }
            if (state == BluetoothProfile.STATE_CONNECTED) {
                subtitle.setText(R.string.pref_bluetooth_connected);
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                subtitle.setText(R.string.device_connecting);
            } else {
                subtitle.setText(
                        DeviceNames.typeLabel(device) + " · " + getString(R.string.device_tap_connect));
            }
            row.setOnClickListener(v -> connectPaired(device));
            pairedList.addView(row);
        }
        pairedEmpty.setVisibility(pairedList.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        refreshNearby();
    }

    private void refreshNearby() {
        if (nearbyList == null || getContext() == null) {
            return;
        }
        nearbyList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (BluetoothDevice device : nearbyDevices.values()) {
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                continue;
            }
            View row = inflater.inflate(R.layout.row_compact_menu_item, nearbyList, false);
            ImageView icon = row.findViewById(R.id.row_icon);
            TextView title = row.findViewById(R.id.row_title);
            TextView subtitle = row.findViewById(R.id.row_subtitle);
            icon.setImageResource(DeviceNames.iconRes(device));
            String name = deviceNames.get(device.getAddress());
            if (TextUtils.isEmpty(name)) {
                name = DeviceNames.resolve(device, null);
                deviceNames.put(device.getAddress(), name);
            }
            title.setText(name);
            if (device.getBondState() == BluetoothDevice.BOND_BONDING) {
                subtitle.setText(R.string.device_pairing);
            } else {
                String addr = device.getAddress();
                String tail =
                        addr != null && addr.length() >= 5
                                ? addr.substring(addr.length() - 5)
                                : "";
                subtitle.setText(
                        DeviceNames.typeLabel(device)
                                + " · "
                                + getString(R.string.device_tap_pair)
                                + (TextUtils.isEmpty(tail) ? "" : " · " + tail));
            }
            row.setOnClickListener(v -> pairNearby(device));
            nearbyList.addView(row);
        }
        nearbyEmpty.setVisibility(nearbyList.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void connectPaired(BluetoothDevice device) {
        stopDiscovery();
        try {
            int state = hidDeviceProfile.getConnectionState(device);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                openAirMouse();
            } else {
                showMessage(getString(R.string.device_connecting));
                keepHidVisible(device, BluetoothProfile.STATE_CONNECTING);
                hidDataSender.requestConnect(device);
            }
        } catch (SecurityException e) {
            requestAllPermissions();
        }
    }

    private void pairNearby(BluetoothDevice device) {
        stopDiscovery();
        try {
            showMessage(getString(R.string.pairing_started));
            if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                boolean started = startBond(device);
                Log.i(TAG, "createBond " + device.getAddress() + " => " + started);
                if (!started) {
                    showMessage(getString(R.string.pairing_failed));
                }
                refreshNearby();
            } else if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                keepHidVisible(device, BluetoothProfile.STATE_CONNECTING);
                hidDataSender.requestConnect(device);
            } else if (device.getBondState() == BluetoothDevice.BOND_BONDING) {
                showMessage(getString(R.string.device_pairing));
                refreshNearby();
            }
        } catch (SecurityException e) {
            requestAllPermissions();
        }
    }

    /** Prefer classic BR/EDR bond — HID mouse needs classic, not BLE-only. */
    private boolean startBond(BluetoothDevice device) {
        try {
            // Hidden/overload not always in Wear stubs — invoke via reflection.
            java.lang.reflect.Method method =
                    BluetoothDevice.class.getMethod("createBond", int.class);
            Object result = method.invoke(device, /* TRANSPORT_BREDR */ 1);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "TRANSPORT_BREDR bond unavailable, falling back", e);
        }
        return device.createBond();
    }

    private void connectAfterBond(BluetoothDevice device) {
        if (getContext() == null || device == null) {
            return;
        }
        // Fast path: FGS first, stop inquiry, connect immediately — HidDataSender retries.
        stopDiscovery();
        showMessage(getString(R.string.device_connecting));
        keepHidVisible(device, BluetoothProfile.STATE_CONNECTING);
        try {
            hidDataSender.requestConnect(device);
        } catch (SecurityException e) {
            Log.w(TAG, "Auto-connect failed", e);
            requestAllPermissions();
        }
    }

    private void keepHidVisible(BluetoothDevice device, int state) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        String label =
                device == null ? getString(R.string.app_name) : DeviceNames.resolve(device, null);
        Intent intent = NotificationService.buildIntent(label, state);
        intent.setClass(context, NotificationService.class);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "startForegroundService failed, falling back", e);
            context.startService(intent);
        }
    }

    private void addNearbyDevice(BluetoothDevice device, Intent intent) {
        if (device == null) {
            return;
        }
        // Do NOT filter by HID UUID — Macs/PCs often advertise HID services (keyboard /
        // trackpad) and would be wrongly hidden from Nearby.
        try {
            int bond = device.getBondState();
            if (bond == BluetoothDevice.BOND_BONDED) {
                nearbyDevices.remove(device.getAddress());
                String name = DeviceNames.resolve(device, intent);
                deviceNames.put(device.getAddress(), name);
                refreshPaired();
                return;
            }
        } catch (SecurityException e) {
            requestAllPermissions();
            return;
        }

        String name = DeviceNames.resolve(device, intent);
        String previous = deviceNames.get(device.getAddress());
        if (previous == null
                || previous.startsWith("Unknown")
                || (!name.startsWith("Unknown") && !name.equals(previous))) {
            deviceNames.put(device.getAddress(), name);
        }

        nearbyDevices.put(device.getAddress(), device);
        refreshNearby();
    }

    private boolean hasAllPermissions() {
        Activity activity = getActivity();
        if (activity == null) {
            return false;
        }
        for (String permission : requiredPermissions()) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] requiredPermissions() {
        List<String> list = new ArrayList<>();
        list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN);
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        return list.toArray(new String[0]);
    }

    private void requestAllPermissions() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String permission : requiredPermissions()) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    private void stopDiscovery() {
        scanning = false;
        if (bluetoothAdapter == null) {
            return;
        }
        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
        }
    }

    private void registerScanReceiver() {
        if (scanReceiver != null || getContext() == null) {
            return;
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        intentFilter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_ALIAS_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_UUID);
        intentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        getContext().registerReceiver(scanReceiver = new BluetoothScanReceiver(), intentFilter);

        requestDiscoverable();
    }

    private void requestDiscoverable() {
        if (bluetoothAdapter == null) {
            return;
        }
        // Re-assert discoverable every time (don't one-shot-lock forever).
        try {
            if (BluetoothUtils.setScanMode(
                    bluetoothAdapter,
                    BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                    DISCOVERABLE_DURATION_SEC)) {
                discoverableRequested = true;
                return;
            }
            if (discoverableRequested) {
                return;
            }
            Intent discoverableIntent =
                    new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(
                    BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION_SEC);
            discoverableRequested = true;
            startActivityForResult(discoverableIntent, DISCOVERABLE_REQUEST);
        } catch (SecurityException e) {
            discoverableRequested = false;
            requestAllPermissions();
        }
    }

    private void unregisterScanReceiver() {
        if (scanReceiver != null && getContext() != null) {
            getContext().unregisterReceiver(scanReceiver);
            scanReceiver = null;
            try {
                BluetoothUtils.setScanMode(
                        bluetoothAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE, 0);
            } catch (SecurityException ignored) {
            }
        }
    }

    private void registerStateReceiver() {
        if (stateReceiver != null || getContext() == null) {
            return;
        }
        IntentFilter intentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        getContext().registerReceiver(stateReceiver = new BluetoothStateReceiver(), intentFilter);
    }

    private void unregisterStateReceiver() {
        if (stateReceiver != null && getContext() != null) {
            getContext().unregisterReceiver(stateReceiver);
            stateReceiver = null;
        }
    }

    private class BluetoothScanReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getContext() == null) {
                return;
            }
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            switch (action == null ? "" : action) {
                case BluetoothDevice.ACTION_PAIRING_REQUEST:
                    if (device != null) {
                        confirmPairing(device, intent);
                    }
                    abortBroadcast();
                    break;
                case BluetoothDevice.ACTION_FOUND:
                case BluetoothDevice.ACTION_NAME_CHANGED:
                case BluetoothDevice.ACTION_ALIAS_CHANGED:
                case BluetoothDevice.ACTION_UUID:
                    addNearbyDevice(device, intent);
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    scanning = true;
                    scanTitle.setText(R.string.pref_bluetoothScan_scanning);
                    showMessage(getString(R.string.status_scanning));
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    scanning = false;
                    scanTitle.setText(R.string.pref_bluetoothScan);
                    if (nearbyDevices.isEmpty()) {
                        // Empty is normal — Mac/PC are rarely discoverable. Pair FROM computer.
                        showMessage(getString(R.string.status_scan_empty));
                    } else {
                        showMessage(
                                nearbyDevices.size()
                                        + " nearby — or pair FROM Mac/PC for best results");
                    }
                    refreshNearby();
                    refreshPaired();
                    // Keep watch discoverable so computer-side pairing still works.
                    requestDiscoverable();
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    if (device == null) {
                        break;
                    }
                    int bond =
                            intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    int prev =
                            intent.getIntExtra(
                                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                                    BluetoothDevice.BOND_NONE);
                    deviceNames.put(device.getAddress(), DeviceNames.resolve(device, intent));
                    Log.i(
                            TAG,
                            "Bond "
                                    + device.getAddress()
                                    + " "
                                    + prev
                                    + " → "
                                    + bond);
                    if (bond == BluetoothDevice.BOND_BONDED) {
                        nearbyDevices.remove(device.getAddress());
                        showMessage(getString(R.string.device_connecting));
                        connectAfterBond(device);
                        refreshPaired();
                    } else if (bond == BluetoothDevice.BOND_NONE) {
                        int reason =
                                intent.getIntExtra(
                                        "android.bluetooth.device.extra.REASON", -1);
                        Log.w(TAG, "Bond failed/removed reason=" + reason);
                        showMessage(getString(R.string.pairing_failed));
                        addNearbyDevice(device, intent);
                        refreshNearby();
                        refreshPaired();
                    } else if (bond == BluetoothDevice.BOND_BONDING) {
                        nearbyDevices.put(device.getAddress(), device);
                        showMessage(getString(R.string.device_pairing));
                        refreshNearby();
                    }
                    break;
                default: // fall out
            }
        }
    }

    private void confirmPairing(BluetoothDevice device, Intent intent) {
        try {
            int variant =
                    intent.getIntExtra(
                            BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
            Log.i(TAG, "PAIRING_REQUEST variant=" + variant + " for " + device.getAddress());
            switch (variant) {
                case BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION:
                case 3: // PAIRING_VARIANT_CONSENT (not in Wear stubs)
                    device.setPairingConfirmation(true);
                    break;
                case BluetoothDevice.PAIRING_VARIANT_PIN:
                    // Common default PIN for simple devices; host may still show its own UI.
                    device.setPin(new byte[] {'0', '0', '0', '0'});
                    device.setPairingConfirmation(true);
                    break;
                default:
                    device.setPairingConfirmation(true);
                    break;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot confirm pairing", e);
        }
    }

    private class BluetoothStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                updateConnectivityStatus();
                int state =
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
                if (state == BluetoothAdapter.STATE_ON && hasAllPermissions()) {
                    beginScan(false);
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    stopDiscovery();
                    nearbyDevices.clear();
                    refreshNearby();
                    refreshPaired();
                    showMessage(getString(R.string.status_bt_off));
                }
            }
        }
    }

    private final HidDataSender.ProfileListener profileListener =
            new HidDataSender.ProfileListener() {
                @Override
                @MainThread
                public void onServiceStateChanged(BluetoothProfile proxy) {
                    refreshPaired();
                }

                @Override
                @MainThread
                public void onConnectionStateChanged(BluetoothDevice device, int state) {
                    refreshPaired();
                    if (state == BluetoothProfile.STATE_CONNECTED && getActivity() != null) {
                        showMessage(null);
                        keepHidVisible(device, state);
                        openAirMouse();
                    } else if (state == BluetoothProfile.STATE_CONNECTING) {
                        showMessage(getString(R.string.device_connecting));
                        keepHidVisible(device, state);
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED
                            && hidDataSender.isConnectingOrConnected()) {
                        showMessage(getString(R.string.device_connecting));
                    }
                }

                @Override
                @MainThread
                public void onAppStatusChanged(boolean registered) {
                    // Do not finish() — SDP can briefly unregister on Wear; recover instead.
                    Log.i(TAG, "HID app registered=" + registered);
                }
            };
}
