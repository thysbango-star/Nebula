package com.nebula.assistant.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sin

private val Blue = Color(0xFF4D8DFF)
private val Violet = Color(0xFFA55CFF)
private val Pink = Color(0xFFFF5FA8)
private val Amber = Color(0xFFFFB443)
private val Cyan = Color(0xFF4FE3FF)
private val Mint = Color(0xFF5CF0B0)
private val RimColors = listOf(Blue, Violet, Pink, Amber, Cyan, Blue)

/**
 * "Hey Nebula" light — Siri's edge glow + Gemini's bloom, born from the Notch:
 *  1. two streams of light pour out of the island and race down both edges of the screen,
 *  2. they meet at the bottom and the whole edge becomes a flowing, voice-reactive rim (Siri),
 *  3. a soft colour bloom rises from the bottom edge (Gemini), and a halo links back to the island.
 * When Nebula closes, the light draws back into the Notch. Never touchable.
 */
@Composable
fun GlowOverlay() {
    val s by VoiceState.session.collectAsState()
    var t by remember { mutableFloatStateOf(0f) }
    val pour = remember { Animatable(0f) }      // 0 = inside the notch, 1 = all the way round
    val presence = remember { Animatable(0f) }

    LaunchedEffect(s.active) {
        if (s.active) {
            val start = withFrameNanos { it }
            launch { presence.animateTo(1f, tween(200)) }
            launch { pour.snapTo(0f); pour.animateTo(1f, tween(760, easing = FastOutSlowInEasing)) }
            while (true) withFrameNanos { t = (it - start) / 1e9f }
        } else if (presence.value > 0f) {
            val start = withFrameNanos { it } - (t * 1e9f).toLong()
            launch { while (true) withFrameNanos { t = (it - start) / 1e9f } }
            launch { pour.animateTo(0f, tween(480, easing = FastOutSlowInEasing)) }
            presence.animateTo(0f, tween(560))
            coroutineContext.cancelChildren()
        }
    }

    val smooth = remember { floatArrayOf(0f) }
    val path = remember { Path() }
    val measure = remember { PathMeasure() }
    val seg = remember { Path() }
    val sizeKey = remember { floatArrayOf(0f, 0f) }

    Canvas(Modifier.fillMaxSize()) {
        val a = presence.value
        if (a <= 0.01f) return@Canvas
        val target = when (s.phase) {
            VoiceState.Phase.LISTENING -> s.level
            VoiceState.Phase.SPEAKING -> 0.35f + 0.25f * ((sin(t * 7.3f) + sin(t * 3.1f)) / 2f)
            VoiceState.Phase.THINKING -> 0.3f + 0.1f * sin(t * 5f)
            else -> 0.12f
        }
        smooth[0] += (target - smooth[0]) * 0.2f
        val lv = smooth[0]

        // Screen-edge path that starts and ends at the notch (top centre), clockwise.
        if (sizeKey[0] != size.width || sizeKey[1] != size.height) {
            sizeKey[0] = size.width; sizeKey[1] = size.height
            buildEdge(path, size.width, size.height, 46.dp.toPx(), 1.5.dp.toPx())
            measure.setPath(path, false)
        }
        val total = measure.length
        val p = pour.value
        val speed = if (s.phase == VoiceState.Phase.THINKING) 160f else 55f
        val spin = (t * speed) % 360f
        val c = Offset(size.width / 2, size.height / 2)
        val sweep = Brush.sweepGradient(flowing(spin / 360f), c) // colours flow round; the rim itself stays put

        // Gemini bloom from the bottom edge (arrives as the streams meet there).
        bloom(a * (p * 1.4f - 0.4f).coerceIn(0f, 1f), lv, t)
        // Halo where the light comes from: the island.
        val notch = Offset(size.width / 2, 20.dp.toPx())
        val halo = 150.dp.toPx() * (0.8f + 0.3f * lv)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.30f * a), Amber.copy(alpha = 0.16f * a), Color.Transparent), notch, halo), halo, notch)

        // Siri rim: two streams from the notch; once round, the full edge flows.
        seg.reset()
        val half = total / 2f * p
        measure.getSegment(0f, half, seg, true)
        measure.getSegment(total - half, total, seg, true)
        val pulse = 1f + 0.9f * lv
        listOf(44.dp to 0.05f, 24.dp to 0.11f, 11.dp to 0.24f, 5.dp to 0.5f, 2.dp to 0.8f).forEach { (w, al) ->
            drawPath(seg, sweep, alpha = al * a, style = Stroke(w.toPx() * pulse, cap = StrokeCap.Round))
        }
        drawPath(seg, Color.White.copy(alpha = 0.55f * a), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
        // Bright heads of the two streams while they travel.
        if (p in 0.02f..0.98f) {
            val pos1 = measure.getPosition(half); val pos2 = measure.getPosition(total - half)
            for (pt in listOf(pos1, pos2)) {
                val r = 34.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * a), Cyan.copy(alpha = 0.35f * a), Color.Transparent), pt, r), r, pt)
            }
        }
    }
}

/** The rim palette shifted round by [phase] (0..1), sampled smoothly so it loops without a seam. */
private fun flowing(phase: Float): List<Color> {
    val n = RimColors.size - 1 // last colour repeats the first
    return List(13) { i ->
        val x = ((i / 12f + phase) % 1f) * n
        val k = x.toInt().coerceAtMost(n - 1); val f = x - k
        androidx.compose.ui.graphics.lerp(RimColors[k], RimColors[k + 1], f)
    }
}

/** Rounded-rectangle screen edge as one path, starting at the top centre and going clockwise back to it. */
private fun buildEdge(p: Path, w: Float, h: Float, r: Float, inset: Float) {
    val l = inset; val t = inset; val rt = w - inset; val b = h - inset
    p.reset()
    p.moveTo(w / 2, t)
    p.lineTo(rt - r, t); p.quadraticBezierTo(rt, t, rt, t + r)
    p.lineTo(rt, b - r); p.quadraticBezierTo(rt, b, rt - r, b)
    p.lineTo(l + r, b); p.quadraticBezierTo(l, b, l, b - r)
    p.lineTo(l, t + r); p.quadraticBezierTo(l, t, l + r, t)
    p.lineTo(w / 2, t)
}

/** Soft colour light rising from the bottom edge; breathes with your voice. */
private fun DrawScope.bloom(k: Float, lv: Float, t: Float) {
    if (k <= 0.01f) return
    val w = size.width; val h = size.height
    val grow = 1f + 0.22f * lv
    fun blob(c: Color, x: Float, y: Float, r: Float, al: Float) {
        val rr = r * grow
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = al * k), c.copy(alpha = al * k * 0.3f), Color.Transparent), Offset(x, y), rr), rr, Offset(x, y))
    }
    val d = sin(t * 0.8f) * w * 0.07f
    blob(Blue, w * 0.22f + d, h * 1.04f, min(w, h) * 0.62f, 0.55f)
    blob(Violet, w * 0.5f - d, h * 1.08f, min(w, h) * 0.66f, 0.5f)
    blob(Pink, w * 0.8f + d, h * 1.04f, min(w, h) * 0.58f, 0.5f)
    blob(Amber, w * 0.55f + d * 0.5f, h * 1.12f, min(w, h) * 0.4f, 0.45f)
    blob(Mint, w * 0.05f, h * 1.0f, min(w, h) * 0.35f, 0.25f)
}
