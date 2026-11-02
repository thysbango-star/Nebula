package com.nebula.assistant.ui.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nebula.assistant.data.TaskStatus
import kotlin.math.cos
import kotlin.math.sin

val Pal: NebulaPalette @Composable get() = LocalPalette.current

/** Springy press feedback for anything tappable. */
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "press")
    this.graphicsLayer { scaleX = s; scaleY = s }.clickable(source, indication = null, enabled = enabled, onClick = onClick)
}

/** The warm, drifting glow behind Nebula's home and onboarding (amber → orange → red). */
@Composable
fun EmberGlow(modifier: Modifier, intensity: Float = 1f, energy: Float = 0f) {
    val t = rememberInfiniteTransition(label = "glow")
    val a by t.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "a")
    val breathe by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(4200), RepeatMode.Reverse), label = "b")
    val p = Pal
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.62f * breathe * (1f + 0.15f * energy)
        fun blob(color: Color, dx: Float, dy: Float, rad: Float, alpha: Float) {
            val o = c + Offset(dx, dy)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha * intensity), color.copy(alpha = 0f)), o, rad), rad, o)
        }
        blob(p.ruby, cos(a) * r * 0.22f, sin(a * 1.3f) * r * 0.18f + r * 0.08f, r * 1.0f, 0.75f)
        blob(p.flame, cos(a + 2f) * r * 0.25f, sin(a + 1f) * r * 0.2f, r * 0.85f, 0.85f)
        blob(p.ember, cos(a * 0.7f + 4f) * r * 0.2f, sin(a * 0.9f + 3f) * r * 0.22f - r * 0.1f, r * 0.62f, 0.9f)
        blob(p.gold, cos(a * 1.2f + 1f) * r * 0.12f, -r * 0.18f + sin(a) * r * 0.08f, r * 0.34f, 0.8f)
    }
}

/** Nebula's mark: a warm orb with an orbit ring. */
@Composable
fun NebulaMark(size: Dp, modifier: Modifier = Modifier, spinning: Boolean = false) {
    val p = Pal
    val t = rememberInfiniteTransition(label = "mark")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (spinning) 1400 else 9000, easing = LinearEasing)), label = "r")
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF1C9), p.gold, p.flame, p.ruby), c + Offset(-r * 0.18f, -r * 0.2f), r * 0.75f), r * 0.46f, c)
        rotateRing(c, r, rot, p.gold)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.rotateRing(c: Offset, r: Float, rot: Float, color: Color) {
    val stroke = Stroke(r * 0.09f, cap = StrokeCap.Round)
    rotate(-18f, c) {
        drawOval(color.copy(alpha = 0.95f), topLeft = Offset(c.x - r * 0.95f, c.y - r * 0.3f),
            size = androidx.compose.ui.geometry.Size(r * 1.9f, r * 0.6f), style = stroke)
        val ang = Math.toRadians(rot.toDouble())
        drawCircle(Color.White, r * 0.08f, Offset(c.x + cos(ang).toFloat() * r * 0.95f, c.y + sin(ang).toFloat() * r * 0.3f))
    }
}

@Composable
fun NCard(modifier: Modifier = Modifier, color: Color = Pal.surface, radius: Dp = 26.dp, onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.then(if (onClick != null) Modifier.pressable(onClick) else Modifier).clip(shape).background(color)
        .border(1.dp, Pal.line, shape), content = content)
}

