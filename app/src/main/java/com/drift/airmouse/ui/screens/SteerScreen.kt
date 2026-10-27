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
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
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
import com.drift.airmouse.core.Bindings
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

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
fun SteerScreen(
    onStop: () -> Unit,
    onModes: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit = {},
) {
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
                ShakeAction.BACK -> {
                    if (mode == Mode.MOUSE) {
                        key(RemoteKey.BACK, Haptics.Kind.CLICK)
                    } else {
                        onBack()
                    }
                }
                ShakeAction.APP_BACK -> onBack()
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
                        Mode.GAME -> {
                            if (tuning.wheelBezelSteer) {
                                val delta = event.verticalScrollPixels * 0.35f
                                act(Action.WheelBezelDelta(delta), Haptics.Kind.TICK)
                            } else {
                                crown[0] += event.verticalScrollPixels
                                if (abs(crown[0]) >= 60f) {
                                    act(Action.Press(if (crown[0] > 0) RemoteKey.UP else RemoteKey.DOWN), Haptics.Kind.CLICK)
                                    crown[0] = 0f
                                }
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
                    Mode.GAME -> GameFace(tuning, bindings, ::act, ::buzz, ::key, onSettings)
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
    val scrollState by PointerBus.mouseScrollState.collectAsState()
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var tapCount by remember { mutableStateOf(0) }
    var tapJob by remember { mutableStateOf<Job?>(null) }
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
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        PointerBus.frozen = true
                        PointerBus.touching(true)
                        val downTime = System.currentTimeMillis()

                        val up = waitForUpOrCancellation()
                        val upTime = System.currentTimeMillis()
                        val holdDuration = upTime - downTime
                        PointerBus.touching(false)
                        pressed = false

                        if (up != null && holdDuration < 320L && !paused) {
                            tapCount++
                            tapJob?.cancel()
                            if (tapCount >= 3) {
                                // Triple tap: Right Click!
                                tapCount = 0
                                PointerBus.freezeFor(350L)
                                act(Action.RightClick, Haptics.Kind.HEAVY)
                            } else {
                                tapJob = scope.launch {
                                    delay(260L)
                                    val count = tapCount
                                    tapCount = 0
                                    if (count == 2) {
                                        // Double tap: Left Click!
                                        PointerBus.freezeFor(350L)
                                        act(Action.Click, Haptics.Kind.CLICK)
                                    } else if (count == 1) {
                                        // Single tap: Left Click!
                                        PointerBus.freezeFor(350L)
                                        act(Action.Click, Haptics.Kind.CLICK)
                                    }
                                }
                            }
                        } else if (holdDuration >= 320L && !paused) {
                            tapCount = 0
                            tapJob?.cancel()
                            if (tuning.holdDrags) {
                                PointerBus.perform(Action.DragStart)
                                dragging = true
                                PointerBus.frozen = false
                                buzz(Haptics.Kind.HEAVY)
                            } else {
                                PointerBus.freezeFor(350L)
                                act(Action.RightClick, Haptics.Kind.HEAVY)
                            }
                        }

                        if (dragging) {
                            PointerBus.perform(Action.DragEnd)
                            dragging = false
                            buzz(Haptics.Kind.TICK)
                        }
                        PointerBus.frozen = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (scrollState != 0) {
                    val isUp = scrollState < 0
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (isUp) "▲ SCROLL UP" else "▼ SCROLL DOWN",
                            color = colors.onPrimaryContainer,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                } else {
                    connectedTo?.let {
                        Text("→ $it", color = colors.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                MouseLogo(accent, flash.value)
                Spacer(Modifier.height(4.dp))
                Caption(
                    when {
                        scrollState < 0 -> "Scrolling up\nTilt neutral to stop"
                        scrollState > 0 -> "Scrolling down\nTilt neutral to stop"
                        paused -> if (tuning.shake == ShakeAction.PAUSE) "Paused\nShake to resume" else "Paused"
                        grabbed -> "Holding\nLet go to drop"
                        else -> "Double tap: Left click\nTriple tap: Right click"
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
 * Game section tabs: Virtual Steering Wheel or Tactile WASD Controller.
 */
enum class GameSection {
    STEERING,
    CONTROLS,
}

@Composable
private fun GameFace(
    tuning: Tuning,
    bindings: Bindings,
    act: (Action, Haptics.Kind) -> Unit,
    buzz: (Haptics.Kind) -> Unit,
    key: (RemoteKey, Haptics.Kind) -> Unit,
    onSettings: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(GameSection.STEERING) }
    val colors = MaterialTheme.colorScheme

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Active Game Face
        when (section) {
            GameSection.STEERING -> WheelFace(tuning, act, buzz, onSettings)
            GameSection.CONTROLS -> GameControlsFace(tuning, bindings, act, key, onSettings)
        }

        // Top Section Switcher Pill
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 38.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainer.copy(alpha = 0.92f))
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (section == GameSection.STEERING) colors.primary else Color.Transparent)
                    .clickable {
                        if (section != GameSection.STEERING) {
                            section = GameSection.STEERING
                            buzz(Haptics.Kind.TICK)
                        }
                    }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_wheel),
                        contentDescription = "Steering",
                        modifier = Modifier.size(11.dp),
                        tint = if (section == GameSection.STEERING) colors.onPrimary else colors.onSurfaceVariant,
                    )
                    Text(
                        "Steer",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (section == GameSection.STEERING) colors.onPrimary else colors.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (section == GameSection.CONTROLS) colors.primary else Color.Transparent)
                    .clickable {
                        if (section != GameSection.CONTROLS) {
                            section = GameSection.CONTROLS
                            buzz(Haptics.Kind.TICK)
                        }
                    }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_console),
                        contentDescription = "WASD Controls",
                        modifier = Modifier.size(11.dp),
                        tint = if (section == GameSection.CONTROLS) colors.onPrimary else colors.onSurfaceVariant,
                    )
                    Text(
                        "WASD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (section == GameSection.CONTROLS) colors.onPrimary else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Game controller steering wheel mode for car games.
 * - Wrist tilt/roll steers the car with ultra-responsive analog precision.
 * - Wrist pitch tilt (forward = Gas, back = Brake) for natural hands-free driving in Forza.
 * - Left half of the screen is the Brake paddle.
 * - Right half of the screen is the Gas / Accelerator paddle.
 * - Center hub tap instantly re-centers/calibrates zero angle.
 * - Settings gear button jumps directly to tuning and button remapping.
 */
@Composable
private fun WheelFace(
    tuning: Tuning,
    act: (Action, Haptics.Kind) -> Unit,
    buzz: (Haptics.Kind) -> Unit,
    onSettings: () -> Unit,
) {
    val angle by PointerBus.wheelAngle.collectAsState()
    val steer by PointerBus.wheelSteer.collectAsState()
    val gyroGas by PointerBus.wheelGyroGas.collectAsState()
    val gyroBrake by PointerBus.wheelGyroBrake.collectAsState()
    var touchThrottle by remember { mutableStateOf(false) }
    var touchBrake by remember { mutableStateOf(false) }
    var touchNitro by remember { mutableStateOf(false) }

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

    DisposableEffect(Unit) {
        onDispose {
            act(Action.WheelPedals(throttle = false, brake = false, nitro = false), Haptics.Kind.TICK)
        }
    }

    fun updatePedals(gas: Boolean, brk: Boolean, ntr: Boolean) {
        if (gas != touchThrottle || brk != touchBrake || ntr != touchNitro) {
            touchThrottle = gas
            touchBrake = brk
            touchNitro = ntr
            act(Action.WheelPedals(gas, brk, ntr), Haptics.Kind.TICK)
        }
    }

    val isGas = touchThrottle || (tuning.wheelTiltPedals && gyroGas)
    val isBrake = touchBrake || (tuning.wheelTiltPedals && gyroBrake)

    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val brakeColor = Color(0xFFFF334B)
    val gasColor = Color(0xFF00E676)

    Box(
        modifier = Modifier.fillMaxSize(),
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
                val rimW = 13.dp.toPx()
                val hubR = r * 0.42f
                val spokePlateH = 14.dp.toPx()

                val spokeBase = Color(0xFF272B32)
                val spokeHighlight = Color(0xFF566070)
                val spokeShadow = Color(0xFF131518)
                val holeBg = Color(0xFF0C0E11)

                // Left Spoke (Horizontal plate with racing speed holes)
                val leftSpokeX = cx - r + rimW * 0.35f
                val leftSpokeW = (cx - hubR + 2.dp.toPx()) - leftSpokeX
                if (leftSpokeW > 0) {
                    drawRoundRect(
                        color = spokeBase,
                        topLeft = Offset(leftSpokeX, cy - spokePlateH / 2f),
                        size = Size(leftSpokeW, spokePlateH),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    )
                    drawLine(
                        color = spokeHighlight,
                        start = Offset(leftSpokeX + 1.dp.toPx(), cy - spokePlateH / 2f),
                        end = Offset(leftSpokeX + leftSpokeW - 1.dp.toPx(), cy - spokePlateH / 2f),
                        strokeWidth = 1.dp.toPx(),
                    )
                    drawLine(
                        color = spokeShadow,
                        start = Offset(leftSpokeX + 1.dp.toPx(), cy + spokePlateH / 2f),
                        end = Offset(leftSpokeX + leftSpokeW - 1.dp.toPx(), cy + spokePlateH / 2f),
                        strokeWidth = 1.dp.toPx(),
                    )
                    val leftHoles = listOf(0.24f to 3.8.dp.toPx(), 0.54f to 3.0.dp.toPx(), 0.82f to 2.2.dp.toPx())
                    for ((pct, holeR) in leftHoles) {
                        val hx = leftSpokeX + leftSpokeW * pct
                        drawCircle(color = holeBg, radius = holeR, center = Offset(hx, cy))
                        drawCircle(
                            color = spokeHighlight.copy(alpha = 0.5f),
                            radius = holeR,
                            center = Offset(hx, cy),
                            style = Stroke(width = 0.75.dp.toPx()),
                        )
                    }
                }

                // Right Spoke (Horizontal plate with racing speed holes)
                val rightSpokeX = cx + hubR - 2.dp.toPx()
                val rightSpokeW = (cx + r - rimW * 0.35f) - rightSpokeX
                if (rightSpokeW > 0) {
                    drawRoundRect(
                        color = spokeBase,
                        topLeft = Offset(rightSpokeX, cy - spokePlateH / 2f),
                        size = Size(rightSpokeW, spokePlateH),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    )
                    drawLine(
                        color = spokeHighlight,
                        start = Offset(rightSpokeX + 1.dp.toPx(), cy - spokePlateH / 2f),
                        end = Offset(rightSpokeX + rightSpokeW - 1.dp.toPx(), cy - spokePlateH / 2f),
                        strokeWidth = 1.dp.toPx(),
                    )
                    drawLine(
                        color = spokeShadow,
                        start = Offset(rightSpokeX + 1.dp.toPx(), cy + spokePlateH / 2f),
                        end = Offset(rightSpokeX + rightSpokeW - 1.dp.toPx(), cy + spokePlateH / 2f),
                        strokeWidth = 1.dp.toPx(),
                    )
                    val rightHoles = listOf(0.18f to 2.2.dp.toPx(), 0.46f to 3.0.dp.toPx(), 0.76f to 3.8.dp.toPx())
                    for ((pct, holeR) in rightHoles) {
                        val hx = rightSpokeX + rightSpokeW * pct
                        drawCircle(color = holeBg, radius = holeR, center = Offset(hx, cy))
                        drawCircle(
                            color = spokeHighlight.copy(alpha = 0.5f),
                            radius = holeR,
                            center = Offset(hx, cy),
                            style = Stroke(width = 0.75.dp.toPx()),
                        )
                    }
                }

                // Bottom Spoke (Vertical plate with racing cutouts)
                val botSpokeY = cy + hubR - 2.dp.toPx()
                val botSpokeH = (cy + r - rimW * 0.35f) - botSpokeY
                val botSpokeW = 13.dp.toPx()
                if (botSpokeH > 0) {
                    drawRoundRect(
                        color = spokeBase,
                        topLeft = Offset(cx - botSpokeW / 2f, botSpokeY),
                        size = Size(botSpokeW, botSpokeH),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    )
                    drawLine(
                        color = spokeHighlight,
                        start = Offset(cx - botSpokeW / 2f, botSpokeY + 1.dp.toPx()),
                        end = Offset(cx - botSpokeW / 2f, botSpokeY + botSpokeH - 1.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                    drawLine(
                        color = spokeShadow,
                        start = Offset(cx + botSpokeW / 2f, botSpokeY + 1.dp.toPx()),
                        end = Offset(cx + botSpokeW / 2f, botSpokeY + botSpokeH - 1.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                    val botHoles = listOf(0.35f to 3.2.dp.toPx(), 0.72f to 2.5.dp.toPx())
                    for ((pct, holeR) in botHoles) {
                        val hy = botSpokeY + botSpokeH * pct
                        drawCircle(color = holeBg, radius = holeR, center = Offset(cx, hy))
                        drawCircle(
                            color = spokeHighlight.copy(alpha = 0.5f),
                            radius = holeR,
                            center = Offset(cx, hy),
                            style = Stroke(width = 0.75.dp.toPx()),
                        )
                    }
                }

                // Outer Rim: Outer Shadow Edge
                drawCircle(
                    color = Color(0xFF0F1114),
                    radius = r + rimW * 0.5f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx()),
                )

                // Outer Rim: Rich Textured Leather/Suede Grip Body
                drawCircle(
                    color = Color(0xFF1E2228),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = rimW),
                )

                // Outer Rim: 3D Specular Highlight Line
                drawCircle(
                    color = Color(0xFF38404C),
                    radius = r - rimW * 0.22f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx()),
                )

                // Outer Rim: Inner Shadow Edge
                drawCircle(
                    color = Color(0xFF0F1114),
                    radius = r - rimW * 0.5f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx()),
                )

                // Ergonomic Contoured Thumb Rests (10-and-2 position)
                drawArc(
                    color = Color(0xFF15181C),
                    startAngle = 168f,
                    sweepAngle = 36f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = rimW + 3.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = Color(0xFF4A5463),
                    startAngle = 172f,
                    sweepAngle = 28f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
                )

                drawArc(
                    color = Color(0xFF15181C),
                    startAngle = 336f,
                    sweepAngle = 36f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = rimW + 3.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = Color(0xFF4A5463),
                    startAngle = 340f,
                    sweepAngle = 28f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
                )

                // Top 12 O'Clock Racing Alignment Stripe with Pinstripe Borders
                drawArc(
                    color = Color(0xFFE2E7ED),
                    startAngle = 262f,
                    sweepAngle = 1.5f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = rimW + 1.dp.toPx(), cap = StrokeCap.Butt),
                )
                drawArc(
                    color = Color(0xFFE2E7ED),
                    startAngle = 276.5f,
                    sweepAngle = 1.5f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = rimW + 1.dp.toPx(), cap = StrokeCap.Butt),
                )
                drawArc(
                    color = primary,
                    startAngle = 263.5f,
                    sweepAngle = 13f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = rimW + 1.5.dp.toPx(), cap = StrokeCap.Butt),
                )

                // Center Billet Aluminum Mounting Hub Flange
                drawCircle(
                    color = Color(0xFF1E2228),
                    radius = hubR,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = Color(0xFF4C5564),
                    radius = hubR,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.8.dp.toPx()),
                )
                drawCircle(
                    color = Color(0xFF121417),
                    radius = hubR * 0.85f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.2.dp.toPx()),
                )

                // 6 Radial Hex Allen Bolts
                val boltRadius = hubR * 0.72f
                for (i in 0 until 6) {
                    val boltAngle = i * (PI * 2.0 / 6.0)
                    val bx = (cx + boltRadius * cos(boltAngle)).toFloat()
                    val by = (cy + boltRadius * sin(boltAngle)).toFloat()
                    drawCircle(
                        color = Color(0xFF383F4C),
                        radius = 2.2.dp.toPx(),
                        center = Offset(bx, by),
                    )
                    drawCircle(
                        color = Color(0xFF0F1114),
                        radius = 1.1.dp.toPx(),
                        center = Offset(bx, by),
                    )
                    drawCircle(
                        color = Color(0xFF8A95A5),
                        radius = 0.5.dp.toPx(),
                        center = Offset(bx - 0.4.dp.toPx(), by - 0.4.dp.toPx()),
                    )
                }

                // Inner Recessed Horn Button Disc
                val hornR = hubR * 0.54f
                drawCircle(
                    color = colors.surfaceContainer,
                    radius = hornR,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = primary.copy(alpha = 0.75f),
                    radius = hornR,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.8.dp.toPx()),
                )
            }
        }

        // Center Horn Hub with Digital Angle Readout and Pitch Status
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .clickable { act(Action.WheelCenter, Haptics.Kind.CLICK) },
        ) {
            val angleStr = if (abs(angle) < 1f) "0°" else if (angle > 0) "+${angle.toInt()}°" else "${angle.toInt()}°"
            Text(
                text = angleStr,
                color = primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            val subText = when {
                tuning.wheelTiltPedals && gyroGas -> "▲ GAS"
                tuning.wheelTiltPedals && gyroBrake -> "▼ BRAKE"
                else -> "CENTER"
            }
            val subColor = when {
                tuning.wheelTiltPedals && gyroGas -> gasColor
                tuning.wheelTiltPedals && gyroBrake -> brakeColor
                else -> colors.onSurfaceVariant
            }
            Text(
                text = subText,
                color = subColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
            )
        }

        // Left Touch Paddle: BRAKE
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 5.dp)
                .size(width = 50.dp, height = 66.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isBrake) brakeColor.copy(alpha = 0.50f) else brakeColor.copy(alpha = 0.15f))
                .border(
                    width = 2.dp,
                    color = if (isBrake) brakeColor else brakeColor.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(14.dp),
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        buzz(Haptics.Kind.TICK)
                        updatePedals(gas = touchThrottle, brk = true, ntr = touchNitro)
                        waitForUpOrCancellation()
                        updatePedals(gas = touchThrottle, brk = false, ntr = touchNitro)
                    }
                }
                .padding(horizontal = 4.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Top pedal grip slots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(bottom = 3.dp),
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .size(width = 6.dp, height = 2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (isBrake) Color.White.copy(alpha = 0.85f) else brakeColor.copy(alpha = 0.6f)),
                        )
                    }
                }
                Text(
                    "BRAKE",
                    color = if (isBrake) Color.White else brakeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                )
                // Bottom pedal grip slots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .size(width = 6.dp, height = 2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (isBrake) Color.White.copy(alpha = 0.85f) else brakeColor.copy(alpha = 0.6f)),
                        )
                    }
                }
            }
        }

        // Right Touch Paddle: GAS
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 5.dp)
                .size(width = 50.dp, height = 66.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isGas) gasColor.copy(alpha = 0.50f) else gasColor.copy(alpha = 0.15f))
                .border(
                    width = 2.dp,
                    color = if (isGas) gasColor else gasColor.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(14.dp),
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        buzz(Haptics.Kind.TICK)
                        updatePedals(gas = true, brk = touchBrake, ntr = touchNitro)
                        waitForUpOrCancellation()
                        updatePedals(gas = false, brk = touchBrake, ntr = touchNitro)
                    }
                }
                .padding(horizontal = 4.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Top pedal grip slots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(bottom = 3.dp),
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .size(width = 6.dp, height = 2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (isGas) Color.White.copy(alpha = 0.85f) else gasColor.copy(alpha = 0.6f)),
                        )
                    }
                }
                Text(
                    "GAS",
                    color = if (isGas) Color.White else gasColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                )
                // Bottom pedal grip slots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .size(width = 6.dp, height = 2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (isGas) Color.White.copy(alpha = 0.85f) else gasColor.copy(alpha = 0.6f)),
                        )
                    }
                }
            }
        }

        // Direct Settings Shortcut Button (Moved comfortably above the bottom bezel & Stop button)
        GameButton(
            icon = R.drawable.ic_settings,
            size = 28.dp,
            contentDescription = "Game Settings",
            onClick = onSettings,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-15).dp, y = (-42).dp),
        )
    }
}

