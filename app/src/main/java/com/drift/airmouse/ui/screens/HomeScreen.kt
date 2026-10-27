package com.drift.airmouse.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import com.drift.airmouse.ui.ListScreen
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.drift.airmouse.R
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.Haptics
import com.drift.airmouse.core.BluetoothSupport
import com.drift.airmouse.core.HidLink
import com.drift.airmouse.core.LinkState
import com.drift.airmouse.core.Mode
import com.drift.airmouse.core.PointerBus
import com.drift.airmouse.core.PointerService
import com.drift.airmouse.core.Transport
import com.drift.airmouse.ui.BrandFont
import com.drift.airmouse.ui.Caption
import com.drift.airmouse.ui.Pill
import com.drift.airmouse.ui.RoundIcon
import com.drift.airmouse.ui.Wordmark
import com.drift.airmouse.ui.rememberGate
import com.drift.airmouse.ui.LocalLook
import com.drift.airmouse.ui.modeIcon
import com.drift.airmouse.ui.themeArt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One screen, no scrolling: a big Go button in the middle, how you connect on the left,
 * settings on the right, and the mode underneath (tap it to pick another). Swipe right to close Drift.
 */
@Composable
fun HomeScreen(onPad: () -> Unit, onPairWifi: () -> Unit, onDevices: () -> Unit, onSettings: () -> Unit, onModes: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val scope = rememberCoroutineScope()
    val state by PointerBus.state.collectAsState()
    val mode by PointerBus.mode.collectAsState()
    val transport by prefs.transport.collectAsState()
    val computer by prefs.computer.collectAsState()
    val bluetoothHost by prefs.bluetoothHost.collectAsState()
    val connectedTo by PointerBus.connectedTo.collectAsState()
    val gate by rememberGate()
    var note by remember { mutableStateOf<String?>(null) }
    var startAfterPermission by remember { mutableStateOf(false) }
    val running = state !is LinkState.Idle

    LaunchedEffect(note) {
        if (note != null) {
            delay(3_500)
            note = null
        }
    }

    fun useBluetooth() {
        scope.launch {
            if (BluetoothSupport.canBeMouse(context)) {
                prefs.setTransport(Transport.BLUETOOTH)
                if (startAfterPermission) {
                    startAfterPermission = false
                    PointerService.start(context)
                    onPad()
                }
            } else {
                startAfterPermission = false
                prefs.setTransport(Transport.WIFI)
                note = "This watch can't be a Bluetooth mouse. Using Wi-Fi."
            }
        }
    }

    val askBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            useBluetooth()
        } else {
            startAfterPermission = false
            note = "Bluetooth needs your permission"
        }
    }

    // If Bluetooth is picked but this watch can't do it, quietly fall back to Wi-Fi.
    LaunchedEffect(transport) {
        if (transport == Transport.BLUETOOTH && HidLink.hasPermission(context)) useBluetooth()
    }

    fun go() {
        Haptics.buzz(context, prefs.tuning.value.vibration, Haptics.Kind.CLICK)
        when {
            running -> onPad()
            transport == Transport.BLUETOOTH && !HidLink.hasPermission(context) -> {
                startAfterPermission = true
                askBluetooth.launch(HidLink.PERMISSIONS)
            }
            transport == Transport.WIFI && computer == null -> onPairWifi()
            else -> {
                PointerService.start(context)
                onPad()
            }
        }
    }


    /** Pick a mode and go: connects if needed, then opens that mode's screen. */
    fun open(next: Mode) {
        prefs.mode = next
        PointerBus.mode.value = next
        if (!running && gate.reason != null) {
            note = gate.reason
            return
        }
        go()
    }

    val device = when (transport) {
        Transport.BLUETOOTH -> bluetoothHost?.name
        Transport.WIFI -> computer?.name
    }
    val status = note ?: when (val current = state) {
        LinkState.Connecting -> "Connecting…"
        LinkState.WaitingForApproval -> "Click Allow on your computer"
        is LinkState.WaitingForHost -> "Waiting for it to connect"
        LinkState.Steering -> "Connected"
        is LinkState.Idle -> gate.reason ?: current.note ?: if (device == null) "Tap to pick one" else "${transport.label} · ready"
    }
    val warning = note != null || (!running && (gate.reason != null || (state as? LinkState.Idle)?.note != null))

    // Like a classic watch mouse app: the device at the top, every mode one tap away below it.
    ListScreen {
        item { Wordmark(size = 26) }
        item {
            DeviceCard(
                icon = if (transport == Transport.BLUETOOTH) R.drawable.ic_bluetooth else R.drawable.ic_wifi,
                name = connectedTo ?: device ?: "Pick a device",
                status = status,
                connected = state == LinkState.Steering,
                warning = warning,
                onClick = onDevices,
            )
        }
        item { Caption("Modes") }
        Mode.picker.forEach { item -> item { ModeRow(item, active = running && item == mode) { open(item) } } }
        item { ModeLikeRow(R.drawable.ic_settings, "Settings", "Speed, pinch, themes and more", onSettings) }
    }
}

/** Which device the watch controls, and whether it's connected. Tap to pick another. */
@Composable
private fun DeviceCard(@DrawableRes icon: Int, name: String, status: String, connected: Boolean, warning: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (connected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp)) },
        secondaryLabel = {
            Text(
                status,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (warning) colors.error else LocalContentColor.current,
            )
        },
        label = { Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun ModeRow(mode: Mode, active: Boolean, onClick: () -> Unit) {
    ModeLikeRow(modeIcon(mode), mode.label, if (active) "Open · in use" else mode.detail, onClick, highlighted = active)
}

@Composable
private fun ModeLikeRow(@DrawableRes icon: Int, label: String, detail: String, onClick: () -> Unit, highlighted: Boolean = false) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (highlighted) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp)) },
        secondaryLabel = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(label, fontWeight = FontWeight.Bold, maxLines = 1) },
    )
}
