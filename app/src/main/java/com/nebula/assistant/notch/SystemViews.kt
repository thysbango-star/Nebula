package com.nebula.assistant.notch

import android.telecom.TelecomManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.assistant.data.NebulaTask
import com.nebula.assistant.data.TaskStatus
import com.nebula.assistant.voice.VoiceState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

// ============================================================================ compact system views

@Composable
internal fun PreviewView() = Compact({ Orb(VoiceState.Phase.SPEAKING, 0.3f, 22.dp); Spacer(Modifier.width(8.dp)); Text("Nebula", color = Color.White, style = label()) },
    { Text("size", color = Dim, style = label()) })

/** Face unlock: a gradient arc scans, then closes into a ring and the check pops. */
@Composable
internal fun UnlockView(success: Boolean, h: Dp) {
    val spin = rememberInfiniteTransition(label = "scan")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "angle")
    val done by animateFloatAsState(if (success) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 260f), label = "done")
    Compact({
        Box(Modifier.size(h - 8.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.4.dp.toPx()
                if (done < 0.99f) drawArc(NotchGradient, angle, 250f * (1 - done) + 110f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                if (done > 0f) drawArc(Mint, -90f, 360f * done, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Icon(if (success) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null, tint = if (success) Mint else Color.White,
                modifier = Modifier.size(h * 0.42f).graphicsLayer { val k = 0.8f + 0.2f * done + 0.15f * sin(done * PI.toFloat()); scaleX = k; scaleY = k })
        }
    }, { Text(if (success) "Unlocked" else "Hi", color = if (success) Mint else Dim, style = label(), modifier = Modifier.rise(1)) })
}

/** Volume: a gradient track that springs to the level, with a glow at its head. */
@Composable
internal fun VolumeView(level: Float, muted: Boolean, h: Dp) {
    val shown by animateFloatAsState(level, spring(dampingRatio = 0.62f, stiffness = 520f), label = "vol")
    Row(Modifier.fillMaxSize().padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, null, tint = Color.White, modifier = Modifier.size(17.dp).rise(0))
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.weight(1f).height(14.dp).rise(1)) {
            val y = size.height / 2; val r = 3.dp.toPx(); val x = size.width * shown.coerceIn(0f, 1f)
            drawRoundRect(Color(0x33FFFFFF), Offset(0f, y - r), Size(size.width, r * 2), CornerRadius(r))
            drawRoundRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White)), Offset(0f, y - r), Size(x, r * 2), CornerRadius(r))
            if (x > 2f) drawCircle(Brush.radialGradient(listOf(Flame.copy(alpha = 0.55f), Color.Transparent), Offset(x, y), 9.dp.toPx()), 9.dp.toPx(), Offset(x, y))
        }
        Spacer(Modifier.width(10.dp))
        Text("${(level * 100).toInt()}", color = Dim, style = label().copy(fontFamily = Display), modifier = Modifier.width(26.dp).rise(2))
    }
}

