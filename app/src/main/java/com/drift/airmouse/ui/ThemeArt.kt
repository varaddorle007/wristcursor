package com.drift.airmouse.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.drift.airmouse.core.ThemeId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the look's background art. Everything is built once per size and cached,
 * then replayed on each frame: no bitmaps in memory and no animation burning battery.
 * [strength] fades the art (1 = full, lower for busy screens).
 */
fun Modifier.themeArt(look: Look, strength: Float = 1f): Modifier = drawWithCache {
    val draw = when (look.id) {
        ThemeId.DRIFT -> driftArt(look)
        ThemeId.WEB -> webArt(look)
        ThemeId.INFERNO -> infernoArt(look)
        ThemeId.REACTOR -> reactorArt(look)
        ThemeId.SYNTH -> synthArt(look)
        ThemeId.TERMINAL -> terminalArt(look)
    }
    onDrawBehind {
        drawRect(Color.Black)
        draw()
        if (strength < 1f) drawRect(Color.Black.copy(alpha = 1f - strength))
    }
}

private typealias Art = DrawScope.() -> Unit

private fun CacheDrawScope.driftArt(look: Look): Art {
    val glow = Brush.radialGradient(
        listOf(look.primary.copy(alpha = 0.28f), Color.Transparent),
        center = Offset(size.width * 0.15f, size.height * 0.1f),
        radius = size.width * 0.9f,
    )
    val trails = (0 until 4).map { i -> size.width * (0.55f + i * 0.17f) }
    val stroke = Stroke(width = 1.5f.dp(this), cap = StrokeCap.Round)
    return {
        drawRect(glow)
        trails.forEachIndexed { i, radius ->
            drawArc(
                color = look.primary.copy(alpha = 0.16f - i * 0.03f),
                startAngle = 200f, sweepAngle = 70f, useCenter = false,
                topLeft = Offset(size.width * 1.05f - radius, size.height * 1.05f - radius),
                size = Size(radius * 2, radius * 2),
                style = stroke,
            )
        }
    }
}

/** A dusky red-to-blue city sky with a spider web strung from the top-right corner. */
private fun CacheDrawScope.webArt(look: Look): Art {
    val sky = Brush.verticalGradient(listOf(Color(0xFF3A0A12), Color(0xFF14102A), Color(0xFF07112E)))
    val anchor = Offset(size.width * 0.92f, size.height * 0.05f)
    val reach = size.width * 1.3f
    val spokes = 11
    val angles = (0 until spokes).map { PI.toFloat() * (0.45f + 0.6f * it / (spokes - 1)) }
    val web = Path().apply {
        angles.forEach { a ->
            moveTo(anchor.x, anchor.y)
            lineTo(anchor.x + cos(a) * reach, anchor.y + sin(a) * reach)
        }
        // Threads sag a little toward the anchor, like a real web.
        for (ring in 1..9) {
            val r = reach * ring / 10f
            angles.zipWithNext().forEachIndexed { i, (a, b) ->
                val start = Offset(anchor.x + cos(a) * r, anchor.y + sin(a) * r)
                val end = Offset(anchor.x + cos(b) * r, anchor.y + sin(b) * r)
                val mid = (a + b) / 2
                val sag = r * 0.88f
                if (i == 0) moveTo(start.x, start.y)
                quadraticTo(anchor.x + cos(mid) * sag, anchor.y + sin(mid) * sag, end.x, end.y)
            }
        }
    }
    val skyline = Path().apply {
        val base = size.height
        moveTo(0f, base)
        var x = 0f
        val rnd = Random(11)
        while (x < size.width) {
            val w = size.width * (0.06f + rnd.nextFloat() * 0.07f)
            val h = size.height * (0.1f + rnd.nextFloat() * 0.22f)
            lineTo(x, base - h)
            lineTo(x + w, base - h)
            x += w
        }
        lineTo(size.width, base)
        close()
    }
    val stroke = Stroke(width = 1.2f.dp(this))
    return {
        drawRect(sky)
        drawPath(skyline, Color(0xFF05060F))
        drawPath(web, Color.White.copy(alpha = 0.2f), style = stroke)
        drawCircle(look.primary.copy(alpha = 0.25f), radius = size.width * 0.5f, center = Offset(0f, size.height), alpha = 0.6f)
    }
}

/** Molten glow from below, rising embers and glowing cracks. */
private fun CacheDrawScope.infernoArt(look: Look): Art {
    val heat = Brush.radialGradient(
        listOf(Color(0xFFFF4A00).copy(alpha = 0.75f), Color(0xFF5A0A00).copy(alpha = 0.6f), Color.Transparent),
        center = Offset(size.width / 2, size.height * 1.05f),
        radius = size.height * 0.85f,
    )
    val rnd = Random(66)
    val embers = List(60) {
        Offset(rnd.nextFloat() * size.width, size.height * (0.25f + rnd.nextFloat() * 0.75f))
    }
    val cracks = Path().apply {
        repeat(4) { c ->
            var x = size.width * (0.12f + c * 0.25f)
            var y = size.height
            moveTo(x, y)
            repeat(6) {
                x += (rnd.nextFloat() - 0.5f) * size.width * 0.12f
                y -= size.height * (0.04f + rnd.nextFloat() * 0.05f)
                lineTo(x, y)
            }
        }
    }
    val crackGlow = Stroke(width = 6f.dp(this), cap = StrokeCap.Round)
    val crackCore = Stroke(width = 1.6f.dp(this), cap = StrokeCap.Round)
    val emberSize = 2.2f.dp(this)
    return {
        drawRect(Color(0xFF0E0302))
        drawRect(heat)
        drawPath(cracks, look.primary.copy(alpha = 0.22f), style = crackGlow)
        drawPath(cracks, look.secondary.copy(alpha = 0.7f), style = crackCore)
        drawPoints(embers, PointMode.Points, look.secondary.copy(alpha = 0.7f), strokeWidth = emberSize, cap = StrokeCap.Round)
    }
}

