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

package com.wristcursor.app.ui.input;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.wearable.preference.WearablePreferenceActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.wristcursor.app.R;
import com.wristcursor.app.bluetooth.HidDataSender;
import com.wristcursor.app.input.KeyboardInputController;
import com.wristcursor.app.ui.devices.FindComputerFragment;
import com.wristcursor.app.ui.devices.NotificationService;
import com.wristcursor.app.ui.input.InputActivity.InputMode;

/** Branded mode picker matching the WristCursor preview UI. */
public class ModeMenuFragment extends Fragment {
    private static final int INPUT_REQUEST_CODE = 1;

    private KeyboardInputController keyboardController;
    private HidDataSender hidDataSender;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_mode_menu, container, false);

        WearablePreferenceActivity activity = (WearablePreferenceActivity) getActivity();
        activity.setAmbientEnabled();
        hidDataSender = HidDataSender.getInstance();

        bindRow(
                root.findViewById(R.id.row_mouse),
                R.drawable.ic_ms_mouse,
                R.string.pref_inputMouse,
                R.string.pref_inputMouse_summary,
                v -> openInput(InputMode.MOUSE));
        bindRow(
                root.findViewById(R.id.row_touchpad),
                R.drawable.ic_ms_touchpad,
                R.string.pref_inputTouchpad,
                R.string.pref_inputTouchpad_summary,
                v -> openInput(InputMode.TOUCHPAD));
        bindRow(
                root.findViewById(R.id.row_cursor),
                R.drawable.ic_ms_keypad,
                R.string.pref_inputCursor,
                R.string.pref_inputCursor_summary,
                v -> openInput(InputMode.KEYPAD));

        // Keep the picker usable during a brief HID reconnect; it is also where users recover
        // from a disconnected pointer mode.
        keyboardController = new KeyboardInputController(() -> {});
        keyboardController.onCreate(getContext());

        View keyboardRow = root.findViewById(R.id.row_keyboard);
        Intent keyboardIntent = keyboardController.getInputIntent(getContext().getPackageManager());
        bindRow(
                keyboardRow,
                R.drawable.ic_ms_keyboard,
                R.string.pref_inputKeyboard,
                R.string.pref_inputKeyboard_summary,
                v -> {
                    if (keyboardIntent != null) {
                        startActivityForResult(keyboardIntent, INPUT_REQUEST_CODE);
                    }
                });
        keyboardRow.setEnabled(keyboardIntent != null);
        keyboardRow.setAlpha(keyboardIntent != null ? 1f : 0.4f);

        bindRow(
                root.findViewById(R.id.row_settings),
                R.drawable.ic_bt_ellipsis,
                R.string.pref_inputSettings,
                R.string.pref_inputSettings_summary,
                v ->
                        activity.startPreferenceFragment(
                                new InputSettingsFragment(), true));

        bindRow(
                root.findViewById(R.id.row_disconnect),
                R.drawable.ic_disconnect,
                R.string.pref_disconnect,
                R.string.pref_disconnect_summary,
                v -> disconnectDevice());

        return root;
    }

    private void disconnectDevice() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        Toast.makeText(context, R.string.pref_bluetooth_disconnecting, Toast.LENGTH_SHORT).show();
        hidDataSender.requestConnect(null);
        try {
            context.stopService(new Intent(context, NotificationService.class));
        } catch (RuntimeException ignored) {
        }
        WearablePreferenceActivity activity = (WearablePreferenceActivity) getActivity();
        if (activity != null) {
            activity.startPreferenceFragment(new FindComputerFragment(), true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (keyboardController != null) {
            keyboardController.onResume();
        }
        View view = getView();
        if (view != null) {
            view.requestFocus();
        }
    }

    @Override
    public void onDestroy() {
        if (keyboardController != null) {
            keyboardController.onDestroy(getContext());
        }
        super.onDestroy();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == INPUT_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            keyboardController.onActivityResult(data);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void openInput(int inputMode) {
        startActivity(
                new Intent(getActivity(), InputActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(InputActivity.EXTRA_INPUT_MODE, inputMode));
    }

    private void bindRow(
            View row, int iconRes, int titleRes, int subtitleRes, View.OnClickListener listener) {
        ImageView icon = row.findViewById(R.id.row_icon);
        TextView title = row.findViewById(R.id.row_title);
        TextView subtitle = row.findViewById(R.id.row_subtitle);
        icon.setImageResource(iconRes);
        title.setText(titleRes);
        subtitle.setText(subtitleRes);
        row.setOnClickListener(listener);
    }
}