/** Charging: a battery that fills with the Nebula gradient while a bolt pulses — the percentage counts up to the real level. */
@Composable
internal fun ChargeView(ch: NotchBus.Charge, h: Dp) {
    val fill = remember(ch.at) { Animatable(0f) }
    LaunchedEffect(ch.at) { fill.animateTo(ch.level / 100f, tween(1100, easing = FastOutSlowInEasing)) }
    val t by rememberClock(ch.at)
    val low = ch.level <= 15
    val col = if (!ch.plugged) Dim else if (low) Red else Mint
    Compact({
        Canvas(Modifier.size(width = 34.dp, height = h * 0.44f).rise(0)) {
            val bw = size.width - 3.dp.toPx(); val r = 4.dp.toPx(); val sw = 1.4.dp.toPx()
            drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(sw / 2, sw / 2), Size(bw - sw, size.height - sw), CornerRadius(r), style = Stroke(sw))
            drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(bw + 0.5.dp.toPx(), size.height * 0.32f), Size(2.dp.toPx(), size.height * 0.36f), CornerRadius(1.dp.toPx()))
            val inner = 2.5.dp.toPx()
            val fw = (bw - inner * 2) * fill.value
            if (ch.plugged) drawRoundRect(Brush.horizontalGradient(if (low) listOf(Red, Flame) else listOf(Color(0xFF34D399), Mint, Color(0xFFB8FF3D))), Offset(inner, inner), Size(fw, size.height - inner * 2), CornerRadius(r * 0.6f))
            else drawRoundRect(Color.White.copy(alpha = 0.6f), Offset(inner, inner), Size(fw, size.height - inner * 2), CornerRadius(r * 0.6f))
            // a light sweep travelling through the charge
            if (ch.plugged) {
                val sx = ((t * 0.7f) % 1f) * (fw + 30f) - 15f
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent), sx - 10f, sx + 10f), Offset(inner, inner), Size(fw, size.height - inner * 2))
            }
        }
        if (ch.plugged) Icon(Icons.Rounded.Bolt, null, tint = col, modifier = Modifier.padding(start = 6.dp).size(16.dp).graphicsLayer {
            val k = 1f + 0.12f * sin(t * 5f).coerceAtLeast(0f); scaleX = k; scaleY = k
        })
    }, {
        Text(if (ch.plugged) (if (ch.fast) "Fast charging" else "Charging") else "Unplugged", color = Dim, style = label().copy(fontSize = 11.sp), modifier = Modifier.rise(1))
        Spacer(Modifier.width(8.dp))
        Text("${(fill.value * 100).toInt()}%", color = col, style = label().copy(fontFamily = Display, fontSize = 14.sp), modifier = Modifier.rise(2))
    })
}

/** Ring / vibrate / silent: the bell swings (or buzzes) and gets its slash. */
@Composable
internal fun RingerView(rg: NotchBus.Ringer, h: Dp) {
    val t by rememberClock(rg.at)
    val silent = rg.mode == android.media.AudioManager.RINGER_MODE_SILENT
    val vibrate = rg.mode == android.media.AudioManager.RINGER_MODE_VIBRATE
    val col = if (silent) Red else if (vibrate) Ember else Mint
    Compact({
        Box(Modifier.size(h - 10.dp).clip(CircleShape).background(col.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(when { silent -> Icons.Rounded.NotificationsOff; vibrate -> Icons.Rounded.Vibration; else -> Icons.Rounded.NotificationsActive }, null, tint = col,
                modifier = Modifier.size(h * 0.42f).graphicsLayer {
                    val decay = (1f - t / 1.2f).coerceAtLeast(0f)
                    if (vibrate) translationX = sin(t * 60f) * 2.dp.toPx() * decay else rotationZ = sin(t * 14f) * 22f * decay
                    transformOrigin = TransformOrigin(0.5f, 0.1f)
                })
        }
    }, { Text(when { silent -> "Silent"; vibrate -> "Vibrate"; else -> "Ring" }, color = col, style = label().copy(fontSize = 13.sp), modifier = Modifier.rise(1).padding(end = 4.dp)) })
}

/** A task Nebula is working on: a gradient progress ring with a comet, then a check or cross. */
@Composable
internal fun TaskView(t: NebulaTask, h: Dp) {
    val spin = rememberInfiniteTransition(label = "task")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "a")
    val progress by animateFloatAsState(t.progress, tween(500), label = "progress")
    Compact({
        Box(Modifier.size(h - 12.dp), contentAlignment = Alignment.Center) {
            when (t.status) {
                TaskStatus.IN_PROGRESS -> {
                    GradientRing(progress, Modifier.fillMaxSize(), 2.4.dp)
                    Canvas(Modifier.fillMaxSize()) { drawArc(Color.White.copy(alpha = 0.6f), angle, 40f, false, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round)) }
                }
                TaskStatus.COMPLETED -> GradientRing(1f, Modifier.fillMaxSize(), 2.4.dp, listOf(Mint, Color(0xFF34D399)))
                TaskStatus.REQUIRES_INPUT -> GradientRing(1f, Modifier.fillMaxSize(), 2.4.dp, listOf(Ember, Flame))
                else -> GradientRing(1f, Modifier.fillMaxSize(), 2.4.dp, listOf(Red, Ruby))
            }
            when (t.status) {
                TaskStatus.COMPLETED -> Icon(Icons.Rounded.Check, null, tint = Mint, modifier = Modifier.size(12.dp))
                TaskStatus.REQUIRES_INPUT -> Icon(Icons.Rounded.PriorityHigh, null, tint = Ember, modifier = Modifier.size(12.dp))
                TaskStatus.FAILED, TaskStatus.CANCELLED -> Icon(Icons.Rounded.Close, null, tint = Red, modifier = Modifier.size(12.dp))
                else -> {}
            }
        }
    }, {
        Text(when (t.status) {
            TaskStatus.IN_PROGRESS -> "${(progress * 100).toInt()}%"
            TaskStatus.REQUIRES_INPUT -> "Needs you"
            TaskStatus.COMPLETED -> "Done"
            TaskStatus.FAILED -> "Failed"
            TaskStatus.CANCELLED -> "Stopped"
        }, color = when (t.status) { TaskStatus.COMPLETED -> Mint; TaskStatus.REQUIRES_INPUT -> Ember; TaskStatus.FAILED -> Red; else -> Color.White },
            style = label().copy(fontFamily = Display), modifier = Modifier.rise(1))
    })
}

