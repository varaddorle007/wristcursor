#!/usr/bin/env python3
"""
Drift Virtual Gamepad Bridge (Xbox 360 Controller Gyro Emulation)
================================================================
Emulates a genuine Xbox 360 Controller using `vgamepad` on Windows.
Maps Drift watch steering angle directly to the Left Thumbstick X axis (-32767 to +32767)
and pedals to analog Left/Right triggers (0 to 255) for 100% authentic controller gyro
steering in games like Forza Horizon 5, F1 24, Assetto Corsa, BeamNG.drive, and NFS.

Prerequisites on Windows:
    pip install vgamepad

Usage:
    python drift_vgamepad.py [port]
    (Default port: 47801 or relay from Drift Receiver)
"""

import socket
import sys

def main():
    try:
        import vgamepad as vg
    except ImportError:
        print("[-] vgamepad not installed.")
        print("    Install via: pip install vgamepad")
        print("    (Requires ViGEmBus driver: https://github.com/nefarius/ViGEmBus/releases)")
        sys.exit(1)

    port = int(sys.argv[1]) if len(sys.argv) > 1 else 47800
    gamepad = vg.VX360Gamepad()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", port))

    print(f"[+] Drift Virtual Xbox 360 Controller Active!")
    print(f"[+] Listening for watch gyro packets on UDP port {port}...")

    try:
        while True:
            data, _ = sock.recvfrom(1024)
            msg = data.decode("utf-8", errors="ignore").strip()
            if not msg.startswith("wheel "):
                continue

            parts = msg.split()
            # Format: wheel <steer_float> <gas_0_1> <brake_0_1> <nitro_0_1>
            steer = float(parts[1]) if len(parts) > 1 else 0.0
            gas = parts[2] == "1" if len(parts) > 2 else False
            brake = parts[3] == "1" if len(parts) > 3 else False
            nitro = parts[4] == "1" if len(parts) > 4 else False

            # Map steering [-1.0, 1.0] -> Left Joystick X [-32767, 32767]
            joy_x = int(steer * 32767)
            joy_x = max(-32767, min(32767, joy_x))
            gamepad.left_joystick(x_value=joy_x, y_value=0)

            # Map Throttle -> Right Trigger (0 to 255)
            gamepad.right_trigger(value=255 if gas else 0)

            # Map Brake -> Left Trigger (0 to 255)
            gamepad.left_trigger(value=255 if brake else 0)

            # Map Nitro / Handbrake -> Button A (0x1000)
            if nitro:
                gamepad.press_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_A)
            else:
                gamepad.release_button(button=vg.XUSB_BUTTON.XUSB_GAMEPAD_A)

            gamepad.update()
    except KeyboardInterrupt:
        print("\n[*] Exiting Drift Virtual Gamepad.")
    finally:
        sock.close()

if __name__ == "__main__":
    main()
