package com.drift.airmouse.ui.screens

import android.bluetooth.BluetoothAdapter
import android.app.RemoteInput
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.wear.input.RemoteInputIntentHelper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.drift.airmouse.R
import com.drift.airmouse.core.Action
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.Haptics
import com.drift.airmouse.core.LinkState
import com.drift.airmouse.core.Mode
import com.drift.airmouse.core.Pad
import com.drift.airmouse.core.PointerBus
import com.drift.airmouse.core.RemoteKey
import com.drift.airmouse.core.ShakeAction
import com.drift.airmouse.core.Tuning
import com.drift.airmouse.core.Transport
import com.drift.airmouse.ui.BrandFont
import com.drift.airmouse.ui.Caption
import com.drift.airmouse.ui.LocalLook
import com.drift.airmouse.ui.ModeChip
import com.drift.airmouse.ui.Pill
import com.drift.airmouse.ui.RingPad
import com.drift.airmouse.ui.themeArt
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val SLIDE_STEP_PX = 90f
private const val DIM_BRIGHTNESS = 0.2f
private const val RING_GAP = 124f

/**
 * One screen per mode while steering. The mode name at the top opens the mode picker.
 * - Mouse: tilt your wrist to move; pinch or tap to click (see Settings for what each pinch does).
 * - Touchpad: the whole screen is a trackpad (see [TouchpadSurface]).
 * - TV remote: a D-pad with OK in the middle, plus Back and Home. The watch's own back button presses Back.
 * - Media: volume, previous/next and play/pause; the bezel or crown sets the volume.
 * - Slides: big Back and Next buttons; the bezel or crown flips slides.
 */
