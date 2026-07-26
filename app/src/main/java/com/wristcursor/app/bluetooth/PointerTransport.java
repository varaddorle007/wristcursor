package com.wristcursor.app.bluetooth;

import android.content.Context;

/** Routes pointer motion to the low-latency macOS receiver.
 *
 * <p>Do not fall back to Android's Bluetooth HID queue here. On Galaxy Watch 6 that queue batches
 * reports for hundreds of milliseconds, which feels worse than a temporarily disconnected cursor
 * and causes old movement to replay after the wrist has stopped.
 */
public final class PointerTransport implements MouseReport.MouseDataSender {

    private static final class Holder {
        static final PointerTransport INSTANCE = new PointerTransport();
    }

    private final BlePointerServer blePointerServer = BlePointerServer.getInstance();

    private PointerTransport() {}

    public static PointerTransport getInstance() {
        return Holder.INSTANCE;
    }

    /** Starts advertising the direct BLE receiver. */
    public void start(Context context) {
        blePointerServer.start(context);
    }

    @Override
    public void sendMouse(boolean left, boolean right, boolean middle, int dX, int dY, int dWheel) {
        if (blePointerServer.isReceiverConnected()) {
            blePointerServer.sendMouse(left, right, middle, dX, dY, dWheel);
        }
    }
}