/**
 * Dedicated gaming controls face:
 * - Center: Round D-pad / WASD cluster (W, A, S, D, Space) remappable via Pad.GAME.
 * - Satellite Buttons: ESC (Pause), SHIFT (Nitro/Drift), E (Action), Settings (Gear).
 * - Live Gyro Pitch driving indicator (hands-free gas/brake while pressing buttons).
 */
@Composable
private fun GameControlsFace(
    tuning: Tuning,
    bindings: Bindings,
    act: (Action, Haptics.Kind) -> Unit,
    key: (RemoteKey, Haptics.Kind) -> Unit,
    onSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val gyroGas by PointerBus.wheelGyroGas.collectAsState()
    val gyroBrake by PointerBus.wheelGyroBrake.collectAsState()
    val gasColor = Color(0xFF00E676)
    val brakeColor = Color(0xFFFF334B)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Gyro Acceleration / Braking Live Status Feedback (Forza hands-free gyro)
        val gyroStatus = when {
            tuning.wheelTiltPedals && gyroGas -> "▲ GYRO GAS"
            tuning.wheelTiltPedals && gyroBrake -> "▼ GYRO BRAKE"
            else -> null
        }
        val gyroStatusColor = when {
            gyroGas -> gasColor
            gyroBrake -> brakeColor
            else -> Color.Transparent
        }

        if (gyroStatus != null) {
            Text(
                text = gyroStatus,
                color = gyroStatusColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .clip(CircleShape)
                    .background(gyroStatusColor.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        // Center: Customizable Game RingPad (W, A, S, D, Space) with instant KeyDown / KeyUp
        RingPad(
            pad = Pad.GAME,
            bindings = bindings,
            onKey = key,
            onKeyDown = { k -> act(Action.KeyDown(k), Haptics.Kind.TICK) },
            onKeyUp = { k -> PointerBus.perform(Action.KeyUp(k)) },
            modifier = Modifier.size(134.dp),
        )

        // Satellite Quick Action Buttons:
        // Top-Left: ESC (Pause / Menu)
        GameButton(
            label = "ESC",
            size = 30.dp,
            contentDescription = "Escape / Pause",
            onClick = { key(RemoteKey.ESCAPE, Haptics.Kind.CLICK) },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 4.dp, y = (-36).dp),
        )

        // Top-Right: SHIFT (Drift / Sprint / Nitro)
        GameButton(
            label = "SHF",
            size = 30.dp,
            primary = true,
            contentDescription = "Shift / Drift",
            onClick = { key(RemoteKey.SHIFT, Haptics.Kind.CLICK) },
            onKeyDown = { act(Action.KeyDown(RemoteKey.SHIFT), Haptics.Kind.TICK) },
            onKeyUp = { PointerBus.perform(Action.KeyUp(RemoteKey.SHIFT)) },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-4).dp, y = (-36).dp),
        )

        // Bottom-Left: E (Action / Horn)
        GameButton(
            label = "E",
            size = 28.dp,
            contentDescription = "E / Action",
            onClick = { key(RemoteKey.KEY_E, Haptics.Kind.CLICK) },
            onKeyDown = { act(Action.KeyDown(RemoteKey.KEY_E), Haptics.Kind.TICK) },
            onKeyUp = { PointerBus.perform(Action.KeyUp(RemoteKey.KEY_E)) },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 8.dp, y = 36.dp),
        )

        // Bottom-Right: Settings Button (Direct shortcut to Remap / Settings)
        GameButton(
            icon = R.drawable.ic_settings,
            size = 28.dp,
            contentDescription = "Game Settings",
            onClick = onSettings,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-8).dp, y = 36.dp),
        )
    }
}

