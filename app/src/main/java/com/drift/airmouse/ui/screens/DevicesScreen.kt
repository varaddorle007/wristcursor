package com.drift.airmouse.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.RemoteInput
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.drift.airmouse.R
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.BluetoothHost
import com.drift.airmouse.core.BluetoothSupport
import com.drift.airmouse.core.Computer
import com.drift.airmouse.core.Credentials
import com.drift.airmouse.core.DeviceKind
import com.drift.airmouse.core.NetDevice
import com.drift.airmouse.core.HidLink
import com.drift.airmouse.core.LanScanner
import com.drift.airmouse.core.LinkState
import com.drift.airmouse.core.PointerBus
import com.drift.airmouse.core.PointerService
import com.drift.airmouse.core.Transport
import com.drift.airmouse.ui.BackEdgeButton
import com.drift.airmouse.ui.Caption
import com.drift.airmouse.ui.ListScreen
import com.drift.airmouse.ui.kindIcon
import kotlinx.coroutines.launch

private const val INPUT_KEY = "text"

/**
 * How Drift connects, kept simple: Bluetooth or Wi-Fi at the top, then the devices
 * for that choice. Bluetooth lists paired and nearby devices (computers, TVs, phones);
 * Wi-Fi lists everything on the network, with the computers Drift can drive at the top.
 * A green dot marks the connected one.
 */
