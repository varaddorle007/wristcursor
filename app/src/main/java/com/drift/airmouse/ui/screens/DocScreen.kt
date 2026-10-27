package com.drift.airmouse.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.drift.airmouse.ui.BackEdgeButton
import com.drift.airmouse.ui.ListScreen

@Composable
fun DocScreen(title: String, body: String, onBack: () -> Unit) {
    ListScreen(edgeButton = { BackEdgeButton(onBack) }) {
        item { ListHeader { Text(title) } }
        body.split("\n\n").forEach { paragraph ->
            item {
                Text(
                    text = paragraph,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

val PRIVACY_TEXT = """
Drift has no accounts, no ads and no analytics. We don't collect personal data.

Motion: the watch's gyroscope moves the pointer. Readings stay on the watch and are never stored.

Bluetooth: the watch acts as a mouse and keyboard for the computer you pair. Only pointer moves, clicks, scrolling and arrow keys are sent.

Wi-Fi: pointer commands, your watch model and a random ID go over your network to the computer you pair. The ID lets the computer remember you allowed this watch.

Nothing goes to us or anyone else.

Battery: Drift reads the battery level and battery saver status on the watch to pause itself. This isn't stored or sent.

Storage: your settings and paired computer live only on this watch. Uninstalling Drift deletes them.
""".trimIndent()

val TERMS_TEXT = """
Drift is provided as is, without warranty. Use it at your own risk.

Only control computers you own or are allowed to use, and keep them secure.

Drift is not a medical device. Don't use it while driving or operating machinery.

Drift pauses below 15% battery and under battery saver. That's on purpose.

Using Drift means you accept these terms, including future updates to them.
""".trimIndent()