@Composable
fun SteerScreen(onStop: () -> Unit, onModes: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val view = LocalView.current
    val prefs = remember { AppPrefs.get(context) }
    val tuning by prefs.tuning.collectAsState()
    val bindings by prefs.bindings.collectAsState()
    val state by PointerBus.state.collectAsState()
    val mode by PointerBus.mode.collectAsState()
    val connectedTo by PointerBus.connectedTo.collectAsState()
    val unsupported by PointerBus.unsupported.collectAsState()
    val transport by prefs.transport.collectAsState()
    val focus = remember { FocusRequester() }
    val crown = remember { floatArrayOf(0f) }
    var hint by remember { mutableStateOf<String?>(null) }
    val makeVisible = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val steering = state == LinkState.Steering

    fun buzz(kind: Haptics.Kind) = Haptics.buzz(context, tuning.vibration, kind)

    fun act(action: Action, kind: Haptics.Kind) {
        PointerBus.perform(action)
        buzz(kind)
    }

    fun key(key: RemoteKey, kind: Haptics.Kind) {
        if (key == RemoteKey.NONE) return
        act(Action.Press(key), kind)
    }

    // The watch's back button is the TV's Back button while the remote is up.
    BackHandler(enabled = steering && mode == Mode.REMOTE) { key(RemoteKey.BACK, Haptics.Kind.CLICK) }

    LaunchedEffect(unsupported) {
        if (unsupported != null) {
            delay(2_500)
            PointerBus.unsupported.value = null
        }
    }

    // Keep the screen on (taps need it) but dim it: on OLED the screen is the biggest battery cost.
    DisposableEffect(tuning.dimScreen) {
        view.keepScreenOn = true
        val window = activity?.window
        if (window != null && tuning.dimScreen) {
            window.attributes = window.attributes.apply { screenBrightness = DIM_BRIGHTNESS }
        }
        onDispose {
            view.keepScreenOn = false
            window?.let {
                it.attributes = it.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
            PointerBus.frozen = false
            PointerBus.touching(false)
            PointerBus.perform(Action.DragEnd)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Shake your wrist: back, pause or stop, as picked in Settings.
    LaunchedEffect(Unit) {
        PointerBus.shakes.collect { action ->
            when (action) {
                ShakeAction.BACK -> onBack()
                ShakeAction.STOP -> onStop()
                ShakeAction.PAUSE -> PointerBus.paused.value = !PointerBus.paused.value
                ShakeAction.OFF -> Unit
            }
        }
    }

    // No clock while steering: the mode chip takes the top of the screen instead.
    ScreenScaffold(timeText = {}) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .themeArt(LocalLook.current, strength = if (mode == Mode.TOUCHPAD) 0.3f else 0.55f)
                .onRotaryScrollEvent { event ->
                    when (mode) {
                        Mode.MOUSE, Mode.TOUCHPAD, Mode.KEYBOARD -> PointerBus.perform(Action.Scroll(event.verticalScrollPixels))
                        Mode.MEDIA, Mode.REMOTE -> PointerBus.perform(Action.Volume(event.verticalScrollPixels))
                        Mode.WHEEL -> {
                            crown[0] += event.verticalScrollPixels
                            if (abs(crown[0]) >= 60f) {
                                act(Action.Press(if (crown[0] > 0) RemoteKey.UP else RemoteKey.DOWN), Haptics.Kind.CLICK)
                                crown[0] = 0f
                            }
                        }
                        Mode.SLIDES -> {
                            crown[0] += event.verticalScrollPixels
                            if (abs(crown[0]) >= SLIDE_STEP_PX) {
                                act(if (crown[0] > 0) Action.NextSlide else Action.PreviousSlide, Haptics.Kind.TICK)
                                crown[0] = 0f
                            }
                        }
                    }
                    true
                }
                .focusRequester(focus)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            if (steering) {
                when (mode) {
                    Mode.MOUSE -> MouseFace(tuning, connectedTo, ::act, ::buzz)
                    Mode.WHEEL -> WheelFace(tuning, ::act, ::buzz)
                    Mode.TOUCHPAD -> TouchpadSurface(tuning, ::buzz, Modifier.fillMaxSize())
                    Mode.REMOTE -> RemoteFace(bindings, ::key)
                    Mode.MEDIA -> RingPad(
                        Pad.MEDIA,
                        bindings,
                        ::key,
                        // Clear of the mode chip at the top and the Stop button at the bottom.
                        Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 34.dp, bottom = 50.dp),
                    )
                    Mode.SLIDES -> SlidesFace(connectedTo, ::act)
                    Mode.KEYBOARD -> KeyboardFace(::act, ::key)
                }
                ModeChip(mode, onModes, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            } else {
                StatusFace(state, hint) {
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                    runCatching { makeVisible.launch(intent) }
                        .onFailure { hint = "Open watch Settings › Connections › Bluetooth" }
                }
            }

            unsupported?.let { key ->
                Text(
                    "${key.label} doesn't work on this connection",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // The remote keeps the bottom for Back and Home; Stop is in the mode picker there.
            if (!(steering && mode == Mode.REMOTE)) {
                EdgeButton(
                    onClick = onStop,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    buttonSize = EdgeButtonSize.ExtraSmall,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text(if (state is LinkState.Idle) "Back" else "Stop", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * Air mouse: tilting moves. The big mouse in the middle blinks on every click and pinch.
 * Tap anywhere inside the ring to click; the outer edge stays free to swipe back.
 */
@Composable
private fun MouseFace(
    tuning: Tuning,
    connectedTo: String?,
    act: (Action, Haptics.Kind) -> Unit,
    buzz: (Haptics.Kind) -> Unit,
) {
    val paused by PointerBus.paused.collectAsState()
    val holding by PointerBus.holding.collectAsState()
    val pulse by PointerBus.clickPulse.collectAsState()
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(420))
        }
    }
    val colors = MaterialTheme.colorScheme
    val grabbed = dragging || holding
    val accent = when {
        paused -> colors.outlineVariant
        grabbed -> colors.secondary
        else -> colors.primary
    }
    val ring by animateColorAsState(
        targetValue = if (pressed || grabbed) accent else accent.copy(alpha = 0.4f),
        label = "ring",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The ring opens at the bottom so the Stop button sits inside it, not across it.
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val width = 2.dp.toPx()
            drawArc(
                color = lerp(ring, accent, flash.value),
                startAngle = 90f + RING_GAP / 2,
                sweepAngle = 360f - RING_GAP,
                useCenter = false,
                topLeft = Offset(width / 2, width / 2),
                size = Size(size.width - width, size.height - width),
                style = Stroke(width + 2.dp.toPx() * flash.value, cap = StrokeCap.Round),
            )
        }
        Box(
            Modifier
                .fillMaxSize(0.82f)
                .clip(CircleShape)
                .pointerInput(tuning.holdDrags, paused) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            PointerBus.frozen = true
                            PointerBus.touching(true)
                            tryAwaitRelease()
                            if (dragging) {
                                PointerBus.perform(Action.DragEnd)
                                dragging = false
                                buzz(Haptics.Kind.TICK)
                            }
                            PointerBus.frozen = false
                            PointerBus.touching(false)
                            pressed = false
                        },
                        onTap = { if (!paused) act(Action.Click, Haptics.Kind.CLICK) },
                        onDoubleTap = { if (!paused) act(Action.DoubleClick, Haptics.Kind.CLICK) },
                        onLongPress = {
                            when {
                                paused -> Unit
                                tuning.holdDrags -> {
                                    // Grab: button down, the cursor follows your wrist until you let go.
                                    PointerBus.perform(Action.DragStart)
                                    dragging = true
                                    PointerBus.frozen = false
                                    buzz(Haptics.Kind.HEAVY)
                                }
                                else -> act(Action.RightClick, Haptics.Kind.HEAVY)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                connectedTo?.let {
                    Text("→ $it", color = colors.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                MouseLogo(accent, flash.value)
                Spacer(Modifier.height(4.dp))
                Caption(
                    when {
                        paused -> if (tuning.shake == ShakeAction.PAUSE) "Paused\nShake to resume" else "Paused"
                        grabbed -> "Holding\nLet go to drop"
                        tuning.pinch -> "1 pinch: ${tuning.pinchOnce.label.lowercase()}\n2 pinches: ${tuning.pinchTwice.label.lowercase()}"
                        else -> "Tilt to move\nTap to click"
                    },
                    maxLines = 2,
                )
            }
        }
    }
}

/** The mouse in the middle: a soft glowing disc that lights up, and the mouse pops, on each click. */
@Composable
private fun MouseLogo(accent: Color, flash: Float) {
    Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(accent.copy(alpha = 0.10f + 0.40f * flash), radius = size.minDimension / 2 * (0.86f + 0.14f * flash))
            drawCircle(accent.copy(alpha = 0.18f + 0.5f * flash), radius = size.minDimension / 2 * 0.86f, style = Stroke(1.5.dp.toPx()))
        }
        Icon(
            painterResource(R.drawable.ic_mouse),
            contentDescription = null,
            modifier = Modifier.size(44.dp).graphicsLayer {
                val pop = 1f + 0.16f * flash
                scaleX = pop
                scaleY = pop
            },
            tint = lerp(accent, Color.White, flash * 0.75f),
        )
    }
}

/** TV remote: the D-pad ring, with Back and Home underneath. */
@Composable
private fun RemoteFace(bindings: com.drift.airmouse.core.Bindings, key: (RemoteKey, Haptics.Kind) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 36.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RingPad(Pad.REMOTE, bindings, key, Modifier.weight(1f).padding(horizontal = 16.dp))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            BigKey(R.drawable.ic_back, "Back") { key(RemoteKey.BACK, Haptics.Kind.CLICK) }
            BigKey(R.drawable.ic_home, "Home") { key(RemoteKey.HOME, Haptics.Kind.CLICK) }
        }
    }
}

@Composable
private fun SlidesFace(connectedTo: String?, act: (Action, Haptics.Kind) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        connectedTo?.let {
            Text("→ $it", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ControlButton(R.drawable.ic_chevron_left, "Previous slide", 58.dp, primary = false) {
                act(Action.PreviousSlide, Haptics.Kind.CLICK)
            }
            ControlButton(R.drawable.ic_chevron_right, "Next slide", 78.dp, primary = true) {
                act(Action.NextSlide, Haptics.Kind.CLICK)
            }
        }
        Caption("Bezel flips slides")
    }
}

/** Not steering (yet): what's happening, and what to do about it. */
@Composable
private fun StatusFace(state: LinkState, hint: String?, onMakeVisible: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 36.dp, bottom = 50.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        val (title, detail) = when (state) {
            LinkState.Connecting -> "Hang on" to "Connecting…"
            LinkState.WaitingForApproval -> "Almost" to "Click Allow in Drift Receiver on your computer"
            is LinkState.WaitingForHost ->
                "Pair me" to "Pair “${state.watchName}” in your computer's or TV's Bluetooth settings"
            is LinkState.Idle -> "Stopped" to (state.note ?: "Steering is off")
            LinkState.Steering -> "" to ""
        }
        Text(title, color = MaterialTheme.colorScheme.primary, fontFamily = BrandFont, fontSize = 28.sp)
        Caption(hint ?: detail, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), maxLines = 4)
        if (state is LinkState.WaitingForHost) {
            Pill(R.drawable.ic_bluetooth, "Make visible", onClick = onMakeVisible)
        }
    }
}

@Composable
private fun BigKey(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    ControlButton(icon, label, 42.dp, primary = false, onClick = onClick)
}

@Composable
private fun SmallKey(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    ControlButton(icon, label, 34.dp, primary = false, onClick = onClick)
}

/** A round control: filled for the main action, quiet for the rest. */
@Composable
private fun ControlButton(@DrawableRes icon: Int, label: String, size: Dp, primary: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (primary) colors.primary else colors.surfaceContainerHigh)
            .pointerInput(Unit) {
                // Pinches are ignored while a finger is down, so tapping here never double-counts.
                detectTapGestures(
                    onPress = {
                        PointerBus.touching(true)
                        tryAwaitRelease()
                        PointerBus.touching(false)
                    },
                    onTap = { onClick() },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(size * 0.45f),
            tint = if (primary) colors.onPrimary else colors.onSurface,
        )
    }
}

