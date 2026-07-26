package com.wristcursor.app.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Low-latency BLE pointer transport for the WristCursor macOS receiver.
 *
 * <p>Android's Classic Bluetooth HID implementation on the Watch 6 batches pointer reports. This
 * GATT notification service bypasses that HID queue entirely; the small macOS companion turns the
 * packets into local cursor events. It is deliberately latest-wins, so a slow radio can never
 * replay old wrist movement after the wrist has stopped.
 */
public final class BlePointerServer implements MouseReport.MouseDataSender {

    private static final String TAG = "BlePointerServer";
    public static final UUID SERVICE_UUID =
            UUID.fromString("8f27c794-c23d-4d71-a57f-134c9d853001");
    public static final UUID POINTER_CHARACTERISTIC_UUID =
            UUID.fromString("8f27c794-c23d-4d71-a57f-134c9d853002");
    private static final UUID CLIENT_CONFIGURATION_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long RETRY_INTERVAL_MS = 8L;
    private static final long HEARTBEAT_INTERVAL_MS = 1000L;
    private static final int AXIS_LIMIT = 127;

    private static final class Holder {
        static final BlePointerServer INSTANCE = new BlePointerServer();
    }

    private final Object lock = new Object();
    private final HandlerThread transmitThread = new HandlerThread("WristCursorBlePointer");
    private final Handler transmitHandler;
    private final ArrayDeque<Packet> orderedPackets = new ArrayDeque<>();
    private final Runnable drainRunnable = this::drainOnePacket;
    private final Runnable heartbeatRunnable = this::sendHeartbeat;

    @Nullable private BluetoothGattServer gattServer;
    @Nullable private BluetoothLeAdvertiser advertiser;
    @Nullable private BluetoothGattCharacteristic pointerCharacteristic;
    @Nullable private BluetoothDevice subscribedDevice;

    private boolean advertising;
    private boolean drainScheduled;
    /** Android permits only a small number of outstanding GATT notifications. */
    private boolean notificationInFlight;
    private boolean heartbeatScheduled;
    private boolean pendingLeft;
    private boolean pendingRight;
    private boolean pendingMiddle;
    private int pendingX;
    private int pendingY;
    private int pendingWheel;
    private boolean hasPendingMotion;

    private BlePointerServer() {
        transmitThread.start();
        transmitHandler = new Handler(transmitThread.getLooper());
    }

    public static BlePointerServer getInstance() {
        return Holder.INSTANCE;
    }

    /** Starts advertising the receiver service. Safe to call repeatedly. */
    public void start(Context context) {
        synchronized (lock) {
            if (gattServer != null) {
                return;
            }
            try {
                BluetoothManager manager = context.getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (manager == null || adapter == null || !adapter.isEnabled()) {
                    Log.w(TAG, "Bluetooth unavailable; BLE receiver not started");
                    return;
                }
                if (!adapter.isMultipleAdvertisementSupported()) {
                    Log.w(TAG, "Watch does not support BLE advertising");
                    return;
                }

                BluetoothGattServer server = manager.openGattServer(context.getApplicationContext(), callback);
                if (server == null) {
                    Log.e(TAG, "openGattServer returned null");
                    return;
                }

                BluetoothGattCharacteristic characteristic =
                        new BluetoothGattCharacteristic(
                                POINTER_CHARACTERISTIC_UUID,
                                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                BluetoothGattCharacteristic.PERMISSION_READ);
                characteristic.addDescriptor(
                        new BluetoothGattDescriptor(
                                CLIENT_CONFIGURATION_UUID,
                                BluetoothGattDescriptor.PERMISSION_READ
                                        | BluetoothGattDescriptor.PERMISSION_WRITE));
                BluetoothGattService service =
                        new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
                service.addCharacteristic(characteristic);
                if (!server.addService(service)) {
                    Log.e(TAG, "Could not add BLE pointer service");
                    server.close();
                    return;
                }

                gattServer = server;
                pointerCharacteristic = characteristic;
                advertiser = adapter.getBluetoothLeAdvertiser();
                if (advertiser == null) {
                    Log.e(TAG, "BluetoothLeAdvertiser unavailable");
                    return;
                }

                AdvertiseSettings settings =
                        new AdvertiseSettings.Builder()
                                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                                .setConnectable(true)
                                .setTimeout(0)
                                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                                .build();
                AdvertiseData data =
                        new AdvertiseData.Builder()
                                .addServiceUuid(new android.os.ParcelUuid(SERVICE_UUID))
                                .build();
                advertiser.startAdvertising(settings, data, advertiseCallback);
            } catch (SecurityException e) {
                Log.e(TAG, "Bluetooth permission missing for BLE receiver", e);
            }
        }
    }

