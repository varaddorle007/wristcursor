package com.drift.airmouse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import com.drift.airmouse.core.ThemeId

/** One look: its colors and the art drawn behind the main screens (see ThemeArt.kt). */
data class Look(
    val id: ThemeId,
    val label: String,
    val tagline: String,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val surface: Color,
)

val Looks: List<Look> = listOf(
    Look(
        ThemeId.DRIFT, "Drift", "Mint on midnight",
        primary = Color(0xFF8BF1D8), onPrimary = Color(0xFF00382D),
        secondary = Color(0xFFB8C7FF), onSecondary = Color(0xFF1B2A5C),
        surface = Color(0xFF1A1E27),
    ),
    Look(
        ThemeId.WEB, "Web", "Red, blue and a city web",
        primary = Color(0xFFFF4B4B), onPrimary = Color(0xFF3B0000),
        secondary = Color(0xFF5B8CFF), onSecondary = Color(0xFF001A4D),
        surface = Color(0xFF261A24),
    ),
    Look(
        ThemeId.INFERNO, "Inferno", "Hellfire and molten metal",
        primary = Color(0xFFFF7A1A), onPrimary = Color(0xFF2A0A00),
        secondary = Color(0xFFFFC14D), onSecondary = Color(0xFF3A2200),
        surface = Color(0xFF2A1A14),
    ),
    Look(
        ThemeId.REACTOR, "Reactor", "Glowing core, armored suit",
        primary = Color(0xFF6FE6FF), onPrimary = Color(0xFF00262F),
        secondary = Color(0xFFFFC857), onSecondary = Color(0xFF3A2A00),
        surface = Color(0xFF161F26),
    ),
    Look(
        ThemeId.SYNTH, "Synthwave", "Neon sun over the grid",
        primary = Color(0xFFFF5FD8), onPrimary = Color(0xFF3A0030),
        secondary = Color(0xFF9B7BFF), onSecondary = Color(0xFF1E0A4D),
        surface = Color(0xFF221632),
    ),
    Look(
        ThemeId.TERMINAL, "Terminal", "Green code rain",
        primary = Color(0xFF45FF8E), onPrimary = Color(0xFF00210E),
        secondary = Color(0xFFB4FFCF), onSecondary = Color(0xFF00210E),
        surface = Color(0xFF14201A),
    ),
)

fun lookFor(id: ThemeId): Look = Looks.first { it.id == id }

/** The look in use, for screens that draw theme art. */
val LocalLook = staticCompositionLocalOf { Looks.first() }

/** Chunky, wide and a little slanted: the playful Drift wordmark. Falls back to bold sans. */
@OptIn(ExperimentalTextApi::class)
val BrandFont = FontFamily(
    Font(
        DeviceFontFamilyName("roboto-flex"),
        weight = FontWeight.Black,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(1000),
            FontVariation.width(151f),
            FontVariation.slant(-10f),
        )
    ),
    Font(DeviceFontFamilyName("sans-serif"), weight = FontWeight.Black),
)

private fun Look.colorScheme(): ColorScheme {
    val text = Color(0xFFEFF1F6)
    return ColorScheme(
        primary = primary,
        primaryDim = lerp(primary, Color.Black, 0.15f),
        primaryContainer = lerp(primary, Color.Black, 0.7f),
        onPrimary = onPrimary,
        onPrimaryContainer = lerp(primary, Color.White, 0.6f),
        secondary = secondary,
        secondaryDim = lerp(secondary, Color.Black, 0.15f),
        secondaryContainer = lerp(secondary, Color.Black, 0.7f),
        onSecondary = onSecondary,
        onSecondaryContainer = lerp(secondary, Color.White, 0.6f),
        tertiary = secondary,
        tertiaryDim = lerp(secondary, Color.Black, 0.15f),
        tertiaryContainer = lerp(secondary, Color.Black, 0.7f),
        onTertiary = onSecondary,
        onTertiaryContainer = lerp(secondary, Color.White, 0.6f),
        surfaceContainerLow = lerp(surface, Color.Black, 0.35f),
        surfaceContainer = surface,
        surfaceContainerHigh = lerp(surface, Color.White, 0.08f),
        onSurface = text,
        onSurfaceVariant = Color(0xFFA9B0C0),
        outline = Color(0xFF6F7788),
        outlineVariant = Color(0xFF3A4050),
        background = Color.Black,
        onBackground = text,
        error = Color(0xFFFF9E9E),
        errorContainer = Color(0xFF7A1F1F),
        onError = Color(0xFF5C1111),
        onErrorContainer = Color(0xFFFFDAD6),
    )
}

@Composable
fun DriftTheme(id: ThemeId, content: @Composable () -> Unit) {
    val look = lookFor(id)
    val scheme = remember(id) { look.colorScheme() }
    CompositionLocalProvider(LocalLook provides look) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
