# Drift Architecture & Technical Specification

Drift (`com.drift.airmouse`) is an open-source, ultra-low-latency air mouse, gaming steering wheel controller, trackpad, and smart remote for Wear OS smartwatches (Samsung Galaxy Watch 4/5/6/7/Ultra, Google Pixel Watch, and Wear OS 3+ devices equipped with a gyroscope and accelerometer).

This document outlines the end-to-end software architecture, sensor processing mathematics, Bluetooth HID descriptor layouts, network protocols, and desktop companion integration.

---

## 1. System Architecture Overview

```
+-------------------------------------------------------------------------+
|                              WEAR OS WATCH                              |
|                                                                         |
|   +-------------------+    +--------------------+    +--------------+   |
|   |   Sensors (FIFO)  |    |  Jetpack Compose   |    |   Settings   |   |
|   | Gyro / Accel / RV |    |  Material 3 Wear   |    |   AppPrefs   |   |
|   +---------+---------+    +---------+----------+    +-------+------+   |
|             |                        |                       |          |
|             v                        v                       |          |
|   +------------------------------------------------------+   |          |
|   |               PointerService (Foreground)            |<--+          |
|   |   Dedicated motion thread (THREAD_PRIORITY_DISPLAY)  |              |
|   |   +--------------------+    +--------------------+   |              |
|   |   |   MotionFilter     |    |   PinchDetector    |   |              |
|   |   | (1€ filter + dead) |    |  (Jerk analysis)   |   |              |
|   |   +---------+----------+    +---------+----------+   |              |
|   +-------------|-------------------------|--------------+              |
|                 |                         |                             |
|                 +------------+------------+                             |
|                              |                                          |
|                              v                                          |
|                   PointerLink (Abstraction)                             |
|                 /            |             \                            |
|                /             |              \                           |
+---------------/--------------|---------------\--------------------------+
               /               |                \
   Bluetooth HID (Driverless)  | Wi-Fi (UDP)     \ Wi-Fi (VNC / RFB)
             /                 |                  \
            v                  v                   v
+------------------+  +--------------------+  +-------------------------+
| Host PC/Mac/TV   |  | Drift Receiver     |  | macOS Screen Sharing    |
| (Native Mouse,   |  | (UDP Port 47800    |  | (Native RFB / Port 5900 |
|  Keyboard,       |  |  Robot input,      |  |  Zero companion software|
|  Consumer Media, |  |  vgamepad virtual  |  |  Apple credentials auth)|
|  Gamepad)        |  |  Xbox controller)  |  |                         |
+------------------+  +--------------------+  +-------------------------+
```

---

## 2. Motion Sensing & Filtering Pipeline

### 2.1 Sensor Acquisition & Threading
Time-critical sensor processing runs on an isolated `HandlerThread("drift-motion", Process.THREAD_PRIORITY_DISPLAY)`:
- **Gyroscope**: `Sensor.TYPE_GYROSCOPE` sampled at `10,000 µs` (100 Hz / Smooth), `16,667 µs` (60 Hz / Balanced), or `25,000 µs` (40 Hz / Saver). Registered with `maxReportLatencyUs = 0` for direct, zero-batching hardware FIFO delivery.
- **Rotation Vector**: `Sensor.TYPE_GAME_ROTATION_VECTOR` (or `TYPE_ROTATION_VECTOR`) sampled for controller gyroscope steering.
- **Accelerometer**: `Sensor.TYPE_ACCELEROMETER` sampled continuously at 100 Hz for gesture detection (pinch, shake, knock, pull).

### 2.2 Mathematical Model (1€ Filter & Deadband Shaping)
To turn raw gyroscope angular velocity ($\text{rad/s}$) into smooth cursor movement without delay:

1. **Adaptive 1€ Filter**:
   $$\alpha(dt, f_c) = \frac{1}{1 + \frac{1}{2\pi f_c \cdot dt}}$$
   $$\hat{x}_k = \alpha_k x_k + (1 - \alpha_k) \hat{x}_{k-1}$$
   Where dynamic cutoff frequency $f_c$ scales with speed:
   $$f_c = f_{c_{min}} + \beta \cdot |\dot{x}|$$
   - At rest or slow speeds: $f_c \approx f_{c_{min}} \in [1.0, 5.5]\,\text{Hz}$, smoothing micro-tremor and physiological pulse jitter.
   - During flicks or repositioning: $f_c$ automatically climbs to $30\text{--}60\,\text{Hz}$, reducing phase lag to practically zero.

2. **Deadzone & Sub-linear Hover Easing**:
   $$\text{live} = \max(0, |\omega| - \text{deadZone})$$
   When aiming at small on-screen targets ($\text{live} < \text{HOVER\_THRESHOLD}$), an easing curve attenuates involuntary drift:
   $$\text{hoverScale} = 0.35 + 0.65 \left(\frac{\text{live}}{\text{HOVER\_THRESHOLD}}\right)^2$$
   $$\text{shapedLive} = \text{live} \cdot \text{hoverScale} \cdot (1 + \text{accel} \cdot \text{live})$$

3. **Symmetric Mid-Tread Sub-Pixel Quantization**:
   Instead of truncating with `.toInt()` (which creates an asymmetric 2-pixel deadzone upon direction change), sub-pixel residuals are accumulated and quantized using symmetric rounding:
   $$x = \text{round}(\text{pendingX}), \quad \text{pendingX} \leftarrow \text{pendingX} - x$$
   This bounds residual error to $[-0.5, +0.5]$ pixels, ensuring instant micro-reversal responsiveness.

---

## 3. Gesture Detection Engine

