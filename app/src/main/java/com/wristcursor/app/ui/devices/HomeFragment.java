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

import android.app.Fragment;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.support.wearable.preference.WearablePreferenceActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.MainThread;
import com.wristcursor.app.R;
import com.wristcursor.app.bluetooth.HidDataSender;
import com.wristcursor.app.bluetooth.HidDeviceProfile;
import com.wristcursor.app.ui.input.InputActivity;
import com.wristcursor.app.ui.input.InputActivity.InputMode;

/**
 * Home menu only — paired devices live under Connect device.
 * Order: Connect device → Rate → About. Pairing help is available from the Connect screen's ?.
 */
public class HomeFragment extends Fragment {
    private static final String TAG = "HomeFragment";
    private static final String PLAY_WEB =
            "https://play.google.com/store/apps/details?id=com.wristcursor.app";

    private BluetoothAdapter bluetoothAdapter;
    private HidDataSender hidDataSender;
    private boolean bondReceiverRegistered;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_home, container, false);

        bindRow(
                root.findViewById(R.id.row_find),
                R.drawable.ic_bt_search,
                R.string.pref_bluetoothAvailable,
                R.string.pref_bluetoothAvailable_summary,
                v -> openConnectDevice());

        bindRow(
                root.findViewById(R.id.row_review),
                R.drawable.ic_star,
                R.string.pref_review,
                R.string.pref_review_summary,
                v -> openReview());

        bindRow(
                root.findViewById(R.id.row_about),
                R.drawable.ic_bt_ellipsis,
                R.string.pref_appAbout,
                R.string.pref_appAbout_summary,
                v -> openAbout());

        return root;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getContext();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        hidDataSender = HidDataSender.getInstance();
        hidDataSender.register(context, profileListener);
        context.registerReceiver(
                bluetoothStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    @Override
    public void onStart() {
        super.onStart();
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            try {
                bluetoothAdapter.enable();
            } catch (SecurityException e) {
                Log.w(TAG, "Cannot enable Bluetooth", e);
            }
        }
        registerBondReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();
        View view = getView();
        if (view != null) {
            view.requestFocus();
            // Ensure every row stays clickable after returning from another screen
            view.findViewById(R.id.row_find).setClickable(true);
            view.findViewById(R.id.row_review).setClickable(true);
            view.findViewById(R.id.row_about).setClickable(true);
        }
    }

    @Override
    public void onStop() {
        unregisterBondReceiver();
        super.onStop();
    }

    @Override
    public void onDestroy() {
        Context context = getContext();
        if (context != null) {
            try {
                context.unregisterReceiver(bluetoothStateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            hidDataSender.unregister(context, profileListener);
            context.stopService(new Intent(context, NotificationService.class));
        }
        super.onDestroy();
    }

    private void openConnectDevice() {
        WearablePreferenceActivity activity = (WearablePreferenceActivity) getActivity();
        if (activity != null) {
            activity.startPreferenceFragment(new FindComputerFragment(), true);
        }
    }

    private void openAbout() {
        WearablePreferenceActivity activity = (WearablePreferenceActivity) getActivity();
        if (activity != null) {
            activity.startPreferenceFragment(new AboutFragment(), true);
        }
    }

    private void openReview() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        try {
            Intent market =
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.wristcursor.app"));
            startActivity(market);
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_WEB)));
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(context, R.string.review_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void bindRow(
            View row, int iconRes, int titleRes, int subtitleRes, View.OnClickListener listener) {
        ImageView icon = row.findViewById(R.id.row_icon);
        TextView title = row.findViewById(R.id.row_title);
        TextView subtitle = row.findViewById(R.id.row_subtitle);
        icon.setImageResource(iconRes);
        title.setText(titleRes);
        subtitle.setText(subtitleRes);
        row.setClickable(true);
        row.setFocusable(true);
        row.setEnabled(true);
        row.setOnClickListener(listener);
    }

    private void registerBondReceiver() {
        if (bondReceiverRegistered || getContext() == null) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        getContext().registerReceiver(bondReceiver, filter);
        bondReceiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (bondReceiverRegistered && getContext() != null) {
            try {
                getContext().unregisterReceiver(bondReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            bondReceiverRegistered = false;
        }
    }

    private final HidDataSender.ProfileListener profileListener =
            new HidDataSender.ProfileListener() {
                @Override
                @MainThread
                public void onServiceStateChanged(BluetoothProfile proxy) {}

                @Override
                @MainThread
                public void onConnectionStateChanged(BluetoothDevice device, int state) {
                    Context context = getContext();
                    if (context == null) {
                        return;
                    }
                    if (state != BluetoothProfile.STATE_DISCONNECTED) {
                        Intent intent =
                                NotificationService.buildIntent(
                                        DeviceNames.resolve(device, null), state);
                        intent.setClass(context, NotificationService.class);
                        try {
                            context.startForegroundService(intent);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "startForegroundService failed", e);
                            context.startService(intent);
                        }
                    }
                    if (state == BluetoothProfile.STATE_CONNECTED && getActivity() != null) {
                        getActivity()
                                .startActivity(
                                        new Intent(getActivity(), InputActivity.class)
                                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                                .putExtra(
                                                        InputActivity.EXTRA_INPUT_MODE,
                                                        InputMode.MOUSE));
                    }
                }

                @Override
                @MainThread
                public void onAppStatusChanged(boolean registered) {
                    Log.i(TAG, "HID app registered=" + registered);
                }
            };

    private final BroadcastReceiver bondReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // Keep HID profile alive; pairing UI is on Connect device screen
                }
            };

    private final BroadcastReceiver bluetoothStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())
                            && getActivity() != null) {
                        int state =
                                intent.getIntExtra(
                                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                        if (state == BluetoothAdapter.STATE_TURNING_ON
                                || state == BluetoothAdapter.STATE_TURNING_OFF) {
                            startActivity(new Intent(getActivity(), BluetoothStateActivity.class));
                        }
                    }
                }
            };
}
