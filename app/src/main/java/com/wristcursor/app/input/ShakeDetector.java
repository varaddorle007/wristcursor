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

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Detects a deliberate wrist shake / flick to go back.
 * Uses linear acceleration when available so gravity doesn't fight the threshold.
 */
public final class ShakeDetector implements SensorEventListener {
    public interface Listener {
        void onShake();
    }

    /**
     * A single hard flick used to be enough to go back. For an air mouse that is unusable: flicking
     * the wrist is the app's primary input, so aiming quickly closed the app. Going back now needs a
     * genuine oscillating shake — several spikes inside one short window.
     */
    private static final float MEDIUM_LINEAR = 6.5f;
    private static final float MEDIUM_G = 1.6f;
    private static final int MEDIUM_HITS = 3;
    private static final long COOLDOWN_MS = 850L;
    private static final long WINDOW_MS = 450L;
    /** Sensor registration delivers a burst of stale samples; ignore them. */
    private static final long STARTUP_GRACE_MS = 1500L;

    private final SensorManager sensorManager;
    private final Sensor sensor;
    private final boolean linear;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastShakeMs;
    private long windowStartMs;
    private int mediumHits;
    private boolean registered;
    private long startedAtMs;
    private volatile boolean suppressed;

    /**
     * Silence the gesture while the pointer is armed. Aiming is continuous wrist motion, so any
     * shake threshold low enough to be convenient is also low enough to quit mid-use.
     */
    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        mediumHits = 0;
        windowStartMs = 0;
    }

    public ShakeDetector(Context context, Listener listener) {
        this.listener = listener;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor linearSensor =
                sensorManager != null
                        ? sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                        : null;
        if (linearSensor != null) {
            sensor = linearSensor;
            linear = true;
        } else {
            sensor =
                    sensorManager != null
                            ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                            : null;
            linear = false;
        }
    }

    public void start() {
        if (registered || sensorManager == null || sensor == null) {
            return;
        }
        // Fastest sampling so short wrist flicks aren't missed between samples.
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        registered = true;
        mediumHits = 0;
        windowStartMs = 0;
        startedAtMs = System.currentTimeMillis();
    }

    public void stop() {
        if (!registered || sensorManager == null) {
            return;
        }
        sensorManager.unregisterListener(this);
        registered = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float medium;
        float mag;
        if (linear) {
            mag = (float) Math.sqrt(x * x + y * y + z * z);
            medium = MEDIUM_LINEAR;
        } else {
            float gX = x / SensorManager.GRAVITY_EARTH;
            float gY = y / SensorManager.GRAVITY_EARTH;
            float gZ = z / SensorManager.GRAVITY_EARTH;
            mag = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ);
            medium = MEDIUM_G;
        }

        long now = System.currentTimeMillis();
        if (now - lastShakeMs < COOLDOWN_MS) {
            return;
        }
        // Settling grace after start(): registering the sensor delivers a burst of stale/large
        // samples, which used to fire "go back" and close the app the instant it opened.
        if (now - startedAtMs < STARTUP_GRACE_MS) {
            return;
        }
        if (suppressed) {
            return;
        }

        if (mag < medium) {
            return;
        }

        // Two medium pulses within the window (shake-shake).
        if (windowStartMs == 0 || now - windowStartMs > WINDOW_MS) {
            windowStartMs = now;
            mediumHits = 1;
            return;
        }
        mediumHits++;
        if (mediumHits >= MEDIUM_HITS) {
            fire(now);
        }
    }

    private void fire(long now) {
        lastShakeMs = now;
        mediumHits = 0;
        windowStartMs = 0;
        mainHandler.post(listener::onShake);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
