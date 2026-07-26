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

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.support.wearable.input.RotaryEncoder;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import com.wristcursor.app.R;
import com.wristcursor.app.input.FingerPinchDetector;
import com.wristcursor.app.input.MouseController;
import com.wristcursor.app.input.MouseSensorListener.HandMode;
import com.wristcursor.app.input.SettingsUtil;
import com.wristcursor.app.input.SettingsUtil.SettingKey;
import com.wristcursor.app.input.ShakeDetector;
import com.wristcursor.app.ui.onboarding.OnboardingController.ScreenKey;
import com.wristcursor.app.ui.onboarding.OnboardingRequest;

/**
 * WowMouse-style air mouse: big center mouse toggle (teal / lime), wrist pointing,
 * IMU finger-pinch clicks, shake to exit.
 */
public class MouseFragment extends Fragment {
    private ImageView mouseIcon;
    private View mouseGlow;
    private TextView mouseStatus;
    private FrameLayout mouseHint;
    private float scrollFactor;
    private MouseController controller;
    private OnboardingRequest onboardingRequest;
    private SettingsUtil settings;
    private ShakeDetector shakeDetector;
    private FingerPinchDetector pinchDetector;
    private Vibrator vibrator;
    private AnimatorSet glowPulse;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getContext();
        settings = new SettingsUtil(context);
        settings.setBoolean(SettingKey.SHOW_CLICK_BUTTONS, false);
        settings.setBoolean(SettingKey.GESTURE_PINCH, true);
        settings.setBoolean(SettingKey.GESTURE_SHAKE_BACK, true);

        controller = new MouseController(context, this::onHostDisconnected);
        controller.onCreate(context);
        controller.setArmed(false);
        vibrator = resolveVibrator(context);

        onboardingRequest = new OnboardingRequest(getActivity(), ScreenKey.MOUSE);
        // Skip old onboarding cards — go straight to WowMouse-style control screen.
        if (!onboardingRequest.isComplete()) {
            onboardingRequest.setComplete();
        }
        // No onboardingRequest.start(this);

