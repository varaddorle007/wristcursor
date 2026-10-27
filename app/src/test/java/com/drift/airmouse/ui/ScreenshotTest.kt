package com.drift.airmouse.ui

import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.drift.airmouse.core.AppPrefs
import com.drift.airmouse.core.BluetoothHost
import com.drift.airmouse.core.DeviceKind
import com.drift.airmouse.core.LinkState
import com.drift.airmouse.core.Mode
import com.drift.airmouse.core.PointerBus
import com.drift.airmouse.core.ThemeId
import com.drift.airmouse.core.Transport
import com.drift.airmouse.ui.screens.CalibrateScreen
import com.drift.airmouse.ui.screens.HomeScreen
import com.drift.airmouse.ui.screens.SettingsScreen
import com.drift.airmouse.ui.screens.SteerScreen
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.wear.compose.material3.AppScaffold
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens at a round Galaxy Watch size, so the UI can be checked
 * (and shared) without a watch. Pictures land in app/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w226dp-h226dp-round-watch-xhdpi")
class ScreenshotTest {

    private val prefs get() = AppPrefs.get(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() {
        prefs.onboarded = true
        prefs.setTransport(Transport.BLUETOOTH)
        prefs.saveBluetoothHost(BluetoothHost("AA:BB:CC:DD:EE:FF", "MacBook Pro", DeviceKind.LAPTOP))
    }

    @After
    fun tearDown() {
        PointerBus.state.value = LinkState.Idle()
        PointerBus.connectedTo.value = null
    }

    private fun shot(name: String, theme: ThemeId = ThemeId.DRIFT, content: @Composable () -> Unit) {
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            DriftTheme(theme) { AppScaffold { content() } }
        }
    }

    private fun steering(mode: Mode) {
        PointerBus.state.value = LinkState.Steering
        PointerBus.connectedTo.value = "MacBook Pro"
        PointerBus.mode.value = mode
    }

    @Test
    fun home() = shot("01-home") { HomeScreen({}, {}, {}, {}, {}) }

    @Test
    fun homeConnected() {
        steering(Mode.MOUSE)
        shot("02-home-connected") { HomeScreen({}, {}, {}, {}, {}) }
    }

    @Test
    fun mouse() {
        steering(Mode.MOUSE)
        shot("03-mouse") { SteerScreen({}, {}, {}) }
    }

    @Test
    fun mouseHolding() {
        steering(Mode.MOUSE)
        PointerBus.holding.value = true
        shot("03b-mouse-holding") { SteerScreen({}, {}, {}) }
        PointerBus.holding.value = false
    }

    @Test
    fun keyboard() {
        steering(Mode.KEYBOARD)
        shot("04-keyboard") { SteerScreen({}, {}, {}) }
    }

    @Test
    fun media() {
        steering(Mode.MEDIA)
        shot("05-media") { SteerScreen({}, {}, {}) }
    }

    @Test
    fun slides() {
        steering(Mode.SLIDES)
        shot("06-slides") { SteerScreen({}, {}, {}) }
    }

    @Test
    fun remote() {
        steering(Mode.REMOTE)
        shot("07-tv-remote") { SteerScreen({}, {}, {}) }
    }

    @Test
    fun touchpad() {
        steering(Mode.TOUCHPAD)
        shot("08-touchpad") { SteerScreen({}, {}, {}) }
    }

    @Test
    fun settings() = shot("09-settings") { SettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }

    /** A tall, unrounded render so every settings row shows at once. */
    @Test
    @Config(qualifiers = "w226dp-h1400dp-notround-watch-xhdpi")
    fun settingsAll() = shot("09b-settings-all") { SettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test
    fun calibrate() = shot("10-calibrate") { CalibrateScreen(onDone = {}) }

    @Test
    fun homeInWebTheme() = shot("11-home-web-theme", ThemeId.WEB) { HomeScreen({}, {}, {}, {}, {}) }

    @Test
    fun game() {
        steering(Mode.GAME)
        shot("12-game") { SteerScreen({}, {}, {}) }
    }
}