/** Earbuds: a ring fills as they connect, the icon pops, and the battery ring counts up. */
@Composable
internal fun DeviceView(d: NotchBus.Device, h: Dp) {
    val ring = remember(d.at) { Animatable(if (d.connected) 0f else 1f) }
    LaunchedEffect(d.at) { ring.animateTo(if (d.connected) 1f else 0f, tween(900, easing = FastOutSlowInEasing)) }
    val pop by animateFloatAsState(if (ring.value > 0.98f && d.connected) 1f else 0.85f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "pop")
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(h + 2.dp), contentAlignment = Alignment.Center) {
            GradientRing(ring.value, Modifier.fillMaxSize(), 2.6.dp, if (d.connected) listOf(Mint, Color(0xFF34D399)) else listOf(Red, Ruby))
            Icon(if (d.kind == "buds") Icons.Rounded.Headphones else Icons.Rounded.Headset, null, tint = Color.White,
                modifier = Modifier.size(h * 0.5f).graphicsLayer { scaleX = pop; scaleY = pop })
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(if (d.connected) "Connected" else "Disconnected", color = if (d.connected) Mint else Dim, style = label().copy(fontSize = 11.sp), modifier = Modifier.rise(0))
            Text(d.name, color = Color.White, style = label().copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(1))
        }
        if (d.connected && d.battery != null) {
            val b = d.battery
            val shown by animateFloatAsState(b / 100f, tween(900, 300), label = "bat")
            Box(Modifier.size(34.dp).rise(2), contentAlignment = Alignment.Center) {
                GradientRing(shown, Modifier.fillMaxSize(), 3.dp, if (b <= 20) listOf(Red, Ruby) else listOf(Mint, Color(0xFF34D399)))
                Text("$b", color = Color.White, style = label().copy(fontFamily = Display, fontSize = 12.sp))
            }
        }
    }
}

// ============================================================================ Hey Nebula

