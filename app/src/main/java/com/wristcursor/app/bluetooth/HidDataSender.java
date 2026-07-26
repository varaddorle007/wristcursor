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

package com.wristcursor.app.bluetooth;

import static com.google.common.base.Preconditions.checkNotNull;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.ArraySet;
import android.util.Log;
import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;
import com.wristcursor.app.bluetooth.HidDeviceProfile.ServiceStateListener;
import java.util.ArrayDeque;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/** Central point for enabling the HID SDP record and sending all data. */
public class HidDataSender
        implements MouseReport.MouseDataSender, KeyboardReport.KeyboardDataSender {

    private static final String TAG = "HidDataSender";
    /** Fast then patient retries — covers bond-not-ready and stack-busy on Wear. */
    private static final long[] CONNECT_RETRY_MS = {0L, 150L, 350L, 700L, 1400L, 2800L, 4500L};

    private static final long SDP_REREGISTER_MS = 250L;
    /** A failed registerApp call does not produce a callback, so retry it explicitly. */
    private static final long SDP_REGISTER_RETRY_MS = 750L;
    private static final int AXIS_LIMIT = 127;
    /** Preserve at most one extra 8-bit packet after a hard flick; never create a long tail. */
    private static final int MAX_PENDING_AXIS = AXIS_LIMIT * 2;
    /**
     * The Watch 6 Bluetooth stack queues fast HID submissions and releases them to macOS in big
     * clumps. It sustains a fresh pointer report about every 40 ms, so merge sensor samples until
     * the next slot instead of burying the stack under 90–125 reports a second.
     */
    private static final long MOUSE_REPORT_INTERVAL_MS = 40L;
    /** Button transitions must stay ordered, but never allow a broken host to grow a long tail. */
    private static final int MAX_ORDERED_MOUSE_REPORTS = 16;

    /** Compound interface that listens to both device and service state changes. */
    public interface ProfileListener
            extends HidDeviceApp.DeviceStateListener, ServiceStateListener {}

    static final class InstanceHolder {
        static final HidDataSender INSTANCE = createInstance();

        private static HidDataSender createInstance() {
            return new HidDataSender(new HidDeviceApp(), new HidDeviceProfile());
        }
    }

    private final BroadcastReceiver batteryReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    onBatteryChanged(intent);
                }
            };

    private final HidDeviceApp hidDeviceApp;
    private final HidDeviceProfile hidDeviceProfile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread mouseReportThread;
    private final Handler mouseReportHandler;

    private final Object lock = new Object();
    private final Object mouseReportLock = new Object();

    @GuardedBy("mouseReportLock")
    private final ArrayDeque<MouseState> orderedMouseReports = new ArrayDeque<>();

    @GuardedBy("mouseReportLock")
    private boolean mouseReportScheduled;

    @GuardedBy("mouseReportLock")
    private boolean pendingLeft;
    @GuardedBy("mouseReportLock")
    private boolean pendingRight;
    @GuardedBy("mouseReportLock")
    private boolean pendingMiddle;
    @GuardedBy("mouseReportLock")
    private int pendingX;
    @GuardedBy("mouseReportLock")
    private int pendingY;
    @GuardedBy("mouseReportLock")
    private int pendingWheel;
    @GuardedBy("mouseReportLock")
    private boolean hasPendingMotion;

    @GuardedBy("lock")
    private final Set<ProfileListener> listeners = new ArraySet<>();

    /**
     * Written under {@code lock}, but read without it on the sensor thread's send path so a pointer
     * report never blocks behind a main-thread connect/disconnect binder call.
     */
    @GuardedBy("lock")
    @Nullable
    private volatile BluetoothDevice connectedDevice;

    @GuardedBy("lock")
    @Nullable
    private BluetoothDevice waitingForDevice;

    @GuardedBy("lock")
    private boolean isAppRegistered;

    @GuardedBy("lock")
    private int connectAttempt;

    private final Runnable connectAttemptRunnable = this::tryFulfillConnect;
    private final Runnable sdpReregisterRunnable = this::reregisterSdpIfNeeded;
    private final Runnable drainMouseReportsRunnable = this::drainOneMouseReport;

    private HidDataSender(HidDeviceApp hidDeviceApp, HidDeviceProfile hidDeviceProfile) {
        this.hidDeviceApp = checkNotNull(hidDeviceApp);
        this.hidDeviceProfile = checkNotNull(hidDeviceProfile);
        mouseReportThread = new HandlerThread("WristCursorHidReports");
        mouseReportThread.start();
        mouseReportHandler = new Handler(mouseReportThread.getLooper());
    }

    private void reregisterSdpIfNeeded() {
        synchronized (lock) {
            if (isAppRegistered || listeners.isEmpty() || !hidDeviceProfile.isReady()) {
                return;
            }
            Log.i(TAG, "Re-registering HID SDP after blip");
            BluetoothProfile proxy = hidDeviceProfile.getService();
            if (proxy != null) {
                if (!hidDeviceApp.registerApp(proxy)) {
                    Log.w(TAG, "HID SDP registration rejected; retrying");
                    mainHandler.postDelayed(sdpReregisterRunnable, SDP_REGISTER_RETRY_MS);
                }
            }
        }
    }

    public static HidDataSender getInstance() {
        return InstanceHolder.INSTANCE;
    }

    @MainThread
    public HidDeviceProfile register(Context context, ProfileListener listener) {
        synchronized (lock) {
            if (!listeners.add(listener)) {
                return hidDeviceProfile;
            }
            if (listeners.size() > 1) {
                return hidDeviceProfile;
            }

            context = checkNotNull(context).getApplicationContext();
            hidDeviceProfile.registerServiceListener(context, profileListener);
            hidDeviceApp.registerDeviceListener(profileListener);
            context.registerReceiver(
                    batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
        return hidDeviceProfile;
    }

    @MainThread
    public void unregister(Context context, ProfileListener listener) {
        synchronized (lock) {
            if (!listeners.remove(listener)) {
                return;
            }
            if (!listeners.isEmpty()) {
                return;
            }

            mainHandler.removeCallbacks(connectAttemptRunnable);
            mainHandler.removeCallbacks(sdpReregisterRunnable);
            context = checkNotNull(context).getApplicationContext();
            context.unregisterReceiver(batteryReceiver);
            hidDeviceApp.unregisterDeviceListener();

            for (BluetoothDevice device : hidDeviceProfile.getConnectedDevices()) {
                hidDeviceProfile.disconnect(device);
            }

            hidDeviceApp.setDevice(null);
            clearPendingMouseReports();
            hidDeviceApp.unregisterApp();

            hidDeviceProfile.unregisterServiceListener();

            connectedDevice = null;
            waitingForDevice = null;
            connectAttempt = 0;
        }
    }

    public boolean isConnected() {
        return (connectedDevice != null);
    }

    /** True while connected or actively trying to connect (protects against background kill). */
    public boolean isConnectingOrConnected() {
        synchronized (lock) {
            return connectedDevice != null || waitingForDevice != null;
        }
    }

    /**
     * Initiate connection sequence for the specified HID Host. If another device is already
     * connected, it will be disconnected first. If the parameter is {@code null}, then the service
     * will only disconnect from the current device.
     */
    @MainThread
    public void requestConnect(BluetoothDevice device) {
        synchronized (lock) {
            mainHandler.removeCallbacks(connectAttemptRunnable);
            waitingForDevice = device;
            connectAttempt = 0;
            if (device == null) {
                connectedDevice = null;
                clearPendingMouseReports();
                updateDeviceListLocked();
                return;
            }
            Log.i(TAG, "requestConnect " + device.getAddress());
            if (!isAppRegistered || !hidDeviceProfile.isReady()) {
                Log.i(
                        TAG,
                        "deferred until ready sdp="
                                + isAppRegistered
                                + " proxy="
                                + hidDeviceProfile.isReady());
                return;
            }
            connectedDevice = null;
            clearPendingMouseReports();
            scheduleConnectAttemptLocked(0);
        }
    }

    @Override
    @WorkerThread
    public void sendMouse(boolean left, boolean right, boolean middle, int dX, int dY, int dWheel) {
        if (connectedDevice == null) {
            return;
        }

        /*
         * BluetoothHidDevice.sendReport() is a Binder call. On the Watch 6 it can wait behind the
         * Bluetooth stack, and calling it from the JNI gyro callback freezes the sensor stream.
         * Keep that wait on a dedicated thread. Pointer reports are latest-wins and capped to one
         * HID packet, so a slow host cannot turn old wrist motion into a long cursor tail.
         */
        synchronized (mouseReportLock) {
            if (left != pendingLeft || right != pendingRight || middle != pendingMiddle) {
                enqueuePendingMotionLocked();
                enqueueOrderedMouseReportLocked(new MouseState(left, right, middle, dX, dY, dWheel));
                pendingLeft = left;
                pendingRight = right;
                pendingMiddle = middle;
            } else if (dX != 0 || dY != 0 || dWheel != 0) {
                pendingX = addAndClamp(pendingX, dX, MAX_PENDING_AXIS);
                pendingY = addAndClamp(pendingY, dY, MAX_PENDING_AXIS);
                pendingWheel = addAndClamp(pendingWheel, dWheel, MAX_PENDING_AXIS);
                hasPendingMotion = true;
            }
            scheduleMouseDrainLocked();
        }
    }

    @GuardedBy("mouseReportLock")
    private void enqueuePendingMotionLocked() {
        if (!hasPendingMotion) {
            return;
        }
        final int x = clampToReportAxis(pendingX);
        final int y = clampToReportAxis(pendingY);
        final int wheel = clampToReportAxis(pendingWheel);
        enqueueOrderedMouseReportLocked(
                new MouseState(pendingLeft, pendingRight, pendingMiddle, x, y, wheel));
        pendingX -= x;
        pendingY -= y;
        pendingWheel -= wheel;
        hasPendingMotion = pendingX != 0 || pendingY != 0 || pendingWheel != 0;
    }

    @GuardedBy("mouseReportLock")
    private void enqueueOrderedMouseReportLocked(MouseState report) {
        if (orderedMouseReports.size() >= MAX_ORDERED_MOUSE_REPORTS) {
            orderedMouseReports.removeFirst();
        }
        orderedMouseReports.addLast(report);
    }

    @GuardedBy("mouseReportLock")
    private void scheduleMouseDrainLocked() {
        if (!mouseReportScheduled) {
            mouseReportScheduled = true;
            mouseReportHandler.post(drainMouseReportsRunnable);
        }
    }

    private void drainOneMouseReport() {
        final MouseState report;
        synchronized (mouseReportLock) {
            if (!orderedMouseReports.isEmpty()) {
                report = orderedMouseReports.removeFirst();
            } else if (hasPendingMotion) {
                final int x = clampToReportAxis(pendingX);
                final int y = clampToReportAxis(pendingY);
                final int wheel = clampToReportAxis(pendingWheel);
                report =
                        new MouseState(
                                pendingLeft,
                                pendingRight,
                                pendingMiddle,
                                x,
                                y,
                                wheel);
                pendingX -= x;
                pendingY -= y;
                pendingWheel -= wheel;
                hasPendingMotion = pendingX != 0 || pendingY != 0 || pendingWheel != 0;
            } else {
                mouseReportScheduled = false;
                return;
            }
        }

        if (connectedDevice != null) {
            hidDeviceApp.sendMouse(
                    report.left, report.right, report.middle, report.x, report.y, report.wheel);
        }
        mouseReportHandler.postDelayed(drainMouseReportsRunnable, MOUSE_REPORT_INTERVAL_MS);
    }

    private void clearPendingMouseReports() {
        synchronized (mouseReportLock) {
            orderedMouseReports.clear();
            pendingLeft = false;
            pendingRight = false;
            pendingMiddle = false;
            pendingX = 0;
            pendingY = 0;
            pendingWheel = 0;
            hasPendingMotion = false;
        }
    }

    private static int addAndClamp(int current, int delta, int limit) {
        return Math.max(-limit, Math.min(limit, current + delta));
    }

    private static int clampToReportAxis(int value) {
        return Math.max(-AXIS_LIMIT, Math.min(AXIS_LIMIT, value));
    }

    private static final class MouseState {
        final boolean left;
        final boolean right;
        final boolean middle;
        final int x;
        final int y;
        final int wheel;

        MouseState(boolean left, boolean right, boolean middle, int x, int y, int wheel) {
            this.left = left;
            this.right = right;
            this.middle = middle;
            this.x = x;
            this.y = y;
            this.wheel = wheel;
        }
    }

    @Override
    @WorkerThread
    public void sendKeyboard(
            int modifier, int key1, int key2, int key3, int key4, int key5, int key6) {
        synchronized (lock) {
            if (connectedDevice != null) {
                hidDeviceApp.sendKeyboard(modifier, key1, key2, key3, key4, key5, key6);
            }
        }
    }

    @MainThread
    private void tryFulfillConnect() {
        synchronized (lock) {
            if (waitingForDevice == null) {
                return;
            }
            if (!isAppRegistered || !hidDeviceProfile.isReady()) {
                Log.i(TAG, "tryFulfillConnect waiting for sdp/proxy");
                return;
            }

            // Already connecting/connected to target — don't spam connect().
            int state = hidDeviceProfile.getConnectionState(waitingForDevice);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = waitingForDevice;
                waitingForDevice = null;
                hidDeviceApp.setDevice(connectedDevice);
                return;
            }
            if (state == BluetoothProfile.STATE_CONNECTING) {
                return;
            }

            // Disconnect strangers first.
            for (BluetoothDevice device : hidDeviceProfile.getConnectedDevices()) {
                if (!device.equals(waitingForDevice)) {
                    hidDeviceProfile.disconnect(device);
                }
            }

            boolean ok = hidDeviceProfile.connect(waitingForDevice);
            Log.i(
                    TAG,
                    "connect attempt "
                            + connectAttempt
                            + " => "
                            + ok
                            + " for "
                            + waitingForDevice.getAddress());
            if (!ok) {
                scheduleNextRetryLocked();
            }
        }
    }

    @GuardedBy("lock")
    private void scheduleConnectAttemptLocked(long delayMs) {
        mainHandler.removeCallbacks(connectAttemptRunnable);
        if (delayMs <= 0) {
            mainHandler.post(connectAttemptRunnable);
        } else {
            mainHandler.postDelayed(connectAttemptRunnable, delayMs);
        }
    }

    @GuardedBy("lock")
    private void scheduleNextRetryLocked() {
        if (waitingForDevice == null) {
            return;
        }
        if (connectAttempt >= CONNECT_RETRY_MS.length - 1) {
            Log.w(TAG, "Connect retries exhausted for " + waitingForDevice.getAddress());
            BluetoothDevice failed = waitingForDevice;
            waitingForDevice = null;
            for (ProfileListener listener : listeners) {
                listener.onConnectionStateChanged(failed, BluetoothProfile.STATE_DISCONNECTED);
            }
            return;
        }
        connectAttempt++;
        long delay = CONNECT_RETRY_MS[connectAttempt];
        Log.i(TAG, "Scheduling connect retry #" + connectAttempt + " in " + delay + "ms");
        scheduleConnectAttemptLocked(delay);
    }

    private final ProfileListener profileListener =
            new ProfileListener() {
                @Override
                @MainThread
                public void onServiceStateChanged(BluetoothProfile proxy) {
                    synchronized (lock) {
                        if (proxy == null) {
                            if (isAppRegistered) {
                                onAppStatusChanged(false);
                            }
                        } else {
                            if (!hidDeviceApp.registerApp(proxy)) {
                                Log.w(TAG, "Initial HID SDP registration rejected; retrying");
                                mainHandler.removeCallbacks(sdpReregisterRunnable);
                                mainHandler.postDelayed(
                                        sdpReregisterRunnable, SDP_REGISTER_RETRY_MS);
                            }
                            if (waitingForDevice != null) {
                                scheduleConnectAttemptLocked(0);
                            }
                        }
                        updateDeviceListLocked();
                        for (ProfileListener listener : listeners) {
                            listener.onServiceStateChanged(proxy);
                        }
                    }
                }

                @Override
                @MainThread
                public void onConnectionStateChanged(BluetoothDevice device, int state) {
                    synchronized (lock) {
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            waitingForDevice = device;
                            connectAttempt = 0;
                            mainHandler.removeCallbacks(connectAttemptRunnable);
                        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                            if (waitingForDevice != null && waitingForDevice.equals(device)) {
                                scheduleNextRetryLocked();
                            }
                        }
                        updateDeviceListLocked();
                        for (ProfileListener listener : listeners) {
                            listener.onConnectionStateChanged(device, state);
                        }
                    }
                }

                @Override
                @MainThread
                public void onAppStatusChanged(boolean registered) {
                    synchronized (lock) {
                        if (isAppRegistered == registered) {
                            return;
                        }
                        isAppRegistered = registered;
                        Log.i(TAG, "HID SDP registered=" + registered);

                        for (ProfileListener listener : listeners) {
                            listener.onAppStatusChanged(registered);
                        }
                        if (registered) {
                            mainHandler.removeCallbacks(sdpReregisterRunnable);
                            if (waitingForDevice != null) {
                                scheduleConnectAttemptLocked(0);
                            }
                        } else if (!listeners.isEmpty()) {
                            // Wear often blips SDP — re-register without dropping connect queue.
                            mainHandler.removeCallbacks(sdpReregisterRunnable);
                            mainHandler.postDelayed(sdpReregisterRunnable, SDP_REREGISTER_MS);
                        }
                    }
                }
            };

    @MainThread
    private void updateDeviceListLocked() {
        BluetoothDevice connected = null;

        for (BluetoothDevice device : hidDeviceProfile.getConnectedDevices()) {
            if (device.equals(waitingForDevice) || device.equals(connectedDevice)) {
                connected = device;
            } else {
                hidDeviceProfile.disconnect(device);
            }
        }

        boolean disconnected = false;
        if (connectedDevice == null && connected != null) {
            connectedDevice = connected;
            waitingForDevice = null;
            connectAttempt = 0;
            mainHandler.removeCallbacks(connectAttemptRunnable);
        } else if (connectedDevice != null && connected == null) {
            connectedDevice = null;
            disconnected = true;
        }
        hidDeviceApp.setDevice(connectedDevice);
        if (disconnected) {
            clearPendingMouseReports();
        }
    }

    @MainThread
    private void onBatteryChanged(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0) {
            float batteryLevel = (float) level / (float) scale;
            hidDeviceApp.sendBatteryLevel(batteryLevel);
        } else {
            Log.e(TAG, "Bad battery level data received: level=" + level + ", scale=" + scale);
        }
    }
}
