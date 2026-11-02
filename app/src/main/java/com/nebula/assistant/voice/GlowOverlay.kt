package com.nebula.assistant.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val Mint = Color(0xFF5CF0B0)
private val Sun = Color(0xFFFFD45E)
private val Sky = Color(0xFF6E9BFF)
private val Rose = Color(0xFFFF72B8)

/**
 * The full-screen "Hey Nebula" light:
 *  1. a soft wave sweeps up across the whole screen and frosts it,
 *  2. a halftone particle field ripples along the bottom,
 *  3. green/yellow/blue/pink light blooms from the bottom edge and corners,
 *  all framed by a frosted white edge glow. Reacts to your voice. Never touchable.
 */
@Composable
fun GlowOverlay() {
    val s by VoiceState.session.collectAsState()
    var t by remember { mutableFloatStateOf(0f) }
    val presence = remember { Animatable(0f) }

    // Each wake replays the sequence from the wave; the frame loop only runs while visible.
    LaunchedEffect(s.active) {
        if (s.active) {
            val start = withFrameNanos { it }
            launch { presence.animateTo(1f, tween(250)) }
            while (true) withFrameNanos { t = (it - start) / 1e9f }
        } else if (presence.value > 0f) {
            val start = withFrameNanos { it } - (t * 1e9f).toLong()
            launch { while (true) withFrameNanos { t = (it - start) / 1e9f } }
            presence.animateTo(0f, tween(650))
            coroutineContext.cancelChildren()
        }
    }
    val smooth = remember { floatArrayOf(0f) }

    Canvas(Modifier.fillMaxSize()) {
        val a = presence.value
        if (a <= 0.01f) return@Canvas
        // Smooth the voice level; while Nebula speaks give it a gentle talking pulse.
        val target = when (s.phase) {
            VoiceState.Phase.LISTENING -> s.level
            VoiceState.Phase.SPEAKING -> 0.35f + 0.25f * ((sin(t * 7.3f) + sin(t * 3.1f)) / 2f)
            VoiceState.Phase.THINKING -> 0.25f
            else -> 0.1f
        }
        smooth[0] += (target - smooth[0]) * 0.18f
        val lv = smooth[0]
        val speed = if (s.phase == VoiceState.Phase.THINKING) 2.6f else 1.4f
        frost(t, a)
        blooms(t, a, lv)
        halftone(t * speed, t, a, lv)
        sweep(t, a)
        edges(a, lv)
    }
}

private fun ease(x: Float): Float { val c = x.coerceIn(0f, 1f); return 1 - (1 - c) * (1 - c) * (1 - c) }
private fun ramp(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

/** A pale veil over the whole screen: strongest as the wave passes, then settles. */
private fun DrawScope.frost(t: Float, a: Float) {
    val peak = ramp(t, 0f, 0.5f) * (1 - 0.45f * ramp(t, 0.6f, 1.4f))
    drawRect(Color.White.copy(alpha = 0.13f * peak * a))
}

/** 1. The wave: a luminous, rippling crest that travels from the bottom to the top of the screen. */
private fun DrawScope.sweep(t: Float, a: Float) {
    val dur = 0.95f
    if (t > dur + 0.35f) return
    val w = size.width; val h = size.height
    val p = ease(t / dur)
    val crest = h * (1.05f - 1.25f * p)
    val fade = (1 - ramp(t, dur - 0.1f, dur + 0.35f)) * a
    val amp = h * 0.025f * (1 - p * 0.5f)
    val path = Path()
    path.moveTo(0f, h)
    var x = 0f
    val step = w / 48f
    while (x <= w + step) {
        val y = crest + amp * sin(x / w * 2f * PI.toFloat() * 1.3f + t * 9f) + amp * 0.5f * sin(x / w * 7f - t * 5f)
        path.lineTo(x, y); x += step
    }
    path.lineTo(w, h); path.close()
    // Bright crest fading into a trailing wash.
    drawPath(path, Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.55f * fade),
        0.06f to Sky.copy(alpha = 0.22f * fade),
        0.18f to Rose.copy(alpha = 0.10f * fade),
        0.4f to Color.Transparent,
        startY = crest - amp, endY = crest - amp + h * 0.6f,
    ))
}