        shakeDetector = new ShakeDetector(context, this::onShakeBack);
        pinchDetector = new FingerPinchDetector(context, this::onFingerPinch);
    }

    private static Vibrator resolveVibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager != null ? manager.getDefaultVibrator() : null;
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_mouse, container, false);

        mouseIcon = root.findViewById(R.id.mouse_icon);
        mouseGlow = root.findViewById(R.id.mouse_glow);
        mouseStatus = root.findViewById(R.id.mouse_status);
        mouseHint = root.findViewById(R.id.mouse_hint);
        scrollFactor = -ViewConfiguration.get(getContext()).getScaledVerticalScrollFactor() / 5.0f;

        mouseHint.setOnTouchListener(
                (v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        v.setVisibility(View.GONE);
                    }
                    return true;
                });

        root.findViewById(R.id.btn_help).setOnClickListener(v -> openHelp());
        root.findViewById(R.id.btn_settings).setOnClickListener(v -> openSettings());

        mouseIcon.setOnClickListener(v -> toggleArmed());
        // Backup click when armed (IMU pinch can miss): tap status = left, long-press = right.
        mouseStatus.setOnClickListener(
                v -> {
                    if (controller.isArmed()) {
                        fireClick(false);
                    }
                });
        mouseStatus.setOnLongClickListener(
                v -> {
                    if (controller.isArmed()) {
                        fireClick(true);
                        return true;
                    }
                    return false;
                });
        applyArmedUi(controller.isArmed());

        return root;
    }

    private String handLabel(int hand) {
        switch (hand) {
            case HandMode.LEFT:
                return getString(R.string.mouse_hand_left_short);
            case HandMode.RIGHT:
                return getString(R.string.mouse_hand_right_short);
            default:
                return getString(R.string.mouse_hand_center_short);
        }
    }

    private void toggleArmed() {
        boolean next = !controller.isArmed();
        controller.setArmed(next);
        applyArmedUi(next);
        // While armed the wrist is constantly moving, so shake-to-go-back must stand down.
        if (shakeDetector != null) {
            shakeDetector.setSuppressed(next);
        }
        // Arming is decisive, pausing is a light tick — different weights, so you can tell which
        // way the toggle went without looking.
        haptic(
                next ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_TICK,
                next ? 320L : 260L);
        // Subtle scale pulse
        if (mouseIcon != null) {
            mouseIcon
                    .animate()
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(60)
                    .withEndAction(
                            () ->
                                    mouseIcon
                                            .animate()
                                            .scaleX(1f)
                                            .scaleY(1f)
                                            .setDuration(90)
                                            .start())
                    .start();
        }
    }

    private void applyArmedUi(boolean armed) {
        if (mouseIcon == null || mouseStatus == null || mouseGlow == null) {
            return;
        }
        Context context = getContext();
        if (context == null) {
            return;
        }
        int tint = ContextCompat.getColor(context, armed ? R.color.wow_lime : R.color.wow_teal);
        mouseIcon.setColorFilter(tint);
        mouseGlow.setBackgroundResource(
                armed ? R.drawable.bg_wow_glow : R.drawable.bg_wow_glow_paused);
        mouseStatus.setText(
                armed ? R.string.mouse_status_ready : R.string.mouse_status_paused);
        mouseStatus.setTextColor(
                ContextCompat.getColor(
                        context, armed ? R.color.wow_lime : R.color.text_secondary));
        if (armed) {
            startGlowPulse();
        } else {
            stopGlowPulse();
        }
    }

    /**
     * Slow breathing glow while armed, so the live state is obvious at a glance. Animates only
     * scale and alpha, which the compositor handles off the main thread — no layout work, so it
     * costs the pointer nothing.
     */
    private void startGlowPulse() {
        stopGlowPulse();
        if (mouseGlow == null) {
            return;
        }
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(mouseGlow, View.SCALE_X, 1f, 1.07f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(mouseGlow, View.SCALE_Y, 1f, 1.07f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(mouseGlow, View.ALPHA, 1f, 0.62f);
        for (ObjectAnimator animator : new ObjectAnimator[] {scaleX, scaleY, alpha}) {
            animator.setDuration(1150);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
        }
        glowPulse = new AnimatorSet();
        glowPulse.playTogether(scaleX, scaleY, alpha);
        glowPulse.start();
    }

    private void stopGlowPulse() {
        if (glowPulse != null) {
            glowPulse.cancel();
            glowPulse = null;
        }
        if (mouseGlow != null) {
            mouseGlow.setScaleX(1f);
            mouseGlow.setScaleY(1f);
            mouseGlow.setAlpha(1f);
        }
    }

    private void onFingerPinch(boolean rightClick) {
        if (!controller.isArmed()) {
            return;
        }
        if (!settings.getBoolean(SettingKey.GESTURE_PINCH)) {
            return;
        }
        fireClick(rightClick);
    }

    private void fireClick(boolean rightClick) {
        if (rightClick) {
            controller.rightClick();
            // Double tap = right click. Unmistakable against the single click below.
            haptic(VibrationEffect.EFFECT_DOUBLE_CLICK, 380L);
        } else {
            controller.leftClick();
            haptic(VibrationEffect.EFFECT_CLICK, 300L);
        }
        flashClickFeedback();
    }

    /** Brief visual confirmation that a click was sent, for when the watch is in view. */
    private void flashClickFeedback() {
        if (mouseIcon == null) {
            return;
        }
        mouseIcon.animate().cancel();
        mouseIcon.setScaleX(1f);
        mouseIcon.setScaleY(1f);
        mouseIcon
                .animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(70)
                .withEndAction(
                        () -> {
                            if (mouseIcon != null) {
                                mouseIcon.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                            }
                        })
                .start();
    }

    /**
     * Plays one of the system's tuned haptic primitives. These are crisper than an arbitrary
     * buzz duration, and giving each action its own signature means the click can be identified
     * by feel alone — which matters here, because the user is looking at their computer, not the
     * watch. Left and right click in particular must never feel the same.
     */
    private void haptic(int predefinedEffect, long suppressMs) {
        // Blind the pinch detector for the buzz plus the mechanical ring-down after it, otherwise
        // the accelerometer hears our own vibration motor and fires another click.
        if (pinchDetector != null) {
            pinchDetector.suppressFor(suppressMs);
        }
        try {
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            vibrator.vibrate(VibrationEffect.createPredefined(predefinedEffect));
        } catch (RuntimeException ignored) {
            // Some effects are not supported on every device; a missing buzz is not fatal.
        }
    }

    private void onHostDisconnected() {
        // Stay on the WowMouse screen — do NOT finish(). Old behaviour bounced users
        // straight back to Connect, so they never saw the new UI.
        Context context = getContext();
        if (mouseStatus != null && context != null) {
            mouseStatus.setText(R.string.mouse_status_paused);
            mouseStatus.setTextColor(ContextCompat.getColor(context, R.color.danger));
        }
        if (controller != null && controller.isArmed()) {
            controller.setArmed(false);
            applyArmedUi(false);
        }
        if (context != null) {
            Toast.makeText(context, R.string.pref_bluetooth_disconnected, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private void onShakeBack() {
        if (!settings.getBoolean(SettingKey.GESTURE_SHAKE_BACK)) {
            return;
        }
        Activity activity = getActivity();
        if (activity != null && !activity.isFinishing()) {
            haptic(VibrationEffect.EFFECT_TICK, 260L);
            activity.runOnUiThread(this::goBack);
        }
    }

    private void goBack() {
        haptic(VibrationEffect.EFFECT_TICK, 260L);
        Activity activity = getActivity();
        if (activity != null) {
            activity.finish();
        }
    }

    /** Opens the real settings page rather than treating the gear as another Back button. */
    private void openSettings() {
        // Pause first: otherwise wrist movement continues to drive the computer while the user is
        // interacting with settings on the watch.
        if (controller.isArmed()) {
            controller.setArmed(false);
            applyArmedUi(false);
        }
        if (shakeDetector != null) {
            shakeDetector.setSuppressed(false);
        }
        haptic(VibrationEffect.EFFECT_CLICK, 260L);
        startActivity(new Intent(getActivity(), InputSettingsActivity.class));
    }

    /** Keeps instructions out of the control surface while leaving them one obvious tap away. */
    private void openHelp() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        haptic(VibrationEffect.EFFECT_TICK, 220L);
        new AlertDialog.Builder(context)
                .setTitle(R.string.mouse_help_title)
                .setMessage(R.string.mouse_help_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Opens the complete mode picker from the middle control instead of hiding it in navigation. */
    private void openModeSelector() {
        if (controller.isArmed()) {
            controller.setArmed(false);
            applyArmedUi(false);
        }
        if (shakeDetector != null) {
            shakeDetector.setSuppressed(false);
        }
        haptic(VibrationEffect.EFFECT_CLICK, 260L);
        startActivity(new Intent(getActivity(), ModeSelectActivity.class));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (onboardingRequest.isMyResult(requestCode, data) && resultCode == Activity.RESULT_OK) {
            mouseHint.setVisibility(View.VISIBLE);
            onboardingRequest.setComplete();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onStart() {
        super.onStart();
        controller.onStart();
        shakeDetector.start();
        pinchDetector.start();
    }

    @Override
    public void onResume() {
        super.onResume();
        settings.setBoolean(SettingKey.GESTURE_SHAKE_BACK, true);
        shakeDetector.start();
        shakeDetector.setSuppressed(controller.isArmed());
        pinchDetector.start();
        applyArmedUi(controller.isArmed());
        View view = getView();
        if (view != null) {
            view.requestFocus();
        }
    }

    @Override
    public void onStop() {
        stopGlowPulse();
        shakeDetector.stop();
        // Keep pinch clicking alive while armed — the watch returns to its face after a few
        // seconds of not being touched, and the mouse has to keep working through that.
        if (!controller.isArmed()) {
            pinchDetector.stop();
        }
        controller.onStop();
        super.onStop();
    }

    @Override
    public void onDestroy() {
        controller.onDestroy(getContext());
        super.onDestroy();
    }

    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && RotaryEncoder.isFromRotaryEncoder(ev)) {
            if (controller.isArmed()) {
                controller.onRotaryInput(RotaryEncoder.getRotaryAxisValue(ev) * scrollFactor);
            }
            return true;
        }
        return false;
    }
}
