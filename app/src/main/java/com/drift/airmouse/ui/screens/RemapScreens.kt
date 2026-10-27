package com.drift.airmouse.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.drift.airmouse.R
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.Binding
import com.drift.airmouse.core.Pad
import com.drift.airmouse.core.Remap
import com.drift.airmouse.core.RemoteKey
import com.drift.airmouse.core.Slot
import com.drift.airmouse.ui.BackEdgeButton
import com.drift.airmouse.ui.Caption
import com.drift.airmouse.ui.ListScreen
import com.drift.airmouse.ui.keyIcon

/** Every pad button, with what a tap and a hold do. Tap one to change it. */
@Composable
fun RemapScreen(onButton: (Pad, Slot) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val bindings by prefs.bindings.collectAsState()

    ListScreen(edgeButton = { BackEdgeButton(onBack) }) {
        item { ListHeader { Text("Remap buttons") } }
        Pad.entries.forEach { pad ->
            item { Caption(pad.title) }
            Slot.entries.forEach { slot ->
                item {
                    val binding = bindings.of(pad, slot)
                    KeyRow(
                        icon = keyIcon(binding.short) ?: R.drawable.ic_ok,
                        label = slot.label,
                        detail = describe(binding),
                        onClick = { onButton(pad, slot) },
                    )
                }
            }
        }
        item {
            KeyRow(R.drawable.ic_tune, "Reset all", "Back to the original buttons", tonal = true) { prefs.resetBindings() }
        }
    }
}

/** One button: its short press and its long press. */
@Composable
fun ButtonScreen(pad: Pad, slot: Slot, onPick: (long: Boolean) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val bindings by prefs.bindings.collectAsState()
    val binding = bindings.of(pad, slot)

    ListScreen(edgeButton = { BackEdgeButton(onBack) }) {
        item { ListHeader { Text("Button: ${slot.label}") } }
        item {
            KeyRow(keyIcon(binding.short) ?: R.drawable.ic_ok, "Short press", binding.short.label) { onPick(false) }
        }
        item {
            val detail = when {
                binding.long != RemoteKey.NONE -> binding.long.label
                Remap.repeats(binding.short) -> "Repeats while held"
                else -> "Nothing"
            }
            KeyRow(keyIcon(binding.long) ?: R.drawable.ic_add, "Long press", detail) { onPick(true) }
        }
        if (binding.long != RemoteKey.NONE) {
            item {
                Button(
                    onClick = { prefs.saveBinding(pad, slot, binding.copy(long = RemoteKey.NONE)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    label = { Text("Clear long press", fontWeight = FontWeight.Bold) },
                )
            }
        }
        if (!bindings.isDefault(pad, slot)) {
            item {
                KeyRow(R.drawable.ic_tune, "Reset", describe(Remap.default(pad, slot)), tonal = true) {
                    prefs.saveBinding(pad, slot, Remap.default(pad, slot))
                }
            }
        }
    }
}

/** Every key a button can press. */
@Composable
fun ActionScreen(pad: Pad, slot: Slot, long: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val bindings by prefs.bindings.collectAsState()
    val binding = bindings.of(pad, slot)
    val current = if (long) binding.long else binding.short

    ListScreen(edgeButton = { BackEdgeButton(onDone) }) {
        item { ListHeader { Text(if (long) "Long press" else "Short press") } }
        RemoteKey.entries.filter { it != RemoteKey.NONE }.forEach { key ->
            item {
                KeyRow(keyIcon(key) ?: R.drawable.ic_ok, key.label, if (key == current) "Current" else null, selected = key == current) {
                    prefs.saveBinding(pad, slot, if (long) binding.copy(long = key) else binding.copy(short = key))
                    onDone()
                }
            }
        }
    }
}

private fun describe(binding: Binding): String = when {
    binding.long != RemoteKey.NONE -> "${binding.short.label} · hold: ${binding.long.label}"
    Remap.repeats(binding.short) -> "${binding.short.label} · hold repeats"
    else -> binding.short.label
}

@Composable
private fun KeyRow(
    icon: Int,
    label: String,
    detail: String?,
    selected: Boolean = false,
    tonal: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = when {
            selected -> ButtonDefaults.buttonColors()
            tonal -> ButtonDefaults.filledTonalButtonColors()
            else -> ButtonDefaults.filledTonalButtonColors()
        },
        icon = {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            )
        },
        secondaryLabel = detail?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}
