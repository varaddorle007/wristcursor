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
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.Haptics
import com.drift.airmouse.core.LinkState
import com.drift.airmouse.core.Mode
import com.drift.airmouse.core.PointerBus
import com.drift.airmouse.ui.BackEdgeButton
import com.drift.airmouse.ui.ListScreen
import com.drift.airmouse.ui.modeIcon

/** Pick what steering does. While steering, Stop lives here too. Switching never drops the connection. */
@Composable
fun ModeScreen(onPicked: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val mode by PointerBus.mode.collectAsState()
    val state by PointerBus.state.collectAsState()
    val running = state !is LinkState.Idle

    ListScreen(
        edgeButton = {
            if (running) {
                EdgeButton(
                    onClick = onStop,
                    buttonSize = EdgeButtonSize.Small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text("Stop", fontWeight = FontWeight.Bold) }
            } else {
                BackEdgeButton(onPicked)
            }
        },
    ) {
        item { ListHeader { Text("Mode") } }
        Mode.picker.forEach { option ->
            item {
                val selected = option == mode
                Button(
                    onClick = {
                        prefs.mode = option
                        PointerBus.mode.value = option
                        Haptics.buzz(context, prefs.tuning.value.vibration, Haptics.Kind.CLICK)
                        onPicked()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Icon(
                            painterResource(modeIcon(option)),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    },
                    secondaryLabel = { Text(option.detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    label = { Text(option.label, fontWeight = FontWeight.Bold, maxLines = 1) },
                )
            }
        }
    }
}