/** A compact tactile gaming button for satellite actions (ESC, SHIFT, E, Settings). */
@Composable
private fun GameButton(
    label: String? = null,
    @DrawableRes icon: Int? = null,
    size: Dp = 30.dp,
    primary: Boolean = false,
    contentDescription: String,
    onClick: () -> Unit = {},
    onKeyDown: (() -> Unit)? = null,
    onKeyUp: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (primary) colors.primary else colors.surfaceContainerHigh.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = if (primary) colors.primary else colors.outline.copy(alpha = 0.35f),
                shape = CircleShape,
            )
            .pointerInput(onKeyDown, onKeyUp, onClick) {
                if (onKeyDown != null && onKeyUp != null) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        PointerBus.touching(true)
                        onKeyDown()
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            onKeyUp()
                            PointerBus.touching(false)
                        }
                    }
                } else {
                    detectTapGestures(
                        onPress = {
                            PointerBus.touching(true)
                            tryAwaitRelease()
                            PointerBus.touching(false)
                        },
                        onTap = { onClick() },
                    )
                }
            }
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.52f),
                tint = if (primary) colors.onPrimary else colors.onSurface,
            )
        } else if (label != null) {
            Text(
                label,
                color = if (primary) colors.onPrimary else colors.onSurface,
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
        }
    }
}