/** A glowing reactor core: concentric rings and a segmented coil. */
private fun CacheDrawScope.reactorArt(look: Look): Art {
    val center = Offset(size.width / 2, size.height / 2)
    val r = min(size.width, size.height) / 2
    val core = Brush.radialGradient(
        listOf(look.primary.copy(alpha = 0.45f), look.primary.copy(alpha = 0.08f), Color.Transparent),
        center = center, radius = r * 0.95f,
    )
    val thin = Stroke(width = 1.4f.dp(this))
    val coil = Stroke(width = 7f.dp(this), cap = StrokeCap.Butt)
    val coilRadius = r * 0.78f
    return {
        drawRect(Color(0xFF02070B))
        drawRect(core)
        listOf(0.42f, 0.6f, 0.93f).forEach { f ->
            drawCircle(look.primary.copy(alpha = 0.22f), radius = r * f, center = center, style = thin)
        }
        repeat(10) { i ->
            drawArc(
                color = look.primary.copy(alpha = 0.28f),
                startAngle = i * 36f + 4f, sweepAngle = 28f, useCenter = false,
                topLeft = Offset(center.x - coilRadius, center.y - coilRadius),
                size = Size(coilRadius * 2, coilRadius * 2),
                style = coil,
            )
        }
    }
}

/** A striped neon sun setting over a perspective grid. */
private fun CacheDrawScope.synthArt(look: Look): Art {
    val sky = Brush.verticalGradient(listOf(Color(0xFF12052A), Color(0xFF3B0B4E), Color(0xFF0A0418)))
    val horizon = size.height * 0.62f
    val sunCenter = Offset(size.width / 2, horizon - size.height * 0.02f)
    val sunRadius = size.width * 0.27f
    val sun = Brush.verticalGradient(
        listOf(Color(0xFFFFE066), look.primary),
        startY = sunCenter.y - sunRadius, endY = sunCenter.y,
    )
    val grid = Path().apply {
        val vanish = Offset(size.width / 2, horizon)
        for (i in -8..8) {
            moveTo(vanish.x, vanish.y)
            lineTo(size.width / 2 + i * size.width * 0.18f, size.height)
        }
        var y = horizon
        var gap = size.height * 0.015f
        while (y < size.height) {
            moveTo(0f, y)
            lineTo(size.width, y)
            y += gap
            gap *= 1.45f
        }
    }
    val stripes = (0 until 5).map { i -> sunCenter.y - sunRadius * (0.5f - i * 0.11f) }
    val stripeHeight = 3f.dp(this)
    val stroke = Stroke(width = 1.2f.dp(this))
    return {
        drawRect(sky)
        drawArc(sun, 180f, 180f, true, Offset(sunCenter.x - sunRadius, sunCenter.y - sunRadius), Size(sunRadius * 2, sunRadius * 2))
        stripes.forEachIndexed { i, y ->
            drawRect(Color(0xFF2A0840), Offset(sunCenter.x - sunRadius, y), Size(sunRadius * 2, stripeHeight * (1 + i * 0.5f)))
        }
        drawRect(Color(0xFF0A0418), Offset(0f, horizon), Size(size.width, size.height - horizon))
        drawPath(grid, look.primary.copy(alpha = 0.45f), style = stroke)
    }
}

/** Columns of falling green code, brightest at the head of each stream. */
private fun CacheDrawScope.terminalArt(look: Look): Art {
    val rnd = Random(3)
    val column = 13f.dp(this)
    val glyph = Size(5f.dp(this), 8f.dp(this))
    val gap = 4f.dp(this)
    val width = size.width
    val height = size.height
    data class Glyph(val at: Offset, val alpha: Float)
    val glyphs = buildList {
        var x = column / 2
        while (x < width) {
            val length = 4 + rnd.nextInt(10)
            var y = rnd.nextFloat() * height * 0.8f
            for (i in 0 until length) {
                val alpha = if (i == length - 1) 0.75f else 0.1f + 0.35f * i / length
                add(Glyph(Offset(x, y), alpha))
                y += glyph.height + gap
            }
            x += column
        }
    }
    return {
        drawRect(Color(0xFF010603))
        glyphs.forEach { g ->
            drawRect(look.primary.copy(alpha = g.alpha), g.at, glyph)
        }
    }
}

private fun Float.dp(scope: CacheDrawScope): Float = this * scope.density
