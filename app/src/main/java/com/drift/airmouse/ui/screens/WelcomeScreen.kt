package com.drift.airmouse.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Text
import com.drift.airmouse.ui.Caption
import com.drift.airmouse.ui.ListScreen
import com.drift.airmouse.ui.RowButton
import com.drift.airmouse.ui.Wordmark

@Composable
fun WelcomeScreen(onTerms: () -> Unit, onPrivacy: () -> Unit, onDone: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onDone() }

    ListScreen(
        edgeButton = {
            EdgeButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onDone()
                    }
                },
                enabled = accepted,
            ) { Text("Let's go", fontWeight = FontWeight.Bold) }
        }
    ) {
        item { Wordmark() }
        item { Caption("Your wrist is the mouse. Tilt to move, tap to click.") }
        item {
            CheckboxButton(
                checked = accepted,
                onCheckedChange = { accepted = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("I agree", fontWeight = FontWeight.Bold) },
                secondaryLabel = { Text("to the Terms & Privacy") },
            )
        }
        item { RowButton("Read terms", onTerms) }
        item { RowButton("Read privacy", onPrivacy) }
        item {
            Caption("Next, Drift asks to show notifications. That's where your Stop button lives while you steer.")
        }
    }
}
