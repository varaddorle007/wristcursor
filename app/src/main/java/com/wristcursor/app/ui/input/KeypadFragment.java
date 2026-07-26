/*
 * Copyright 2026 WristCursor
 */

package com.wristcursor.app.ui.input;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.support.wearable.input.RotaryEncoder;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import com.wristcursor.app.R;
import com.wristcursor.app.input.KeyboardHelper.Key;
import com.wristcursor.app.input.KeypadController;

/** Direct Bluetooth keyboard remote for navigating a paired computer. */
public class KeypadFragment extends Fragment {
    private KeypadController controller;
    private float scrollFactor;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getContext();
        controller = new KeypadController(context, new KeypadUi(), key -> "");
        controller.onCreate(context);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_keypad, container, false);
        scrollFactor = -ViewConfiguration.get(getContext()).getScaledVerticalScrollFactor() / 5.0f;

        root.findViewById(R.id.remote_up).setOnClickListener(v -> send(Key.UP));
        root.findViewById(R.id.remote_left).setOnClickListener(v -> send(Key.LEFT));
        root.findViewById(R.id.remote_select).setOnClickListener(v -> send(Key.ENTER));
        root.findViewById(R.id.remote_right).setOnClickListener(v -> send(Key.RIGHT));
        root.findViewById(R.id.remote_down).setOnClickListener(v -> send(Key.DOWN));
        root.findViewById(R.id.remote_escape).setOnClickListener(v -> send(Key.ESCAPE));
        root.findViewById(R.id.remote_tab).setOnClickListener(v -> send(Key.TAB));
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        controller.onResume();
        View view = getView();
        if (view != null) {
            view.requestFocus();
        }
    }

    @Override
    public void onDestroy() {
        controller.onDestroy(getContext());
        super.onDestroy();
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL && RotaryEncoder.isFromRotaryEncoder(event)) {
            controller.onRotaryInput(RotaryEncoder.getRotaryAxisValue(event) * scrollFactor);
            return true;
        }
        return false;
    }

    private void send(@Key int key) {
        controller.sendRemoteKey(key);
    }

    private class KeypadUi implements KeypadController.Ui {
        @Override
        public void showUsageHint() {}

        @Override
        public void showDismissOverlay() {}

        @Override
        public void setPointerPosition(float x, float y) {}

        @Override
        public void resetPointerPosition() {}

        @Override
        public void setCenterText(String text, boolean is8Way) {}

        @Override
        public void onDeviceDisconnected() {
            Activity activity = getActivity();
            if (activity != null) {
                activity.finish();
            }
        }
    }
}
