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
import android.os.SystemClock;

/**
 * Thumb–index pinch tap detector (WowMouse-style) using IMU peak + jerk + optional gyro.
 *
 * <p>Not Doublepoint ML — tuned for Galaxy Watch deliberate pinches. Palm-up + pinch = right click.
 */
public final class FingerPinchDetector implements SensorEventListener {
    public interface Listener {
        /** @param rightClick {@code true} if palm was roughly up when the pinch fired. */
        void onPinch(boolean rightClick);
    }

    // A pinch is a short, sharp acceleration transient made by the fingers while the wrist stays
    // more or less still. Aiming the cursor is the opposite: lots of wrist rotation, but smooth
    // acceleration. So gyro is used to *suppress* detection during wrist motion rather than to
    // encourage it — which in turn lets these accel thresholds sit low enough to catch a light,
    // deliberate pinch.
    private static final float PEAK_LINEAR = 2.0f; // m/s², wrist steady
    private static final float PEAK_G_DELTA = 0.20f; // accelerometer fallback equivalent
    /**
     * Minimum rise rate, in m/s² per second, for a spike to count as a finger tap rather than a
     * slow arm movement. This is a *corroborating* requirement on top of amplitude, never a trigger
     * on its own: at 200 Hz a sample gap is 5 ms, so the old "jerk >= 22" test was satisfied by a
     * per-sample change of 0.11 m/s² — below the sensor's own noise floor. That is what made the
     * app click continuously. A real pinch rises several m/s² in 10-20 ms, i.e. of order 200.
     *
     * <p>Android actually delivers these samples at ~100 Hz, not the 200 Hz requested, so a sample
     * gap is ~10 ms. At that spacing 120 demanded a 1.2 m/s² jump between consecutive samples,
     * which combined with the amplitude gate was strict enough to miss real pinches. 55 asks for a
     * 0.55 m/s² jump — still far above the noise floor, but reachable by a normal firm pinch.
     */
    private static final float MIN_RISE_RATE = 55f;
    /** Wrist clearly being swung — demand a much sharper spike before believing it's a pinch. */
    private static final float GYRO_VETO = 1.2f; // rad/s
    /** Wrist genuinely still — full sensitivity. */
    private static final float GYRO_STEADY = 0.45f; // rad/s
    private static final float GYRO_VETO_SCALE = 3.0f;
    /** Hold the recent gyro peak so a decelerating swing still suppresses. */
    private static final long GYRO_HOLD_MS = 120L;
    private static final long PEAK_WINDOW_MS = 220L;
    private static final long COOLDOWN_MS = 280L;
    private static final float PALM_UP_Z = 5.5f;
    private static final float BASELINE_ALPHA = 0.08f;

    /**
     * Flip to true to build a trace APK. Every motion and gyro sample is written to
     * {@code /sdcard/Android/data/com.wristcursor.app/files/pinch_trace.csv}, which can be pulled
     * with adb and used to fit the thresholds below to real recorded pinches instead of estimates.
     * Never ship this enabled — it writes continuously.
     */
    private static final boolean TRACE = false;

    private final SensorManager sensorManager;
    private final Context appContext;
    private java.io.Writer traceWriter;
    private int traceLines;

    private final Sensor motionSensor;
    private final Sensor gravitySensor;
    private final Sensor gyroSensor;
    private final boolean linear;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean registered;
    private long lastPinchMs;
    private long peakAtMs;
    private float peakMag;
    private float lastMag;
    private long lastMagAtMs;
    private float baselineMag;
    private float gravityZ;
    private boolean hasGravity;
    private boolean pendingPeak;
    private float gyroPeak;
    private long gyroPeakAtMs;
    private long suppressUntilMs;

    public FingerPinchDetector(Context context, Listener listener) {
        this.listener = listener;
        this.appContext = context.getApplicationContext();
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor linearSensor =
                sensorManager != null
                        ? sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                        : null;
        if (linearSensor != null) {
            motionSensor = linearSensor;
            linear = true;
        } else {
            motionSensor =
                    sensorManager != null
                            ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                            : null;
            linear = false;
        }
        gravitySensor =
                sensorManager != null
                        ? sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                        : null;
        gyroSensor =
                sensorManager != null
                        ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                        : null;
    }