private const val TYPE_KEY = "text"

/**
 * Keyboard: speak or type on the watch and it lands where the computer's cursor is,
 * handy from across the room. Delete and Enter work like the keys on a keyboard.
 */
@Composable
private fun KeyboardFace(act: (Action, Haptics.Kind) -> Unit, key: (RemoteKey, Haptics.Kind) -> Unit) {
    var last by remember { mutableStateOf<String?>(null) }

    fun send(text: String?) {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return
        act(Action.Type(clean), Haptics.Kind.CLICK)
        last = clean
    }

    val keyboard = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        send(result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(TYPE_KEY)?.toString())
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        send(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
    }
    fun openKeyboard() = keyboard.launch(typingInput())
    fun speak() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak")
        // No speech app on this watch: the watch keyboard has a mic button too.
        runCatching { speech.launch(intent) }.onFailure { openKeyboard() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ControlButton(R.drawable.ic_mic, "Speak", 64.dp, primary = true, onClick = ::speak)
            ControlButton(R.drawable.ic_keyboard, "Type", 52.dp, primary = false, onClick = ::openKeyboard)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            ControlButton(R.drawable.ic_backspace, "Delete", 38.dp, primary = false) { key(RemoteKey.BACKSPACE, Haptics.Kind.TICK) }
            ControlButton(R.drawable.ic_enter, "Enter", 38.dp, primary = false) { key(RemoteKey.OK, Haptics.Kind.CLICK) }
        }
        Caption(last?.let { "Sent: “$it”" } ?: "Speak or type", maxLines = 1)
    }
}

