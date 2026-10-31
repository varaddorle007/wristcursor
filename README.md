# Drift

**Your wrist, your cursor.** Drift transforms your Wear OS smartwatch (Samsung Galaxy Watch 4/5/6/7/Ultra, Pixel Watch, or any Wear OS 3+ smartwatch equipped with a gyroscope) into an ultra-low-latency air mouse, gaming steering controller, trackpad, TV remote, media control, and presentation clicker.

Connect driverless over **Bluetooth HID** to Macs, PCs, Android TVs, and tablets—or connect over **Wi-Fi** using the companion desktop receiver or Mac Screen Sharing.

---

## Connectivity: Universal & Driverless

| | Bluetooth HID (Recommended) | Wi-Fi Direct (Desktop Receiver) | Mac Screen Sharing (VNC) |
| --- | --- | --- | --- |
| **Driver / App on Host** | **None** (Native Bluetooth Mouse/Gamepad) | Drift Receiver (`.jar` or Mac `.app`) | **None** (Built-in macOS Sharing) |
| **Latency** | Sub-10ms (Real-time 100 Hz report delivery) | ~15ms (Low-latency UDP) | ~20ms (Standard RFB protocol) |
| **Supported Devices** | macOS, Windows, Linux, iPadOS, iOS, Android, Android TV, Google TV, Fire TV | Windows, macOS, Linux | macOS |
| **Discovery** | Live scanning of paired & nearby devices + 1-tap "Make watch discoverable" + BLE HID advertisement | Automatic mDNS / UDP broadcast + manual IP entry | Bonjour LAN auto-discovery + manual IP |

---

## Operating Modes

Switch modes on the fly by tapping the header on the steering screen:

1. **Mouse (Air Mouse)**:
   - **Motion**: Wrist orientation rates (rad/s) pass through a 1€ filter and progressive acceleration curve. Heavy smoothing when moving slowly for pixel-precise button targeting; zero phase lag when flicking or moving fast.
   - **Clicks**:
     - Single Tap on screen = Left Click.
     - Double Tap on screen = Left Double Click.
     - Triple Tap on screen = Right Click.
     - Screen Touch Freeze: Cursor automatically stabilizes during finger taps to eliminate target nudge.
   - **Wrist Tilt Scroll**: Tilt wrist up to scroll up, down to scroll down. Speed progressively scales with tilt angle.
   - **Physical Rotating Bezel**: Turn bezel clockwise/counterclockwise to scroll up or down.

2. **Game (Controller Gyroscope Steering)**:
   - **1:1 Gyroscope Steering**: 3D orientation vector projection calculates true steering angle in degrees relative to calibrated wrist position. Zero-latency 1€ filter provides instant cornering response and stable straightaways.
   - **Rotating Bezel Steering**: Turn the physical rotating bezel to adjust or steer vehicles.
   - **Dedicated Touch Pedals**: Left pedal = Brake/Reverse, Right pedal = Gas/Throttle. Strict mutual exclusion ensures active braking overrides throttle.
   - **Tactile Pause / Resume**: Symmetrical pause button opposite Settings pauses sensors and sends `ESC` to bring up in-game pause menus.
   - **Dual Output**: Sends simultaneous Gamepad HID reports (Report ID 4) and crisp keyboard mappings (`A`/`D`, Left/Right arrows, `W`/`S`, Spacebar for Nitro) for out-of-the-box compatibility with all PC/web games.

3. **Touchpad**:
   - The watch display becomes a high-precision multi-touch trackpad (gyroscope disabled).
   - Drag finger to move, flick for inertia glide, tap for left click, hold for right click, drag after hold to select/drag items, two fingers to scroll.

4. **TV Remote**:
   - D-Pad navigation ring with central OK button, Back, and Home.
   - Physical rotating bezel controls volume up/down.
   - Works natively with Android TV, Google TV, Apple TV, and Fire TV.

5. **Media Controller**:
   - Volume control, Previous / Next track, Play / Pause.
   - Rotating bezel adjusts volume smoothly.

6. **Keyboard & Dictation**:
   - **Speak**: Voice-to-text dictation typed directly where the host cursor is positioned.
   - **Type**: On-screen watch keyboard sends keystrokes with Enter and Backspace.

7. **Slides (Presentation Remote)**:
   - Big Next and Back click targets with haptic feedback. Bezel advances or steps back slides.

---

## Gestures

- **Single Pinch**: Thumb meets index finger once. Default: **Right Click** (configurable). Emits crisply with sub-240ms latency.
- **Double Pinch**: Thumb meets index finger twice in quick succession. Default: **Left Click** (configurable).
- **Pinch and Hold**: Thumb meets index finger and stays closed. Default: **Hold Button / Drag** until fingers part.
- **Wrist Tilt Scroll**: Tilt hand upward to scroll up; tilt hand downward to scroll down.
- **Wrist Shake**: A quick back-and-forth wrist flick triggers **Browser Back**, pause, or custom remapped action.
- **Double Knock**: Sharp double tap on wrist or watch case. Configurable in Settings.
- **Pull-Pull**: Two quick backward arm retractions. Configurable in Settings.

---

## Pairing Guide

### Pairing over Bluetooth (PCs, Macs, TVs, Tablets):
1. Open **Drift** on the watch and tap **Go** or **Connection** in Settings.
2. Under Bluetooth:
   - **If your device is scanning**: Tap **"Make watch discoverable"** on the watch. The watch becomes visible to all nearby TVs, PCs, and tablets for 3 minutes.
   - **On your device**: Select the watch (e.g., "Galaxy Watch" or "Drift") in Bluetooth settings and confirm pairing.
   - **Scanning from watch**: Tap **"Scan again"** to discover nearby host devices and initiate pairing directly from the watch.
3. Once paired, Drift connects automatically.

### Pairing over Wi-Fi (Drift Receiver):
1. Run Drift Receiver on your PC or Mac. Ensure both watch and computer are on the same Wi-Fi.
2. In Drift, select **Wi-Fi** mode.
3. Drift automatically detects the receiver. Tap **Go** and click **Allow** on the computer.

---

## Building from Source

### Requirements:
- Android Studio Ladybug / Meerkat or later
- Android SDK 35, Build Tools 35.0.0
- JDK 17 or later

### Watch App (`app`):
```bash
# Run unit tests and screenshot verifications
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug

# Deploy directly to connected watch via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Desktop Receiver (`receiver`):
```bash
# Build standalone runnable JAR
./gradlew :receiver:jar

# Run Receiver on desktop
java -jar receiver/build/libs/drift-receiver.jar

# Build native macOS .app package (with bundled runtime)
./receiver/package-mac.sh
```

---

## Project Structure

```
wristcursor/
├── app/                                 # Wear OS Application (Compose for Wear OS Material 3)
│   ├── src/main/java/com/drift/airmouse/
│   │   ├── core/                        # Service, MotionFilter, HidLink, VncLink, WifiLink, PinchDetector, AppPrefs
│   │   └── ui/                          # Compose Screens (HomeScreen, SteerScreen, DevicesScreen, SettingsScreen)
│   └── src/test/java/com/drift/airmouse/ # Unit tests & Roborazzi screenshot verification tests
├── receiver/                            # Desktop Companion Receiver (Kotlin / Swing Robot input)
│   ├── src/main/kotlin/com/drift/receiver/
│   └── drift_vgamepad.py               # Virtual Xbox / PlayStation gamepad bridge script
└── docs/                                # Documentation and guides
```

---

## Open Source & Licensing

Drift is open-source software licensed under the [Apache License, Version 2.0](LICENSE).
Contributions, bug reports, and pull requests are welcome!