/** 3. Soft coloured light rising from the bottom edge and both corners; breathes with your voice. */
private fun DrawScope.blooms(t: Float, a: Float, lv: Float) {
    val w = size.width; val h = size.height
    val k = ease(ramp(t, 0.55f, 1.35f)) * a
    if (k <= 0f) return
    val grow = 1f + 0.18f * lv
    fun bloom(c: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
        val rr = r * grow
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = alpha * k), c.copy(alpha = alpha * k * 0.35f), Color.Transparent), Offset(cx, cy), rr), rr, Offset(cx, cy))
    }
    val d1 = sin(t * 0.7f) * w * 0.06f; val d2 = sin(t * 0.53f + 2f) * w * 0.06f
    bloom(Mint, -w * 0.05f + d1, h * 1.0f, w * 0.85f, 0.55f)
    bloom(Rose, w * 1.05f + d2, h * 0.98f, w * 0.85f, 0.55f)
    bloom(Sun, w * 0.5f - d2, h * 1.08f, w * 0.7f, 0.6f)
    bloom(Sky, w * 0.28f + d2, h * 1.04f, w * 0.55f, 0.45f)
    bloom(Sky, w * 0.78f - d1, h * 1.05f, w * 0.5f, 0.35f)
    // Faint top-corner echoes so the whole frame feels lit.
    bloom(Mint, w * 1.0f, -h * 0.02f, w * 0.45f, 0.18f)
    bloom(Rose, 0f, -h * 0.02f, w * 0.45f, 0.18f)
}

/** 2. Halftone particles: a dot grid whose dots swell along a moving wave, fading up the screen. */
private fun DrawScope.halftone(t: Float, appear: Float, a: Float, lv: Float) {
    val w = size.width; val h = size.height
    val k = ease(ramp(appear, 0.45f, 1.15f)) * a
    if (k <= 0f) return
    val gap = 9.dp.toPx()
    val maxR = gap * 0.46f
    val buckets = 7
    val pts = Array(buckets) { ArrayList<Offset>(400) }
    val top = h * 0.55f
    val band = h * (0.075f + 0.035f * lv)
    var y = h - gap * 0.5f
    var row = 0
    while (y > top) {
        val shift = if (row % 2 == 0) 0f else gap / 2
        var x = shift
        while (x < w) {
            val centre = h * 0.80f + h * 0.045f * sin(x / w * 2f * PI.toFloat() * 1.15f + t * 1.6f) + h * 0.02f * sin(x / w * 9f - t * 2.3f)
            val d = (y - centre) / band
            // Dense below the crest, fading upward above it.
            val v = if (d > 0) 0.35f + 0.65f * exp(-d * d * 0.35f) else exp(-d * d)
            val edgeFade = min(1f, (h - y) / (gap * 2f)) * min(1f, (y - top) / (h * 0.12f))
            val r = maxR * v * edgeFade * (0.75f + 0.25f * lv)
            if (r > 0.5f) {
                val b = min(buckets - 1, (r / maxR * buckets).toInt())
                pts[b].add(Offset(x, y))
            }
            x += gap
        }
        y -= gap * 0.87f; row++
    }
    for (b in 0 until buckets) {
        if (pts[b].isEmpty()) continue
        val r = maxR * (b + 0.7f) / buckets
        drawPoints(pts[b], PointMode.Points, Color.White.copy(alpha = (0.35f + 0.4f * b / buckets) * k), strokeWidth = r * 2, cap = StrokeCap.Round)
    }
}

/** The frosted white rim hugging the screen's rounded corners. */
private fun DrawScope.edges(a: Float, lv: Float) {
    val corner = 44.dp.toPx()
    val glow = 0.75f + 0.25f * lv
    listOf(36.dp to 0.035f, 18.dp to 0.07f, 8.dp to 0.14f, 3.dp to 0.30f, 1.2.dp to 0.55f).forEach { (wd, al) ->
        val sw = wd.toPx()
        drawRoundRect(Color.White.copy(alpha = al * a * glow), Offset(sw / 2, sw / 2),
            androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw), CornerRadius(max(0f, corner - sw / 2)), style = Stroke(sw))
    }
}
