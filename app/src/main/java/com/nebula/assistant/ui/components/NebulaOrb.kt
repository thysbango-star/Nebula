package com.nebula.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.nebula.assistant.ui.theme.NebulaColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbMood { Idle, Listening, Thinking, Speaking, Error }

/**
 * Nebula's face: a liquid orb of swirling gas.
 * The edge wobbles like a droplet, it swells with your voice, spins faster while thinking,
 * and pulses while speaking.
 */
@Composable
fun NebulaOrb(
    modifier: Modifier = Modifier,
    mood: OrbMood = OrbMood.Idle,
    level: Float = 0f,
) {
    val lvl by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "level",
    )
    val speed = when (mood) {
        OrbMood.Idle -> 0.35f
        OrbMood.Listening -> 0.9f
        OrbMood.Thinking -> 2.6f
        OrbMood.Speaking -> 1.3f
        OrbMood.Error -> 0.2f
    }
    val speedNow by rememberUpdatedState(speed)

    // Our own clock so the swirl can speed up and slow down smoothly.
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) t += (now - last) / 1_000_000_000f * speedNow
                last = now
            }
        }
    }

    val colorSpec = tween<Color>(600)
    val c1 by animateColorAsState(
        when (mood) {
            OrbMood.Error -> NebulaColors.Magenta
            OrbMood.Thinking -> NebulaColors.Magenta
            else -> NebulaColors.Violet
        }, colorSpec, label = "c1"
    )
    val c2 by animateColorAsState(
        when (mood) {
            OrbMood.Listening -> NebulaColors.Cyan
            OrbMood.Speaking -> NebulaColors.Lilac
            OrbMood.Error -> NebulaColors.Amber
            else -> NebulaColors.Magenta
        }, colorSpec, label = "c2"
    )
    val c3 by animateColorAsState(
        when (mood) {
            OrbMood.Thinking -> NebulaColors.Cyan
            OrbMood.Error -> NebulaColors.Violet
            else -> NebulaColors.Lilac
        }, colorSpec, label = "c3"
    )

    Canvas(modifier) {
        val speaking = if (mood == OrbMood.Speaking) 0.35f * (0.5f + 0.5f * sin(t * 7f)) else 0f
        val energy = (lvl + speaking).coerceIn(0f, 1f)
        val base = size.minDimension / 2f * 0.64f
        val r = base * (1f + 0.13f * energy)
        val c = center

        // Soft halo
        drawCircle(
            Brush.radialGradient(
                listOf(c1.copy(alpha = 0.38f + 0.32f * energy), Color.Transparent),
                center = c, radius = r * 1.55f,
            ),
            radius = r * 1.55f, center = c,
        )

        // Droplet outline that wobbles a little, more when there's sound.
        val body = Path()
        val steps = 72
        for (i in 0..steps) {
            val a = (i.toFloat() / steps) * 2f * PI.toFloat()
            val wobble = 1f +
                0.022f * sin(3 * a + t * 1.7f) +
                0.016f * sin(5 * a - t * 2.3f) +
                0.045f * energy * sin(4 * a + t * 5f)
            val p = Offset(c.x + cos(a) * r * wobble, c.y + sin(a) * r * wobble)
            if (i == 0) body.moveTo(p.x, p.y) else body.lineTo(p.x, p.y)
        }
        body.close()

        clipPath(body) {
            // Deep core
            drawCircle(
                Brush.radialGradient(
                    listOf(NebulaColors.Ink, Color(0xFF05040C)),
                    center = c, radius = r * 1.1f,
                ),
                radius = r * 1.2f, center = c,
            )
            // Three clouds of gas orbiting inside
            val clouds = listOf(c1, c2, c3)
            clouds.forEachIndexed { i, col ->
                val a = t * (0.9f + i * 0.37f) + i * 2.1f
                val off = Offset(cos(a), sin(a * 1.3f)) * (r * 0.42f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(col.copy(alpha = 0.95f), col.copy(alpha = 0f)),
                        center = c + off, radius = r * 0.95f,
                    ),
                    radius = r * 0.95f, center = c + off,
                    blendMode = BlendMode.Screen,
                )
            }
            // Matte frost over the gas
            drawCircle(Color.White.copy(alpha = 0.05f), radius = r * 1.2f, center = c)
            // Specular highlight, top-left
            val hl = c + Offset(-r * 0.34f, -r * 0.40f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                    center = hl, radius = r * 0.5f,
                ),
                radius = r * 0.5f, center = hl,
            )
        }

        // Glass rim
        drawPath(body, Color.White.copy(alpha = 0.24f), style = Stroke(width = 1.2.dp.toPx()))
    }
}
