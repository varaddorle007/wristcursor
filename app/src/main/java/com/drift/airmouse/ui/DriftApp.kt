package com.drift.airmouse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.LinkState
import com.drift.airmouse.core.Mode
import com.drift.airmouse.core.Pad
import com.drift.airmouse.core.Slot
import com.drift.airmouse.core.PointerBus
import com.drift.airmouse.core.PointerService
import com.drift.airmouse.ui.screens.ActionScreen
import com.drift.airmouse.ui.screens.ButtonScreen
import com.drift.airmouse.ui.screens.CalibrateScreen
import com.drift.airmouse.ui.screens.ModeScreen
import com.drift.airmouse.ui.screens.RemapScreen
import com.drift.airmouse.ui.screens.DevicesScreen
import com.drift.airmouse.ui.screens.DocScreen
import com.drift.airmouse.ui.screens.HomeScreen
import com.drift.airmouse.ui.screens.Knob
import com.drift.airmouse.ui.screens.PRIVACY_TEXT
import com.drift.airmouse.ui.screens.PairScreen
import com.drift.airmouse.ui.screens.SettingsScreen
import com.drift.airmouse.ui.screens.SteerScreen
import com.drift.airmouse.ui.screens.TERMS_TEXT
import com.drift.airmouse.ui.screens.ThemeScreen
import com.drift.airmouse.ui.screens.ValueScreen
import com.drift.airmouse.ui.screens.WelcomeScreen

private object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val PAD = "pad"
    const val PAIR = "pair"
    const val SETTINGS = "settings"
    const val VALUE = "value/{knob}"
    const val THEMES = "themes"
    const val DEVICES = "devices"
    const val CALIBRATE_FIRST = "calibrate-first"
    const val CALIBRATE = "calibrate"
    const val TERMS = "terms"
    const val PRIVACY = "privacy"
    const val MODES = "modes"
    const val REMAP = "remap"
    const val REMAP_BUTTON = "remap/{pad}/{slot}"
    const val REMAP_ACTION = "remap/{pad}/{slot}/{which}"

    fun value(knob: Knob) = "value/${knob.name}"
    fun remapButton(pad: Pad, slot: Slot) = "remap/${pad.name}/${slot.name}"
    fun remapAction(pad: Pad, slot: Slot, long: Boolean) = "remap/${pad.name}/${slot.name}/${if (long) "long" else "short"}"
}

/** Every screen closes with a swipe right; on Home that swipe closes the app. */
@Composable
fun DriftApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val start = remember {
        when {
            !prefs.onboarded -> Routes.WELCOME
            !prefs.calibrationOffered -> Routes.CALIBRATE_FIRST
            else -> Routes.HOME
        }
    }
    val theme by prefs.theme.collectAsState()
    val mode by PointerBus.mode.collectAsState()

    LaunchedEffect(Unit) {
        if (PointerBus.state.value is LinkState.Idle) PointerBus.mode.value = prefs.mode
    }

    DriftTheme(theme) {
        AppScaffold {
            val nav = rememberSwipeDismissableNavController()
            val current by nav.currentBackStackEntryFlow.collectAsState(initial = null)
            // On the touchpad a rightward drag moves the cursor; it must not close the screen.
            val swipeBack = !(current?.destination?.route == Routes.PAD && mode == Mode.TOUCHPAD)
            SwipeDismissableNavHost(navController = nav, startDestination = start, userSwipeEnabled = swipeBack) {
                composable(Routes.WELCOME) {
                    WelcomeScreen(
                        onTerms = { nav.navigate(Routes.TERMS) },
                        onPrivacy = { nav.navigate(Routes.PRIVACY) },
                        onDone = {
                            prefs.onboarded = true
                            nav.navigate(Routes.CALIBRATE_FIRST) {
                                popUpTo(Routes.WELCOME) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.CALIBRATE_FIRST) {
                    CalibrateScreen(onDone = {
                        prefs.calibrationOffered = true
                        nav.navigate(Routes.HOME) { popUpTo(Routes.CALIBRATE_FIRST) { inclusive = true } }
                    })
                }
                composable(Routes.CALIBRATE) { CalibrateScreen(onDone = { nav.popBackStack() }) }
                composable(Routes.HOME) {
                    HomeScreen(
                        onPad = { nav.navigate(Routes.PAD) },
                        onPairWifi = { nav.navigate(Routes.DEVICES) },
                        onDevices = { nav.navigate(Routes.DEVICES) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onModes = { nav.navigate(Routes.MODES) },
                    )
                }
                composable(Routes.PAD) {
                    SteerScreen(
                        onStop = {
                            PointerService.stop(context)
                            nav.popBackStack()
                        },
                        onModes = { nav.navigate(Routes.MODES) },
                        // Shake to go back: leave steering running, like swiping back.
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.PAIR) {
                    PairScreen(onDone = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onKnob = { nav.navigate(Routes.value(it)) },
                        onThemes = { nav.navigate(Routes.THEMES) },
                        onDevices = { nav.navigate(Routes.DEVICES) },
                        onCalibrate = { nav.navigate(Routes.CALIBRATE) },
                        onRemap = { nav.navigate(Routes.REMAP) },
                        onPairWifi = { nav.navigate(Routes.DEVICES) },
                        onTerms = { nav.navigate(Routes.TERMS) },
                        onPrivacy = { nav.navigate(Routes.PRIVACY) },
                        onClose = {
                            PointerService.stop(context)
                            onClose()
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.VALUE) { entry ->
                    val name = entry.arguments?.getString("knob")
                    ValueScreen(Knob.entries.firstOrNull { it.name == name } ?: Knob.SPEED, onBack = { nav.popBackStack() })
                }
                composable(Routes.THEMES) { ThemeScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.DEVICES) {
                    DevicesScreen(
                        onBack = { nav.popBackStack() },
                        onSteer = { nav.navigate(Routes.PAD) { popUpTo(Routes.HOME) } },
                    )
                }
                composable(Routes.TERMS) { DocScreen("Terms", TERMS_TEXT, onBack = { nav.popBackStack() }) }
                composable(Routes.PRIVACY) { DocScreen("Privacy", PRIVACY_TEXT, onBack = { nav.popBackStack() }) }
                composable(Routes.MODES) {
                    ModeScreen(
                        onPicked = { nav.popBackStack() },
                        onStop = {
                            PointerService.stop(context)
                            nav.popBackStack(Routes.HOME, inclusive = false)
                        },
                    )
                }
                composable(Routes.REMAP) {
                    RemapScreen(onButton = { pad, slot -> nav.navigate(Routes.remapButton(pad, slot)) }, onBack = { nav.popBackStack() })
                }
                composable(Routes.REMAP_BUTTON) { entry ->
                    val pad = Pad.entries.firstOrNull { it.name == entry.arguments?.getString("pad") } ?: Pad.REMOTE
                    val slot = Slot.entries.firstOrNull { it.name == entry.arguments?.getString("slot") } ?: Slot.UP
                    ButtonScreen(pad, slot, onPick = { long -> nav.navigate(Routes.remapAction(pad, slot, long)) }, onBack = { nav.popBackStack() })
                }
                composable(Routes.REMAP_ACTION) { entry ->
                    val pad = Pad.entries.firstOrNull { it.name == entry.arguments?.getString("pad") } ?: Pad.REMOTE
                    val slot = Slot.entries.firstOrNull { it.name == entry.arguments?.getString("slot") } ?: Slot.UP
                    ActionScreen(pad, slot, long = entry.arguments?.getString("which") == "long", onDone = { nav.popBackStack() })
                }
            }
        }
    }
}
