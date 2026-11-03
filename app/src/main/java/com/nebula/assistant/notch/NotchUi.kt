package com.nebula.assistant.notch

import android.telecom.TelecomManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.assistant.data.NebulaTask
import com.nebula.assistant.data.Store
import com.nebula.assistant.data.TaskStatus
import com.nebula.assistant.game.R3cket
import com.nebula.assistant.voice.VoiceState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.math.sin

private val Ember = Color(0xFFF2A31B)
private val Flame = Color(0xFFE8531E)
private val Ruby = Color(0xFFC7301F)
private val Mint = Color(0xFF4ADE80)
private val Dim = Color(0x99FFFFFF)
private val NotchGradient = Brush.linearGradient(listOf(Ember, Flame, Ruby))

private enum class Mode { NONE, PREVIEW, UNLOCK, CALL, VOICE, VOLUME, DEVICE, R3CKET, MESSAGE, TASK, MUSIC, MUSIC_OPEN }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NotchUi(c: NotchController) {
    val prefs = c.notchPrefs
    val media by NotchBus.media.collectAsState()
    val call by NotchBus.call.collectAsState()
    val message by NotchBus.message.collectAsState()
    val preview by NotchBus.preview.collectAsState()
    val tasks by Store.tasks.collectAsState()
    val voice by VoiceState.session.collectAsState()
    val gaming by R3cket.inGame.collectAsState()
    val clicker by R3cket.clicker.collectAsState()
    val device by NotchBus.device.collectAsState()
    val L = c.layout
    val base = LocalDensity.current

