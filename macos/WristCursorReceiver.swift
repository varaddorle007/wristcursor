import ApplicationServices
import Cocoa
import CoreBluetooth

private let serviceUUID = CBUUID(string: "8F27C794-C23D-4D71-A57F-134C9D853001")
private let pointerUUID = CBUUID(string: "8F27C794-C23D-4D71-A57F-134C9D853002")

final class CursorOutput {
    private let outputQueue = DispatchQueue(label: "com.wristcursor.cursor-output", qos: .userInteractive)
    private let lock = NSLock()
    private var buttons: UInt8 = 0
    private var desiredButtons: UInt8 = 0
    private var buttonTransitions: [UInt8] = []
    private var pendingX = 0
    private var pendingY = 0
    private var pendingWheel = 0
    private var flushScheduled = false

    func apply(_ packet: Data) {
        guard packet.count == 4 else { return }
        let values = [UInt8](packet)
        let nextButtons = values[0]
        let dx = Int(Int8(bitPattern: values[1]))
        let dy = Int(Int8(bitPattern: values[2]))
        let wheel = Int(Int8(bitPattern: values[3]))

        // CoreBluetooth invokes this on the main run loop. Never enqueue one main-thread task per
        // radio packet: that is how a fast wrist movement becomes a multi-second cursor tail.
        // Keep one latest-wins output task instead.
        lock.lock()
        if nextButtons != desiredButtons {
            if buttonTransitions.count == 8 { buttonTransitions.removeFirst() }
            buttonTransitions.append(nextButtons)
            desiredButtons = nextButtons
        }
        pendingX = max(-127, min(127, pendingX + dx))
        pendingY = max(-127, min(127, pendingY + dy))
        pendingWheel = max(-127, min(127, pendingWheel + wheel))
        if !flushScheduled {
            flushScheduled = true
            outputQueue.async { [weak self] in self?.flush() }
        }
        lock.unlock()
    }

    private func flush() {
        lock.lock()
        let dx = pendingX
        let dy = pendingY
        let wheel = pendingWheel
        let transitions = buttonTransitions
        pendingX = 0
        pendingY = 0
        pendingWheel = 0
        buttonTransitions.removeAll(keepingCapacity: true)
        flushScheduled = false
        lock.unlock()

        guard let currentEvent = CGEvent(source: nil) else { return }
        let current = currentEvent.location
        let target = CGPoint(x: current.x + CGFloat(dx), y: current.y + CGFloat(dy))

        for nextButtons in transitions {
            postButtonChanges(from: buttons, to: nextButtons, at: target)
            buttons = nextButtons
        }
        if buttons == 0 && (dx != 0 || dy != 0) {
            post(type: .mouseMoved, at: target, button: .left)
        } else if buttons & 1 != 0 && (dx != 0 || dy != 0) {
            post(type: .leftMouseDragged, at: target, button: .left)
        } else if buttons & 2 != 0 && (dx != 0 || dy != 0) {
            post(type: .rightMouseDragged, at: target, button: .right)
        }
        if wheel != 0,
           let scroll = CGEvent(
               scrollWheelEvent2Source: nil,
               units: .line,
               wheelCount: 1,
               wheel1: Int32(wheel),
               wheel2: 0,
               wheel3: 0) {
            scroll.post(tap: .cghidEventTap)
        }
    }

    private func postButtonChanges(from old: UInt8, to next: UInt8, at point: CGPoint) {
        if old & 1 != next & 1 {
            post(type: next & 1 != 0 ? .leftMouseDown : .leftMouseUp, at: point, button: .left)
        }
        if old & 2 != next & 2 {
            post(type: next & 2 != 0 ? .rightMouseDown : .rightMouseUp, at: point, button: .right)
        }
        if old & 4 != next & 4 {
            post(type: next & 4 != 0 ? .otherMouseDown : .otherMouseUp, at: point, button: .center)
        }
    }

    private func post(type: CGEventType, at point: CGPoint, button: CGMouseButton) {
        CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: point, mouseButton: button)?.post(tap: .cghidEventTap)
    }
}

final class Receiver: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private var central: CBCentralManager!
    private var watch: CBPeripheral?
    private let cursor = CursorOutput()
    private var reconnectTimer: Timer?
    private var lastSignal = Date.distantPast

    func run() {
        central = CBCentralManager(delegate: self, queue: nil)
        reconnectTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            self?.ensureConnection()
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            print("Bluetooth unavailable: \(central.state.rawValue)")
            return
        }
        scanForWatch()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard watch == nil else { return }
        watch = peripheral
        central.stopScan()
        peripheral.delegate = self
        print("Connecting to \(peripheral.name ?? "WristCursor Watch")…")
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        lastSignal = Date()
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        watch = nil
        lastSignal = Date.distantPast
        print("Watch disconnected; scanning again…")
        scanForWatch()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        watch = nil
        lastSignal = Date.distantPast
        print("Watch connection failed; scanning again…")
        scanForWatch()
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else { return }
        peripheral.discoverCharacteristics([pointerUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristic = service.characteristics?.first(where: { $0.uuid == pointerUUID }) else { return }
        peripheral.setNotifyValue(true, for: characteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if error == nil && characteristic.isNotifying {
            print("WristCursor BLE receiver ready")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.uuid == pointerUUID, let data = characteristic.value else { return }
        lastSignal = Date()
        cursor.apply(data)
    }

    private func ensureConnection() {
        guard central.state == .poweredOn else { return }
        if let watch {
            if watch.state == .disconnected {
                self.watch = nil
            } else if Date().timeIntervalSince(lastSignal) > 5 {
                print("Watch link stale; reconnecting…")
                lastSignal = Date()
                central.cancelPeripheralConnection(watch)
            }
        }
        if watch == nil {
            scanForWatch()
        }
    }

    private func scanForWatch() {
        guard central.state == .poweredOn, watch == nil else { return }
        print("Looking for WristCursor Watch…")
        central.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }
}

let prompt = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
if !AXIsProcessTrustedWithOptions(prompt) {
    let alert = NSAlert()
    alert.messageText = "Allow WristCursor Receiver"
    alert.informativeText = "macOS needs one permission before this app can move your cursor. Enable WristCursor Receiver in Accessibility, then leave this app running."
    alert.addButton(withTitle: "Open Accessibility Settings")
    alert.addButton(withTitle: "Not Now")
    NSApp.activate(ignoringOtherApps: true)
    if alert.runModal() == .alertFirstButtonReturn {
        NSWorkspace.shared.open(
            URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }
}
let receiver = Receiver()
receiver.run()
RunLoop.main.run()
