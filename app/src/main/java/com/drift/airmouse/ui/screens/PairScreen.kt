package com.drift.airmouse.ui.screens

import android.app.RemoteInput
import android.content.Intent
import android.net.InetAddresses
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.Computer
import com.drift.airmouse.core.Discovery
import com.drift.airmouse.core.Transport
import com.drift.airmouse.ui.Caption
import com.drift.airmouse.ui.ListScreen
import com.drift.airmouse.ui.RowButton

private const val ADDRESS_KEY = "address"

private sealed interface PairPhase {
    data object Searching : PairPhase
    data class Paired(val computer: Computer) : PairPhase
    data object NotFound : PairPhase
    data object NoWifi : PairPhase
    data object BadAddress : PairPhase
}

@Composable
fun PairScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf<PairPhase>(PairPhase.Searching) }

    LaunchedEffect(attempt) {
        phase = PairPhase.Searching
        phase = when (val result = Discovery.find(context)) {
            is Discovery.Result.Found -> {
                prefs.saveComputer(result.computer)
                PairPhase.Paired(result.computer)
            }
            Discovery.Result.NotFound -> PairPhase.NotFound
            Discovery.Result.NoWifi -> PairPhase.NoWifi
        }
    }

    val keyboard = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val typed = result.data
            ?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(ADDRESS_KEY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (typed.isEmpty()) return@rememberLauncherForActivityResult
        phase = if (InetAddresses.isNumericAddress(typed)) {
            Computer(typed, typed).also(prefs::saveComputer).let(PairPhase::Paired)
        } else {
            PairPhase.BadAddress
        }
    }

    ListScreen(
        edgeButton = {
            val paired = phase is PairPhase.Paired
            EdgeButton(
                onClick = { if (paired) onDone() else attempt++ },
                enabled = phase != PairPhase.Searching,
            ) { Text(if (paired) "Done" else "Try again", fontWeight = FontWeight.Bold) }
        }
    ) {
        item { ListHeader { Text("Pair computer") } }
        item {
            when (val current = phase) {
                PairPhase.Searching -> CircularProgressIndicator(modifier = Modifier.size(36.dp))
                is PairPhase.Paired -> Caption("Paired with ${current.computer.name}", color = MaterialTheme.colorScheme.primary)
                PairPhase.NotFound -> Caption("Drift Receiver didn't answer. Is it open on your computer?", color = MaterialTheme.colorScheme.error)
                PairPhase.NoWifi -> Caption("Wi-Fi isn't set up on this watch", color = MaterialTheme.colorScheme.error)
                PairPhase.BadAddress -> Caption("That doesn't look like an IP address", color = MaterialTheme.colorScheme.error)
            }
        }
        if (phase !is PairPhase.Paired) {
            item { Caption("Open Drift Receiver on your computer. Both need the same Wi-Fi.") }
        }
        item { RowButton("Type address", onClick = { keyboard.launch(addressInput()) }) }
        if (phase == PairPhase.NotFound) {
            item {
                RowButton("Use Bluetooth instead", onClick = {
                    prefs.setTransport(Transport.BLUETOOTH)
                    onDone()
                }, secondary = "No app needed on the computer")
            }
        }
    }
}

private fun addressInput(): Intent {
    val input = RemoteInput.Builder(ADDRESS_KEY).setLabel("Computer IP, like 192.168.1.20").build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
        RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(input))
    }
}