@Composable
internal fun VoiceView(s: VoiceState.Session, c: NotchHost) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
            Text(phaseLabel(s), color = if (s.phase == VoiceState.Phase.ERROR) Red else Dim, style = label())
            if (c.notchPrefs.screenShare) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Visibility, "Screen shared", tint = Sky, modifier = Modifier.size(13.dp))
                Text(" Screen", color = Sky, style = label().copy(fontSize = 11.sp))
            }
            Spacer(Modifier.weight(1f))
            if (s.phase == VoiceState.Phase.LISTENING) LiveWave(s.level, Modifier.size(width = 54.dp, height = 18.dp), bars = 9)
            else if (s.phase == VoiceState.Phase.THINKING) ThinkingDots()
            else Orb(s.phase, s.level, 22.dp)
            Spacer(Modifier.width(6.dp))
            CloseDot { VoiceState.onStop?.invoke() }
        }
        val bigText = when {
            s.phase == VoiceState.Phase.LISTENING && s.transcript.isBlank() && s.followUp && s.reply.isNotBlank() -> s.reply
            s.phase == VoiceState.Phase.LISTENING && s.transcript.isBlank() -> if (s.followUp) "Anything else?" else "Go ahead, I'm listening"
            s.phase == VoiceState.Phase.LISTENING || s.phase == VoiceState.Phase.THINKING -> s.transcript
            else -> s.reply
        }
        val faded = s.phase == VoiceState.Phase.LISTENING && s.transcript.isBlank() && !(s.followUp && s.reply.isNotBlank())
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, end = 6.dp).verticalScroll(rememberScrollState()).rise(1)) {
            Text(bigText, color = if (faded) Faint else Color.White, style = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 25.sp))
        }
        if (s.phase == VoiceState.Phase.ERROR) Row(Modifier.padding(top = 8.dp)) {
            Text("Report to Claude", color = Color.White, style = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                modifier = Modifier.clip(CircleShape).background(Glass).clickable {
                    com.nebula.assistant.support.Reporter.open(ctx, "Voice error: ${s.reply}", "Heard: ${s.transcript}")
                    VoiceState.onStop?.invoke()
                }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        if (s.sources.isNotEmpty() && s.phase != VoiceState.Phase.LISTENING) {
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()).rise(2), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.sources.take(4).forEach { src ->
                    val host = runCatching { android.net.Uri.parse(src.url).host?.removePrefix("www.") }.getOrNull() ?: src.title
                    Text(host, color = Color.White, style = TextStyle(fontFamily = Body, fontSize = 11.sp), maxLines = 1,
                        modifier = Modifier.clip(CircleShape).background(Glass).clickable {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(src.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            VoiceState.onStop?.invoke()
                        }.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
        Row(Modifier.padding(top = 10.dp).rise(3), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (s.phase) {
                VoiceState.Phase.LISTENING -> ActionPill("I'm done", Modifier.weight(1f)) { VoiceState.onAgain?.invoke() }
                VoiceState.Phase.SPEAKING -> {
                    ActionPill("Open chat", Modifier.weight(1f)) { c.openApp("chat", null); VoiceState.onStop?.invoke() }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(NotchGradient).clickable { VoiceState.onStop?.invoke() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Stop, "Stop", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                else -> {
                    if (s.taskId != null) ActionPill("View task", Modifier.weight(1f)) { c.openApp("tasks", s.taskId); VoiceState.onStop?.invoke() }
                    ActionPill("Open chat", Modifier.weight(1f)) { c.openApp("chat", null); VoiceState.onStop?.invoke() }
                }
            }
        }
    }
}

/** In a game: a slim bar that never covers the game. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VoiceCompact(s: VoiceState.Session, h: Dp) {
    Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Orb(s.phase, s.level, h - 10.dp)
        Spacer(Modifier.width(10.dp))
        val line = when {
            s.reply.isNotBlank() && s.phase != VoiceState.Phase.LISTENING -> s.reply
            s.transcript.isNotBlank() -> s.transcript
            else -> phaseLabel(s)
        }
        Text(line, color = Color.White, style = label(), maxLines = 1, modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE))
        if (s.phase == VoiceState.Phase.LISTENING) LiveWave(s.level, Modifier.padding(horizontal = 6.dp).size(width = 34.dp, height = 16.dp), bars = 6)
        Icon(Icons.Rounded.Close, "Close", tint = Dim, modifier = Modifier.size(h).clip(CircleShape).clickable { VoiceState.onStop?.invoke() }.padding(9.dp))
    }
}

private fun phaseLabel(s: VoiceState.Session) = when (s.phase) {
    VoiceState.Phase.LISTENING -> if (s.followUp) "Listening for a follow-up" else "Listening"
    VoiceState.Phase.THINKING -> "Thinking"
    VoiceState.Phase.SPEAKING, VoiceState.Phase.DONE -> "Nebula"
    VoiceState.Phase.ERROR -> "Hmm"
    VoiceState.Phase.IDLE -> ""
}

// ============================================================================ calls & messages

/** A call: the caller's photo with a ripple while it rings, big name, and slide-free Decline / Accept. */
@Composable
internal fun CallView(call: NotchBus.Call) {
    val ctx = LocalContext.current
    val end = { call.decline?.let { runCatching { it.send() } } ?: runCatching { @Suppress("DEPRECATION", "MissingPermission") ctx.getSystemService(TelecomManager::class.java).endCall() }; Unit }
    val inf = rememberInfiniteTransition(label = "ring")
    val ripple by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "r")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val since = remember(call.key, call.ringing) { System.currentTimeMillis() }
    LaunchedEffect(call.ringing) { if (!call.ringing) while (true) { delay(1000); now = System.currentTimeMillis() } }
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                if (call.ringing) Canvas(Modifier.fillMaxSize()) {
                    for (k in 0..1) { val f = (ripple + k * 0.5f) % 1f; drawCircle(Mint.copy(alpha = (1f - f) * 0.5f), size.minDimension / 2 * (0.8f + 0.2f * f), style = Stroke(2.dp.toPx())) }
                }
                Avatar(call.icon, call.caller.take(1), 42.dp)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(if (call.ringing) "Incoming call" else "On call · ${clock(now - since)}", color = Dim, style = label())
                Text(call.caller, color = Color.White, style = big(22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.rise(1)) {
            ActionPill("Decline", Modifier.weight(1f), Red, onClick = end)
            if (call.ringing) ActionPill("Accept", Modifier.weight(1f), Mint) {
                call.answer?.let { runCatching { it.send() } } ?: runCatching { @Suppress("MissingPermission") ctx.getSystemService(TelecomManager::class.java).acceptRingingCall() }
            }
        }
    }
}

@Composable
internal fun MessageView(m: NotchBus.Message, dismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
            Avatar(m.icon, m.sender.take(1), 38.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("${m.app} · now", color = Dim, style = label().copy(fontSize = 11.sp))
                Text(m.sender, color = Color.White, style = label().copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CloseDot(dismiss)
        }
        Text(m.text, color = Color(0xE6FFFFFF), style = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 19.sp), maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).rise(1))
        Spacer(Modifier.weight(1f))
        if (m.open != null || m.replyAction != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.rise(2)) {
            ActionPill("Reply", Modifier.weight(1f)) { runCatching { (m.replyAction?.actionIntent ?: m.open)?.send() }; dismiss() }
            if (m.open != null) ActionPill("Open", Modifier.weight(1f)) { runCatching { m.open.send() }; dismiss() }
        }
    }
}

// ============================================================================ Live Activities

private fun liveColor(l: NotchBus.Live): Color = when {
    l.color != 0 && l.color != android.graphics.Color.BLACK -> Color(l.color).let { if (it.luminance() < 0.08f) Flame else it }
    l.kind == "ride" -> Color(0xFF34C3FF); l.kind == "food" -> Color(0xFFB45CFF); l.kind == "nav" -> Color(0xFF4CD964)
    l.kind == "timer" || l.kind == "stopwatch" -> Color(0xFFFF9F0A)
    else -> Flame
}

@Composable
private fun liveValue(l: NotchBus.Live): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(l.chrono) { if (l.chrono > 0) while (true) { delay(250); now = System.currentTimeMillis() } }
    return when {
        l.chrono > 0 -> { val ms = if (l.countDown) l.chrono - now else now - l.chrono; clock(ms.coerceAtLeast(0)) }
        l.eta.isNotEmpty() -> l.eta
        l.progress != null -> "${(l.progress * 100).toInt()}%"
        else -> ""
    }
}

@Composable
private fun LiveIcon(l: NotchBus.Live, size: Dp) {
    val bmp = remember(l.icon) { l.icon?.asImageBitmap() }
    if (bmp != null) Image(bmp, l.app, modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)))
    else Box(Modifier.size(size).clip(CircleShape).background(liveColor(l)))
}