private fun typingInput(): Intent {
    val input = RemoteInput.Builder(TYPE_KEY).setLabel("Type for your computer").build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
        RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(input))
    }
}

/**
 * Game controller steering wheel mode for car games.
 * - Wrist tilt/roll steers the car with ultra-responsive analog precision.
 * - Left half of the screen is the Brake paddle.
 * - Right half of the screen is the Gas / Accelerator paddle.
 * - Center hub tap instantly re-centers/calibrates zero angle.
 */
@Composable
private fun WheelFace(
    tuning: Tuning,
    act: (Action, Haptics.Kind) -> Unit,
    buzz: (Haptics.Kind) -> Unit,
) {
    val angle by PointerBus.wheelAngle.collectAsState()
    val steer by PointerBus.wheelSteer.collectAsState()
    var throttle by remember { mutableStateOf(false) }
    var brake by remember { mutableStateOf(false) }
    var nitro by remember { mutableStateOf(false) }

    // Haptics on center crossing and full lock limits
    var lastAngleInt by remember { mutableStateOf(0) }
    LaunchedEffect(angle) {
        val currentInt = angle.toInt()
        if (lastAngleInt != currentInt) {
            if (lastAngleInt != 0 && currentInt == 0) {
                buzz(Haptics.Kind.TICK) // Center detent feel
            } else if (abs(currentInt) >= 58 && abs(lastAngleInt) < 58) {
                buzz(Haptics.Kind.CLICK) // Full lock alert
            }
            lastAngleInt = currentInt
        }
    }

    fun updatePedals(gas: Boolean, brk: Boolean, ntr: Boolean) {
        if (gas != throttle || brk != brake || ntr != nitro) {
            throttle = gas
            brake = brk
            nitro = ntr
            act(Action.WheelPedals(gas, brk, ntr), Haptics.Kind.TICK)
        }
    }

    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val brakeColor = Color(0xFFFF334B)
    val gasColor = Color(0xFF00E676)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val bounds = size
                        val cx = bounds.width / 2f
                        val cy = bounds.height / 2f
                        val centerRadius = bounds.width * 0.22f

                        var anyGas = false
                        var anyBrake = false
                        var anyCenter = false

                        val pressedPointers = event.changes.filter { it.pressed }
                        for (change in pressedPointers) {
                            val pos = change.position
                            val dx = pos.x - cx
                            val dy = pos.y - cy
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy)

                            if (dist <= centerRadius) {
                                anyCenter = true
                            } else if (pos.x < cx) {
                                anyBrake = true
                            } else {
                                anyGas = true
                            }
                        }

                        if (anyCenter && pressedPointers.size == 1 && event.changes.any { !it.previousPressed && it.pressed }) {
                            act(Action.WheelCenter, Haptics.Kind.CLICK)
                        }

                        updatePedals(anyGas, anyBrake, false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Rotating Racing Steering Wheel & Turn Meter
        Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val r = (minOf(w, h) / 2f) - 6.dp.toPx()

            // Outer Steer Gauge Arc
            val sweep = (steer * 90f).coerceIn(-90f, 90f)
            drawArc(
                color = colors.surfaceContainer.copy(alpha = 0.5f),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
            )
            if (abs(sweep) > 1f) {
                drawArc(
                    color = primary,
                    startAngle = 270f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // Draw Rotating Racing Steering Wheel
            rotate(degrees = angle, pivot = Offset(cx, cy)) {
                // Outer Rim
                drawCircle(
                    color = colors.onSurface.copy(alpha = 0.85f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 10.dp.toPx()),
                )

                // Top 12 O'Clock Racing Alignment Stripe
                drawArc(
                    color = primary,
                    startAngle = 262f,
                    sweepAngle = 16f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Butt),
                )

                // Left Spoke
                drawLine(
                    color = colors.onSurfaceVariant,
                    start = Offset(cx - r * 0.35f, cy),
                    end = Offset(cx - r + 5.dp.toPx(), cy),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // Right Spoke
                drawLine(
                    color = colors.onSurfaceVariant,
                    start = Offset(cx + r * 0.35f, cy),
                    end = Offset(cx + r - 5.dp.toPx(), cy),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // Bottom Spoke
                drawLine(
                    color = colors.onSurfaceVariant,
                    start = Offset(cx, cy + r * 0.35f),
                    end = Offset(cx, cy + r - 5.dp.toPx()),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // Center Hub Disc
                drawCircle(
                    color = colors.surfaceContainer,
                    radius = r * 0.36f,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = primary.copy(alpha = 0.6f),
                    radius = r * 0.36f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Center Horn Hub with Digital Angle Readout
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val angleStr = if (abs(angle) < 1f) "0°" else if (angle > 0) "+${angle.toInt()}°" else "${angle.toInt()}°"
            Text(
                text = angleStr,
                color = primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "CENTER",
                color = colors.onSurfaceVariant,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Left Touch Paddle: BRAKE
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 6.dp)
                .size(width = 56.dp, height = 76.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (brake) brakeColor.copy(alpha = 0.55f) else Color.Transparent)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "BRAKE",
                color = if (brake) Color.White else colors.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        // Right Touch Paddle: GAS
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .size(width = 56.dp, height = 76.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (throttle) gasColor.copy(alpha = 0.55f) else Color.Transparent)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "GAS",
                color = if (throttle) Color.White else colors.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}