@Composable
fun DevicesScreen(onBack: () -> Unit, onSteer: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val scope = rememberCoroutineScope()
    val transport by prefs.transport.collectAsState()
    val wifiComputer by prefs.computer.collectAsState()
    val chosen by prefs.bluetoothHost.collectAsState()
    val state by PointerBus.state.collectAsState()
    val running = state !is LinkState.Idle
    val steering = state == LinkState.Steering
    var note by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var offer by remember { mutableStateOf<BluetoothHost?>(null) }

    /** Save a new choice; if steering, reconnect straight away to what was just picked. */
    fun switched() {
        note = null
        info = null
        offer = null
        if (running) PointerService.restart(context)
    }

    /** Like any Bluetooth mouse: picking a device connects to it and opens the pad. */
    fun connectNow() {
        note = null
        info = null
        offer = null
        if (running) PointerService.restart(context) else PointerService.start(context)
        onSteer()
    }

    // --- Bluetooth state ---
    var allowed by remember { mutableStateOf(HidLink.hasPermission(context)) }
    var canScan by remember { mutableStateOf(hasScanPermission(context)) }
    var paired by remember { mutableStateOf(emptyList<BluetoothHost>()) }
    var nearby by remember { mutableStateOf(emptyList<BluetoothHost>()) }
    var scanning by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<String?>(null) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = HidLink.hasPermission(context)
        canScan = hasScanPermission(context)
        if (allowed && canScan && transport == Transport.BLUETOOTH) scanning = true
    }

    // --- Wi-Fi state ---
    var devices by remember { mutableStateOf(emptyList<NetDevice>()) }
    var lanScanning by remember { mutableStateOf(false) }
    var noWifi by remember { mutableStateOf(false) }
    var lanRound by remember { mutableIntStateOf(0) }
    var signingIn by remember { mutableStateOf<Computer?>(null) }
    var typedUser by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    // For a computer the scan can't see (a laptop whose firewall hides it): type its address.
    val typeAddress = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val typed = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(INPUT_KEY)?.toString()?.trim().orEmpty()
        when {
            typed.isEmpty() -> Unit
            android.net.InetAddresses.isNumericAddress(typed) -> {
                prefs.saveComputer(Computer(typed, typed))
                switched()
            }
            else -> note = "That doesn't look like an address (like 192.168.1.20)"
        }
    }

    val keyboard = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = signingIn ?: return@rememberLauncherForActivityResult
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(INPUT_KEY)?.toString()?.trim().orEmpty()
        when {
            text.isEmpty() -> {
                signingIn = null
                typedUser = null
            }
            typedUser == null -> {
                typedUser = text
                askPassword = true
            }
            else -> {
                Credentials.save(context, target.name, Credentials.Login(typedUser!!, text))
                prefs.saveComputer(target)
                switched()
                signingIn = null
                typedUser = null
            }
        }
    }
    LaunchedEffect(askPassword) {
        if (askPassword) {
            askPassword = false
            keyboard.launch(textInput("Mac password"))
        }
    }

    fun pick(next: Transport) {
        if (next == transport) return
        when {
            next == Transport.WIFI -> {
                prefs.setTransport(Transport.WIFI)
                switched()
            }
            !allowed -> askBluetooth.launch(HidLink.PERMISSIONS + SCAN_PERMISSIONS)
            else -> scope.launch {
                if (BluetoothSupport.canBeMouse(context)) {
                    prefs.setTransport(Transport.BLUETOOTH)
                    switched()
                } else {
                    note = "This watch can't be a Bluetooth mouse"
                }
            }
        }
    }

    fun useComputer(computer: Computer) {
        when {
            computer.kind == Computer.Kind.OTHER ->
                note = "Drift can't control ${computer.name}. On a Mac, turn on Screen Sharing in System Settings › General › Sharing."
            computer.kind == Computer.Kind.SCREEN_SHARING &&
                (Credentials.load(context, computer.name) == null || computer.name == wifiComputer?.name) -> {
                // First time (or tapping the current one again): sign in with the Mac's own login.
                signingIn = computer
                typedUser = null
                keyboard.launch(textInput("Mac username"))
            }
            else -> {
                prefs.saveComputer(computer)
                connectNow()
            }
        }
    }

    // Bluetooth: paired list, and scanning for nearby devices whenever this screen is open.
    LaunchedEffect(allowed, state) { paired = if (allowed) pairedDevices(context) else emptyList() }
    LaunchedEffect(transport, allowed, canScan) {
        if (transport == Transport.BLUETOOTH && allowed && canScan) scanning = true
    }
    DisposableEffect(scanning, pairing) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> foundDevice(intent)?.let { found ->
                        if (paired.none { it.address == found.address } && nearby.none { it.address == found.address }) {
                            nearby = nearby + found
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        paired = pairedDevices(context)
                        nearby = nearby.filter { n -> paired.none { it.address == n.address } }
                        paired.firstOrNull { it.address == pairing }?.let {
                            prefs.saveBluetoothHost(it)
                            pairing = null
                            // Paired from the watch: go straight to steering it.
                            connectNow()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (scanning) startScan(adapter)
        onDispose {
            context.unregisterReceiver(receiver)
            if (scanning) stopScan(adapter)
        }
    }

    /** The phone this watch is paired with, if Bluetooth permission allows looking. */
    fun companionPhone(): BluetoothHost? =
        if (allowed) pairedDevices(context).singleOrNull { it.kind == DeviceKind.PHONE } else null

    /** Tapping something Drift can't drive over Wi-Fi says how to control it instead. */
    fun explain(device: NetDevice) {
        val phone = companionPhone()
        offer = if (device.kind in BLUETOOTH_KINDS && phone != null && device.name == phone.name) phone else null
        if (offer != null) {
            info = "${device.name} is paired with this watch. Control it over Bluetooth:"
            return
        }
        info = when {
            device.self -> "That's this watch."
            device.kind == DeviceKind.COMPUTER || device.kind == DeviceKind.LAPTOP ->
                "To use ${device.name} over Wi-Fi, open Drift Receiver on it (or turn on Screen Sharing on a Mac). Or pair it over Bluetooth."
            device.kind in BLUETOOTH_KINDS ->
                "Control ${device.name} over Bluetooth: pick Bluetooth above, then add this watch in its Bluetooth settings."
            else -> "Drift can't control a ${device.kind.label.lowercase()}."
        }
    }

    // Wi-Fi: scan the whole network whenever Wi-Fi is picked. Devices appear as they're found.
    LaunchedEffect(transport, lanRound) {
        if (transport != Transport.WIFI) return@LaunchedEffect
        lanScanning = true
        noWifi = false
        val main = ContextCompat.getMainExecutor(context)
        val outcome = LanScanner.scan(context, companionPhone()?.name) { found -> main.execute { devices = found } }
        noWifi = outcome == LanScanner.Outcome.NO_WIFI
        lanScanning = false
    }

    ListScreen(edgeButton = { BackEdgeButton(onBack) }) {
        item { ListHeader { Text("Connect with") } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Choice(R.drawable.ic_bluetooth, "Bluetooth", transport == Transport.BLUETOOTH) { pick(Transport.BLUETOOTH) }
                Choice(R.drawable.ic_wifi, "Wi-Fi", transport == Transport.WIFI) { pick(Transport.WIFI) }
            }
        }
        note?.let { message -> item { Caption(message, color = MaterialTheme.colorScheme.error) } }
        info?.let { message -> item { Caption(message, color = MaterialTheme.colorScheme.onSurface) } }
        offer?.let { host ->
            item {
                Row(R.drawable.ic_bluetooth, "Use Bluetooth", "Control ${host.name}", Status.NONE) {
                    prefs.saveBluetoothHost(host)
                    if (transport == Transport.BLUETOOTH) {
                        switched()
                    } else {
                        pick(Transport.BLUETOOTH)
                    }
                    info = "Tap Go. ${host.name} shows a cursor you can move."
                }
            }
        }

        when (transport) {
            Transport.BLUETOOTH -> if (!allowed) {
                item { Row(R.drawable.ic_bluetooth, "Allow Bluetooth", "Needed to connect", Status.NONE) { askBluetooth.launch(HidLink.PERMISSIONS + SCAN_PERMISSIONS) } }
            } else {
                item { Caption("Paired") }
                if (paired.isEmpty()) item { Caption("Nothing yet") }
                paired.forEach { host ->
                    item {
                        val picked = host.address == chosen?.address
                        val status = when {
                            picked && steering -> Status.CONNECTED
                            picked -> Status.READY
                            else -> Status.AVAILABLE
                        }
                        Row(btIcon(host), host.name, if (status == Status.CONNECTED) "Connected" else "Tap to connect", status) {
                            when {
                                // Already steering this one: just open the pad.
                                status == Status.CONNECTED -> onSteer()
                                else -> {
                                    prefs.saveBluetoothHost(host)
                                    connectNow()
                                }
                            }
                        }
                    }
                }
                item { Caption(if (scanning) "Nearby · scanning…" else "Nearby") }
                nearby.forEach { host ->
                    item {
                        Row(btIcon(host), host.name, if (host.address == pairing) "Pairing… confirm on the device" else "Tap to pair", Status.NONE) {
                            pairing = host.address
                            if (!startPairing(context, host.address)) note = "Couldn't start pairing"
                        }
                    }
                }
                if (!scanning) {
                    item {
                        Row(R.drawable.ic_bluetooth, "Scan again", "", Status.NONE) {
                            if (canScan) scanning = true else askBluetooth.launch(SCAN_PERMISSIONS)
                        }
                    }
                }
                item { Caption("TV: open its Bluetooth (or Remotes & accessories) settings and add this watch.") }
            }

            Transport.WIFI -> {
                item { Caption("Put the watch and your device on the same Wi-Fi.") }
                val saved = wifiComputer
                val controllable = devices.filter { it.computer != null }
                val savedHere = saved != null && controllable.any { d ->
                    val c = d.computer!!
                    c.name == saved.name || c.host == saved.host || LanScanner.sameDevice(c.name, saved.name)
                }
                if (controllable.isNotEmpty() || saved != null) item { Caption("Control") }
                controllable.forEach { device ->
                    item {
                        val computer = device.computer!!
                        val picked = saved != null &&
                            (computer.name == saved.name || computer.host == saved.host || LanScanner.sameDevice(computer.name, saved.name))
                        val status = when {
                            picked && steering -> Status.CONNECTED
                            picked -> Status.READY
                            else -> Status.AVAILABLE
                        }
                        val detail = when {
                            status == Status.CONNECTED -> "Connected"
                            computer.kind == Computer.Kind.SCREEN_SHARING && Credentials.load(context, computer.name) == null -> "Tap to sign in"
                            picked && computer.kind == Computer.Kind.SCREEN_SHARING -> "Ready · tap to sign in again"
                            picked -> "Ready · ${device.ip}"
                            else -> "Tap to use · ${device.ip}"
                        }
                        Row(kindIcon(device.kind), device.name, detail, status) {
                            if (status == Status.CONNECTED) onSteer() else useComputer(computer)
                        }
                    }
                }
                if (saved != null && !savedHere) {
                    item {
                        Row(
                            R.drawable.ic_laptop,
                            saved.name,
                            if (lanScanning) "Looking for it…" else "Not on this Wi-Fi right now",
                            Status.READY,
                        ) {
                            if (steering) onSteer() else useComputer(saved)
                        }
                    }
                }
                val others = devices.filter { it.computer == null }
                if (others.isNotEmpty()) item { Caption("On this Wi-Fi · ${devices.size}") }
                others.forEach { device ->
                    item { Row(kindIcon(device.kind), device.name, subtitle(device), Status.NONE) { explain(device) } }
                }
                when {
                    noWifi -> item {
                        Caption("Couldn't turn on the watch's Wi-Fi. Check Settings › Connections › Wi-Fi.", color = MaterialTheme.colorScheme.error)
                    }
                    lanScanning -> item { Caption(if (devices.isEmpty()) "Scanning your Wi-Fi…" else "Scanning… ${devices.size} found") }
                    else -> item { Row(R.drawable.ic_wifi, "Scan again", "", Status.NONE) { lanRound++ } }
                }
                item {
                    Row(R.drawable.ic_add, "Add by address", "For a computer the scan can't see", Status.NONE) {
                        typeAddress.launch(textInput("Computer address, like 192.168.1.20"))
                    }
                }
            }
        }
    }
}

