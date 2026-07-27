package com.wristcursor.app.bluetooth;

import android.content.Context;

/**
 * Routes pointer motion to the connected host.
 *
 * <p>Standard Bluetooth HID is the primary transport, because that is the whole point of the
 * product: an unmodified phone, PC or TV sees an ordinary mouse and installs nothing.
 *
 * <p>This class previously sent to the direct BLE receiver <em>only</em>, on the theory that the
 * Galaxy Watch 6 HID queue batches reports for hundreds of milliseconds. That batching was real but
 * self-inflicted — the pointer was being paced to 125 Hz and then re-throttled to 25 Hz further down
 * the stack, so reports piled into a capped accumulator and the overflow was discarded. With that
 * second throttle removed, HID delivers at the rate it is registered for. Routing around it cost
 * every host that has no companion receiver app its cursor entirely: reports were silently dropped
 * and the app looked dead.
 *
 * <p>The BLE receiver is kept as an optional accelerator for hosts that do run the helper, but it is
 * only used when no HID host is connected, so it can never become a requirement.
 */
public final class PointerTransport implements MouseReport.MouseDataSender {

    private static final class Holder {
        static final PointerTransport INSTANCE = new PointerTransport();
    }

    private final BlePointerServer blePointerServer = BlePointerServer.getInstance();
    private final HidDataSender hidDataSender = HidDataSender.getInstance();

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
        if (hidDataSender.isConnected()) {
            hidDataSender.sendMouse(left, right, middle, dX, dY, dWheel);
        } else if (blePointerServer.isReceiverConnected()) {
            blePointerServer.sendMouse(left, right, middle, dX, dY, dWheel);
        }
    }
}