    // Messages show for a few seconds, then tuck away.
    var shownMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message?.key, message?.at) {
        val m = message ?: return@LaunchedEffect
        shownMessage = m.key; delay(5500); if (shownMessage == m.key) shownMessage = null
    }
    // A finished task shows its result briefly.
    val active = tasks.firstOrNull { it.isActive }
    val recent = tasks.firstOrNull { !it.isActive && System.currentTimeMillis() - it.updatedAt < 4000 }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recent?.id, recent?.updatedAt) { if (recent != null) { delay(4100); tick++ } }
    var musicOpen by remember { mutableStateOf(false) }
    LaunchedEffect(musicOpen) { if (musicOpen) { delay(7000); musicOpen = false } }

    val quiet = gaming && prefs.r3cketQuiet
    val task = if (prefs.notchTasks) active ?: recent?.takeIf { System.currentTimeMillis() - it.updatedAt < 4000 + tick * 0 } else null
    val mode = when {
        preview -> Mode.PREVIEW
        voice.active -> Mode.VOICE // Hey Nebula always shows, even with the notch's extras switched off
        !prefs.notchEnabled -> Mode.NONE
        c.unlock != null -> Mode.UNLOCK
        call != null && prefs.notchCalls -> Mode.CALL
        c.volume != null -> Mode.VOLUME
        device != null -> Mode.DEVICE
        clicker.running || clicker.editing -> Mode.R3CKET
        !quiet && message != null && shownMessage == message?.key && prefs.notchMessages -> Mode.MESSAGE
        task != null && !quiet -> Mode.TASK
        media != null && media?.playing == true && prefs.notchMusic && !quiet -> if (musicOpen) Mode.MUSIC_OPEN else Mode.MUSIC
        else -> Mode.NONE
    }

    // Size: the camera-hole height × the user's height setting; everything else scales with Size.
    val pillDp = with(base) { (c.cutoutHeightPx().toDp() - 4.dp).coerceIn(30.dp, 44.dp) }.value * L.height
    CompositionLocalProvider(LocalDensity provides Density(base.density * L.scale, base.fontScale)) {
        val d = LocalDensity.current
        val pillH = pillDp.dp
        val bound: Pair<Dp, Dp> = when (mode) {
            Mode.VOICE -> if (voice.gaming) 360.dp to pillH + 34.dp else 384.dp to 460.dp
            Mode.CALL, Mode.MESSAGE, Mode.MUSIC_OPEN -> 384.dp to 270.dp
            Mode.R3CKET -> 364.dp to 150.dp
            Mode.NONE -> 1.dp to 1.dp
            else -> 300.dp to pillH + 30.dp
        }
        LaunchedEffect(mode, voice.gaming, L) { with(d) { c.reserve(bound.first.roundToPx(), bound.second.roundToPx()) } }
        var island by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(island, mode) {
            if (mode == Mode.NONE) c.fit(1, 1, 520) else c.fit(island.width + 4, island.height + 4)
        }

        val expanded = mode in setOf(Mode.CALL, Mode.MESSAGE, Mode.MUSIC_OPEN, Mode.R3CKET) || (mode == Mode.VOICE && !voice.gaming)
        val corner by animateFloatAsState(if (expanded) 30f else 100f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "corner")

        Box(Modifier.fillMaxSize().wrapContentSize(Alignment.TopCenter, unbounded = true)) {
            Box(Modifier.onSizeChanged { island = it }.padding(top = 5.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(if (expanded) corner.dp else pillH / 2))
                        .background(Color.Black)
                        .animateContentSize(spring(dampingRatio = 0.74f, stiffness = 420f))
                        .combinedClickable(
                            enabled = mode in setOf(Mode.MUSIC, Mode.TASK, Mode.VOLUME),
                            onLongClick = { c.talk() }, // hold the island to talk to Nebula
                        ) {
                            if (mode == Mode.MUSIC) musicOpen = true
                            if (mode == Mode.TASK) c.openApp("tasks", task?.id)
                        },
                ) {
                    AnimatedContent(
                        targetState = mode,
                        transitionSpec = { (fadeIn(tween(220, 80)) + scaleIn(initialScale = 0.92f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.96f)) },
                        label = "notch",
                    ) { m ->
                        when (m) {
                            Mode.PREVIEW -> PreviewView(pillH)
                            Mode.UNLOCK -> UnlockView(c.unlock == NotchController.UnlockPhase.SUCCESS, pillH)
                            Mode.CALL -> call?.let { CallView(it, c) }
                            Mode.VOICE -> if (voice.gaming) VoiceCompact(voice, pillH) else VoiceView(voice, c)
                            Mode.VOLUME -> c.volume?.let { VolumeView(it.first, it.second, pillH) }
                            Mode.DEVICE -> device?.let { DeviceView(it, pillH) }
                            Mode.R3CKET -> R3cketView(clicker, c)
                            Mode.MESSAGE -> message?.let { MessageView(it) { shownMessage = null } }
                            Mode.TASK -> task?.let { TaskView(it, pillH) }
                            Mode.MUSIC -> media?.let { MusicCompact(it, pillH) }
                            Mode.MUSIC_OPEN -> media?.let { MusicOpen(it) }
                            Mode.NONE -> Box(Modifier.size(0.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- size preview (Settings sliders)

@Composable
private fun PreviewView(h: Dp) {
    Row(Modifier.width(230.dp).height(h).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Orb(VoiceState.Phase.SPEAKING, 0.3f, h - 14.dp)
        Spacer(Modifier.width(10.dp))
        Text("Nebula Notch", color = Color.White, style = small().copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.weight(1f))
        Text("size", color = Dim, style = small())
    }
}

// ---------------------------------------------------------------------- Hey Nebula, inside the island

@Composable
private fun VoiceView(s: VoiceState.Session, c: NotchController) {
    val ctx = LocalContext.current
    Column(Modifier.width(364.dp).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Orb(s.phase, s.level, 38.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(phaseLabel(s.phase), color = Dim, style = small())
                val head = when {
                    s.transcript.isNotBlank() -> "“${s.transcript}”"
                    s.phase == VoiceState.Phase.LISTENING -> "Go ahead, I'm listening"
                    else -> "Nebula"
                }
                Text(head, color = Color.White, style = title(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.Close, "Close", tint = Dim, modifier = Modifier.size(34.dp).clip(CircleShape).clickable { VoiceState.onStop?.invoke() }.padding(7.dp))
        }
        when (s.phase) {
            VoiceState.Phase.LISTENING -> LiveWave(s.level, Modifier.padding(top = 14.dp).fillMaxWidth().height(34.dp))
            VoiceState.Phase.THINKING -> ThinkingBar(Modifier.padding(top = 16.dp).fillMaxWidth().height(4.dp))
            else -> {}
        }
        if (s.reply.isNotBlank() && s.phase != VoiceState.Phase.LISTENING) {
            Box(Modifier.padding(top = 12.dp).heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                Text(s.reply, color = Color(0xEEFFFFFF), style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp))
            }
        }
        if (s.sources.isNotEmpty() && s.phase != VoiceState.Phase.LISTENING) {
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.sources.take(4).forEach { src ->
                    val host = runCatching { android.net.Uri.parse(src.url).host?.removePrefix("www.") }.getOrNull() ?: src.title
                    Text(host, color = Color.White, style = TextStyle(fontSize = 11.sp), maxLines = 1,
                        modifier = Modifier.clip(CircleShape).background(Color(0x22FFFFFF)).clickable {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(src.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            VoiceState.onStop?.invoke()
                        }.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
        if (s.phase == VoiceState.Phase.SPEAKING || s.phase == VoiceState.Phase.DONE || s.phase == VoiceState.Phase.ERROR) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Icons.Rounded.Mic, "Ask again") { VoiceState.onAgain?.invoke() }
                if (s.taskId != null) Pill(null, "View task") { c.openApp("tasks", s.taskId); VoiceState.onStop?.invoke() }
                else Pill(null, "Open chat") { c.openApp("chat"); VoiceState.onStop?.invoke() }
            }
        } else if (s.phase == VoiceState.Phase.LISTENING) {
            Row(Modifier.padding(top = 10.dp)) { Pill(Icons.Rounded.Check, "Done talking") { VoiceState.onAgain?.invoke() } }
        }
    }
}

/** R3cket: a slim bar that never covers the game. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VoiceCompact(s: VoiceState.Session, h: Dp) {
    Row(Modifier.width(340.dp).height(h + 6.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Orb(s.phase, s.level, h - 8.dp)
        Spacer(Modifier.width(10.dp))
        val line = when {
            s.reply.isNotBlank() && s.phase != VoiceState.Phase.LISTENING -> s.reply
            s.transcript.isNotBlank() -> s.transcript
            else -> phaseLabel(s.phase)
        }
        Text(line, color = Color.White, style = small().copy(fontWeight = FontWeight.Medium), maxLines = 1,
            modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE))
        if (s.phase == VoiceState.Phase.LISTENING) LiveWave(s.level, Modifier.padding(horizontal = 6.dp).size(width = 34.dp, height = 18.dp), bars = 6)
        Icon(Icons.Rounded.Close, "Close", tint = Dim, modifier = Modifier.size(h).clip(CircleShape).clickable { VoiceState.onStop?.invoke() }.padding(8.dp))
    }
}

private fun phaseLabel(p: VoiceState.Phase) = when (p) {
    VoiceState.Phase.LISTENING -> "Listening…"
    VoiceState.Phase.THINKING -> "Thinking…"
    VoiceState.Phase.SPEAKING -> "Nebula"
    VoiceState.Phase.DONE -> "Nebula"
    VoiceState.Phase.ERROR -> "Hmm"
    VoiceState.Phase.IDLE -> ""
}

/** Nebula's living orb: breathes when listening (with your voice), spins while thinking. */
@Composable
private fun Orb(phase: VoiceState.Phase, level: Float, dim: Dp) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (phase == VoiceState.Phase.THINKING) 900 else 4200, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0.9f, 1.05f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "b")
    val lv by animateFloatAsState(level, spring(dampingRatio = 0.6f, stiffness = 600f), label = "lv")
    val colors = listOf(Color(0xFF7CF2C4), Color(0xFFFFD66B), Color(0xFFFF7AB6), Color(0xFF7AA8FF), Color(0xFF7CF2C4))
    Canvas(Modifier.size(dim)) {
        val r = dim.toPx() / 2 * (if (phase == VoiceState.Phase.LISTENING) 0.78f + lv * 0.22f else 0.84f * breathe)
        rotate(spin) {
            drawCircle(Brush.sweepGradient(colors), r)
        }
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), Color.Transparent), center, r * 0.9f), r * 0.9f)
        if (phase == VoiceState.Phase.ERROR) drawCircle(Color(0x88EF4444), r)
    }
}

/** Live voice bars, centre-weighted, following the microphone level. */
@Composable
private fun LiveWave(level: Float, modifier: Modifier, bars: Int = 28) {
    val hist = remember { FloatArray(bars) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(level) {
        System.arraycopy(hist, 1, hist, 0, bars - 1); hist[bars - 1] = level; frame++
    }
    val t = rememberInfiniteTransition(label = "wave")
    val ph by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ph")
    Canvas(modifier) {
        frame.let { }
        val gap = size.width / (bars * 2f)
        for (i in 0 until bars) {
            val centre = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / (bars / 2f) * 0.55f
            val idle = 0.10f + 0.06f * ((sin(ph + i * 0.5f) + 1) / 2)
            val a = (idle + hist[i] * 0.95f * centre).coerceIn(0.08f, 1f)
            val hh = size.height * a
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF9BE7FF), Color(0xFFFFB3D9))),
                Offset(i * gap * 2 + gap / 2, (size.height - hh) / 2), Size(gap, hh), androidx.compose.ui.geometry.CornerRadius(gap / 2))
        }
    }
}

