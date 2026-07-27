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

import androidx.annotation.IntDef;
import com.wristcursor.app.bluetooth.MouseReport.MouseDataSender;
import com.wristcursor.app.sensors.SensorService;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/** Helper class that interprets sensor data and translates it to Mouse data events. */
public class MouseSensorListener implements SensorService.OrientationListener {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HandMode.LEFT, HandMode.CENTER, HandMode.RIGHT})
    public @interface HandMode {
        int LEFT = 0;
        int CENTER = 1;
        int RIGHT = 2;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MouseButton.LEFT, MouseButton.RIGHT, MouseButton.MIDDLE})
    public @interface MouseButton {
        int LEFT = 0;
        int RIGHT = 1;
        int MIDDLE = 2;
    }

    /**
     * Direct gyro→cursor (no EMA lag).
     *
     * <p>A mouse axis is one signed byte, and Bluetooth HID is paced at 125 Hz. Extra reports sent
     * in one callback do not create extra wire bandwidth; they queue behind the first report and
     * become the trailing motion we are trying to remove. Therefore the pointer transfer function
     * is tuned to keep an intended fast wrist turn (3.5 rad/s) under one 127-count report per
     * 8 ms tick, rather than relying on bursts.
     */
    // 1.75 * 1.75 * 3.5 rad/s * 8 ms = about 112 counts, below the 127-count HID limit.
    private static final double CURSOR_SPEED = (1024.0 / (Math.PI / 4)) * 1.75;
    private static final double STABILIZE_BIAS = 28.0;
    /**
     * Bluetooth HID drains roughly 100-125 reports/sec. The gyro fires several hundred times a
     * second, so sending one report per sample buries the BT stack in a queue that grows while you
     * move and drains after you stop — that backlog is what feels like "lag" and "trailing", and it
     * gets worse the faster you move. Emit at a fixed 125 Hz instead and bank the motion in between;
     * every sample still contributes, it just rides the next report.
     */
    private static final long SEND_INTERVAL_NS = 8000000L; // 8 ms == 125 Hz
    /** Per-report range of a signed 8-bit relative axis. */
    private static final double AXIS_LIMIT = 127.0;
    /**
     * Hard ceiling on un-sent motion, in HID counts. Carrying an unbounded residual would make the
     * cursor keep gliding after the wrist has already stopped — the same trailing feel we are
     * removing from the Bluetooth queue, just relocated into this class. Keep at most one report
     * of remainder, which bounds a hard flick's tail to one 8 ms interval.
     */
    private static final double MAX_RESIDUAL_COUNTS = AXIS_LIMIT;
    /**
     * Drift control. The tracker runs on the UNCALIBRATED gyroscope, so a small constant bias
     * survives into the deltas — and a constant angular rate integrates into the cursor sliding on
     * its own forever. That is the "stick drift". Two defences:
     *
     * <p>1. Auto-zero: whenever the wrist is genuinely still, slowly learn the residual per-sample
     * delta and subtract it, so the bias is cancelled instead of accumulated.
     * 2. A rate deadzone below which motion is treated as noise and emitted as exactly zero.
     */
    private static final double STILL_RATE = 0.035; // rad/s — wrist considered at rest
    private static final double BIAS_ALPHA = 0.0025; // slow, so real motion is never learned away
    private static final double DEADZONE_RATE = 0.010; // rad/s — below this, output nothing
    /** Fades the deadzone out over this range instead of making the cursor jump at its edge. */
    private static final double DEADZONE_RAMP_RATE = 0.080;

    /**
     * Pointer transfer function. A real mouse is not a fixed multiplier: slow movement needs
     * precision, fast movement needs reach. Gain ramps from PRECISION_GAIN at a crawl to FAST_GAIN
     * at FAST_RATE, which is what makes it feel like a mouse rather than a lever.
     */
    private static final double PRECISION_GAIN = 1.10;
    private static final double FAST_GAIN = 1.75;
    private static final double FAST_RATE = 3.5; // rad/s where gain saturates

    static final class ButtonEvent {
        final @MouseButton int button;
        final boolean state;

        ButtonEvent(@MouseButton int b, boolean s) {
            button = b;
            state = s;
        }
    }

    /**
     * A list of button events that are pending and need to be sent. The oldest event is at the
     * front.
     */
    private final List<ButtonEvent> pendingEvents = new ArrayList<>();

    private final MouseDataSender dataSender;

    private double yaw;
    private double pitch;
    private double dYaw;
    private double dPitch;
    private double dWheel;

    /** Monotonic timestamp of the last HID report, for output pacing. */
    private long lastSendNs;

    private int orientationCount;
    private int reportCount;
    private long rateWindowStartNs;

    /** Learned gyro bias, in radians per sample, continuously auto-zeroed while at rest. */
    private double biasYaw;
    private double biasPitch;

    private long lastSampleNs;

    /**
     * Whether this is the very first event we received after starting to listen or changing the
     * wrist mode.
     */
    private boolean firstRead;

    private boolean leftButtonPressed;
    private boolean rightButtonPressed;
    private boolean middleButtonPressed;
    private @HandMode int handMode = HandMode.CENTER;
    private boolean stabilize;
    private boolean lefty;
    /** When false, orientation is tracked for re-arm reset but HID moves are suppressed. */
    private boolean armed = false;

    /** @param dataSender Interface to send Mouse data with. */
    MouseSensorListener(MouseDataSender dataSender) {
        this.dataSender = checkNotNull(dataSender);
    }

    /** Temporary diagnostic: logs orientation-callback Hz and HID report Hz once a second. */
    private static final boolean RATE_DEBUG = false;

    private void tickRateDebug() {
        orientationCount++;
        long now = System.nanoTime();
        if (rateWindowStartNs == 0) {
            rateWindowStartNs = now;
            return;
        }
        if (now - rateWindowStartNs >= 1000000000L) {
            android.util.Log.i(
                    "WCRate",
                    "orientation="
                            + orientationCount
                            + "Hz report="
                            + reportCount
                            + "Hz armed="
                            + armed);
            orientationCount = 0;
            reportCount = 0;
            rateWindowStartNs = now;
        }
    }

    @Override
    public void onOrientation(double[] quaternion) {
        if (RATE_DEBUG) {
            tickRateDebug();
        }
        double q1 = quaternion[0]; // X * sin(T/2)
        double q2 = quaternion[1]; // Y * sin(T/2)
        double q3 = quaternion[2]; // Z * sin(T/2)
        double q0 = quaternion[3]; // cos(T/2)

        if (lefty) {
            // Rotate 180 degrees
            q1 = -q1;
            q2 = -q2;
        }

        if (handMode == HandMode.LEFT) {
            // Rotate 90 degrees counter-clockwise
            double x = q1;
            double y = q2;
            q1 = -y;
            q2 = x;
        } else if (handMode == HandMode.RIGHT) {
            // Rotate 90 degrees clockwise
            double x = q1;
            double y = q2;
            q1 = y;
            q2 = -x;
        } // else it's CENTER for which we do not need to rotate.

        double yaw = Math.atan2(2 * (q0 * q3 - q1 * q2), (1 - 2 * (q1 * q1 + q3 * q3)));
        double pitch = Math.asin(2 * (q0 * q1 + q2 * q3));
        // double roll = Math.atan2(2 * (q0 * q2 - q1 * q3), (1 - 2 * (q1 * q1 + q2 * q2)));

        if (Double.isNaN(yaw) || Double.isNaN(pitch)) {
            // NaN case, skip it
            return;
        }

        if (firstRead) {
            this.yaw = yaw;
            this.pitch = pitch;
            firstRead = false;
            return;
        }

        if (!armed) {
            // Keep absolute orientation warm so re-arm doesn't jump.
            this.yaw = yaw;
            this.pitch = pitch;
            dYaw = 0;
            dPitch = 0;
            return;
        }

        {
            final double newYaw = highpass(this.yaw, yaw);
            final double newPitch = highpass(this.pitch, pitch);
            // Raw deltas — no EMA. Smoothing was the main "laggy gyro" feel.
            double moveYaw = clamp(this.yaw - newYaw);
            double movePitch = this.pitch - newPitch;
            this.yaw = newYaw;
            this.pitch = newPitch;

            final long sampleNs = System.nanoTime();
            double dt = (lastSampleNs == 0) ? 0 : (sampleNs - lastSampleNs) / 1e9;
            lastSampleNs = sampleNs;
            if (dt <= 0 || dt > 0.2) {
                // First sample, or a stall: no reliable rate, so contribute nothing this frame.
                return;
            }

            // Learn the residual bias only while the wrist is effectively at rest.
            if (Math.hypot(moveYaw, movePitch) / dt < STILL_RATE) {
                biasYaw += (moveYaw - biasYaw) * BIAS_ALPHA;
                biasPitch += (movePitch - biasPitch) * BIAS_ALPHA;
            }
            moveYaw -= biasYaw;
            movePitch -= biasPitch;

            final double rate = Math.hypot(moveYaw, movePitch) / dt;
            if (rate <= DEADZONE_RATE) {
                // Hold perfectly still and the cursor holds perfectly still.
                moveYaw = 0;
                movePitch = 0;
            } else {
                // Blend out the noise gate continuously. A hard deadzone made the first few
                // pixels jump, which reads as both rough and insensitive during fine aiming.
                final double noiseGate =
                        Math.min(
                                1.0,
                                (rate - DEADZONE_RATE)
                                        / (DEADZONE_RAMP_RATE - DEADZONE_RATE));
                final double t = Math.min(1.0, rate / FAST_RATE);
                // A linear ramp gives useful response throughout the wrist-speed range; the old
                // squared curve stayed sluggish until it suddenly became fast.
                final double gain = PRECISION_GAIN + (FAST_GAIN - PRECISION_GAIN) * t;
                moveYaw *= noiseGate * gain;
                movePitch *= noiseGate * gain;
            }

            this.dYaw += moveYaw;
            this.dPitch += movePitch;
        }

        // Pace the HID link. Motion accumulates in dYaw/dPitch until the next tick, so nothing is
        // dropped — only the number of BT reports is bounded. Pending button events bypass the
        // pacing so a click never waits on the cursor cadence.
        final long now = System.nanoTime();
        final boolean buttonsWaiting;
        synchronized (pendingEvents) {
            buttonsWaiting = !pendingEvents.isEmpty();
        }
        if (!buttonsWaiting && now - lastSendNs < SEND_INTERVAL_NS) {
            return;
        }
        // Advance the deadline by whole intervals rather than resetting it to now. Sensor samples
        // land on their own grid — about 5 ms apart at the 200 Hz the watch actually delivers — so
        // "lastSendNs = now" rounds every tick up to the next sample boundary and the 8 ms target
        // silently becomes 10 ms: 100 Hz instead of the intended 125 Hz, measured at 95 Hz on the
        // Watch 6. Stepping the deadline keeps the long-run average at the rate the link is
        // registered for. Resynchronise after a stall so catch-up can never emit a burst.
        if (lastSendNs == 0 || now - lastSendNs > 4 * SEND_INTERVAL_NS) {
            lastSendNs = now;
        } else {
            lastSendNs += SEND_INTERVAL_NS;
        }

        sendCurrentState();
        limitPendingMotion();
    }

    /** Should be called in the controller's onCreate() method. */
    void onCreate() {
        pendingEvents.clear();
        firstRead = true;
        yaw = 0;
        pitch = 0;
        dYaw = 0;
        dPitch = 0;
        dWheel = 0;
        lastSendNs = 0;
        lastSampleNs = 0;
        biasYaw = 0;
        biasPitch = 0;
    }

    /**
     * Enqueue a button press event.
     *
     * @param button Button index. Can be one of LEFT, RIGHT, MIDDLE.
     * @param state {@code true} if the button is pressed, {@code false} otherwise.
     */
    void sendButtonEvent(@MouseButton int button, boolean state) {
        synchronized (pendingEvents) {
            pendingEvents.add(new ButtonEvent(button, state));
        }
    }

    /**
     * Immediate click (down + up) — does not wait for the next sensor frame.
     */
    void pulseClick(@MouseButton int button) {
        if (!armed) {
            return;
        }
        synchronized (pendingEvents) {
            pendingEvents.clear();
        }
        applyButton(button, true);
        dataSender.sendMouse(
                leftButtonPressed, rightButtonPressed, middleButtonPressed, 0, 0, 0);
        applyButton(button, false);
        dataSender.sendMouse(
                leftButtonPressed, rightButtonPressed, middleButtonPressed, 0, 0, 0);
    }

    /**
     * Arm / pause cursor tracking (WowMouse-style tap-to-toggle).
     * Re-arming resets the orientation baseline so the cursor does not jump.
     */
    void setArmed(boolean armed) {
        this.armed = armed;
        if (armed) {
            firstRead = true;
            dYaw = 0;
            dPitch = 0;
            dWheel = 0;
            // Let the very first wrist move after arming go out without waiting for a tick.
            lastSendNs = 0;
            // Force a fresh dt; the learned bias is deliberately kept across arm/pause.
            lastSampleNs = 0;
        } else {
            dYaw = 0;
            dPitch = 0;
            dWheel = 0;
        }
    }

    boolean isArmed() {
        return armed;
    }

    private void applyButton(@MouseButton int button, boolean state) {
        if (button == MouseButton.LEFT) {
            leftButtonPressed = state;
        } else if (button == MouseButton.RIGHT) {
            rightButtonPressed = state;
        } else if (button == MouseButton.MIDDLE) {
            middleButtonPressed = state;
        }
    }

    /**
     * Adjust the enqueued Mouse data with an offset.
     *
     * @param x Extra displacement along X axis.
     * @param y Extra displacement along Y axis.
     * @param wheel Extra Wheel rotation.
     */
    void sendMouseMove(double x, double y, double wheel) {
        dYaw += x / CURSOR_SPEED;
        dPitch += y / CURSOR_SPEED;
        dWheel += wheel;
    }

    /**
     * Sets the current watch location: left/right wrist, or in the hand.
     *
     * @param hand Can be one of LEFT, CENTER, RIGHT.
     */
    void setHand(@HandMode int hand) {
        handMode = hand;
        firstRead = true;
        // A report accumulated in the old coordinate system must never be sent using the new one.
        dYaw = 0;
        dPitch = 0;
        lastSampleNs = 0;
    }

    /**
     * Set the pointer stabilization setting state.
     *
     * @param stabilize {@code true} if pointer stabilization is enabled, {@code false} otherwise.
     */
    void setStabilize(boolean stabilize) {
        this.stabilize = stabilize;
    }

    /**
     * Sets the "lefty" mode for the mouse data. This inverts all movements along Y axis.
     *
     * @param isLefty {@code true} if "lefty" mode is active, {@code false} if not.
     */
    void setLefty(boolean isLefty) {
        lefty = isLefty;
    }

    private static double clamp(double val) {
        while (val <= -Math.PI) {
            val += 2 * Math.PI;
        }
        while (val >= Math.PI) {
            val -= 2 * Math.PI;
        }
        return val;
    }

    /**
     * Applies an adaptive high-pass filter if mStability is {@code true}. Otherwise simple returns
     * the new value.
     */
    private double highpass(double oldVal, double newVal) {
        if (!stabilize) {
            return newVal;
        }
        double delta = clamp(oldVal - newVal);
        double alpha =
                Math.max(0, 1 - Math.pow(Math.abs(delta) * CURSOR_SPEED / STABILIZE_BIAS, 3));
        return newVal + alpha * delta;
    }

    /** Sends the current displacement, clamped to one signed-byte HID report. */
    private void sendCurrentState() {
        double dX = dYaw * CURSOR_SPEED;
        double dY = dPitch * CURSOR_SPEED;
        double dZ = dWheel;

        // Axes are 8-bit. Scale both together when clamping so the direction of travel is
        // preserved, and let the burst loop drain whatever did not fit.
        if (dX > AXIS_LIMIT) {
            dY *= AXIS_LIMIT / dX;
            dX = AXIS_LIMIT;
        }
        if (dX < -AXIS_LIMIT) {
            dY *= -AXIS_LIMIT / dX;
            dX = -AXIS_LIMIT;
        }
        if (dY > AXIS_LIMIT) {
            dX *= AXIS_LIMIT / dY;
            dY = AXIS_LIMIT;
        }
        if (dY < -AXIS_LIMIT) {
            dX *= -AXIS_LIMIT / dY;
            dY = -AXIS_LIMIT;
        }
        if (dZ > 127) {
            dZ = 127;
        }
        if (dZ < -127) {
            dZ = -127;
        }

        final int x = (int) Math.round(dX);
        final int y = (int) Math.round(dY);
        final byte z = (byte) Math.round(dZ);
        sendData(x, y, z);

        // Only subtract the part of the error that was already sent.
        if (x != 0) {
            dYaw -= x / CURSOR_SPEED;
        }
        if (y != 0) {
            dPitch -= y / CURSOR_SPEED;
        }
        if (z != 0) {
            dWheel -= z;
        }
    }

    /**
     * Keeps deferred pointer movement below one HID report while preserving its direction. This is
     * only reached on a flick faster than the transfer function was designed for; discarding the
     * excess is preferable to replaying it after the wrist has stopped.
     */
    private void limitPendingMotion() {
        final double pendingX = dYaw * CURSOR_SPEED;
        final double pendingY = dPitch * CURSOR_SPEED;
        final double largestAxis = Math.max(Math.abs(pendingX), Math.abs(pendingY));
        if (largestAxis <= MAX_RESIDUAL_COUNTS) {
            return;
        }

        final double scale = MAX_RESIDUAL_COUNTS / largestAxis;
        dYaw *= scale;
        dPitch *= scale;
    }

    private void sendData(int x, int y, byte wheel) {
        if (RATE_DEBUG) {
            reportCount++;
        }
        synchronized (pendingEvents) {
            if (!pendingEvents.isEmpty()) {
                ButtonEvent event = pendingEvents.remove(0);
                if (event.button == MouseButton.LEFT) {
                    leftButtonPressed = event.state;
                } else if (event.button == MouseButton.RIGHT) {
                    rightButtonPressed = event.state;
                } else if (event.button == MouseButton.MIDDLE) {
                    middleButtonPressed = event.state;
                }
            }
        }

        dataSender.sendMouse(
                leftButtonPressed, rightButtonPressed, middleButtonPressed, x, y, wheel);
    }
}