private enum class Status(val label: String) {
    CONNECTED("Connected"),
    READY("Ready"),
    AVAILABLE("Tap to use"),
    NONE(""),
}

/** One of the two big mode buttons at the top. */
@Composable
private fun Choice(@DrawableRes icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) colors.primary else colors.surfaceContainer)
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) colors.onPrimary else colors.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selected) colors.onPrimary else colors.onSurface)
    }
}

/** A device row: what it is, its name, what's going on with it, and a dot when it's yours. */
@Composable
private fun Row(@DrawableRes icon: Int?, name: String, detail: String, status: Status, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dot = when (status) {
        Status.CONNECTED -> Color(0xFF34D399)
        Status.READY -> colors.primary
        Status.AVAILABLE -> colors.outline
        Status.NONE -> Color.Transparent
    }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(colors.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = name, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (status == Status.CONNECTED) dot else colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    fontSize = 11.sp,
                    color = if (status == Status.CONNECTED) dot else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (status != Status.NONE && status != Status.AVAILABLE) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        }
    }
}

private fun textInput(label: String): Intent {
    val input = RemoteInput.Builder(INPUT_KEY).setLabel(label).build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
        RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(input))
    }
}

private val SCAN_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasScanPermission(context: Context): Boolean =
    SCAN_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Every device paired with this watch, computers first. */