@Composable
private fun ThinkingBar(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "think")
    val x by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x")
    Canvas(modifier.clip(CircleShape)) {
        drawRect(Color(0x22FFFFFF))
        val w = size.width
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF9BE7FF), Color(0xFFFFB3D9), Color.Transparent), w * (x - 0.3f), w * (x + 0.3f)))
    }
}

// ---------------------------------------------------------------------- R3cket auto-clicker controls

@Composable
private fun R3cketView(k: R3cket.Clicker, c: NotchController) {
    val ctx = c.context
    Column(Modifier.width(344.dp).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(NotchGradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.TouchApp, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("R3cket auto-click", color = Dim, style = small())
                Text(when {
                    k.editing -> "Tap the screen to add points"
                    k.running -> "${k.clicks} taps · ${k.points.size} point${if (k.points.size == 1) "" else "s"}"
                    else -> "Paused"
                }, color = Color.White, style = title(), maxLines = 1)
            }
            Icon(Icons.Rounded.Close, "Stop", tint = Dim, modifier = Modifier.size(34.dp).clip(CircleShape).clickable { R3cket.stopClicker(); R3cket.setEditing(false); R3cket.clearPoints() }.padding(7.dp))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (k.editing) Pill(Icons.Rounded.Check, "Done") { R3cket.setEditing(false); R3cket.startClicker(ctx, centerIfEmpty = false) }
            else Pill(if (k.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (k.running) "Pause" else "Start") {
                if (k.running) R3cket.stopClicker() else R3cket.startClicker(ctx)
            }
            if (!k.editing) Pill(null, "Points") { R3cket.setEditing(true) }
            Spacer(Modifier.weight(1f))
            IconTap(Icons.Rounded.Remove, 18.dp) { R3cket.setInterval(ctx, (k.intervalMs * 1.5f).toInt()) }
            Text("${k.intervalMs}ms", color = Dim, style = small())
            IconTap(Icons.Rounded.Add, 18.dp) { R3cket.setInterval(ctx, (k.intervalMs / 1.5f).toInt()) }
        }
    }
}

