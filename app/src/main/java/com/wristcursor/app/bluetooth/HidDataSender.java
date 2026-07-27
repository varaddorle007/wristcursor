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
    /**
     * Hard ceiling on un-sent motion. Carrying more than a single report of residual is what turns
     * a hard flick into a cursor that keeps gliding after the wrist has already stopped — the
     * trailing feel simply relocates out of the Bluetooth queue and into this class. One report of
     * remainder bounds that tail to a single interval.
     */
    private static final int MAX_PENDING_AXIS = AXIS_LIMIT;
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
    private final Runnable drainMouseReportsRunnable = this::drainMouseReports;

    /**
     * Temporary diagnostic: counts reports that actually reach the Bluetooth stack, once a second.
     * This is the number the 40 ms drain was suppressing — the upstream pacing in
     * MouseSensorListener always ran at 125 Hz, so only a counter here can tell the two apart.
     */
    private static final boolean WIRE_RATE_DEBUG = false;

    private int wireReportCount;
    private long wireRateWindowStartNs;

    private int inboundCount;
    private int inboundNonZero;
    private int inboundNoDevice;
    private long inboundWindowStartNs;

    /**
     * Counts what arrives at the sender versus what survives, so a dead pointer can be pinned to a
     * specific stage instead of guessed at: no device, all-zero motion, or a stalled drain.
     */
    private void tickInboundDebug(int dX, int dY, int dWheel, boolean noDevice) {
        inboundCount++;
        if (dX != 0 || dY != 0 || dWheel != 0) {
            inboundNonZero++;
        }
        if (noDevice) {
            inboundNoDevice++;
        }
        final long now = System.nanoTime();
        if (inboundWindowStartNs == 0) {
            inboundWindowStartNs = now;
            return;
        }
        if (now - inboundWindowStartNs >= 1_000_000_000L) {
            Log.i(
                    TAG,
                    "WCIn: calls="
                            + inboundCount
                            + " nonZero="
                            + inboundNonZero
                            + " noDevice="
                            + inboundNoDevice);
            inboundCount = 0;
            inboundNonZero = 0;
            inboundNoDevice = 0;
            inboundWindowStartNs = now;
        }
    }

    private void tickWireRateDebug() {
        wireReportCount++;
        final long now = System.nanoTime();
        if (wireRateWindowStartNs == 0) {
            wireRateWindowStartNs = now;
            return;
        }
        if (now - wireRateWindowStartNs >= 1_000_000_000L) {
            Log.i(TAG, "WCWire: hid reports/sec=" + wireReportCount);
            wireReportCount = 0;
            wireRateWindowStartNs = now;
        }
    }

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
        if (WIRE_RATE_DEBUG) {
            tickInboundDebug(dX, dY, dWheel, connectedDevice == null);
        }
        if (connectedDevice == null) {
            return;
        }

        /*
         * BluetoothHidDevice.sendReport() is a Binder call. On the Watch 6 it can wait behind the
         * Bluetooth stack, and calling it from the JNI gyro callback freezes the sensor stream.
         * Keep that wait on a dedicated thread, and hand it off without adding any delay of our
         * own — the 125 Hz pacing was already applied upstream. Pointer reports coalesce and the
         * residual is capped to one HID packet, so a slow host cannot turn old wrist motion into a
         * long cursor tail.
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

    /**
     * Drains every queued report, then returns. There is deliberately no delay in this loop.
     *
     * <p>{@link com.wristcursor.app.input.MouseSensorListener} already paces the pointer to one
     * report per 8 ms tick, which is exactly the 125 Hz budget the HID link is registered for (see
     * {@code Constants.QOS_OUT}). Re-throttling on this side re-creates the very queue this thread
     * exists to avoid. It is also worse than a plain delay: because the producer banks motion
     * between ticks and the residual is capped, a slow drain does not just postpone the cursor, it
     * puts a ceiling on how fast the cursor can travel at all — fast wrist motion is accumulated,
     * clamped, and then discarded rather than delivered.
     *
     * <p>The dedicated thread still earns its keep: {@code BluetoothHidDevice.sendReport()} is a
     * Binder call that can wait on the Bluetooth stack, and making that call from the JNI gyro
     * callback stalls the sensor stream. Running it here lets the stack apply its own backpressure
     * while fresh samples coalesce into the pending accumulator, so the link self-paces instead of
     * being paced by a hardcoded guess.
     */
    private void drainMouseReports() {
        while (true) {
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
                    // Disarm while still holding the lock, so the next sample either posts a fresh
                    // drain or is picked up by this pass — never dropped, never left waiting on an
                    // already-scheduled timer.
                    mouseReportScheduled = false;
                    return;
                }
            }

            if (connectedDevice != null) {
                if (WIRE_RATE_DEBUG) {
                    tickWireRateDebug();
                }
                hidDeviceApp.sendMouse(
                        report.left, report.right, report.middle, report.x, report.y, report.wheel);
            }
        }
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
                            // This callback is authoritative: the stack is telling us this exact
                            // device is connected right now. Promote it here instead of waiting for
                            // updateDeviceListLocked() to rediscover it via getConnectedDevices(),
                            // which on Wear can still be empty at the instant the callback fires.
                            // That race left connectedDevice null while the link was genuinely up,
                            // and sendMouse()/sendKeyboard() drop every report when it is null — so
                            // the pointer, pinch clicks and media keys all silently went nowhere.
                            connectAttempt = 0;
                            mainHandler.removeCallbacks(connectAttemptRunnable);
                            waitingForDevice = null;
                            connectedDevice = device;
                            hidDeviceApp.setDevice(device);
                            clearPendingMouseReports();
                            Log.i(TAG, "connectedDevice set from callback: " + device.getAddress());
                        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                            if (device.equals(connectedDevice)) {
                                connectedDevice = null;
                                hidDeviceApp.setDevice(null);
                                clearPendingMouseReports();
                            }
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
            // getConnectedDevices() lags the connection callback on Wear, and an empty list is not
            // proof that the link dropped. Only let go of the device once the stack itself agrees
            // it is gone; otherwise we would null out a live connection and silently stop sending
            // every report, which looks exactly like "the app does nothing".
            if (hidDeviceProfile.getConnectionState(connectedDevice)
                    == BluetoothProfile.STATE_CONNECTED) {
                connected = connectedDevice;
            } else {
                connectedDevice = null;
                disconnected = true;
            }
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
