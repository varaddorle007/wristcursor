package com.drift.airmouse.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.drift.airmouse.core.BatteryGate
import com.drift.airmouse.core.GateStatus
import com.drift.airmouse.core.DeviceKind
import com.drift.airmouse.core.Mode
import com.drift.airmouse.core.RemoteKey
import com.drift.airmouse.R
import kotlinx.coroutines.delay

@Composable
fun Wordmark(modifier: Modifier = Modifier, size: Int = 34) {
    Text(
        text = "Drift",
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        fontFamily = BrandFont,
        fontSize = size.sp,
    )
}

@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        color = color,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Every list action uses this, so all buttons share one size, shape and color. */
@Composable
fun RowButton(
    label: String,
    onClick: () -> Unit,
    secondary: String? = null,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        secondaryLabel = if (secondary != null) {
            { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        label = {
            Text(label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

/** A round icon button; every icon button in the app is this size. */
@Composable
fun RoundIcon(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(IconButtonDefaults.SmallButtonSize),
        enabled = enabled,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(IconButtonDefaults.SmallIconSize),
        )
    }
}

/** A small tappable capsule with an icon and a word, for quick toggles. */
@Composable
fun Pill(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A row of segments showing [value] out of [max]. */
@Composable
fun LevelBar(value: Int, max: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(max) { index ->
            Box(
                Modifier
                    .width(if (max > 6) 9.dp else 16.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (index < value) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
            )
        }
    }
}

/** A scrolling round-screen list with an optional button hugging the bottom edge. */
@Composable
fun ListScreen(
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberScalingLazyListState()
    if (edgeButton != null) {
        ScreenScaffold(scrollState = listState, edgeButton = edgeButton) { padding ->
            ScalingLazyColumn(
                state = listState,
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
    } else {
        ScreenScaffold(scrollState = listState) { padding ->
            ScalingLazyColumn(
                state = listState,
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
    }
}

/** Battery gate, refreshed every few seconds while the screen is visible. */
@Composable
fun rememberGate(): State<GateStatus> {
    val context = LocalContext.current
    return produceState(BatteryGate.read(context)) {
        while (true) {
            delay(5_000)
            value = BatteryGate.read(context)
        }
    }
}

fun modeIcon(mode: Mode): Int = when (mode) {
    Mode.MOUSE -> R.drawable.ic_mouse
    Mode.WHEEL -> R.drawable.ic_wheel
    Mode.TOUCHPAD -> R.drawable.ic_touchpad
    Mode.REMOTE -> R.drawable.ic_remote
    Mode.SLIDES -> R.drawable.ic_slides
    Mode.MEDIA -> R.drawable.ic_media
    Mode.KEYBOARD -> R.drawable.ic_keyboard
}

fun kindIcon(kind: DeviceKind): Int = when (kind) {
    DeviceKind.COMPUTER -> R.drawable.ic_desktop
    DeviceKind.LAPTOP -> R.drawable.ic_laptop
    DeviceKind.PHONE -> R.drawable.ic_phone
    DeviceKind.TABLET -> R.drawable.ic_tablet
    DeviceKind.TV -> R.drawable.ic_tv
    DeviceKind.SPEAKER -> R.drawable.ic_speaker
    DeviceKind.PRINTER -> R.drawable.ic_printer
    DeviceKind.ROUTER -> R.drawable.ic_router
    DeviceKind.WATCH -> R.drawable.ic_watch
    DeviceKind.CAMERA -> R.drawable.ic_camera
    DeviceKind.CONSOLE -> R.drawable.ic_console
    DeviceKind.SMART_HOME -> R.drawable.ic_bulb
    DeviceKind.UNKNOWN -> R.drawable.ic_device
}

/** The icon drawn on a pad button for a key, or null for keys shown as text ("OK"). */
fun keyIcon(key: RemoteKey): Int? = when (key) {
    RemoteKey.UP -> R.drawable.ic_chevron_up
    RemoteKey.DOWN -> R.drawable.ic_chevron_down
    RemoteKey.LEFT -> R.drawable.ic_chevron_left
    RemoteKey.RIGHT -> R.drawable.ic_chevron_right
    RemoteKey.OK -> null
    RemoteKey.BACK -> R.drawable.ic_back
    RemoteKey.HOME -> R.drawable.ic_home
    RemoteKey.MENU -> R.drawable.ic_menu
    RemoteKey.SEARCH -> R.drawable.ic_search
    RemoteKey.PLAY_PAUSE -> R.drawable.ic_play_pause
    RemoteKey.NEXT -> R.drawable.ic_next
    RemoteKey.PREVIOUS -> R.drawable.ic_previous
    RemoteKey.FAST_FORWARD -> R.drawable.ic_fast_forward
    RemoteKey.REWIND -> R.drawable.ic_rewind
    RemoteKey.VOLUME_UP -> R.drawable.ic_volume_up
    RemoteKey.VOLUME_DOWN -> R.drawable.ic_volume_down
    RemoteKey.MUTE -> R.drawable.ic_mute
    RemoteKey.BACKSPACE -> R.drawable.ic_backspace
    RemoteKey.NONE -> null
}

/** The mode name at the top of the steering screen; tap it to pick another mode. */
@Composable
fun ModeChip(mode: Mode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
            .clickable(role = Role.Button, onClickLabel = "Change mode", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(modeIcon(mode)),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(5.dp))
        Text(mode.label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(4.dp))
        Icon(
            painter = painterResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The bottom-edge Back button every sub-screen uses. */
@Composable
fun BackEdgeButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    EdgeButton(onClick = onBack, modifier = modifier, buttonSize = EdgeButtonSize.Small) {
        Text("Back", fontWeight = FontWeight.Bold)
    }
}