    /** True only after the macOS receiver enables notifications. */
    public boolean isReceiverConnected() {
        synchronized (lock) {
            return subscribedDevice != null && gattServer != null && pointerCharacteristic != null;
        }
    }

    @Override
    public void sendMouse(boolean left, boolean right, boolean middle, int dX, int dY, int dWheel) {
        synchronized (lock) {
            if (subscribedDevice == null) {
                return;
            }
            if (left != pendingLeft || right != pendingRight || middle != pendingMiddle) {
                enqueuePendingMotionLocked();
                enqueueOrderedLocked(new Packet(left, right, middle, dX, dY, dWheel));
                pendingLeft = left;
                pendingRight = right;
                pendingMiddle = middle;
            } else if (dX != 0 || dY != 0 || dWheel != 0) {
                pendingX = addAndClamp(pendingX, dX);
                pendingY = addAndClamp(pendingY, dY);
                pendingWheel = addAndClamp(pendingWheel, dWheel);
                hasPendingMotion = true;
            }
            scheduleDrainLocked();
        }
    }

    private void enqueuePendingMotionLocked() {
        if (!hasPendingMotion) {
            return;
        }
        enqueueOrderedLocked(new Packet(pendingLeft, pendingRight, pendingMiddle, pendingX, pendingY, pendingWheel));
        pendingX = 0;
        pendingY = 0;
        pendingWheel = 0;
        hasPendingMotion = false;
    }

    private void enqueueOrderedLocked(Packet packet) {
        // There are only click transitions here. Never turn a malformed client into a long tail.
        if (orderedPackets.size() >= 8) {
            orderedPackets.removeFirst();
        }
        orderedPackets.addLast(packet);
    }

    private void scheduleDrainLocked() {
        if (!drainScheduled && !notificationInFlight) {
            drainScheduled = true;
            transmitHandler.post(drainRunnable);
        }
    }

    private void drainOnePacket() {
        final Packet packet;
        final BluetoothGattServer server;
        final BluetoothGattCharacteristic characteristic;
        final BluetoothDevice device;
        synchronized (lock) {
            drainScheduled = false;
            if (notificationInFlight) {
                return;
            }
            if (!orderedPackets.isEmpty()) {
                packet = orderedPackets.removeFirst();
            } else if (hasPendingMotion) {
                packet =
                        new Packet(
                                pendingLeft,
                                pendingRight,
                                pendingMiddle,
                                pendingX,
                                pendingY,
                                pendingWheel);
                pendingX = 0;
                pendingY = 0;
                pendingWheel = 0;
                hasPendingMotion = false;
            } else {
                drainScheduled = false;
                return;
            }

            server = gattServer;
            characteristic = pointerCharacteristic;
            device = subscribedDevice;
            notificationInFlight = server != null && characteristic != null && device != null;
        }

        if (server != null && characteristic != null && device != null) {
            byte[] value = packet.toBytes();
            try {
                characteristic.setValue(value);
                if (!server.notifyCharacteristicChanged(device, characteristic, false)) {
                    Log.w(TAG, "BLE pointer notification rejected");
                    synchronized (lock) {
                        notificationInFlight = false;
                        if (subscribedDevice != null) {
                            transmitHandler.postDelayed(drainRunnable, RETRY_INTERVAL_MS);
                            drainScheduled = true;
                        }
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Unable to notify BLE pointer receiver", e);
                synchronized (lock) {
                    notificationInFlight = false;
                }
            }
        }
    }

    private void clearLocked() {
        transmitHandler.removeCallbacks(heartbeatRunnable);
        subscribedDevice = null;
        orderedPackets.clear();
        pendingLeft = false;
        pendingRight = false;
        pendingMiddle = false;
        pendingX = 0;
        pendingY = 0;
        pendingWheel = 0;
        hasPendingMotion = false;
        notificationInFlight = false;
        drainScheduled = false;
        heartbeatScheduled = false;
    }

    /**
     * Keeps the central link observable while the wrist is still. If Android restarts its GATT
     * server, the Mac can then identify and replace a stale connection instead of silently
     * falling back to delayed HID reports.
     */
    private void sendHeartbeat() {
        synchronized (lock) {
            heartbeatScheduled = false;
            if (subscribedDevice == null) {
                return;
            }
            if (orderedPackets.isEmpty() && !hasPendingMotion) {
                enqueueOrderedLocked(new Packet(pendingLeft, pendingRight, pendingMiddle, 0, 0, 0));
            }
            scheduleDrainLocked();
            scheduleHeartbeatLocked();
        }
    }

    private void scheduleHeartbeatLocked() {
        if (!heartbeatScheduled && subscribedDevice != null) {
            heartbeatScheduled = true;
            transmitHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
        }
    }

    private static int addAndClamp(int current, int delta) {
        return Math.max(-AXIS_LIMIT, Math.min(AXIS_LIMIT, current + delta));
    }

    private final AdvertiseCallback advertiseCallback =
            new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    synchronized (lock) {
                        advertising = true;
                    }
                    Log.i(TAG, "BLE pointer receiver advertising");
                }

                @Override
                public void onStartFailure(int errorCode) {
                    synchronized (lock) {
                        advertising = false;
                    }
                    Log.e(TAG, "BLE pointer advertising failed: " + errorCode);
                }
            };

