package com.nebula.assistant.ui.vibrance

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.nebula.assistant.ui.Phase
import kotlinx.coroutines.delay

/**
 * Vibrance: Nebula's visual system. A ribbon of light flows around the screen edges
 * while an island grows out of the camera hole.
 */
object VibrancePalette {
    val Flow = listOf(
        Color(0xFFFF8A3D), // ember
        Color(0xFFFF4F9A), // rose
        Color(0xFFB45CFF), // violet
        Color(0xFF5B6CFF), // indigo
        Color(0xFF3FC8FF), // sky
        Color(0xFFFF6FD8), // orchid
    )
    val Alert = listOf(
        Color(0xFFFF6A3D),
        Color(0xFFFFB347),
        Color(0xFFFF4F6D),
        Color(0xFFFF8A3D),
    )

    fun forPhase(phase: Phase) = if (phase == Phase.Error) Alert else Flow
}

/** Real geometry of this phone's screen: corner rounding and the camera hole. */
data class ScreenShape(
    val cornerRadiusPx: Float = 0f,
    /** Top camera cutout in window pixels, if the phone has one. */
    val cutout: android.graphics.Rect? = null,
)

@Composable
fun rememberScreenShape(): ScreenShape {
    val view = LocalView.current
    val density = LocalDensity.current
    var shape by remember { mutableStateOf(ScreenShape(cornerRadiusPx = 44f * density.density)) }
    LaunchedEffect(view) {
        repeat(10) {
            val insets = view.rootWindowInsets
            if (insets != null) {
                val corner = if (Build.VERSION.SDK_INT >= 31) {
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
                } else null
                val cutout = if (Build.VERSION.SDK_INT >= 28) {
                    insets.displayCutout?.boundingRects
                        ?.filter { it.top <= view.height / 4 && it.width() < view.width / 2 }
                        ?.minByOrNull { it.top }
                } else null
                shape = ScreenShape(
                    cornerRadiusPx = corner?.takeIf { it > 0f } ?: shape.cornerRadiusPx,
                    cutout = cutout,
                )
                return@LaunchedEffect
            }
            delay(40)
        }
    }
    return shape
}

/** Shared clock so the glow and the island flow together. Speeds up while thinking. */
@Composable
fun rememberVibranceClock(phase: Phase): State<Float> {
    val speed = when (phase) {
        Phase.Listening -> 1f
        Phase.Thinking -> 3.2f
        Phase.Speaking -> 1.6f
        Phase.Done -> 0.6f
        Phase.Error -> 0.4f
    }
    val speedNow by rememberUpdatedState(speed)
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time.floatValue += (now - last) / 1_000_000_000f * speedNow
                last = now
            }
        }
    }
    return time
}

/** A sweep gradient whose colours rotate around [center] as [phase] goes 0 → 1. */
fun flowingSweep(colors: List<Color>, phase: Float, center: Offset, alpha: Float = 1f): Brush {
    val n = colors.size
    val stops = (0 until n).map { i ->
        val pos = ((i.toFloat() / n + phase) % 1f + 1f) % 1f
        pos to colors[i].copy(alpha = colors[i].alpha * alpha)
    }.sortedBy { it.first }
    // Make the seam at 0°/360° invisible by blending the colours either side of it.
    val first = stops.first()
    val last = stops.last()
    val gap = (1f - last.first) + first.first
    val t = if (gap <= 0f) 0f else (1f - last.first) / gap
    val seam = lerp(last.second, first.second, t)
    val all = buildList {
        add(0f to seam)
        addAll(stops)
        add(1f to seam)
    }
    return Brush.sweepGradient(*all.toTypedArray(), center = center)
}