@Composable
internal fun LiveCompact(l: NotchBus.Live, h: Dp) {
    val c = liveColor(l)
    val v = liveValue(l)
    Compact({ LiveIcon(l, h - 12.dp) }, {
        if (l.progress != null && l.chrono == 0L && l.eta.isEmpty()) {
            val p by animateFloatAsState(l.progress, tween(500), label = "lp")
            GradientRing(p, Modifier.size(h - 14.dp), 3.dp, listOf(c.light(0.3f), c))
        } else Text(v, color = c, maxLines = 1, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 17.sp), modifier = Modifier.rise(1))
    })
}

@Composable
internal fun LiveCard(l: NotchBus.Live) {
    val c = liveColor(l)
    val v = liveValue(l)
    val inf = rememberInfiniteTransition(label = "live")
    val sweep by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "s")
    val p by animateFloatAsState(l.progress ?: 0f, tween(600), label = "lp")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111114), Color(0xFF0A0A0C))))) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(c.copy(alpha = 0.22f), Color.Transparent), radius = 600f)))
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
                LiveIcon(l, 26.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.title.ifBlank { l.app }, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = label().copy(fontSize = 15.sp))
                    if (l.text.isNotBlank()) Text(l.text, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, style = label().copy(fontSize = 12.sp))
                }
                Spacer(Modifier.width(8.dp))
                Text(v, color = c, maxLines = 1, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 30.sp))
            }
            Spacer(Modifier.weight(1f))
            Canvas(Modifier.fillMaxWidth().height(22.dp).rise(1)) {
                val y = size.height / 2; val r = 4.dp.toPx(); val w = size.width
                drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(0f, y - r), Size(w, r * 2), CornerRadius(r))
                if (l.progress != null) {
                    drawRoundRect(Brush.horizontalGradient(listOf(c.copy(alpha = 0.6f), c)), Offset(0f, y - r), Size(w * p, r * 2), CornerRadius(r))
                    val x = (w * p).coerceIn(10.dp.toPx(), w - 10.dp.toPx())
                    drawCircle(c.copy(alpha = 0.35f), 11.dp.toPx(), Offset(x, y)); drawCircle(Color.White, 7.dp.toPx(), Offset(x, y)); drawCircle(c, 4.dp.toPx(), Offset(x, y))
                } else {
                    val x = sweep * (w + 200f) - 100f
                    drawRoundRect(Brush.horizontalGradient(listOf(Color.Transparent, c, Color.Transparent), x - 100f, x + 100f), Offset(0f, y - r), Size(w, r * 2), CornerRadius(r))
                }
                drawCircle(Color.White.copy(alpha = 0.85f), 5.dp.toPx(), Offset(w - 5.dp.toPx(), y), style = Stroke(2.dp.toPx()))
            }
            Text("Tap to open ${l.app}", color = Faint, style = label().copy(fontSize = 10.sp), modifier = Modifier.padding(top = 4.dp))
        }
    }
}