| Gesture | Sensor Mechanism | Timing & Thresholds | Default Action |
| --- | --- | --- | --- |
| **Single Pinch** | Accelerometer jerk derivative $\frac{d\vec{a}}{dt}$ | Threshold: $300\,\text{m/s}^3$ (calibrated), gap: $240\,\text{ms}$, release quiet: $60\,\text{ms}$ | **Right Click** |
| **Double Pinch** | Two jerk peaks within inter-tap window | Gap: $160\text{--}240\,\text{ms}$ | **Left Click** |
| **Pinch & Hold** | Fingers stay closed longer than $350\,\text{ms}$ | $\text{holdDuration} > 350\,\text{ms}$, release jolt: $\text{threshold} \times 0.55$ | **Drag / Hold Button** |
| **Wrist Tilt Scroll** | Gravity projection on forearm axis (Y/Z) | Active when pitch $> 15^\circ$ or $< -15^\circ$; scroll rate: $35\text{--}220\,\text{ms}$ | **Scroll Up / Down** |
| **Wrist Shake** | Alternating linear acceleration swings | 4 directional reversals $> 10\text{--}17\,\text{m/s}^2$ within $1.0\,\text{s}$ | **Browser Back** |
| **Double Knock** | Sharp casing/wrist impact jerk | 2 impacts $> 320\,\text{m/s}^3$ within $160\text{--}600\,\text{ms}$ | **Configurable (Off)** |
| **Pull-Pull** | Rapid backward arm retractions | 2 negative linear $a_y$ pulses $> 5.5\,\text{m/s}^2$ within $180\text{--}750\,\text{ms}$ | **Configurable (Back)** |

---

## 4. Bluetooth HID Protocol & Descriptors

Drift implements a composite Bluetooth Human Interface Device (HID) Profile over Android's `BluetoothHidDevice`. The watch presents itself as a combo peripheral requiring no host software:

### 4.1 HID Report Descriptors
- **Report ID 1 (Mouse, 6 bytes)**:
  - Byte 0: Buttons bitmask (Bit 0: Left, Bit 1: Right, Bit 2: Middle)
  - Bytes 1-2: Relative X displacement (16-bit signed integer, $-32767\dots+32767$)
  - Bytes 3-4: Relative Y displacement (16-bit signed integer, $-32767\dots+32767$)
  - Byte 5: Vertical scroll wheel (8-bit signed integer, $-127\dots+127$)
- **Report ID 2 (Keyboard, 8 bytes)**:
  - Byte 0: Modifiers bitmask (Shift, Ctrl, Alt, GUI)
  - Byte 1: Reserved (`0x00`)
  - Bytes 2-7: 6 simultaneous key codes (USB HID Usage Table)
- **Report ID 3 (Consumer Media / Remote, 2 bytes)**:
  - Bytes 0-1: 16-bit consumer usage (Volume Up/Down, Mute, Play/Pause, Next/Prev Track, Home, Back, Menu)
- **Report ID 4 (Gamepad Controller, 5 bytes)**:
  - Bytes 0-1: 16-bit X-axis (Steering angle, $-32767\dots+32767$)
  - Bytes 2-3: 16-bit Y-axis (Throttle/Brake, $-32767\dots+32767$)
  - Byte 4: 8 digital buttons (Gas, Brake, Nitro, Handbrake, Pause)

### 4.2 BLE HID Advertising
When in discovery/waiting mode, Drift initiates a connectable BLE advertisement with `ADVERTISE_MODE_LOW_LATENCY`, `ADVERTISE_TX_POWER_HIGH`, and standard HID Service UUID `00001812-0000-1000-8000-00805f9b34fb`. This ensures discovery by macOS, iOS/iPadOS, Windows 11, and Android TV BLE scanners.

---

## 5. Wi-Fi Protocol & Desktop Receiver

### 5.1 UDP Transport Specification (Port 47800)
For computers running **Drift Receiver**, commands are sent as lightweight UTF-8 text datagrams:

```
DRIFT? <model>                  # Broadcast discovery probe
DRIFT! <computer_name>          # Receiver announcement reply
HELLO <device_id> <model>       # Pair request
OK / WAIT / NO                  # Receiver handshake responses
m <dx> <dy>                     # Relative pointer motion
click                           # Left button click (Down -> 35ms -> Up)
right                           # Right button click
double                          # True double click (Down -> Up -> 60ms -> Down -> Up)
down / up                       # Left button press and hold / release (drag)
s <steps>                       # Scroll wheel steps
wheel <steer> <gas> <brk> <ntr> # Game controller metrics (steer: -1.0..+1.0)
key <key_name>                  # Key press (next, prev, up, down, enter, esc, etc.)
BYE                             # Session teardown
```

### 5.2 Desktop Companion (`receiver/`)
- Written in Kotlin and Java Swing (`com.drift.receiver`).
- Simulates native OS input via `java.awt.Robot` with high-resolution sub-millisecond thread timing.
- Includes virtual gamepad bridge (`drift_vgamepad.py`) supporting virtual Xbox 360 / DualShock 4 controllers for PC games (Forza, Assetto Corsa, BeamNG.drive).
- Native macOS packaging script (`./receiver/package-mac.sh`) bundles an isolated Java runtime into `Drift Receiver.app`.

---

## 6. Safety, Power & Wear OS Integration

1. **Battery Gate**: Automatically halts sensing and alerts the user if battery falls below 15% or system enters Battery Saver mode.
2. **Wi-Fi Low-Latency Lock**: Acquires `WIFI_MODE_FULL_LOW_LATENCY` only while an active steering session is in progress; releases immediately upon pause or exit.
3. **Screen Dimming**: Automatically drops display brightness to minimum during active steering while preserving touch input to conserve OLED power.
4. **Auto-Stop**: Shuts down the background service after 5 consecutive minutes of inactivity.
