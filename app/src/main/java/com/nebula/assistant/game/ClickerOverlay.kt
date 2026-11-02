package com.nebula.assistant.game

import android.content.Context
import android.graphics.PointF
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R3cket tap points. While editing (touchable window): tap to add, drag to move.
 * While clicking (untouchable window): numbered rings that flash on each tap.
 */
@Composable
fun ClickerOverlay(@Suppress("UNUSED_PARAMETER") context: Context) {
    val k by R3cket.clicker.collectAsState()
    val pulse = rememberInfiniteTransition(label = "pts").animateFloat(0.6f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "p")
    val dragging = remember { intArrayOf(-1) }

    Box(Modifier.fillMaxSize().then(if (k.editing) Modifier.background(Color(0x33000000)) else Modifier)) {
        Canvas(
            Modifier.fillMaxSize().then(
                if (!k.editing) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { R3cket.addPoint(PointF(it.x, it.y)) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { o ->
                                val pts = R3cket.clicker.value.points
                                dragging[0] = pts.indexOfFirst { (Offset(it.x, it.y) - o).getDistance() < 48.dp.toPx() }
                            },
                            onDragEnd = { dragging[0] = -1 },
                            onDrag = { change, _ ->
                                val i = dragging[0]
                                if (i >= 0) { change.consume(); R3cket.movePoint(i, PointF(change.position.x, change.position.y)) }
                            },
                        )
                    }
            )
        ) {
            val r = 22.dp.toPx()
            val active = if (k.running && k.points.isNotEmpty()) ((k.clicks - 1).coerceAtLeast(0) % k.points.size).toInt() else -1
            k.points.forEachIndexed { i, p ->
                val c = Offset(p.x, p.y)
                val hot = i == active
                drawCircle(Color(0xFFE8531E).copy(alpha = if (hot) 0.55f else 0.22f), r, c)
                drawCircle(Color.White.copy(alpha = if (k.editing) pulse.value else 0.85f), r, c, style = Stroke(2.5.dp.toPx()))
                drawCircle(Color.White, 3.dp.toPx(), c)
            }
        }
        if (k.editing) {
            Text(
                "Tap where Nebula should tap · drag a ring to move it · press Done in the island",
                color = Color.White, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp, start = 24.dp, end = 24.dp)
                    .clip(CircleShape).background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