@Composable
fun Chip(label: String, selected: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val p = Pal
    val bg by animateColorAsState(if (selected) p.text else p.surface, tween(250), label = "chipBg")
    val fg by animateColorAsState(if (selected) p.bg else p.text, tween(250), label = "chipFg")
    Row(Modifier.pressable(onClick).clip(CircleShape).background(bg).border(1.dp, if (selected) Color.Transparent else p.line, CircleShape)
        .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/** The ask bar: text + mode chip + mic + send. */
@Composable
fun AskBar(
    value: String, onChange: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit,
    busy: Boolean, onStop: () -> Unit, placeholder: String = "Ask Nebula anything…", modifier: Modifier = Modifier,
) {
    val p = Pal
    NCard(modifier.fillMaxWidth(), color = p.raised, radius = 28.dp) {
        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 24.dp)) {
                if (value.isEmpty()) Text(placeholder, color = p.faint, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(value, onChange, textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text),
                    cursorBrush = SolidColor(p.flame), maxLines = 5, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }))
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(CircleShape).border(1.dp, p.line, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(p.gradient))
                    Spacer(Modifier.width(6.dp))
                    Text("Agent", style = MaterialTheme.typography.labelMedium, color = p.dim)
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.Mic, "Talk", tint = p.dim, modifier = Modifier.size(38.dp).clip(CircleShape).pressable(onMic).padding(8.dp))
                Spacer(Modifier.width(6.dp))
                val canSend = value.isNotBlank()
                Box(Modifier.size(38.dp).clip(CircleShape).background(if (busy) p.text else if (canSend) p.text else p.surface2)
                    .pressable({ if (busy) onStop() else if (canSend) onSend() }), contentAlignment = Alignment.Center) {
                    Icon(if (busy) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.ArrowForward, if (busy) "Stop" else "Send",
                        tint = if (busy || canSend) p.bg else p.faint, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

data class KindStyle(val icon: ImageVector, val color: Color)

@Composable
fun kindStyle(kind: String): KindStyle {
    val p = Pal
    return when (kind) {
        "message" -> KindStyle(Icons.Rounded.Call, p.pink)
        "document" -> KindStyle(Icons.Rounded.Description, p.violet)
        "screen" -> KindStyle(Icons.Rounded.TouchApp, p.orange)
        "web" -> KindStyle(Icons.Rounded.Language, p.blue)
        "phone" -> KindStyle(Icons.Rounded.PhoneAndroid, p.teal)
        else -> KindStyle(Icons.Rounded.AutoAwesome, p.flame)
    }
}

@Composable
fun KindIcon(kind: String, size: Dp = 44.dp) {
    val s = kindStyle(kind)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(s.color), contentAlignment = Alignment.Center) {
        Icon(s.icon, null, tint = Color.White, modifier = Modifier.size(size * 0.48f))
    }
}

/** Right-hand status mark on task rows: spinner, needs-you, done tick, failed cross. */
@Composable
fun StatusMark(status: TaskStatus, progress: Float, size: Dp = 26.dp) {
    val p = Pal
    val t = rememberInfiniteTransition(label = "status")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        when (status) {
            TaskStatus.IN_PROGRESS -> Canvas(Modifier.size(size)) {
                val s = 2.5.dp.toPx()
                drawArc(p.line, 0f, 360f, false, style = Stroke(s))
                drawArc(p.gradient, -90f, 360f * progress.coerceAtLeast(0.08f), false, style = Stroke(s, cap = StrokeCap.Round))
                drawArc(p.flame.copy(alpha = 0.5f), spin, 50f, false, style = Stroke(s, cap = StrokeCap.Round))
            }
            TaskStatus.REQUIRES_INPUT -> Box(Modifier.size(size).graphicsLayer { scaleX = 0.9f + 0.1f * pulse; scaleY = 0.9f + 0.1f * pulse }
                .clip(CircleShape).background(p.warn), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PriorityHigh, "Needs you", tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
            TaskStatus.COMPLETED -> Box(Modifier.size(size).clip(CircleShape).background(p.text), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, "Completed", tint = p.bg, modifier = Modifier.size(size * 0.62f))
            }
            TaskStatus.FAILED -> Box(Modifier.size(size).clip(CircleShape).background(p.bad), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Failed", tint = Color.White, modifier = Modifier.size(size * 0.6f))
            }
            TaskStatus.CANCELLED -> Box(Modifier.size(size).clip(CircleShape).border(2.dp, p.faint, CircleShape))
        }
    }
}

fun statusLabel(s: TaskStatus) = when (s) {
    TaskStatus.IN_PROGRESS -> "In progress"; TaskStatus.REQUIRES_INPUT -> "Needs you"; TaskStatus.COMPLETED -> "Completed"
    TaskStatus.FAILED -> "Failed"; TaskStatus.CANCELLED -> "Cancelled"
}

/** Three breathing dots for "thinking". */
@Composable
fun ThinkingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(7.dp).graphicsLayer { alpha = a; scaleX = 0.7f + 0.3f * a; scaleY = 0.7f + 0.3f * a }.clip(CircleShape).background(color))
        }
    }
}

@Composable
fun FadeScale(visible: Boolean, content: @Composable () -> Unit) =
    AnimatedVisibility(visible, enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.9f), exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.9f)) { content() }
