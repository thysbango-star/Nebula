package com.nebula.assistant.ui.vibrance

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nebula.assistant.ui.Phase
import kotlin.math.min
import kotlin.math.sin

/**
 * The Vibrance edge glow: a ribbon of flowing colour hugging the screen's rounded edges.
 *
 * - Appears by tracing up both sides from the bottom and meeting at the top.
 * - Breathes with your voice while listening.
 * - Spins faster with bright comets racing around while thinking.
 * - Pulses while speaking, and retracts back down when it closes.
 */
@Composable
fun VibranceGlow(
    modifier: Modifier,
    phase: Phase,
    level: Float,
    visible: Boolean,
    cornerRadiusPx: Float,
    intensity: Float,
    clock: State<Float>,
) {
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 950 else 480, easing = FastOutSlowInEasing),
        label = "reveal",
    )
    val voice by animateFloatAsState(
        targetValue = if (phase == Phase.Listening) level.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "voice",
    )
    val calm by animateFloatAsState(
        targetValue = if (phase == Phase.Done) 0.55f else 1f,
        animationSpec = tween(700),
        label = "calm",
    )
    val colors = VibrancePalette.forPhase(phase)
    val thinking = phase == Phase.Thinking
    val speaking = phase == Phase.Speaking
    val strength = 0.35f + 0.65f * intensity.coerceIn(0f, 1f)

    Spacer(
        modifier.drawWithCache {
            val core = 3.dp.toPx()
            val inset = core * 0.55f
            val outline = edgePath(size.width, size.height, inset, cornerRadiusPx)
            val measure = PathMeasure().apply { setPath(outline, false) }
            val length = measure.length
            val center = Offset(size.width / 2f, size.height / 2f)
            val traced = Path()
            val comet = Path()

            onDrawBehind {
                if (reveal <= 0.001f) return@onDrawBehind
                val t = clock.value

                // Trace from the bottom centre up both sides.
                traced.reset()
                val half = length * reveal / 2f
                measure.getSegment(0f, half, traced, true)
                measure.getSegment(length - half, length, traced, true)

                val pulse = if (speaking) 0.5f + 0.5f * sin(t * 7.5f) else 0f
                val energy = (voice * 0.9f + pulse * 0.55f + if (thinking) 0.25f else 0f).coerceIn(0f, 1.2f)
                val width = core * (1f + 0.9f * energy)
                val brush = flowingSweep(colors, phase = t * 0.08f, center = center)
                val alpha = strength * calm * min(1f, reveal * 1.6f)

                // Soft bloom layers (wide + faint → narrow + bright). The outer half falls off-screen,
                // so the light appears to spill inward from the bezel.
                val layers = floatArrayOf(18f, 10f, 5f, 2.4f, 1f)
                val alphas = floatArrayOf(0.05f, 0.10f, 0.20f, 0.42f, 0.95f)
                for (i in layers.indices) {
                    drawPath(
                        traced, brush,
                        alpha = (alphas[i] * alpha * (1f + 0.4f * energy)).coerceAtMost(1f),
                        style = Stroke(width = width * layers[i], cap = StrokeCap.Round),
                    )
                }
                // Hot white core for a glassy shine.
                drawPath(
                    traced, Color.White,
                    alpha = 0.35f * alpha * (0.6f + 0.4f * energy),
                    style = Stroke(width = width * 0.45f, cap = StrokeCap.Round),
                )

                // Thinking: two bright comets race around the edge.
                if (thinking && reveal > 0.98f) {
                    val span = length * 0.12f
                    for (k in 0..1) {
                        val head = ((t * 0.35f + k * 0.5f) % 1f) * length
                        comet.reset()
                        val start = head - span
                        if (start >= 0f) {
                            measure.getSegment(start, head, comet, true)
                        } else {
                            measure.getSegment(length + start, length, comet, true)
                            measure.getSegment(0f, head, comet, true)
                        }
                        drawPath(comet, Color.White, alpha = 0.18f * alpha, style = Stroke(width * 7f, cap = StrokeCap.Round))
                        drawPath(comet, Color.White, alpha = 0.75f * alpha, style = Stroke(width * 1.3f, cap = StrokeCap.Round))
                    }
                }
            }
        }
    )
}

/**
 * The screen outline, starting at the bottom centre and running anticlockwise
 * (up the right side, across the top, down the left), so a segment from 0 and one
 * from the end grow toward each other at the top.
 */
private fun edgePath(w: Float, h: Float, inset: Float, radius: Float): Path {
    val l = inset
    val t = inset
    val r = w - inset
    val b = h - inset
    val rr = (radius - inset).coerceIn(1f, min(r - l, b - t) / 2f)
    return Path().apply {
        moveTo(w / 2f, b)
        lineTo(r - rr, b)
        arcTo(Rect(r - 2 * rr, b - 2 * rr, r, b), 90f, -90f, false)
        lineTo(r, t + rr)
        arcTo(Rect(r - 2 * rr, t, r, t + 2 * rr), 0f, -90f, false)
        lineTo(l + rr, t)
        arcTo(Rect(l, t, l + 2 * rr, t + 2 * rr), 270f, -90f, false)
        lineTo(l, b - rr)
        arcTo(Rect(l, b - 2 * rr, l + 2 * rr, b), 180f, -90f, false)
        lineTo(w / 2f, b)
    }
}