@SuppressLint("MissingPermission") // only called once HidLink.hasPermission() is true
private fun pairedDevices(context: Context): List<BluetoothHost> {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    val devices = runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
    return devices
        .sortedBy { if (it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER) 0 else 1 }
        .map { BluetoothHost(it.address, it.name ?: it.address, kindOf(it)) }
}

/** Scans for about 12 seconds; the system stops it on its own. */
@SuppressLint("MissingPermission") // checked by hasScanPermission() before scanning starts
private fun startScan(adapter: BluetoothAdapter?) {
    if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
    adapter?.startDiscovery()
}

@SuppressLint("MissingPermission")
private fun stopScan(adapter: BluetoothAdapter?) {
    runCatching { adapter?.cancelDiscovery() }
}

@SuppressLint("MissingPermission")
private fun foundDevice(intent: Intent): BluetoothHost? {
    val device = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    } ?: return null
    val name = device.name?.takeIf { it.isNotBlank() } ?: return null // skip nameless beacons
    return BluetoothHost(device.address, name, kindOf(device))
}

/** Asks the other device to pair with this watch; it shows a confirm prompt there. */
@SuppressLint("MissingPermission")
private fun startPairing(context: Context, address: String): Boolean {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
    runCatching { adapter.cancelDiscovery() }
    return runCatching { adapter.getRemoteDevice(address).createBond() }.getOrDefault(false)
}

/** "Chromecast · 192.168.1.20", or "TV · 192.168.1.20" when there's no model. */
private fun subtitle(device: NetDevice): String {
    val what = device.detail?.takeIf { !it.equals(device.name, ignoreCase = true) }
        ?: device.kind.label.takeIf { device.kind != DeviceKind.UNKNOWN && !device.name.equals(it, ignoreCase = true) }
    return listOfNotNull(what, device.ip).joinToString(" · ")
}

private fun btIcon(host: BluetoothHost): Int =
    if (host.kind == DeviceKind.UNKNOWN) R.drawable.ic_bluetooth else kindIcon(host.kind)

/** Things the watch can control as a Bluetooth mouse, keyboard and remote. */
private val BLUETOOTH_KINDS = setOf(DeviceKind.TV, DeviceKind.PHONE, DeviceKind.TABLET, DeviceKind.CONSOLE, DeviceKind.UNKNOWN)

/** What a Bluetooth device is, from the class it announces. */
@SuppressLint("MissingPermission")
private fun kindOf(device: BluetoothDevice): DeviceKind {
    val type = runCatching { device.bluetoothClass }.getOrNull() ?: return DeviceKind.UNKNOWN
    return when (type.majorDeviceClass) {
        BluetoothClass.Device.Major.COMPUTER ->
            if (type.deviceClass == BluetoothClass.Device.COMPUTER_LAPTOP) DeviceKind.LAPTOP else DeviceKind.COMPUTER
        BluetoothClass.Device.Major.PHONE -> DeviceKind.PHONE
        BluetoothClass.Device.Major.AUDIO_VIDEO -> when (type.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
            BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX -> DeviceKind.TV
            else -> DeviceKind.SPEAKER
        }
        BluetoothClass.Device.Major.WEARABLE -> DeviceKind.WATCH
        BluetoothClass.Device.Major.IMAGING -> DeviceKind.PRINTER
        BluetoothClass.Device.Major.TOY -> DeviceKind.CONSOLE
        else -> DeviceKind.UNKNOWN
    }
}
