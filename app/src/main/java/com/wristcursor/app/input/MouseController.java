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

package com.wristcursor.app.input;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.Surface;
import com.wristcursor.app.bluetooth.PointerTransport;
import com.wristcursor.app.input.MouseSensorListener.HandMode;
import com.wristcursor.app.input.MouseSensorListener.MouseButton;
import com.wristcursor.app.sensors.SensorService;
import com.wristcursor.app.sensors.SensorServiceConnection;

/** Controls the sensor-based Mouse input behaviour for the corresponding UI. */
public class MouseController {

    /** Callback for the UI. */
    public interface Ui {
        /** Called when the connection with the current device has been lost. */
        void onDeviceDisconnected();
    }

    private final Ui ui;
    private final SettingsUtil settings;
    private final PointerTransport pointerTransport;
    private final MouseSensorListener sensorListener;
    private final SensorServiceConnection connection;

    /**
     * @param context Activity this controller is bound to.
     * @param ui Callback for receiving the UI updates.
     */
    public MouseController(Context context, Ui ui) {
        this.ui = checkNotNull(ui);
        this.settings = new SettingsUtil(context);
        this.pointerTransport = PointerTransport.getInstance();
        this.sensorListener = new MouseSensorListener(pointerTransport);
        this.connection = new SensorServiceConnection(context, this::onServiceConnected);
    }

    /**
     * Should be called in the Activity's (or Fragment's) onCreate() method.
     *
     * @param context The context to register listener with.
     */
    public void onCreate(Context context) {
        sensorListener.onCreate();
        pointerTransport.start(context);
    }

    /** Should be called in the Activity's (or Fragment's) onStart() method. */
    public void onStart() {
        connection.bind();
    }

    /**
     * Should be called in the Activity's (or Fragment's) onStop() method.
     *
     * <p>While armed the binding is deliberately kept. Wear returns to the watch face after a short
     * idle period, and an air mouse is used with your eyes on the computer, not the watch — tearing
     * the sensors down there killed the pointer mid-use. The connected-device foreground service
     * keeps the process alive and unthrottled, so tracking simply continues.
     */
    public void onStop() {
        if (!isArmed()) {
            connection.unbind();
        }
    }

    /**
     * Should be called in the Activity's (or Fragment's) onDestroy() method.
     *
     * @param context The context to unregister listener with.
     */
    public void onDestroy(Context context) {
        // onStop() may have intentionally left the binding open while armed; always release here.
        connection.unbind();
    }

    /**
     * Should be called from a Mouse Button View's OnTouchListener callback.
     *
     * @param leftButton {@code true} if the event came from the Left mouse button, {@code false} if
     *     it was from the right one.
     * @param event Touch event to react to.
     */
    public void onTouch(MotionEvent event, boolean leftButton) {
        final int action = event.getActionMasked();
        final @MouseButton int button = leftButton ? MouseButton.LEFT : MouseButton.RIGHT;
        if (action == MotionEvent.ACTION_DOWN) {
            sendButtonEvent(button, true);
        } else if (action == MotionEvent.ACTION_UP) {
            sendButtonEvent(button, false);
        }
    }

    /**
     * Should be called when an RSB event is detected.
     *
     * @param delta Movement of the Mouse Wheel.
     */
    public void onRotaryInput(float delta) {
        sensorListener.sendMouseMove(0, 0, delta);
    }

    /** Sends a Left Mouse Button click (down + up). */
    public void leftClick() {
        sensorListener.pulseClick(MouseButton.LEFT);
    }

    /** Sends a Right Mouse Button click (down + up). */
    public void rightClick() {
        sensorListener.pulseClick(MouseButton.RIGHT);
    }

    /** Sends a Left Mouse Button "down" event. */
    public void leftClickAndHold() {
        sendButtonEvent(MouseButton.LEFT, true);
    }

    /** Sends a Right Mouse Button "down" event. */
    public void rightClickAndHold() {
        sendButtonEvent(MouseButton.RIGHT, true);
    }

    /** Sends a Middle Mouse Button "down" event immediately followed by an "up" event. */
    public void middleClick() {
        sensorListener.pulseClick(MouseButton.MIDDLE);
    }

    /**
     * Arm or pause cursor tracking (WowMouse-style center toggle).
     *
     * @param armed {@code true} to move the host cursor from wrist motion.
     */
    public void setArmed(boolean armed) {
        sensorListener.setArmed(armed);
    }

    /** @return whether wrist motion currently drives the host cursor. */
    public boolean isArmed() {
        return sensorListener.isArmed();
    }

    /**
     * Sets the current watch location.
     *
     * @param hand Hand index: left wrist, center (in the hand) or right wrist.
     * @see MouseSensorListener
     */
    public void setMouseHand(@HandMode int hand) {
        settings.putMouseHand(hand);
        sensorListener.setHand(hand);
    }

    /**
     * Get the last used watch location from the last time.
     *
     * @return Hand index.
     * @see MouseSensorListener
     */
    public @HandMode int getMouseHand() {
        return settings.getMouseHand();
    }

    private void onServiceConnected(SensorService service) {
        sensorListener.setLefty(isLefty(service.getApplicationContext()));
        sensorListener.setHand(settings.getMouseHand());
        // These are optional settings; honoring them here makes the controls opened from the
        // mouse screen useful rather than resetting them on every reconnect.
        sensorListener.setStabilize(settings.getBoolean(SettingsUtil.SettingKey.STABILIZE));
        service.startInput(
                sensorListener, settings.getBoolean(SettingsUtil.SettingKey.REDUCED_RATE));
        // Warm the first samples so the cursor starts moving immediately.
        sensorListener.onCreate();
    }

    private boolean isLefty(Context context) {
        return Settings.System.getInt(
                        context.getContentResolver(),
                        Settings.System.USER_ROTATION,
                        Surface.ROTATION_0)
                == Surface.ROTATION_180;
    }

    private void sendButtonEvent(int button, boolean state) {
        sensorListener.sendButtonEvent(button, state);
    }
}
