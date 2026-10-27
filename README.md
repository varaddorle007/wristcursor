# Drift

**Your wrist, your cursor.** Drift turns a Samsung Galaxy Watch (any Wear OS 3+ watch with a gyroscope) into an air mouse, a touchpad, a TV remote, a media remote and a presentation remote.

## Two ways to connect

| | Bluetooth (default) | Wi-Fi |
| --- | --- | --- |
| Needs on the computer | nothing, it's a normal Bluetooth mouse | Drift Receiver |
| Delay | lowest | low |
| Battery | lowest | higher |
| Works with | Mac, Windows, Linux, iPad, Android phones, Android TV, Google TV, Fire TV | Mac, Windows, Linux |

Bluetooth mode needs the watch to support Android's Bluetooth HID device profile. Drift checks on first use and switches to Wi-Fi if the watch can't do it.

## Features

**Modes** (tap the mode name at the top of the steering screen to switch; the connection stays up)
- **Mouse:** tilt to move. Each gyro reading is filtered and sent as soon as it arrives (up to 100 a second). 1€ filter: steady when you move slowly, no lag when you move fast. Tap = click, double-tap = double-click, hold = right-click (or drag, if Hold to drag is on), bezel = scroll. The cursor freezes while your finger is down, so taps land where you aimed.
- **Touchpad:** the whole screen is a trackpad; the gyroscope and pinch are off. Drag to move with pointer acceleration (faster swipes go further) and an optional glide after a flick. Tap = click, hold = right-click, hold then drag = grab and drop, two fingers = scroll (two-finger tap = right-click), bezel = scroll. Swipe-to-go-back is off here so rightward drags never close the screen.
- **TV remote:** a D-pad ring with OK in the middle, plus Back and Home. Arrows repeat while held. The watch's own back button presses Back. Bezel = volume. Works with Android TV, Google TV and Fire TV over Bluetooth (they see the watch as a remote); over Wi-Fi to a computer, arrows/OK/Back become arrow keys, Enter and Escape.
- **Media:** volume up/down, previous/next (hold: rewind/fast-forward), play/pause (hold: mute). Bezel = volume. Works over Bluetooth and over Wi-Fi with Drift Receiver (not over Mac Screen Sharing).
- **Keyboard:** tap **Speak** and talk, or **Type** on the watch keyboard; the text lands where the computer's cursor is. Delete and Enter keys too. Bluetooth types like a US keyboard (letters, numbers, symbols); Wi-Fi with Drift Receiver pastes any language and emoji; Mac Screen Sharing types any language.
- **Slides:** big Back and Next buttons; the bezel flips slides.
- **Remap buttons:** every button on the TV remote and Media pads has a short-press and a long-press action you can change (Settings › Remap buttons).
- **Pinch gestures** (Mouse, Slides, Media): **double pinch** = click / next slide / play-pause; **triple pinch** = right-click / previous slide / next track. A single pinch does nothing, so stray jolts never click. Ignored while a finger is on the screen and just after.

**Aiming**
- Calibration asks you to point at the middle of your screen and hold still, so drift is measured in your real pointing pose; then three pinches tune pinch detection to your hand.
- Over Wi-Fi the cursor jumps to the middle of the screen when you start (point there as you tap Go), after calibration, and whenever you tap **Center**.

**Finding devices**
- **Wi-Fi:** lists everything on the network (computers, laptops, phones, tablets, TVs, speakers, printers, the router) with an icon, name and address, computers Drift can steer at the top. The watch brings up its own Wi-Fi first (Wear OS otherwise routes through the phone), then pings every address, tries a connection on the ones that ignore pings (a refusal still proves a device is there, which catches most phones), and asks devices their names over Bonjour, UPnP, NetBIOS and reverse DNS. Open ports tell a TV from a phone from a PC. A full scan takes about ten seconds; a phone whose Wi-Fi is asleep may only show up on the next scan.
- **Hidden laptops:** a laptop whose firewall ignores everything (Windows on a "Public" network does this) can't be found by any scan. Switch that network to Private, or use **Add by address** and type its IP.
- **Bluetooth:** paired and nearby devices with icons for computers, phones and TVs.

**Themes**
- Drift, Web, Inferno, Reactor, Synthwave, Terminal. Each recolors the app and draws its own background art, generated in code (no image files, nothing animating, no extra battery).

**Settings (all apply instantly, even while steering)**
- Theme picker with live previews
- Speed 1–10, Acceleration 0–5, Steadiness 1–5, Scroll speed 1–10. Adjust with the bezel, crown or −/+.
- Power: Smooth (100/s), Balanced (60/s), Saver (40/s)
- Touchpad speed 1–10, glide, gesture hints
- Vibration: Off, Light 25%, Medium 50%, Strong 75%, Max 100%
- Right wrist, reverse scroll
- Dim while steering, auto-stop after 5 quiet minutes
- Reset settings, forget computers, close Drift

**Battery and safety**
- Never runs under battery saver or below 15% battery, and stops itself if either happens mid-session
- Nothing runs in the background when you're not steering; no animations loop while steering
- Wi-Fi switches to low-latency mode only while steering

## Using it

**Bluetooth:** tap **Go**, allow Bluetooth, then on your computer open Bluetooth settings and pair with your watch's name. If it isn't listed, tap **Make visible** on the watch. Next time, Go reconnects by itself.

**Wi-Fi:** run Drift Receiver on the computer, tap the Bluetooth icon on the watch to switch to Wi-Fi, tap **Go**, then click **Allow** on the computer the first time.

**TV (Android TV, Google TV, Fire TV):** on the TV open Settings › Remotes & Accessories (Fire TV: Controllers & Bluetooth Devices) › Add accessory. On the watch pick Bluetooth, tap **Go**, then **Make visible**, and choose the watch on the TV. Switch to **TV remote** mode.

## Build

### Watch app
Open the folder in Android Studio, pick the `app` configuration and your watch, then Run. Or:
```bash
./gradlew :app:assembleDebug
```

### Drift Receiver (Wi-Fi mode only)
- **Mac app:** `./receiver/package-mac.sh` builds `receiver/build/mac/Drift Receiver.app` (includes its own Java). The first time, allow it under **System Settings → Privacy & Security → Accessibility**.
- **Any OS with Java 17+:** `./gradlew :receiver:jar`, then `java -jar receiver/build/libs/drift-receiver.jar`.

## Wi-Fi protocol

UTF-8 text over UDP, receiver port `47800`:

| Watch sends | Meaning |
| --- | --- |
| `DRIFT? <model>` | broadcast search; receiver answers `DRIFT! <computer name>` |
| `HELLO <id> <model>` | ask to connect; receiver answers `OK`, `WAIT` (click Allow) or `NO` |
| `m dx dy` | move pointer |
| `click` / `right` | mouse buttons |
| `s steps` | wheel steps, positive scrolls down |
| `key next` / `key prev` | right / left arrow |
| `key up` / `key down` | up / down arrow |
| `key enter` / `key esc` | Enter / Escape (remote OK / Back) |
| `BYE` | session ended |

## Project layout

```
app/        Wear OS app (Kotlin, Compose for Wear OS Material 3)
  core/     service, motion filter, Bluetooth HID, Wi-Fi link, LAN scanner, discovery, remap, haptics, settings
  ui/       theme, components, screens
receiver/   desktop receiver for Wi-Fi mode (Kotlin, Swing) and Mac packaging script
```

## Before publishing
- Put your contact email in `PRIVACY.md` and `TERMS.md`, and have a lawyer review both.
- Create a release signing key and Play Console listing.
- Check the name "Drift" for trademark conflicts in your market.
