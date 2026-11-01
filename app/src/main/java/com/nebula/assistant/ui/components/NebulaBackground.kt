package com.nebula.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nebula.assistant.ui.theme.LocalGrain
import com.nebula.assistant.ui.theme.NebulaColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Slowly drifting clouds of colour behind everything, with a matte grain on top. */
@Composable
fun NebulaBackground(modifier: Modifier = Modifier, energy: Float = 0f) {
    val drift = rememberInfiniteTransition(label = "drift")
    val t by drift.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(30_000, easing = LinearEasing)),
        label = "t",
    )
    val e by animateFloatAsState(energy, label = "energy")
    val grain = LocalGrain.current

    Canvas(modifier) {
        drawRect(NebulaColors.Void)
        val w = size.width
        val h = size.height
        cloud(NebulaColors.Violet.copy(alpha = 0.40f + 0.18f * e), w * (0.22f + 0.08f * cos(t)), h * (0.20f + 0.05f * sin(2 * t)), w * 0.95f)
        cloud(NebulaColors.Magenta.copy(alpha = 0.20f), w * (0.88f + 0.06f * sin(t)), h * (0.52f + 0.06f * cos(t)), w * 0.80f)
        cloud(NebulaColors.Cyan.copy(alpha = 0.14f + 0.08f * e), w * (0.18f + 0.10f * sin(1.5f * t)), h * 0.95f, w * 0.90f)
        drawRect(grain, alpha = 0.9f)
    }
}

private fun DrawScope.cloud(color: Color, x: Float, y: Float, radius: Float) {
    val c = Offset(x, y)
    drawCircle(
        Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = c, radius = radius),
        radius = radius,
        center = c,
    )
}