    public void start() {
        if (registered || sensorManager == null || motionSensor == null) {
            return;
        }
        // 5ms = 200Hz — catch short finger taps.
        sensorManager.registerListener(this, motionSensor, 5_000);
        if (gravitySensor != null) {
            sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, 5_000);
        }
        registered = true;
        pendingPeak = false;
        peakAtMs = 0;
        peakMag = 0;
        lastMag = 0;
        lastMagAtMs = 0;
        baselineMag = 0;
        gyroPeak = 0;
        gyroPeakAtMs = 0;
    }

    public void stop() {
        if (!registered || sensorManager == null) {
            return;
        }
        sensorManager.unregisterListener(this);
        registered = false;
        if (traceWriter != null) {
            try {
                traceWriter.flush();
                traceWriter.close();
            } catch (java.io.IOException ignored) {
                // Nothing useful to do if the trace cannot be flushed.
            }
            traceWriter = null;
        }
    }

    /**
     * Blind the detector for {@code ms} milliseconds. The vibration motor sits in the same case as
     * the accelerometer, so any haptic buzz reads as a large sharp transient — exactly the shape of
     * a pinch. Without this, one click's own confirmation buzz can retrigger the next click.
     */
    public void suppressFor(long ms) {
        long until = SystemClock.elapsedRealtime() + ms;
        if (until > suppressUntilMs) {
            suppressUntilMs = until;
        }
        pendingPeak = false;
        peakMag = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (TRACE) {
            trace(
                    SystemClock.elapsedRealtime(),
                    type,
                    event.values[0],
                    event.values[1],
                    event.values[2]);
        }
        if (type == Sensor.TYPE_GRAVITY) {
            gravityZ = event.values[2];
            hasGravity = true;
            return;
        }
        if (type == Sensor.TYPE_GYROSCOPE) {
            float gx = event.values[0];
            float gy = event.values[1];
            float gz = event.values[2];
            float gMag = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
            long gNow = SystemClock.elapsedRealtime();
            // Peak-hold with decay: a swing that is winding down should still suppress.
            if (gMag >= gyroPeak || gNow - gyroPeakAtMs > GYRO_HOLD_MS) {
                gyroPeak = gMag;
                gyroPeakAtMs = gNow;
            }
            return;
        }

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        long now = SystemClock.elapsedRealtime();
        boolean inCooldown = now - lastPinchMs < COOLDOWN_MS || now < suppressUntilMs;

        float mag;
        float peakThresh;
        if (linear) {
            mag = (float) Math.sqrt(x * x + y * y + z * z);
            peakThresh = PEAK_LINEAR;
            if (!hasGravity) {
                gravityZ = z;
            }
        } else {
            float gX = x / SensorManager.GRAVITY_EARTH;
            float gY = y / SensorManager.GRAVITY_EARTH;
            float gZ = z / SensorManager.GRAVITY_EARTH;
            float gForce = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ);
            mag = Math.abs(gForce - 1f);
            peakThresh = PEAK_G_DELTA;
            gravityZ = z;
            hasGravity = true;
        }

        float jerk = 0f;
        if (lastMagAtMs > 0) {
            float dt = (now - lastMagAtMs) / 1000f;
            if (dt > 0.0005f && dt < 0.05f) {
                jerk = Math.abs(mag - lastMag) / dt;
            }
        }

        // Soft baseline of quiet motion — peaks relative to it.
        if (!pendingPeak) {
            baselineMag += (mag - baselineMag) * BASELINE_ALPHA;
        }
        float relative = mag - baselineMag;

        // Keep tracking through the cooldown so the baseline and jerk history stay warm; we just
        // don't let anything fire yet. Returning early here used to leave stale state behind.
        if (inCooldown) {
            pendingPeak = false;
            peakMag = 0;
            lastMag = mag;
            lastMagAtMs = now;
            return;
        }

        // How much the wrist is moving right now decides how sharp a spike has to be.
        float gyroNow = (now - gyroPeakAtMs > GYRO_HOLD_MS) ? 0f : gyroPeak;
        float gyroScale;
        if (gyroNow <= GYRO_STEADY) {
            gyroScale = 1f;
        } else if (gyroNow >= GYRO_VETO) {
            gyroScale = GYRO_VETO_SCALE;
        } else {
            gyroScale =
                    1f
                            + (GYRO_VETO_SCALE - 1f)
                                    * (gyroNow - GYRO_STEADY)
                                    / (GYRO_VETO - GYRO_STEADY);
        }
        float effThresh = peakThresh * gyroScale;

        // Amplitude is mandatory and sharpness only corroborates it. Previously these were OR-ed,
        // so the (mis-scaled) jerk term could fire a click all by itself on sensor noise.
        boolean isPeak = relative >= effThresh && jerk >= MIN_RISE_RATE;

        if (isPeak) {
            if (!pendingPeak || mag > peakMag) {
                pendingPeak = true;
                peakAtMs = now;
                peakMag = mag;
            }
        } else if (pendingPeak) {
            long age = now - peakAtMs;
            // Fire when the spike settles, or timed out with a clear peak.
            boolean settled = mag < peakMag * 0.55f || relative < effThresh * 0.4f;
            if ((settled && age >= 12 && age <= PEAK_WINDOW_MS)
                    || (age >= 40 && age <= PEAK_WINDOW_MS && peakMag >= effThresh)) {
                fire(now);
            } else if (age > PEAK_WINDOW_MS) {
                pendingPeak = false;
                peakMag = 0;
            }
        }

        lastMag = mag;
        lastMagAtMs = now;
    }

    /** Appends one raw sample to the trace file. No-op unless {@link #TRACE} is enabled. */
    private void trace(long now, int type, float x, float y, float z) {
        if (!TRACE) {
            return;
        }
        try {
            if (traceWriter == null) {
                java.io.File dir = appContext.getExternalFilesDir(null);
                if (dir == null) {
                    return;
                }
                traceWriter =
                        new java.io.BufferedWriter(
                                new java.io.FileWriter(
                                        new java.io.File(dir, "pinch_trace.csv"), true));
                traceWriter.write("ms,type,x,y,z\n");
            }
            traceWriter.write(now + "," + type + "," + x + "," + y + "," + z + "\n");
            if (++traceLines % 200 == 0) {
                traceWriter.flush();
            }
        } catch (java.io.IOException ignored) {
            traceWriter = null;
        }
    }

    /** Marks the moment a pinch was accepted, so recorded traces can be labelled. */
    private void traceFire(long now) {
        trace(now, 99, 1f, 0f, 0f);
    }

    private void fire(long now) {
        pendingPeak = false;
        peakMag = 0;
        traceFire(now);
        lastPinchMs = now;
        boolean palmUp = hasGravity && gravityZ >= PALM_UP_Z;
        mainHandler.post(() -> listener.onPinch(palmUp));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