// ---------------------------------------------------------------------- unlock: scan ring → tick

@Composable
private fun UnlockView(success: Boolean, h: Dp) {
    val spin = rememberInfiniteTransition(label = "scan")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "angle")
    val done by animateFloatAsState(if (success) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 260f), label = "done")
    Row(Modifier.height(h + 14.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(h), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(h - 4.dp)) {
                val stroke = 2.6.dp.toPx()
                if (done < 0.99f) drawArc(NotchGradient, angle, 250f * (1 - done) + 110f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                if (done > 0f) drawArc(Mint, -90f, 360f * done, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Icon(if (success) Icons.Rounded.Check else Icons.Rounded.Face, null,
                tint = if (success) Mint else Color.White, modifier = Modifier.size(h * 0.5f))
        }
        Spacer(Modifier.width(40.dp))
        Text(if (success) "Unlocked" else "Looking for you", color = Dim, style = small())
    }
}

// ---------------------------------------------------------------------- incoming call

@Composable
private fun CallView(call: NotchBus.Call, c: NotchController) {
    val ctx = LocalContext.current
    Row(Modifier.width(360.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(call.icon, call.caller.take(1))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(if (call.ringing) "Incoming call" else "On call", color = Dim, style = small())
            Text(call.caller, color = Color.White, style = title(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (call.ringing) {
            RoundAction(Icons.Rounded.CallEnd, Color(0xFFEF4444)) {
                call.decline?.let { runCatching { it.send() } } ?: runCatching {
                    @Suppress("DEPRECATION", "MissingPermission") ctx.getSystemService(TelecomManager::class.java).endCall() }
            }
            Spacer(Modifier.width(10.dp))
            RoundAction(Icons.Rounded.Call, Mint) {
                call.answer?.let { runCatching { it.send() } } ?: runCatching {
                    @Suppress("MissingPermission") ctx.getSystemService(TelecomManager::class.java).acceptRingingCall() }
            }
        } else {
            RoundAction(Icons.Rounded.CallEnd, Color(0xFFEF4444)) {
                call.decline?.let { runCatching { it.send() } } ?: runCatching {
                    @Suppress("DEPRECATION", "MissingPermission") ctx.getSystemService(TelecomManager::class.java).endCall() }
            }
        }
    }
}

// ---------------------------------------------------------------------- volume

@Composable
private fun VolumeView(level: Float, muted: Boolean, h: Dp) {
    val shown by animateFloatAsState(level, spring(dampingRatio = 0.7f, stiffness = 500f), label = "vol")
    Row(Modifier.width(230.dp).height(h).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(NotchGradient))
        }
        Spacer(Modifier.width(10.dp))
        Text("${(level * 100).toInt()}", color = Dim, style = small())
    }
}

// ---------------------------------------------------------------------- earbuds connected

@Composable
private fun DeviceView(d: NotchBus.Device, h: Dp) {
    // A ring fills around the earbuds icon as they connect (empties when they disconnect).
    val ring = remember(d.at) { androidx.compose.animation.core.Animatable(if (d.connected) 0f else 1f) }
    LaunchedEffect(d.at) { ring.animateTo(if (d.connected) 1f else 0f, tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val pop by animateFloatAsState(if (ring.value > 0.98f && d.connected) 1f else 0.85f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "pop")
    Row(Modifier.width(300.dp).height(h + 18.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(h + 6.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(h + 6.dp)) {
                val s = 2.6.dp.toPx()
                drawArc(Color(0x33FFFFFF), 0f, 360f, false, style = Stroke(s))
                drawArc(if (d.connected) Mint else Color(0xFFEF4444), -90f, 360f * ring.value, false, style = Stroke(s, cap = StrokeCap.Round))
            }
            Icon(if (d.kind == "buds") Icons.Rounded.Headphones else Icons.Rounded.Headset, null, tint = Color.White,
                modifier = Modifier.size(h * 0.52f).graphicsLayer { scaleX = pop; scaleY = pop })
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(d.name, color = Color.White, style = small().copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (d.connected) "Connected" else "Disconnected", color = if (d.connected) Mint else Dim, style = TextStyle(fontSize = 11.sp))
        }
    }
}

// ---------------------------------------------------------------------- message

@Composable
private fun MessageView(m: NotchBus.Message, dismiss: () -> Unit) {
    Column(Modifier.width(360.dp).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(m.icon, m.sender.take(1))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("${m.app} · now", color = Dim, style = small())
                Text(m.sender, color = Color.White, style = title(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.Close, "Dismiss", tint = Dim, modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = dismiss).padding(6.dp))
        }
        Text(m.text, color = Color(0xE6FFFFFF), style = TextStyle(fontSize = 14.sp, lineHeight = 19.sp), maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
        if (m.open != null || m.replyAction != null) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Icons.Rounded.Reply, "Reply") { runCatching { (m.replyAction?.actionIntent ?: m.open)?.send() }; dismiss() }
                if (m.open != null) Pill(null, "Open") { runCatching { m.open.send() }; dismiss() }
            }
        }
    }
}

// ---------------------------------------------------------------------- Nebula task

@Composable
private fun TaskView(t: NebulaTask, h: Dp) {
    val spin = rememberInfiniteTransition(label = "task")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "a")
    val pulse by spin.animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    val progress by animateFloatAsState(t.progress, tween(500), label = "progress")
    Row(Modifier.width(250.dp).height(h).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(h - 10.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(h - 10.dp)) {
                val s = 2.4.dp.toPx()
                drawArc(Color(0x33FFFFFF), 0f, 360f, false, style = Stroke(s))
                when (t.status) {
                    TaskStatus.IN_PROGRESS -> { drawArc(NotchGradient, -90f, 360f * progress, false, style = Stroke(s, cap = StrokeCap.Round))
                        drawArc(Color.White.copy(alpha = 0.6f), angle, 40f, false, style = Stroke(s, cap = StrokeCap.Round)) }
                    TaskStatus.COMPLETED -> drawArc(Mint, 0f, 360f, false, style = Stroke(s))
                    TaskStatus.REQUIRES_INPUT -> drawArc(Ember.copy(alpha = pulse), 0f, 360f, false, style = Stroke(s))
                    else -> drawArc(Color(0xFFEF4444), 0f, 360f, false, style = Stroke(s))
                }
            }
            when (t.status) {
                TaskStatus.COMPLETED -> Icon(Icons.Rounded.Check, null, tint = Mint, modifier = Modifier.size(14.dp))
                TaskStatus.REQUIRES_INPUT -> Icon(Icons.Rounded.PriorityHigh, null, tint = Ember, modifier = Modifier.size(14.dp))
                TaskStatus.FAILED, TaskStatus.CANCELLED -> Icon(Icons.Rounded.Close, null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                else -> {}
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(when (t.status) {
                TaskStatus.IN_PROGRESS -> t.steps.lastOrNull()?.label ?: "Working…"
                TaskStatus.REQUIRES_INPUT -> "Needs you — tap"
                TaskStatus.COMPLETED -> "Done"
                TaskStatus.FAILED -> "Couldn't finish"
                TaskStatus.CANCELLED -> "Cancelled"
            }, color = Color.White, style = small().copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.title, color = Dim, style = TextStyle(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------- music

@Composable
private fun MusicCompact(m: NotchBus.Media, h: Dp) {
    Row(Modifier.width(210.dp).height(h).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(m, h - 12.dp, 8.dp)
        Spacer(Modifier.weight(1f))
        Equalizer(m.playing, Modifier.size(width = 22.dp, height = 14.dp))
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun MusicOpen(m: NotchBus.Media) {
    val tc = m.controller?.transportControls
    Column(Modifier.width(360.dp).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Art(m, 52.dp, 12.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(m.title, color = Color.White, style = title(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.artist, color = Dim, style = small(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Equalizer(m.playing, Modifier.size(width = 22.dp, height = 16.dp))
        }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }
        val pos = if (m.playing) m.position + (now - m.updatedAt) else m.position
        val frac = if (m.duration > 0) (pos.toFloat() / m.duration).coerceIn(0f, 1f) else 0f
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(Color.White))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconTap(Icons.Rounded.SkipPrevious, 30.dp) { tc?.skipToPrevious() }
            IconTap(if (m.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 40.dp) { if (m.playing) tc?.pause() else tc?.play() }
            IconTap(Icons.Rounded.SkipNext, 30.dp) { tc?.skipToNext() }
        }
    }
}

@Composable
private fun Equalizer(playing: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val phase by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "ph")
    Canvas(modifier) {
        val n = 4; val gap = size.width / (n * 2 - 1)
        for (i in 0 until n) {
            val amp = if (playing) 0.35f + 0.65f * ((sin(phase * (1 + i * 0.37f) + i * 1.7f) + 1) / 2) else 0.25f
            val hh = size.height * amp
            drawRoundRect(NotchGradient, Offset(i * gap * 2, (size.height - hh) / 2), Size(gap, hh),
                androidx.compose.ui.geometry.CornerRadius(gap / 2))
        }
    }
}

// ---------------------------------------------------------------------- bits

@Composable
private fun Art(m: NotchBus.Media, size: Dp, radius: Dp) {
    val bmp = m.art
    if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(RoundedCornerShape(radius)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(NotchGradient))
}

@Composable
private fun Avatar(bmp: android.graphics.Bitmap?, letter: String) {
    if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(42.dp).clip(CircleShape))
    else Box(Modifier.size(42.dp).clip(CircleShape).background(NotchGradient), contentAlignment = Alignment.Center) {
        Text(letter.uppercase(), color = Color.White, style = title())
    }
}

@Composable
private fun RoundAction(icon: ImageVector, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Pill(icon: ImageVector?, label: String, onClick: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(Color(0x26FFFFFF)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, color = Color.White, style = small().copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun IconTap(icon: ImageVector, size: Dp, onClick: () -> Unit) =
    Icon(icon, null, tint = Color.White, modifier = Modifier.size(size + 12.dp).clip(CircleShape).clickable(onClick = onClick).padding(6.dp))

private fun small() = TextStyle(fontSize = 12.sp, lineHeight = 15.sp)
private fun title() = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