    private final BluetoothGattServerCallback callback =
            new BluetoothGattServerCallback() {
                @Override
                public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                    synchronized (lock) {
                        if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                            clearLocked();
                            Log.i(TAG, "BLE pointer receiver disconnected");
                        }
                    }
                }

                @Override
                public void onDescriptorWriteRequest(
                        BluetoothDevice device,
                        int requestId,
                        BluetoothGattDescriptor descriptor,
                        boolean preparedWrite,
                        boolean responseNeeded,
                        int offset,
                        byte[] value) {
                    boolean enabled =
                            descriptor.getUuid().equals(CLIENT_CONFIGURATION_UUID)
                                    && value != null
                                    && java.util.Arrays.equals(
                                            value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    synchronized (lock) {
                        if (enabled) {
                            subscribedDevice = device;
                            Log.i(TAG, "BLE pointer receiver ready: " + device.getAddress());
                            scheduleHeartbeatLocked();
                        } else if (device.equals(subscribedDevice)) {
                            clearLocked();
                        }
                    }
                    if (responseNeeded && gattServer != null) {
                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                value == null ? new byte[0] : value);
                    }
                }

                @Override
                public void onNotificationSent(BluetoothDevice device, int status) {
                    synchronized (lock) {
                        notificationInFlight = false;
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.w(TAG, "BLE pointer notification failed: " + status);
                        }
                        if (device.equals(subscribedDevice)) {
                            scheduleDrainLocked();
                        }
                    }
                }

                @Override
                public void onCharacteristicReadRequest(
                        BluetoothDevice device,
                        int requestId,
                        int offset,
                        BluetoothGattCharacteristic characteristic) {
                    if (gattServer != null) {
                        gattServer.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                "WristCursor BLE receiver".getBytes(StandardCharsets.UTF_8));
                    }
                }
            };

    private static final class Packet {
        final boolean left;
        final boolean right;
        final boolean middle;
        final int x;
        final int y;
        final int wheel;

        Packet(boolean left, boolean right, boolean middle, int x, int y, int wheel) {
            this.left = left;
            this.right = right;
            this.middle = middle;
            this.x = x;
            this.y = y;
            this.wheel = wheel;
        }

        byte[] toBytes() {
            int buttons = (left ? 1 : 0) | (right ? 2 : 0) | (middle ? 4 : 0);
            return new byte[] {(byte) buttons, (byte) x, (byte) y, (byte) wheel};
        }
    }
}
