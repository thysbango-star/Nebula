package com.nebula.assistant.notch

import android.os.Build
import android.telecom.TelecomManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.border
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.assistant.R
import com.nebula.assistant.data.NebulaTask
import com.nebula.assistant.data.Store
import com.nebula.assistant.data.TaskStatus
import com.nebula.assistant.game.R3cket
import com.nebula.assistant.sports.Sports
import com.nebula.assistant.voice.VoiceState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================ look

private val Ember = Color(0xFFF2A31B)
private val Flame = Color(0xFFE8531E)
private val Ruby = Color(0xFFC7301F)
private val Mint = Color(0xFF4ADE80)
private val Lime = Color(0xFFB8FF3D)
private val Sky = Color(0xFF5AA9FF)
private val Violet = Color(0xFF9B7BFF)
private val Red = Color(0xFFEF4444)
private val Dim = Color(0x99FFFFFF)
private val Faint = Color(0x59FFFFFF)
private val Glass = Color(0x24FFFFFF)
private val NotchGradient = Brush.linearGradient(listOf(Ember, Flame, Ruby))
private val Display = FontFamily(Font(R.font.space_grotesk, FontWeight.Medium), Font(R.font.space_grotesk, FontWeight.SemiBold))
/** Scoreboard digits: the phone's own condensed face (Roboto Condensed), bold — like a stadium board. */
private val Condensed = FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)))
private val Body = FontFamily(Font(R.font.manrope, FontWeight.Medium), Font(R.font.manrope, FontWeight.SemiBold), Font(R.font.manrope, FontWeight.Bold))

private enum class Mode { NONE, PREVIEW, UNLOCK, CALL, VOICE, VOLUME, DEVICE, R3CKET, MESSAGE, TASK, MUSIC, MUSIC_OPEN, GAME, SPORTS, SPORTS_OPEN, GOAL, LIVE, LIVE_OPEN }

/** Exact size and corner of the island for a view. Fixed sizes = no measuring, no jitter. */
private data class Geo(val w: Dp, val h: Dp, val r: Dp)

/**
 * The Nebula Notch.
 *
 * One black shape morphs between views on a single spring (width, height and corners together),
 * the way Apple's Dynamic Island does: the old content fades out first, the shape grows or shrinks,
 * and the new content fades in from a soft blur once the shape is on its way. Every view has a fixed
 * size, so nothing is measured mid-animation and the overlay window only changes size twice per
 * transition (grow first, trim after) — that's what made the old notch glitch.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val game by R3cket.session.collectAsState()
    val panel by R3cket.panel.collectAsState()
    val device by NotchBus.device.collectAsState()
    val games by Sports.games.collectAsState()
    val lives by NotchBus.live.collectAsState()
    val goal by Sports.goal.collectAsState()
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
    var recentId by remember { mutableStateOf<String?>(null) }
    val newest = tasks.firstOrNull { !it.isActive && System.currentTimeMillis() - it.updatedAt < 4000 }
    LaunchedEffect(newest?.id, newest?.updatedAt) { if (newest != null) { recentId = newest.id; delay(4000); recentId = null } }
    val recent = tasks.firstOrNull { it.id == recentId }
    var musicOpen by remember { mutableStateOf(false) }
    LaunchedEffect(musicOpen) { if (musicOpen) { delay(7000); musicOpen = false } }
    // R3cket: show what the boost did, then settle into the HUD.
    var boostCard by remember { mutableStateOf(false) }
    LaunchedEffect(game?.startedAt) {
        if (game == null) { boostCard = false; return@LaunchedEffect }
        boostCard = true
        snapshotFlow { R3cket.session.value?.boosting }.collectLatest { b -> if (b == false) { delay(3200); boostCard = false } }
    }

    // Sports: tap the score to open the card (tap again for your next game); a goal plays its moment, then settles.
    var sportsOpen by remember { mutableStateOf(false) }
    var sportsIdx by remember { mutableStateOf(0) }
    LaunchedEffect(sportsOpen, sportsIdx) { if (sportsOpen) { delay(8000); sportsOpen = false } }
    val ctx = LocalContext.current
    LaunchedEffect(goal?.at) {
        val g = goal ?: return@LaunchedEffect
        if (!prefs.sportsGoals || g.label.isEmpty()) { Sports.clearGoal(); return@LaunchedEffect }
        momentBuzz(ctx, g.kind)
        delay(when {
            g.kind == "sub" -> 5200L + 900L * (g.subs.size - 1).coerceAtLeast(0)
            g.kind == "added" -> 4600L
            g.kind == "check" -> 5200L
            g.kind == "decision" -> 5600L
            g.kind == "lineup" -> lineupDuration(g)
            g.kind != "score" -> 5600L
            g.chant.isNotEmpty() -> 7600L
            else -> 6800L
        } + if (g.motif in FANCY) 1100L else 0L)
        if (Sports.goal.value?.at == g.at) Sports.clearGoal()
    }
    var liveOpen by remember { mutableStateOf(false) }
    LaunchedEffect(liveOpen) { if (liveOpen) { delay(8000); liveOpen = false } }
    val liveAct = if (prefs.notchLive) lives.firstOrNull() else null
    val liveGame = if (prefs.sportsOn) games.getOrNull(if (games.isEmpty()) 0 else sportsIdx % games.size) else null

    val quiet = gaming && prefs.r3cketQuiet
    val task = if (prefs.notchTasks) active ?: recent else null
    val mode = when {
        preview -> Mode.PREVIEW
        voice.active -> Mode.VOICE // Hey Nebula always shows, even with the notch's extras switched off
        !prefs.notchEnabled -> Mode.NONE
        c.unlock != null -> Mode.UNLOCK
        call != null && prefs.notchCalls && (c.phoneInCall || call?.ringing == true) -> Mode.CALL
        c.volume != null -> Mode.VOLUME
        device != null -> Mode.DEVICE
        clicker.editing || panel || (boostCard && game != null) -> Mode.R3CKET
        goal != null && goal?.label?.isNotEmpty() == true && prefs.sportsGoals && !quiet -> Mode.GOAL
        sportsOpen && liveGame != null -> Mode.SPORTS_OPEN
        !quiet && message != null && shownMessage == message?.key && prefs.notchMessages -> Mode.MESSAGE
        task != null && !quiet -> Mode.TASK
        liveOpen && liveAct != null -> Mode.LIVE_OPEN
        liveGame != null && !quiet -> Mode.SPORTS
        liveAct != null && !quiet -> Mode.LIVE
        media != null && media?.playing == true && prefs.notchMusic && !quiet -> if (musicOpen) Mode.MUSIC_OPEN else Mode.MUSIC
        gaming && game != null && prefs.r3cketHud -> Mode.GAME
        else -> Mode.NONE
    }

    // Size: the camera-hole height × the user's height setting; everything else scales with Size.
    val pillDp = with(base) { (c.cutoutHeightPx().toDp() - 4.dp).coerceIn(30.dp, 44.dp) }.value * L.height
    CompositionLocalProvider(LocalDensity provides Density(base.density * L.scale, base.fontScale)) {
        val d = LocalDensity.current
        val pillH = pillDp.dp
        val target = geoFor(mode, pillH, voice, clicker.editing, liveGame)

        // ---- one spring for the whole shape
        val w = remember { Animatable(target.w.value) }
        val h = remember { Animatable(target.h.value) }
        val r = remember { Animatable(target.r.value) }
        val shapeAlpha = remember { Animatable(if (mode == Mode.NONE) 0f else 1f) }
        val contentAlpha = remember { Animatable(1f) }
        var shown by remember { mutableStateOf(mode) }

        LaunchedEffect(target, mode) {
            val growing = target.w.value * target.h.value >= w.value * h.value
            // Make room first (never shrink mid-animation), then trim to the final size once settled.
            with(d) { c.reserve(maxOf(w.value, target.w.value).dp.roundToPx() + 4, (maxOf(h.value, target.h.value) + 6f).dp.roundToPx()) }
            coroutineScope {
                if (shown != mode) launch {
                    contentAlpha.animateTo(0f, tween(if (growing) 90 else 110))
                    shown = mode
                    delay(if (growing) 90 else 40)
                    contentAlpha.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
                }
                val sp = spring<Float>(dampingRatio = if (growing) 0.78f else 0.9f, stiffness = if (growing) 340f else 520f)
                launch { shapeAlpha.animateTo(if (mode == Mode.NONE) 0f else 1f, tween(if (mode == Mode.NONE) 260 else 120, if (mode == Mode.NONE) 120 else 0)) }
                launch { w.animateTo(target.w.value, sp) }
                launch { h.animateTo(target.h.value, sp) }
                launch { r.animateTo(target.r.value, sp) }
            }
            with(d) {
                if (mode == Mode.NONE) c.fit(1, 1, 60)
                else c.fit(target.w.roundToPx() + 4, (target.h + 6.dp).roundToPx(), 60)
            }
        }

        val accent by animateColorAsState(accentFor(shown, voice, task, call), tween(400), label = "accent")
        val expanded = target.r.value > pillH.value / 2 + 1
        val glowAlpha by animateFloatAsState(if (expanded) 1f else 0f, tween(450), label = "glow")

        Box(Modifier.fillMaxSize().wrapContentSize(Alignment.TopCenter, unbounded = true)) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(w.value.dp, h.value.dp)
                    .graphicsLayer { alpha = shapeAlpha.value }
                    .clip(RoundedCornerShape(r.value.coerceAtMost(h.value / 2).dp))
                    .background(Color.Black)
                    // Liquid glass, once it opens up: a bright top edge, a bevelled rim and a little depth at the bottom,
                    // drawn over the content so every card reads as one piece of glass.
                    .drawWithContent {
                        drawContent()
                        if (glowAlpha > 0.01f) {
                            val rr = r.value.coerceAtMost(h.value / 2).dp.toPx(); val a = glowAlpha
                            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f * a), 0.18f to Color.Transparent, 0.82f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.22f * a)))
                            val sw = 1.dp.toPx()
                            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f * a), Color.White.copy(alpha = 0.06f * a), Color.White.copy(alpha = 0.16f * a))),
                                Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(rr), style = Stroke(sw))
                        }
                    }
                    .combinedClickable(
                        enabled = shown in setOf(Mode.MUSIC, Mode.TASK, Mode.VOLUME, Mode.GAME, Mode.SPORTS, Mode.SPORTS_OPEN, Mode.GOAL, Mode.LIVE, Mode.LIVE_OPEN),
                        onLongClick = { c.talk() }, // hold the island to talk to Nebula
                    ) {
                        when (shown) {
                            Mode.MUSIC -> musicOpen = true
                            Mode.TASK -> c.openApp("tasks", task?.id)
                            Mode.GAME -> R3cket.panel.value = true
                            Mode.SPORTS -> sportsOpen = true
                            Mode.SPORTS_OPEN -> if (games.size > 1) sportsIdx++ else sportsOpen = false
                            Mode.GOAL -> Sports.clearGoal()
                            Mode.LIVE -> liveOpen = true
                            Mode.LIVE_OPEN -> { runCatching { liveAct?.open?.send() }; liveOpen = false }
                            else -> {}
                        }
                    },
            ) {
                // The card's own light rising from its bottom edge (like a Live Activity).
                if (glowAlpha > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = glowAlpha }.background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 0.82f to accent.copy(alpha = 0.16f), 1f to accent.copy(alpha = 0.55f))))
                // Content is laid out at its final size, so it never reflows while the shape morphs.
                val g = geoFor(shown, pillH, voice, clicker.editing, liveGame)
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .requiredSize(g.w, g.h)
                        .graphicsLayer {
                            alpha = contentAlpha.value
                            val s = 0.94f + 0.06f * contentAlpha.value
                            scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .then(if (Build.VERSION.SDK_INT >= 31 && contentAlpha.value < 0.99f) Modifier.blur(((1f - contentAlpha.value) * 10f).dp) else Modifier),
                ) {
                    when (shown) {
                        Mode.PREVIEW -> PreviewView()
                        Mode.UNLOCK -> UnlockView(c.unlock == NotchController.UnlockPhase.SUCCESS, pillH)
                        Mode.CALL -> call?.let { CallView(it) }
                        Mode.VOICE -> if (voice.gaming) VoiceCompact(voice, pillH) else VoiceView(voice, c)
                        Mode.VOLUME -> c.volume?.let { VolumeView(it.first, it.second, pillH) }
                        Mode.DEVICE -> device?.let { DeviceView(it, pillH) }
                        Mode.R3CKET -> R3cketPanel(c)
                        Mode.MESSAGE -> message?.let { MessageView(it) { shownMessage = null } }
                        Mode.TASK -> task?.let { TaskView(it, pillH) }
                        Mode.MUSIC -> media?.let { MusicCompact(it, pillH) }
                        Mode.MUSIC_OPEN -> media?.let { MusicOpen(it) }
                        Mode.GAME -> GameHud(pillH)
                        Mode.SPORTS -> liveGame?.let { SportsCompact(it, pillH) }
                        Mode.LIVE -> liveAct?.let { LiveCompact(it, pillH) }
                        Mode.LIVE_OPEN -> liveAct?.let { LiveCard(it) }
                        Mode.SPORTS_OPEN -> liveGame?.let { ScoreCard(it, more = games.size, index = sportsIdx % games.size.coerceAtLeast(1)) }
                        Mode.GOAL -> goal?.let { when (it.kind) { "yellow", "red", "red20" -> CardMoment(it); "sub" -> SubBoard(it); "added" -> AddedTime(it)
                            "check", "decision" -> Review(it); "lineup" -> LineupReel(it); else -> GoalView(it) } }
                        Mode.NONE -> {}
                    }
                }
            }
        }
    }
}

private fun geoFor(mode: Mode, pillH: Dp, v: VoiceState.Session, editing: Boolean, sport: Sports.Game? = null): Geo {
    val card = 30.dp
    val full = 372.dp
    return when (mode) {
        Mode.NONE -> Geo(pillH * 2.6f, pillH, pillH / 2)
        Mode.PREVIEW -> Geo(232.dp, pillH, pillH / 2)
        Mode.UNLOCK -> Geo(196.dp, pillH, pillH / 2)
        Mode.VOLUME -> Geo(236.dp, pillH, pillH / 2)
        Mode.TASK -> Geo(268.dp, pillH, pillH / 2)
        Mode.MUSIC -> Geo(232.dp, pillH, pillH / 2)
        Mode.GAME -> Geo(276.dp, pillH, pillH / 2)
        Mode.SPORTS -> Geo(252.dp, pillH, pillH / 2)
        Mode.LIVE -> Geo(236.dp, pillH, pillH / 2)
        Mode.LIVE_OPEN -> Geo(full, 150.dp, card)
        Mode.SPORTS_OPEN -> Geo(full, (if (sport?.lastPlay.isNullOrBlank()) 152.dp else 190.dp) + (if (sport?.note.isNullOrBlank()) 0.dp else 18.dp) +
            (if (sport?.cards.isNullOrEmpty()) 0.dp else 26.dp) + (if (sport?.stats.isNullOrEmpty()) 0.dp else (24 + 17 * sport!!.stats.size.coerceAtMost(4)).dp), card)
        Mode.GOAL -> Geo(full, 190.dp, card)
        Mode.DEVICE -> Geo(312.dp, pillH + 18.dp, (pillH + 18.dp) / 2)
        Mode.CALL -> Geo(full, 158.dp, card)
        Mode.MESSAGE -> Geo(full, 172.dp, card)
        Mode.MUSIC_OPEN -> Geo(full, 196.dp, card)
        Mode.R3CKET -> Geo(full, if (editing) 150.dp else 214.dp, card)
        Mode.VOICE -> if (v.gaming) Geo(340.dp, pillH + 6.dp, (pillH + 6.dp) / 2) else {
            val text = if ((v.phase == VoiceState.Phase.LISTENING && !(v.followUp && v.transcript.isBlank())) || v.phase == VoiceState.Phase.THINKING) v.transcript else v.reply
            val lines = ceil(text.length / 34f).toInt().coerceIn(1, 9)
            val extra = if (v.sources.isNotEmpty() && v.phase != VoiceState.Phase.LISTENING) 36 else 0
            Geo(full, (112 + lines * 25 + extra).coerceIn(150, 420).dp, card)
        }
    }
}

private fun accentFor(mode: Mode, v: VoiceState.Session, t: NebulaTask?, call: NotchBus.Call?): Color = when (mode) {
    Mode.VOICE -> when (v.phase) { VoiceState.Phase.ERROR -> Red; VoiceState.Phase.LISTENING -> Sky; else -> Flame }
    Mode.CALL -> if (call?.ringing == true) Mint else Mint
    Mode.MESSAGE -> Sky
    Mode.MUSIC_OPEN -> Color.Transparent // the song's own colours
    Mode.R3CKET, Mode.GAME -> Lime
    Mode.SPORTS_OPEN, Mode.GOAL -> Color.Transparent // the score card brings its own team colours
    Mode.TASK -> when (t?.status) { TaskStatus.COMPLETED -> Mint; TaskStatus.FAILED -> Red; else -> Ember }
    else -> Flame
}

// ============================================================================ compact views
// Compact views keep the middle clear for the camera: something on the left, something on the right.

@Composable
private fun Compact(left: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        left(); Spacer(Modifier.weight(1f)); right()
    }
}

@Composable
private fun PreviewView() = Compact({ Orb(VoiceState.Phase.SPEAKING, 0.3f, 22.dp); Spacer(Modifier.width(8.dp)); Text("Nebula", color = Color.White, style = label()) },
    { Text("size", color = Dim, style = label()) })

@Composable
private fun UnlockView(success: Boolean, h: Dp) {
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
            Icon(if (success) Icons.Rounded.Check else Icons.Rounded.Face, null, tint = if (success) Mint else Color.White, modifier = Modifier.size(h * 0.45f))
        }
    }, { Text(if (success) "Unlocked" else "Hi", color = if (success) Mint else Dim, style = label()) })
}

@Composable
private fun VolumeView(level: Float, muted: Boolean, h: Dp) {
    val shown by animateFloatAsState(level, spring(dampingRatio = 0.7f, stiffness = 500f), label = "vol")
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, null, tint = Color.White, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Color.White))
        }
        Spacer(Modifier.width(10.dp))
        Text("${(level * 100).toInt()}", color = Dim, style = label(), modifier = Modifier.width(24.dp))
    }
}

@Composable
private fun TaskView(t: NebulaTask, h: Dp) {
    val spin = rememberInfiniteTransition(label = "task")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "a")
    val progress by animateFloatAsState(t.progress, tween(500), label = "progress")
    Compact({
        Box(Modifier.size(h - 12.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = 2.4.dp.toPx()
                drawArc(Color(0x33FFFFFF), 0f, 360f, false, style = Stroke(s))
                when (t.status) {
                    TaskStatus.IN_PROGRESS -> { drawArc(NotchGradient, -90f, 360f * progress, false, style = Stroke(s, cap = StrokeCap.Round))
                        drawArc(Color.White.copy(alpha = 0.6f), angle, 40f, false, style = Stroke(s, cap = StrokeCap.Round)) }
                    TaskStatus.COMPLETED -> drawArc(Mint, 0f, 360f, false, style = Stroke(s))
                    TaskStatus.REQUIRES_INPUT -> drawArc(Ember, 0f, 360f, false, style = Stroke(s))
                    else -> drawArc(Red, 0f, 360f, false, style = Stroke(s))
                }
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
            style = label().copy(fontFamily = Display))
    })
}

@Composable
private fun MusicCompact(m: NotchBus.Media, h: Dp) = Box(Modifier.fillMaxSize()) {
    val th = MusicTheme.of(m.art)
    val c by animateColorAsState(th.blobs.first(), tween(900), label = "mc")
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.copy(alpha = 0.45f), 0.35f to Color.Transparent, 0.65f to Color.Transparent, 1f to c.copy(alpha = 0.45f))))
    Compact({ Art(m, h - 12.dp, 7.dp) }, { Equalizer(m.playing, Modifier.size(width = 20.dp, height = 13.dp)) })
}

/** R3cket HUD: game time on the left, ping + temperature on the right. Tap for the panel. */
@Composable
private fun GameHud(h: Dp) {
    val ctx = LocalContext.current
    val s by R3cket.session.collectAsState()
    val ping by R3cket.ping.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var temp by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); temp = R3cket.battery(ctx).second; delay(1000) } }
    Compact({
        Icon(Icons.Rounded.Bolt, null, tint = Lime, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(clock(now - (s?.startedAt ?: now)), color = Color.White, style = label().copy(fontFamily = Display))
    }, {
        Text(ping?.let { "${it}ms" } ?: "—", color = pingColor(ping), style = label().copy(fontFamily = Display))
        Spacer(Modifier.width(8.dp))
        Text("${temp.toInt()}°", color = if (temp >= 42f) Red else Dim, style = label().copy(fontFamily = Display))
    })
}

private fun pingColor(p: Int?) = when { p == null -> Dim; p < 60 -> Mint; p < 120 -> Ember; else -> Red }
private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

// ============================================================================ Sports
// Apple Sports-style: team colours meet in the middle of a dark card, big scores, the clock between them.

/** A team's badge: the official logo from ESPN, or (while it loads, or if there isn't one) its colour and short name. */
@Composable
private fun Badge(s: Sports.Side, size: Dp, ring: Boolean = false) {
    val ctx = LocalContext.current
    val logo by androidx.compose.runtime.produceState(com.nebula.assistant.sports.Logos.cached(s.logo), s.logo) {
        if (value == null && s.logo.isNotBlank()) value = com.nebula.assistant.sports.Logos.get(ctx, s.logo)
    }
    val bmp = logo
    if (bmp != null) {
        Box(Modifier.size(size).then(if (ring) Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.92f)) else Modifier), contentAlignment = Alignment.Center) {
            Image(bmp.asImageBitmap(), s.name, contentScale = ContentScale.Fit, modifier = Modifier.size(if (ring) size * 0.72f else size))
        }
        return
    }
    val c = Color(s.color)
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(c, c.copy(red = c.red * 0.6f, green = c.green * 0.6f, blue = c.blue * 0.6f)))),
        contentAlignment = Alignment.Center) {
        Text(s.abbr.take(4), color = Color.White, maxLines = 1,
            style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = (size.value * (if (s.abbr.length > 3) 0.24f else 0.3f)).sp, letterSpacing = 0.2.sp))
    }
}

/** Score that rolls up when it changes (old number slides out the top, new one rises in). Text, so "161/5" works too. */
@Composable
private fun ScoreNum(value: String, size: Int, color: Color = Color.White, from: String? = null, rollAfterMs: Long = 0, key: Any? = null) {
    var shownValue by remember(key) { mutableStateOf(from ?: value) }
    LaunchedEffect(value, key) { if (shownValue != value) { delay(rollAfterMs); shownValue = value } }
    AnimatedContent(shownValue, transitionSpec = {
        (slideInVertically(spring(dampingRatio = 0.62f, stiffness = 300f)) { it } + fadeIn(tween(200))) togetherWith
            (slideOutVertically(tween(260)) { -it } + fadeOut(tween(200)))
    }, label = "score") { n ->
        Text(n, color = color, maxLines = 1,
            style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size * 1.05f).sp))
    }
}

@Composable
private fun SportsCompact(g: Sports.Game, h: Dp) = Box(Modifier.fillMaxSize()) {
    // each half carries its team's colour, meeting in black around the camera
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(g.home.color).copy(alpha = 0.55f), 0.38f to Color.Transparent,
        0.62f to Color.Transparent, 1f to Color(g.away.color).copy(alpha = 0.55f))))
    SportsCompactRow(g, h)
}

@Composable
private fun SportsCompactRow(g: Sports.Game, h: Dp) {
    val cricket = g.league.kind == Sports.Kind.CRICKET
    Compact({
        Badge(g.home, h - 12.dp); Spacer(Modifier.width(7.dp)); ScoreNum(g.home.text, if (cricket) 15 else 17)
    }, {
        val timed = g.cards.lastOrNull { it.until > System.currentTimeMillis() }
        if (timed != null) Row(Modifier.padding(end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniCard(timed.kind); Spacer(Modifier.width(4.dp)); Countdown(timed.until, Dim, 10)
        } else if (!cricket) Text(if (g.live) g.clock.take(6) else "", color = Dim, style = label().copy(fontSize = 10.sp), modifier = Modifier.padding(end = 7.dp))
        ScoreNum(g.away.text, if (cricket) 15 else 17); Spacer(Modifier.width(7.dp)); Badge(g.away, h - 12.dp)
    })
}

/** The open card. During a moment it starts from [roll]'s old score and rolls the scorer's number up. */
@Composable
private fun ScoreCard(g: Sports.Game, roll: Sports.Score? = null, rollAfterMs: Long = 0, more: Int = 0, index: Int = 0) {
    val hc = Color(g.home.color); val ac = Color(g.away.color)
    val done = g.state == "post"
    val cricket = g.league.kind == Sports.Kind.CRICKET
    val big = if (cricket) 30 else 40
    Box(Modifier.fillMaxSize()) {
        LiveGradient(hc, ac, Modifier.fillMaxSize())
        // Content starts below the camera hole; the league name sits in the middle column above the clock.
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 30.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TeamCol(g.home)
                Spacer(Modifier.width(if (cricket) 8.dp else 12.dp))
                ScoreNum(g.home.text, big, if (done && g.home.score < g.away.score && !cricket) Dim else Color.White,
                    from = roll?.takeIf { it.home }?.before, rollAfterMs = rollAfterMs, key = roll?.at)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (g.leagueLogo.isNotBlank()) LogoImage(g.leagueLogo, 18.dp, Modifier.padding(bottom = 2.dp))
                    Text(g.league.name, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, style = label().copy(fontSize = 10.sp),
                        modifier = Modifier.padding(bottom = 3.dp))
                    Text(g.clock, color = Color.White, maxLines = 1, style = label().copy(fontFamily = Display, fontSize = if (cricket) 14.sp else 16.sp))
                    if (g.state == "pre") Text(if (g.league.kind == Sports.Kind.SOCCER || g.league.kind == Sports.Kind.RUGBY) "Kick-off" else "Start", color = Faint, style = label().copy(fontSize = 10.sp))
                    else if (g.live) LiveDot()
                }
                ScoreNum(g.away.text, big, if (done && g.away.score < g.home.score && !cricket) Dim else Color.White,
                    from = roll?.takeIf { !it.home }?.before, rollAfterMs = rollAfterMs, key = roll?.at)
                Spacer(Modifier.width(if (cricket) 8.dp else 12.dp))
                TeamCol(g.away)
            }
            if (g.cards.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                g.cards.forEach { cd -> CardChip(cd, if (cd.home) g.home else g.away) }
            }
            if (g.note.isNotBlank()) Text(g.note.uppercase(), color = if (g.note.endsWith("missed")) Dim else Ember, maxLines = 1,
                style = label().copy(fontSize = 10.sp, letterSpacing = 1.2.sp), modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
            if (g.lastPlay.isNotBlank()) Row(Modifier.padding(top = 10.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.5.dp).fillMaxHeight().clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
                Spacer(Modifier.width(9.dp))
                Text((when (g.league.kind) { Sports.Kind.SOCCER -> "⚽  "; Sports.Kind.RUGBY -> "🏉  "; Sports.Kind.CRICKET -> "🏏  "; else -> "" }) + g.lastPlay,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp))
            }
            if (g.stats.isNotEmpty()) StatsBlock(g)
            if (more > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                repeat(more.coerceAtMost(6)) { i -> Box(Modifier.padding(horizontal = 2.5.dp).size(5.dp).clip(CircleShape).background(if (i == index) Color.White else Faint)) }
            }
        }
    }
}

@Composable
private fun TeamCol(s: Sports.Side) = Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
    Badge(s, 40.dp)
    Text(s.name, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, style = label().copy(fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
    if (s.sub.isNotBlank()) Text(s.sub, color = Faint, maxLines = 1, style = label().copy(fontSize = 9.sp))
}

@Composable
private fun LiveDot() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Red))
        Spacer(Modifier.width(4.dp))
        Text("LIVE", color = Faint, style = label().copy(fontSize = 9.sp, letterSpacing = 1.sp))
    }
}

/**
 * The score moment, built like a Premier League broadcast graphic (~7 s), for every sport:
 *  1. a neon glitch flicker over the board,
 *  2. the scorer's colour wipes across from their side with a jagged edge, carrying halftone dots and speed streaks,
 *  3. their badge slams in with a shockwave ring,
 *  4. a supporters' word flies through first when the team has one (BOKKE, COYS…),
 *  5. the big word (GOAL / TRY / SIX / WICKET…) rises letter by letter with echo outlines, scorer underneath,
 *  6. the colour wipes off the far side, the card is back and the score rolls up.
 */
@Composable
private fun GoalView(s: Sports.Score) {
    val side = if (s.home) s.game.home else s.game.away
    val tc = Color(side.color)
    val light = androidx.compose.ui.graphics.lerp(tc, Color.White, 0.4f)
    val deep = androidx.compose.ui.graphics.lerp(tc, Color.Black, 0.5f)
    val glitch = remember(s.at) { Animatable(0f) }
    val wipe = remember(s.at) { Animatable(0f) }
    val badge = remember(s.at) { Animatable(0f) }
    val ring = remember(s.at) { Animatable(0f) }
    val chant = remember(s.at) { Animatable(0f) }
    val letters = remember(s.at) { List(s.label.length) { Animatable(0f) } }
    val sub = remember(s.at) { Animatable(0f) }
    val out = remember(s.at) { Animatable(0f) }
    val hasChant = s.chant.isNotEmpty()
    val wordAt = if (hasChant) 1500L else 640L
    val hold = if (s.motif in FANCY) 3200L else 2100L // club moments get longer on screen
    LaunchedEffect(s.at) {
        launch { glitch.animateTo(1f, tween(260, easing = LinearEasing)) }
        launch { delay(120); wipe.animateTo(1f, tween(460, easing = FastOutSlowInEasing)) }
        launch { delay(320); badge.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f)) }
        launch { delay(360); ring.animateTo(1f, tween(760, easing = FastOutSlowInEasing)) }
        if (hasChant) launch { delay(560); chant.animateTo(1f, tween(300, easing = FastOutSlowInEasing)); delay(560); chant.animateTo(2f, tween(260)) }
        letters.forEachIndexed { i, a -> launch { delay(wordAt + i * 55L); a.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 320f)) } }
        launch { delay(wordAt + 320); sub.animateTo(1f, tween(360)) }
        delay(wordAt + hold)
        out.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    val inf = rememberInfiniteTransition(label = "pan")
    val pan by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pan")
    // The club's own ESPN logo drives its motif: tiled through the wipe and trailing as it slams in.
    val ctx = LocalContext.current
    val logoBmp by androidx.compose.runtime.produceState(com.nebula.assistant.sports.Logos.cached(side.logo), side.logo) {
        if (value == null && side.logo.isNotBlank()) value = com.nebula.assistant.sports.Logos.get(ctx, side.logo)
    }
    val logoImg = remember(logoBmp) { logoBmp?.asImageBitmap() }
    val t by androidx.compose.runtime.produceState(0f, s.at) { val st = withFrameNanos { it }; while (true) withFrameNanos { value = (it - st) / 1e9f } }
    val motif = if (s.motif == "logopeek" && s.let { (if (it.home) it.game.home else it.game.away).logo.isBlank() }) "burst" else s.motif
    val u1 = with(LocalDensity.current) { 1.dp.toPx() }
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val wallWord = remember(s.label, light) {
        tm.measure(s.label, TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 2.sp,
            drawStyle = Stroke(1.2f * u1)))
    }
    val shake = if (motif == "cannons") cannonShake(t, u1) else Offset.Zero
    val dir = if (s.home) 1f else -1f // the colour travels away from the scorer's side

    Box(Modifier.fillMaxSize().graphicsLayer { translationX = shake.x; translationY = shake.y }) {
        // The card underneath; the score rolls once the colour has gone.
        ScoreCard(s.game, roll = s, rollAfterMs = wordAt + hold + 400)

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val zig = 16.dp.toPx(); val teeth = 7
            val span = w + zig * 2
            val lead = wipe.value * span - zig        // leading edge, measured from the scorer's side
            val tail = out.value * span - zig         // trailing edge (the colour leaves the far side)
            if (lead > tail) {
                fun x(v: Float) = if (dir > 0) v else w - v
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x(tail), 0f)
                    for (i in 0..teeth) lineTo(x(tail + if (i % 2 == 1) zig else 0f), h * i / teeth)
                    for (i in teeth downTo 0) lineTo(x(lead + if (i % 2 == 1) zig else 0f), h * i / teeth)
                    close()
                }
                clipPath(path) {
                    drawRect(Brush.linearGradient(listOf(deep, tc, light), Offset(x(0f), h), Offset(x(w), 0f)))
                    // The crest motif: the club's logo tiled at an angle, sliding with the wipe.
                    if (motif == "logos" || motif.isEmpty()) logoImg?.let { img ->
                        val ts = 40.dp.toPx(); val gap = 68.dp.toPx(); val drift = (pan * gap) * dir
                        var row = 0; var yy = -gap * 0.4f
                        while (yy < h + gap) {
                            var xx = -gap + drift + if (row % 2 == 0) 0f else gap / 2
                            while (xx < w + gap) {
                                drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                                    dstOffset = androidx.compose.ui.unit.IntOffset((xx - ts / 2).toInt(), (yy - ts / 2).toInt()),
                                    dstSize = androidx.compose.ui.unit.IntSize(ts.toInt(), ts.toInt()), alpha = 0.16f)
                                xx += gap
                            }
                            yy += gap * 0.62f; row++
                        }
                    }
                    // Halftone dots, bigger toward the leading edge, drifting.
                    val step = 13.dp.toPx(); val off = pan * step
                    val dotA = if (motif == "cinematic") 0.1f else 0.28f
                    var yy = -step
                    var row = 0
                    while (yy < h + step) {
                        var xx = -step + off + if (row % 2 == 0) 0f else step / 2
                        while (xx < w + step) {
                            val k = ((if (dir > 0) xx else w - xx) / w).coerceIn(0f, 1f)
                            drawCircle(light.copy(alpha = dotA), (0.8f + 3.2f * k).dp.toPx(), Offset(xx, yy))
                            xx += step
                        }
                        yy += step * 0.86f; row++
                    }
                    // Speed streaks.
                    for (i in 0 until 4) {
                        val sy = h * (0.18f + i * 0.21f)
                        val sx = ((pan + i * 0.27f) % 1f) * (w + 160f) - 80f
                        val px = x(sx)
                        drawLine(Color.White.copy(alpha = 0.18f), Offset(px - 70f * dir, sy), Offset(px + 70f * dir, sy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
            // The club's own moment, over the colour and under the words.
            if (lead > tail || t < 4.5f) when (motif) {
                "cannons" -> drawCannons(t, dir)
                "gulls", "magpies", "eagles" -> drawBirds(t, dir, birdLook(motif), if (motif == "eagles") 6 else 10)
                "cinematic" -> drawCinematic(t, logoImg, s.home, tc)
                "wordwall" -> if (lead > tail) drawWordWall(wallWord, t, light)
                "logopeek" -> logoImg?.let { drawLogoPeek(t, it, s.home) }
                "burst" -> drawBurst(t, Offset(if (s.home) 58.dp.toPx() else size.width - 58.dp.toPx(), 26.dp.toPx() + (size.height - 26.dp.toPx()) / 2), tc, light)
            }
            // Neon glitch flicker at the start.
            if (glitch.value < 1f) {
                val f = (glitch.value * 9).toInt()
                val rnd = java.util.Random(f * 7919L + s.at)
                repeat(7) {
                    val y = rnd.nextFloat() * h; val sh = (4 + rnd.nextInt(14)).dp.toPx()
                    val dx = (rnd.nextFloat() - 0.5f) * 60.dp.toPx()
                    drawRect(if (rnd.nextBoolean()) Color(0xFF38FFE1) else Color(0xFFB8FF3D), Offset(dx, y), Size(w, sh), alpha = 0.55f * (1f - glitch.value))
                }
            }
            // Motion trail behind the logo as it slams in.
            logoImg?.let { img ->
                val b = badge.value.coerceIn(0f, 1f)
                if (b < 0.98f) {
                    val bs = (56 * 0.72f).dp.toPx(); val cx = if (s.home) 58.dp.toPx() else w - 58.dp.toPx(); val cy = 26.dp.toPx() + (h - 26.dp.toPx()) / 2
                    for (i in 1..3) {
                        val x = cx - dir * i * 30.dp.toPx() * (1f - b)
                        drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset((x - bs / 2).toInt(), (cy - bs / 2).toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(bs.toInt(), bs.toInt()), alpha = 0.32f * (1f - b) / i)
                    }
                }
            }
            // Shockwave around the badge.
            if (ring.value in 0.01f..0.99f) {
                val c = Offset(w * (if (s.home) 0.2f else 0.8f), h * 0.55f)
                drawCircle(Color.White.copy(alpha = 0.7f * (1f - ring.value)), (26 + 110 * ring.value).dp.toPx(), c, style = Stroke((3f * (1f - ring.value) + 0.5f).dp.toPx()))
            }
        }

        // Badge + words, riding the colour (they leave with it).
        val leave = out.value
        Box(Modifier.fillMaxSize().padding(top = 26.dp).graphicsLayer { alpha = 1f - leave; translationX = dir * leave * 80.dp.toPx() }) {
            Box(Modifier.align(if (s.home) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 30.dp).graphicsLayer {
                val b = badge.value; alpha = if (motif == "logopeek") 0f else b.coerceIn(0f, 1f); val k = 1f + (1f - b) * 1.4f; scaleX = k; scaleY = k; rotationZ = (1f - b) * -12f * dir
            }) { Badge(side, 56.dp, ring = true) }
            Column(Modifier.align(Alignment.Center).padding(start = if (s.home) 70.dp else 0.dp, end = if (s.home) 0.dp else 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(side.name.uppercase(), color = Color.White.copy(alpha = 0.9f * sub.value), maxLines = 1, style = label().copy(fontSize = 10.sp, letterSpacing = 2.4.sp))
                Box(contentAlignment = Alignment.Center) {
                    // Supporters' word flies through first.
                    if (hasChant && chant.value in 0.001f..1.999f) {
                        val c = chant.value
                        val x = if (c < 1f) (1f - c) * -1f else (c - 1f)
                        Text(s.chant, color = Color.White, maxLines = 1, modifier = Modifier.graphicsLayer {
                            translationX = x * dir * 220.dp.toPx(); alpha = 1f - kotlin.math.abs(x) * 0.8f; scaleX = 1f + kotlin.math.abs(x) * 0.25f
                        }, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            fontSize = (if (s.chant.length > 8) 34 else 48).sp, letterSpacing = 3.sp))
                    }
                    Row {
                        s.label.forEachIndexed { i, ch ->
                            val v = letters.getOrNull(i)?.value ?: 1f
                            val fs = (when { s.label.length > 9 -> 30; s.label.length > 6 -> 38; else -> 54 }).sp
                            Box(Modifier.clip(androidx.compose.ui.graphics.RectangleShape)) {
                                // echo outline trailing behind each letter as it lands
                                Text(ch.toString(), color = light.copy(alpha = 0.6f * (1f - v).coerceIn(0f, 1f)), modifier = Modifier.graphicsLayer {
                                    translationY = (1f - v) * 40.dp.toPx() + 6.dp.toPx() * (1f - v)
                                }, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = fs, letterSpacing = 1.sp))
                                Text(ch.toString(), color = Color.White, modifier = Modifier.graphicsLayer {
                                    alpha = v.coerceIn(0f, 1f); translationY = (1f - v) * 30.dp.toPx(); val k = 0.6f + 0.4f * v; scaleX = k; scaleY = k
                                }, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = fs, letterSpacing = 1.sp))
                            }
                        }
                    }
                }
                if (s.who.isNotBlank()) Text(s.who, color = Color.White.copy(alpha = sub.value), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = label().copy(fontSize = 12.sp), modifier = Modifier.padding(horizontal = 10.dp).graphicsLayer { translationY = (1f - sub.value) * 8.dp.toPx() })
            }
        }
    }
}

// ---- cards and substitutions

private val FANCY = setOf("cannons", "gulls", "magpies", "eagles", "cinematic", "wordwall", "logopeek", "burst")
private val CardYellow = Color(0xFFFFD60A)
private val CardRed = Color(0xFFE5172F)

/** A ticking mm:ss to [until]. */
@Composable
private fun Countdown(until: Long, color: Color, size: Int) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) { while (now < until) { delay(500); now = System.currentTimeMillis() } }
    val left = ((until - now) / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(left / 60, left % 60), color = color, maxLines = 1, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = size.sp))
}

@Composable
private fun MiniCard(kind: String, h: Dp = 11.dp) =
    Box(Modifier.size(h * 0.72f, h).graphicsLayer { rotationZ = -8f }.clip(RoundedCornerShape(2.dp)).background(if (kind == "yellow") CardYellow else CardRed))

@Composable
private fun CardChip(c: Sports.Card, side: Sports.Side) {
    Row(Modifier.padding(horizontal = 3.dp).clip(CircleShape).background(Color(0x33000000)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MiniCard(c.kind)
        Spacer(Modifier.width(6.dp))
        Text("${c.player.substringAfterLast(' ')} · ${side.abbr}", color = Color.White, maxLines = 1, style = label().copy(fontSize = 11.sp))
        if (c.until > System.currentTimeMillis()) { Spacer(Modifier.width(6.dp)); Countdown(c.until, if (c.kind == "yellow") CardYellow else CardRed, 12) }
        else if (c.kind == "red") Text("  off", color = Dim, style = label().copy(fontSize = 10.sp))
    }
}

/**
 * Card moment: the card spins in like the referee just pulled it from the pocket, with a sheen, the player's name
 * and — in rugby — the clock they're off for: 10:00 in the sin bin, 20:00 until a replacement for a 20-minute red,
 * or off for the match.
 */
@Composable
private fun CardMoment(s: Sports.Score) {
    val side = if (s.home) s.game.home else s.game.away
    val tc = Color(side.color)
    val yellow = s.kind == "yellow"
    val cc = if (yellow) CardYellow else CardRed
    val flip = remember(s.at) { Animatable(0f) }
    val words = remember(s.at) { Animatable(0f) }
    val shine = remember(s.at) { Animatable(0f) }
    LaunchedEffect(s.at) {
        launch { delay(120); flip.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 220f)) }
        launch { delay(380); words.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
        delay(760); shine.animateTo(1f, tween(650))
    }
    val inf = rememberInfiniteTransition(label = "stripes")
    val pan by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pan")
    val rugby = s.game.league.kind == Sports.Kind.RUGBY
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF0D0D10))
            val o = Offset(if (s.home) 0f else size.width, size.height * 0.6f)
            drawCircle(Brush.radialGradient(listOf(tc.copy(alpha = 0.7f), Color.Transparent), o, size.width * 0.7f), size.width * 0.7f, o)
            val gap = 26.dp.toPx(); val sw = 9.dp.toPx(); var x = -size.height + pan * gap
            while (x < size.width + size.height) {
                drawLine(cc.copy(alpha = 0.09f), Offset(x, size.height), Offset(x + size.height, 0f), sw)
                x += gap
            }
        }
        Row(Modifier.fillMaxSize().padding(start = 26.dp, end = 22.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp, 72.dp).graphicsLayer {
                val f = flip.value
                rotationY = (1f - f) * -110f; rotationZ = -8f + (1f - f) * -24f; cameraDistance = 14f * density
                val k = 0.6f + 0.4f * f; scaleX = k; scaleY = k; alpha = f.coerceIn(0f, 1f)
            }.clip(RoundedCornerShape(7.dp)).background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(cc, Color.White, 0.25f), cc, androidx.compose.ui.graphics.lerp(cc, Color.Black, 0.2f))))) {
                Canvas(Modifier.fillMaxSize()) {
                    val sx = shine.value * (size.width + size.height * 2) - size.height
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.6f), Color.Transparent), Offset(sx - 30f, 0f), Offset(sx + 30f, size.height)))
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).graphicsLayer { alpha = words.value; translationX = (1f - words.value) * 24.dp.toPx() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(side, 18.dp); Spacer(Modifier.width(6.dp))
                    Text(listOf(side.name.uppercase(), s.minute).filter { it.isNotBlank() }.joinToString("  ·  "), color = Dim, maxLines = 1, style = label().copy(fontSize = 10.sp, letterSpacing = 1.6.sp))
                }
                Text(s.label, color = cc, maxLines = 1, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = 1.sp))
                if (s.who.isNotBlank()) Text(s.who, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = label().copy(fontSize = 15.sp))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val tag = when {
                        rugby && yellow -> "SIN BIN"
                        rugby && s.kind == "red20" -> "REPLACEMENT IN"
                        rugby -> "OFF FOR THE MATCH"
                        !yellow -> "SENT OFF"
                        else -> ""
                    }
                    if (tag.isNotEmpty()) Text(tag, color = Faint, style = label().copy(fontSize = 10.sp, letterSpacing = 1.4.sp))
                    if (s.until > 0) { Spacer(Modifier.width(8.dp)); Countdown(s.until, cc, 18) }
                }
            }
        }
    }
}

/** Substitution board: a dot-matrix LED board rises up; the numbers flicker on — green arrow on, red arrow off. */
@Composable
private fun SubBoard(s: Sports.Score) {
    val side = if (s.home) s.game.home else s.game.away
    val rise = remember(s.at) { Animatable(0f) }
    val rows = remember(s.at) { List(s.subs.size.coerceAtLeast(1)) { Animatable(0f) } }
    val t by androidx.compose.runtime.produceState(0f, s.at) { val st = withFrameNanos { it }; while (true) withFrameNanos { value = (it - st) / 1e9f } }
    LaunchedEffect(s.at) {
        launch { delay(80); rise.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 240f)) }
        rows.forEachIndexed { i, a -> launch { delay(520L + i * 700L); a.animateTo(1f, tween(260)) } }
    }
    val green = Color(0xFF3DFF7A); val red = Color(0xFFFF4D4D); val amber = Color(0xFFFFB020)
    fun led(c: Color) = androidx.compose.ui.graphics.Shadow(c.copy(alpha = 0.8f), Offset.Zero, 14f)
    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D10))) {
        Box(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 30.dp, bottom = 12.dp).graphicsLayer {
            translationY = (1f - rise.value) * size.height; alpha = rise.value.coerceIn(0f, 1f)
        }.clip(RoundedCornerShape(16.dp)).background(Color(0xFF050608))) {
            Canvas(Modifier.fillMaxSize()) { // the LED grid
                val st = 4.dp.toPx(); var y = st / 2
                while (y < size.height) { var x = st / 2; while (x < size.width) { drawCircle(Color.White.copy(alpha = 0.045f), 0.7.dp.toPx(), Offset(x, y)); x += st }; y += st }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(side, 22.dp); Spacer(Modifier.width(8.dp))
                    Text(s.label, color = amber, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 3.sp, shadow = led(amber)))
                    Spacer(Modifier.weight(1f))
                    Text(s.minute, color = amber, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 15.sp, shadow = led(amber)))
                }
                s.subs.forEachIndexed { i, (on, off) ->
                    val v = rows.getOrNull(i)?.value ?: 1f
                    val flick = if (v < 1f) (if (sin(t * 90f + i) > 0f) 1f else 0.25f) * v else 1f
                    Column(Modifier.padding(top = 3.dp).graphicsLayer { alpha = flick }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("▲", color = green, style = TextStyle(fontSize = 13.sp, shadow = led(green)))
                            Spacer(Modifier.width(8.dp))
                            Text(on.uppercase(), color = green, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 19.sp, letterSpacing = 1.sp, shadow = led(green)))
                        }
                        if (off.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("▼", color = red, style = TextStyle(fontSize = 13.sp, shadow = led(red)))
                            Spacer(Modifier.width(8.dp))
                            Text(off.uppercase(), color = red, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 19.sp, letterSpacing = 1.sp, shadow = led(red)))
                        }
                    }
                }
            }
        }
    }
}

// ---- Live Activities (rides, deliveries, directions, timers, downloads — from any app's ongoing notification)

private fun liveColor(l: NotchBus.Live): Color = when {
    l.color != 0 && l.color != android.graphics.Color.BLACK -> Color(l.color).let { if (it.luminance() < 0.08f) Flame else it }
    l.kind == "ride" -> Color(0xFF34C3FF); l.kind == "food" -> Color(0xFFB45CFF); l.kind == "nav" -> Color(0xFF4CD964)
    l.kind == "timer" || l.kind == "stopwatch" -> Color(0xFFFF9F0A)
    else -> Flame
}

/** The live number: a timer counting down, a stopwatch counting up, an ETA, or a percentage. */
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
private fun LiveCompact(l: NotchBus.Live, h: Dp) {
    val c = liveColor(l)
    val v = liveValue(l)
    Compact({ LiveIcon(l, h - 12.dp) }, {
        if (l.progress != null && l.chrono == 0L && l.eta.isEmpty()) {
            val p by animateFloatAsState(l.progress, tween(500), label = "lp")
            Canvas(Modifier.size(h - 14.dp)) {
                drawCircle(c.copy(alpha = 0.25f), style = Stroke(3.dp.toPx()))
                drawArc(c, -90f, 360f * p, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        } else Text(v, color = c, maxLines = 1, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 17.sp))
    })
}

/**
 * The open Live Activity, like the ride / delivery cards: app icon and what's happening, a track with a marker
 * riding along it (or a shimmer when there's no progress), and the big number on the right. Tap to open the app.
 */
@Composable
private fun LiveCard(l: NotchBus.Live) {
    val c = liveColor(l)
    val v = liveValue(l)
    val inf = rememberInfiniteTransition(label = "live")
    val sweep by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "s")
    val p by animateFloatAsState(l.progress ?: 0f, tween(600), label = "lp")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111114), Color(0xFF0A0A0C))))) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(c.copy(alpha = 0.22f), Color.Transparent), radius = 600f)))
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveIcon(l, 26.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.title.ifBlank { l.app }, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = label().copy(fontSize = 15.sp))
                    if (l.text.isNotBlank()) Text(l.text, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, style = label().copy(fontSize = 12.sp))
                }
                Spacer(Modifier.width(8.dp))
                Text(v, color = c, maxLines = 1, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 30.sp))
            }
            Spacer(Modifier.weight(1f))
            // the track
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val y = size.height / 2; val r = 4.dp.toPx(); val w = size.width
                drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(0f, y - r), Size(w, r * 2), CornerRadius(r))
                if (l.progress != null) {
                    drawRoundRect(Brush.horizontalGradient(listOf(c.copy(alpha = 0.6f), c)), Offset(0f, y - r), Size(w * p, r * 2), CornerRadius(r))
                    val x = (w * p).coerceIn(10.dp.toPx(), w - 10.dp.toPx())
                    drawCircle(c.copy(alpha = 0.35f), 11.dp.toPx(), Offset(x, y)); drawCircle(Color.White, 7.dp.toPx(), Offset(x, y)); drawCircle(c, 4.dp.toPx(), Offset(x, y))
                } else { // no progress from the app: a travelling shimmer
                    val x = sweep * (w + 200f) - 100f
                    drawRoundRect(Brush.horizontalGradient(listOf(Color.Transparent, c, Color.Transparent), x - 100f, x + 100f), Offset(0f, y - r), Size(w, r * 2), CornerRadius(r))
                }
                // destination marker at the end
                drawCircle(Color.White.copy(alpha = 0.85f), 5.dp.toPx(), Offset(w - 5.dp.toPx(), y), style = Stroke(2.dp.toPx()))
            }
            Text("Tap to open ${l.app}", color = Faint, style = label().copy(fontSize = 10.sp), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ---- competition logos, stats, added time, VAR/TMO, lineups

@Composable
private fun LogoImage(url: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by androidx.compose.runtime.produceState(com.nebula.assistant.sports.Logos.cached(url), url) {
        if (value == null && url.isNotBlank()) value = com.nebula.assistant.sports.Logos.get(ctx, url)
    }
    bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = modifier.size(size)) }
}

/** The match in numbers — possession, shots, on target, corners — with bars in each team's colour. Stats by ESPN. */
@Composable
private fun StatsBlock(g: Sports.Game) = Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
    g.stats.take(4).forEach { st ->
        Row(Modifier.fillMaxWidth().height(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(st.home, color = Color.White, style = label().copy(fontSize = 11.sp), modifier = Modifier.width(34.dp))
            val tot = (st.h + st.a).takeIf { it > 0f } ?: 1f
            Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight((st.h / tot).coerceAtLeast(0.001f)).fillMaxHeight().background(Color(g.home.color)))
                    Spacer(Modifier.width(2.dp))
                    Box(Modifier.weight((st.a / tot).coerceAtLeast(0.001f)).fillMaxHeight().background(Color(g.away.color)))
                }
            }
            Text(st.label, color = Dim, maxLines = 1, style = label().copy(fontSize = 10.sp), modifier = Modifier.padding(horizontal = 8.dp).width(64.dp))
            Box(Modifier.weight(1f))
            Text(st.away, color = Color.White, style = label().copy(fontSize = 11.sp), modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
    Text("STATS BY ESPN", color = Color.White, style = label().copy(fontSize = 8.sp, letterSpacing = 1.6.sp),
        modifier = Modifier.padding(top = 3.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(3.dp)).background(Color(0xFFCC1A16)).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** "+4 minutes of added time": a sliding orange-violet gradient, the competition logo, the number counting up. */
@Composable
private fun AddedTime(s: Sports.Score) {
    val n = s.extra.toIntOrNull() ?: 0
    val count = remember(s.at) { Animatable(0f) }
    val inn = remember(s.at) { Animatable(0f) }
    LaunchedEffect(s.at) { launch { inn.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 260f)) }; delay(250); count.animateTo(n.toFloat(), tween(700, easing = FastOutSlowInEasing)) }
    val inf = rememberInfiniteTransition(label = "added")
    val slide by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "s")
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val x = slide * w
            drawRect(Brush.horizontalGradient(listOf(Color(0xFFFF8F4F), Color(0xFF8B5CFF), Color(0xFFFF8F4F), Color(0xFF8B5CFF), Color(0xFFFF8F4F)), -w + x, w + x,
                androidx.compose.ui.graphics.TileMode.Repeated))
            drawRect(Brush.verticalGradient(listOf(Color(0x33000000), Color.Transparent, Color(0x40000000))))
        }
        Row(Modifier.fillMaxSize().padding(start = 26.dp, end = 22.dp, top = 26.dp).graphicsLayer { val k = 0.85f + 0.15f * inn.value; scaleX = k; scaleY = k; alpha = inn.value.coerceIn(0f, 1f) },
            verticalAlignment = Alignment.CenterVertically) {
            if (s.game.leagueLogo.isNotBlank()) { LogoImage(s.game.leagueLogo, 46.dp); Spacer(Modifier.width(14.dp)) }
            Text("+${count.value.toInt()}", color = Color.White, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 64.sp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("MINUTES OF", color = Color.White, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp))
                Text("ADDED TIME", color = Color.White, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp))
            }
        }
    }
}

/**
 * VAR (football) / TMO (rugby). Checking: the official's tag with a scanning light and what they're looking at in
 * silver ("Possible offside"). Decision: the call ("GOAL STANDS" / "NO TRY") with a tick or cross, and which team
 * it went for.
 */
@Composable
private fun Review(s: Sports.Score) {
    val tmo = s.extra == "TMO" || s.label == "TMO"
    val decision = s.kind == "decision"
    val accent = if (tmo) Color(0xFFFF7A1A) else Color.White
    val inn = remember(s.at) { Animatable(0f) }
    val mark = remember(s.at) { Animatable(0f) }
    LaunchedEffect(s.at) { launch { inn.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }; if (decision) { delay(700); mark.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) } }
    val inf = rememberInfiniteTransition(label = "scan")
    val scan by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "scan")
    val favoured = when (s.favour) { 1 -> s.game.home; 0 -> s.game.away; else -> null }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (tmo) drawRect(Brush.linearGradient(listOf(Color(0xFF2457E0), Color(0xFF1B1C66), Color(0xFF2457E0)), Offset.Zero, Offset(size.width, size.height)))
            else drawRect(Color(0xFF2E0B3A))
            if (!decision) { // scanning light
                val x = scan * (size.width + 200f) - 100f
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent), x - 80f, x + 80f))
            }
            favoured?.let { f -> drawCircle(Brush.radialGradient(listOf(Color(f.color).copy(alpha = 0.55f), Color.Transparent), Offset(if (s.favour == 1) 0f else size.width, size.height), size.width * 0.6f), size.width * 0.6f, Offset(if (s.favour == 1) 0f else size.width, size.height)) }
        }
        Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 20.dp, top = 26.dp).graphicsLayer { alpha = inn.value; translationY = (1f - inn.value) * 10.dp.toPx() },
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (tmo) "TMO" else "VAR", color = accent, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 44.sp, letterSpacing = 1.sp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(if (decision) "CHECK COMPLETE" else "CHECKING" + ".".repeat(1 + (scan * 3).toInt() % 3), color = Faint, style = label().copy(fontSize = 10.sp, letterSpacing = 1.6.sp))
                val silver = Brush.verticalGradient(listOf(Color.White, Color(0xFFB9B9C2)))
                Text(s.who, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = if (s.who.length > 16) 24.sp else 30.sp,
                    brush = if (tmo) Brush.verticalGradient(listOf(accent, accent)) else silver))
                if (decision && favoured != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Badge(favoured, 18.dp); Spacer(Modifier.width(6.dp))
                    Text("In favour of ${favoured.name}", color = Color.White, style = label().copy(fontSize = 12.sp))
                }
            }
            if (decision) Box(Modifier.size(40.dp).graphicsLayer { val k = mark.value; scaleX = k; scaleY = k }.clip(CircleShape).background(if (s.good) Mint else Red), contentAlignment = Alignment.Center) {
                Icon(if (s.good) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

private fun lineupDuration(s: Sports.Score): Long {
    val st = s.players.count { it.starter }; val b = s.players.size - st
    return 1400L + st * 800L + (if (b > 0) 600L + b * 450L else 0L)
}

/** Lineup reel: the team's colours, its logo peeking in from the corner, and each player — "No.6 SIYA KOLISI" — then the bench. */
@Composable
private fun LineupReel(s: Sports.Score) {
    val side = if (s.home) s.game.home else s.game.away
    val tc = Color(side.color)
    val starters = s.players.filter { it.starter }; val bench = s.players.filter { !it.starter }
    var idx by remember(s.at) { mutableStateOf(-1) }
    LaunchedEffect(s.at) {
        delay(700)
        for (i in s.players.indices) { idx = i; delay(if (s.players[i].starter) 800 else 450); if (i == starters.size - 1 && bench.isNotEmpty()) delay(150) }
    }
    val logoIn = remember(s.at) { Animatable(0f) }
    LaunchedEffect(s.at) { logoIn.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 200f)) }
    val inf = rememberInfiniteTransition(label = "lineup")
    val drift by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "d")
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val l = androidx.compose.ui.graphics.lerp(tc, Color.White, 0.35f)
            drawRect(Brush.horizontalGradient(listOf(l, tc, l), -size.width * drift, size.width * (2 - drift), androidx.compose.ui.graphics.TileMode.Mirror))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33000000))))
        }
        if (side.logo.isNotBlank()) LogoImage(side.logo, 150.dp, Modifier.align(Alignment.BottomStart).graphicsLayer {
            translationX = (-60 - 90 * (1f - logoIn.value)).dp.toPx(); translationY = 46.dp.toPx(); rotationZ = -8f; alpha = logoIn.value.coerceIn(0f, 1f)
        }) else Box(Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) { Badge(side, 64.dp) }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 26.dp, top = 22.dp).width(210.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val p = s.players.getOrNull(idx)
            val onBench = p != null && !p.starter
            Text(listOf(if (onBench) "BENCH" else s.label, s.extra).filter { it.isNotBlank() }.joinToString("  ·  "), color = Color.White.copy(alpha = 0.85f),
                style = label().copy(fontSize = 10.sp, letterSpacing = 2.sp))
            AnimatedContent(idx, transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.7f, stiffness = 380f)) { it / 2 } + fadeIn(tween(160))) togetherWith (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(140)))
            }, label = "player") { i ->
                val pl = s.players.getOrNull(i)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (pl == null) side.name.uppercase() else "No.${pl.no}", color = Color.White, maxLines = 1,
                        style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = if (pl == null) 30.sp else 52.sp, lineHeight = 54.sp))
                    if (pl != null) Text(pl.name.uppercase(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 18.sp))
                }
            }
            if (p != null) Text(if (onBench) "${idx - starters.size + 1} of ${bench.size}" else "${idx + 1} of ${starters.size}", color = Color.White.copy(alpha = 0.7f), style = label().copy(fontSize = 10.sp))
        }
    }
}

/** The moment's buzz: three quick pulses and a cheer for a score; a sharp double tap for a card; a soft tick for a sub. */
private fun momentBuzz(ctx: android.content.Context, kind: String) {
    if (kind != "score") {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                else @Suppress("DEPRECATION") ctx.getSystemService(android.os.Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) v?.vibrate(if (kind == "sub") android.os.VibrationEffect.createOneShot(30, 120)
                else android.os.VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), intArrayOf(0, 255, 0, 255), -1))
        }
        com.nebula.assistant.audio.Sounds.play(ctx, if (kind in setOf("sub", "lineup", "added", "check")) com.nebula.assistant.audio.NebulaSound.SOFT else com.nebula.assistant.audio.NebulaSound.NOTIFICATION)
        return
    }
    goalBuzz(ctx)
}

/** Goal buzz: three quick pulses, plus the goal sound. */
private fun goalBuzz(ctx: android.content.Context) {
    runCatching {
        val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(android.os.Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= 26) v?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 40, 70, 40, 70, 120), intArrayOf(0, 200, 0, 200, 0, 255), -1))
    }
    com.nebula.assistant.audio.Sounds.play(ctx, com.nebula.assistant.audio.NebulaSound.GOAL)
}

// ============================================================================ Hey Nebula

@Composable
private fun VoiceView(s: VoiceState.Session, c: NotchController) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        val big = when {
            s.phase == VoiceState.Phase.LISTENING && s.transcript.isBlank() && s.followUp && s.reply.isNotBlank() -> s.reply
            s.phase == VoiceState.Phase.LISTENING && s.transcript.isBlank() -> if (s.followUp) "Anything else?" else "Go ahead, I'm listening"
            s.phase == VoiceState.Phase.LISTENING || s.phase == VoiceState.Phase.THINKING -> s.transcript
            else -> s.reply
        }
        val faded = s.phase == VoiceState.Phase.LISTENING && s.transcript.isBlank() && !(s.followUp && s.reply.isNotBlank())
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, end = 6.dp).verticalScroll(rememberScrollState())) {
            Text(big, color = if (faded) Faint else Color.White, style = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 25.sp))
        }
        if (s.sources.isNotEmpty() && s.phase != VoiceState.Phase.LISTENING) {
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        // No "speak again" button: after Nebula answers it simply keeps listening for a follow-up.
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (s.phase) {
                VoiceState.Phase.LISTENING -> ActionPill("I'm done", Modifier.weight(1f)) { VoiceState.onAgain?.invoke() }
                VoiceState.Phase.SPEAKING -> { ActionPill("Open chat", Modifier.weight(1f)) { c.openApp("chat"); VoiceState.onStop?.invoke() }
                    StopButton { VoiceState.onStop?.invoke() } }
                else -> {
                    if (s.taskId != null) ActionPill("View task", Modifier.weight(1f)) { c.openApp("tasks", s.taskId); VoiceState.onStop?.invoke() }
                    ActionPill("Open chat", Modifier.weight(1f)) { c.openApp("chat"); VoiceState.onStop?.invoke() }
                }
            }
        }
    }
}

/** R3cket: a slim bar that never covers the game. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceCompact(s: VoiceState.Session, h: Dp) {
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

// ============================================================================ R3cket panel

@Composable
private fun R3cketPanel(c: NotchController) {
    val ctx = c.context
    val s by R3cket.session.collectAsState()
    val k by R3cket.clicker.collectAsState()
    val ping by R3cket.ping.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var batt by remember { mutableStateOf(R3cket.battery(ctx)) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); batt = R3cket.battery(ctx); delay(1000) } }
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bolt, null, tint = Lime, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (s?.boosting == true) "R3cket · boosting…" else "R3cket · ${s?.game ?: "Game mode"}", color = Lime, style = label(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            CloseDot { R3cket.panel.value = false; if (k.editing) R3cket.setEditing(false) }
        }
        if (k.editing) {
            Text("Tap where Nebula should tap", color = Color.White, style = big(24), modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill("Clear", Modifier.weight(1f)) { R3cket.clearPoints(); R3cket.setEditing(true) }
                ActionPill("Done · start", Modifier.weight(1f), Lime) { R3cket.setEditing(false); R3cket.startClicker(ctx, centerIfEmpty = false) }
            }
            return@Column
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(if (s?.boosting == true) "Optimising" else if ((s?.freedMb ?: 0) > 0) "Freed ${s?.freedMb} MB" else "Ready",
                    color = Color.White, style = big(26))
                Text(buildString {
                    s?.let { if (it.closedApps > 0) append("${it.closedApps} background apps closed") else append("Screen stays on") }
                    if (s?.dnd == true) append(" · Do not disturb")
                }, color = Dim, style = label().copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(clock(now - (s?.startedAt ?: now)), color = Lime, style = big(34))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Stat("Ping", ping?.let { "${it}ms" } ?: "—", pingColor(ping))
            Stat("Temp", "${batt.second.toInt()}°C", if (batt.second >= 42f) Red else Color.White)
            Stat("Battery", "${batt.first}%", if (batt.first <= 15) Red else Color.White)
            if (k.running) Stat("Taps", "${k.clicks}", Lime)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolTile(if (k.running) Icons.Rounded.Pause else Icons.Rounded.TouchApp, k.running, Modifier.weight(1f)) {
                if (k.running) R3cket.stopClicker() else if (k.points.isEmpty()) R3cket.setEditing(true) else R3cket.startClicker(ctx)
            }
            ToolTile(Icons.Rounded.GpsFixed, s?.crosshair == true, Modifier.weight(1f)) { R3cket.toggleCrosshair(ctx) }
            ToolTile(Icons.Rounded.DoNotDisturbOn, s?.dnd == true, Modifier.weight(1f)) { R3cket.toggleDnd(ctx) }
            ToolTile(Icons.Rounded.CameraAlt, false, Modifier.weight(1f)) { R3cket.panel.value = false; c.later(450) { R3cket.screenshot() } }
        }
    }
}

@Composable
private fun Stat(name: String, value: String, color: Color) {
    Column {
        Text(name.uppercase(), color = Faint, style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.8.sp))
        Text(value, color = color, style = label().copy(fontFamily = Display))
    }
}

@Composable
private fun ToolTile(icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(46.dp).clip(RoundedCornerShape(23.dp)).background(if (on) Lime else Glass).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(21.dp))
    }
}

// ============================================================================ calls, messages, music, devices

@Composable
private fun CallView(call: NotchBus.Call) {
    val ctx = LocalContext.current
    val end = { call.decline?.let { runCatching { it.send() } } ?: runCatching { @Suppress("DEPRECATION", "MissingPermission") ctx.getSystemService(TelecomManager::class.java).endCall() }; Unit }
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(call.icon, call.caller.take(1), 44.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(if (call.ringing) "Incoming call" else "On call", color = Dim, style = label())
                Text(call.caller, color = Color.White, style = big(22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionPill("Decline", Modifier.weight(1f), Red, iconless = true, onClick = end)
            if (call.ringing) ActionPill("Accept", Modifier.weight(1f), Mint, iconless = true) {
                call.answer?.let { runCatching { it.send() } } ?: runCatching { @Suppress("MissingPermission") ctx.getSystemService(TelecomManager::class.java).acceptRingingCall() }
            }
        }
    }
}

@Composable
private fun MessageView(m: NotchBus.Message, dismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(m.icon, m.sender.take(1), 38.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("${m.app} · now", color = Dim, style = label().copy(fontSize = 11.sp))
                Text(m.sender, color = Color.White, style = label().copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CloseDot(dismiss)
        }
        Text(m.text, color = Color(0xE6FFFFFF), style = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 19.sp), maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.weight(1f))
        if (m.open != null || m.replyAction != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionPill("Reply", Modifier.weight(1f)) { runCatching { (m.replyAction?.actionIntent ?: m.open)?.send() }; dismiss() }
            if (m.open != null) ActionPill("Open", Modifier.weight(1f)) { runCatching { m.open.send() }; dismiss() }
        }
    }
}

/**
 * The open music card — liquid glass. The song's own cover, blurred, is the light behind the glass; the card has
 * a bright top edge, a soft bevel and a sheen that drifts across, so it reads as a lens rather than a flat panel.
 * The cover floats on its own glass tile, the title glides on a track that fades at its edges, and a fine
 * waveform (the real audio on Android) breathes behind the controls. A new song melts in: the light cross-fades,
 * the cover blooms in from a blur, and play/pause morphs.
 */
@Composable
private fun MusicOpen(m: NotchBus.Media) {
    val tc = m.controller?.transportControls
    val th = MusicTheme.of(m.art)
    val light = th.style == "mono" && th.text != Color.White // silver theme: dark type
    val ink by animateColorAsState(if (light) Color(0xFF111114) else Color.White, tween(700), label = "ink")
    val inkSub = ink.copy(alpha = 0.66f)
    val accent by animateColorAsState(th.accent, tween(900), label = "ac")
    val bloom = remember(m.title) { Animatable(0f) }
    LaunchedEffect(m.title) { bloom.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    val inf = rememberInfiniteTransition(label = "glass")
    val drift by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "d")
    val float by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "f")
    DisposableEffect(m.playing) {
        val on = m.playing && MusicTheme.startBars()
        onDispose { if (on) MusicTheme.stopBars() }
    }
    val bands by MusicTheme.bands.collectAsState()
    val smooth = remember { FloatArray(16) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(250); now = System.currentTimeMillis() } }
    val pos = (if (m.playing) m.position + (now - m.updatedAt).coerceAtLeast(0) else m.position).let { if (m.duration > 0) it.coerceIn(0, m.duration) else it.coerceAtLeast(0) }
    val frac = if (m.duration > 0) pos.toFloat() / m.duration else 0f
    val fracA by animateFloatAsState(frac, tween(260, easing = LinearEasing), label = "p")
    val art = remember(m.art) { m.art?.asImageBitmap() }

    Box(Modifier.fillMaxSize()) {
        // 1. the light behind the glass: the cover itself, blurred and drifting
        androidx.compose.animation.Crossfade(targetState = art, animationSpec = tween(900), label = "backlight") { img ->
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(th.bg1, th.bg2)))) {
                if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer {
                    val k = 1.35f + 0.05f * sin(drift * 6.283f); scaleX = k; scaleY = k; translationX = 18.dp.toPx() * cos(drift * 6.283f); alpha = 0.9f
                }.then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(38.dp) else Modifier))
            }
        }
        // 2. the glass: a tint for legibility, a bright top edge, bevel and a drifting sheen
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRect(if (light) Color.White.copy(alpha = 0.42f) else Color.Black.copy(alpha = 0.38f))
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.22f to Color.White.copy(alpha = 0.04f), 0.75f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.18f)))
            val sx = drift * (w + 400f) - 200f
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent), Offset(sx - 140f, 0f), Offset(sx + 60f, h)))
            // fine waveform behind the controls
            val n = 16; val path = androidx.compose.ui.graphics.Path(); val base = h * 0.86f
            for (i in 0 until n) smooth[i] += ((if (m.playing) bands[i] else 0f) - smooth[i]) * 0.3f
            for (k in 0..64) {
                val x = w * 0.30f + (w * 0.66f) * k / 64f
                val bi = (k / 64f * (n - 1)); val i0 = bi.toInt().coerceAtMost(n - 2); val f = bi - i0
                val amp = (smooth[i0] * (1 - f) + smooth[i0 + 1] * f) * h * 0.11f + 1.2f
                val y = base + sin(k * 0.55f + drift * 40f) * amp
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent.copy(alpha = if (m.playing) 0.55f else 0.2f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        }
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            // 3. the cover on its own floating glass tile
            Box(Modifier.size(100.dp).graphicsLayer {
                val b = bloom.value; val k = 0.86f + 0.14f * b; scaleX = k; scaleY = k; alpha = b
                rotationY = 6f * sin(float * 6.283f); rotationX = 4f * cos(float * 6.283f); cameraDistance = 16f * density
                translationY = -2.dp.toPx() * sin(float * 6.283f)
                shadowElevation = 18.dp.toPx(); shape = RoundedCornerShape(24.dp); clip = true
            }.border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f))), RoundedCornerShape(24.dp))) {
                if (art != null) Image(art, m.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= 31 && bloom.value < 0.99f) Modifier.blur((14 * (1f - bloom.value)).dp) else Modifier))
                else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(th.bg1, th.bg2))))
                Canvas(Modifier.fillMaxSize()) { // glare on the tile
                    drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), Offset.Zero, Offset(size.width * 0.6f, size.height * 0.6f)))
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(m.title, transitionSpec = { (fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 3 }) togetherWith fadeOut(tween(200)) }, label = "title") { t ->
                    Text(t, color = ink, maxLines = 1, modifier = Modifier.fadeEdges().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2200, repeatDelayMillis = 2500,
                        spacing = androidx.compose.foundation.MarqueeSpacing(40.dp), velocity = 28.dp),
                        style = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp))
                }
                Text(m.artist, color = inkSub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.sp))
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTap(Icons.Rounded.SkipPrevious, 24.dp, ink) { tc?.skipToPrevious() }
                    Spacer(Modifier.width(10.dp))
                    // play/pause on a frosted glass disc, the icon morphing between states
                    Box(Modifier.size(40.dp).clip(CircleShape).background(ink.copy(alpha = 0.14f))
                        .border(1.dp, Brush.verticalGradient(listOf(ink.copy(alpha = 0.45f), ink.copy(alpha = 0.06f))), CircleShape)
                        .clickable { if (m.playing) tc?.pause() else tc?.play() }, contentAlignment = Alignment.Center) {
                        AnimatedContent(m.playing, transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f), 0.4f) + fadeIn()) togetherWith (scaleOut(tween(120), 0.4f) + fadeOut(tween(120))) },
                            label = "pp") { playing ->
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = ink, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    IconTap(Icons.Rounded.SkipNext, 24.dp, ink) { tc?.skipToNext() }
                }
                // a slim glass track with a glowing knob
                Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(10.dp)) {
                    val y = size.height / 2; val r = 2.dp.toPx(); val x1 = size.width
                    drawRoundRect(ink.copy(alpha = 0.18f), Offset(0f, y - r), Size(x1, r * 2), CornerRadius(r))
                    drawRoundRect(Brush.horizontalGradient(listOf(ink.copy(alpha = 0.7f), ink)), Offset(0f, y - r), Size(x1 * fracA, r * 2), CornerRadius(r))
                    val kx = x1 * fracA
                    drawCircle(accent.copy(alpha = 0.35f), 8.dp.toPx(), Offset(kx, y))
                    drawCircle(ink, 4.5.dp.toPx(), Offset(kx, y))
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(clock(pos), color = inkSub, style = label().copy(fontSize = 10.sp))
                    Spacer(Modifier.weight(1f))
                    if (m.duration > 0) Text("-" + clock(m.duration - pos), color = inkSub, style = label().copy(fontSize = 10.sp))
                }
            }
        }
    }
}

/** Fades a scrolling line out at both ends instead of chopping letters. */
private fun Modifier.fadeEdges() = this.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val f = 18.dp.toPx() / size.width
    drawRect(Brush.horizontalGradient(0f to Color.Transparent, f to Color.Black, 1f - f to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
}


@Composable
private fun DeviceView(d: NotchBus.Device, h: Dp) {
    // A ring fills around the earbuds icon as they connect (empties when they disconnect).
    val ring = remember(d.at) { Animatable(if (d.connected) 0f else 1f) }
    LaunchedEffect(d.at) { ring.animateTo(if (d.connected) 1f else 0f, tween(900, easing = FastOutSlowInEasing)) }
    val pop by animateFloatAsState(if (ring.value > 0.98f && d.connected) 1f else 0.85f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "pop")
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(h + 2.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = 2.6.dp.toPx()
                drawArc(Color(0x33FFFFFF), 0f, 360f, false, style = Stroke(s))
                drawArc(if (d.connected) Mint else Red, -90f, 360f * ring.value, false, style = Stroke(s, cap = StrokeCap.Round))
            }
            Icon(if (d.kind == "buds") Icons.Rounded.Headphones else Icons.Rounded.Headset, null, tint = Color.White,
                modifier = Modifier.size(h * 0.5f).graphicsLayer { scaleX = pop; scaleY = pop })
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(if (d.connected) "Connected" else "Disconnected", color = if (d.connected) Mint else Dim, style = label().copy(fontSize = 11.sp))
            Text(d.name, color = Color.White, style = label().copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (d.connected && d.battery != null) {
            val b = d.battery
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = 3.dp.toPx()
                    drawArc(Color(0x33FFFFFF), 0f, 360f, false, style = Stroke(s))
                    drawArc(if (b <= 20) Red else Mint, -90f, 360f * b / 100f, false, style = Stroke(s, cap = StrokeCap.Round))
                }
                Text("$b", color = Color.White, style = label().copy(fontFamily = Display, fontSize = 12.sp))
            }
        }
    }
}

// ============================================================================ bits

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
        rotate(spin) { drawCircle(Brush.sweepGradient(colors), r) }
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), Color.Transparent), center, r * 0.9f), r * 0.9f)
        if (phase == VoiceState.Phase.ERROR) drawCircle(Color(0x88EF4444), r)
    }
}

/** Live voice bars, centre-weighted, following the microphone level. */
@Composable
private fun LiveWave(level: Float, modifier: Modifier, bars: Int = 28) {
    val hist = remember { FloatArray(bars) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(level) { System.arraycopy(hist, 1, hist, 0, bars - 1); hist[bars - 1] = level; frame++ }
    val t = rememberInfiniteTransition(label = "wave")
    val ph by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ph")
    Canvas(modifier) {
        frame.let { }
        val gap = size.width / (bars * 2f)
        for (i in 0 until bars) {
            val centre = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / (bars / 2f) * 0.55f
            val idle = 0.14f + 0.08f * ((sin(ph + i * 0.5f) + 1) / 2)
            val a = (idle + hist[i] * 0.95f * centre).coerceIn(0.1f, 1f)
            val hh = size.height * a
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF9BE7FF), Color(0xFFFFB3D9))),
                Offset(i * gap * 2 + gap / 2, (size.height - hh) / 2), Size(gap, hh), CornerRadius(gap / 2))
        }
    }
}

@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
    Canvas(Modifier.size(width = 34.dp, height = 14.dp)) {
        for (i in 0 until 3) {
            val k = ((sin((p - i * 0.18f) * 6.283f) + 1) / 2)
            drawCircle(Flame.copy(alpha = 0.3f + 0.7f * k), 3.dp.toPx(), Offset(size.width * (0.2f + i * 0.3f), size.height / 2 - k * 3.dp.toPx()))
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
            drawRoundRect(NotchGradient, Offset(i * gap * 2, (size.height - hh) / 2), Size(gap, hh), CornerRadius(gap / 2))
        }
    }
}

@Composable
private fun Art(m: NotchBus.Media, size: Dp, radius: Dp) {
    val bmp = m.art
    if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(RoundedCornerShape(radius)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(NotchGradient))
}

@Composable
private fun Avatar(bmp: android.graphics.Bitmap?, letter: String, size: Dp) {
    if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(NotchGradient), contentAlignment = Alignment.Center) {
        Text(letter.uppercase(), color = Color.White, style = label().copy(fontSize = 16.sp))
    }
}

/** Big rounded button, like the Live Activity references. */
@Composable
private fun ActionPill(text: String, modifier: Modifier = Modifier, tint: Color? = null, iconless: Boolean = true, onClick: () -> Unit) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(tint?.copy(alpha = 0.9f) ?: Glass).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (tint == Lime) Color.Black else Color.White, style = label().copy(fontSize = 14.sp))
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Flame).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Stop, "Stop", tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CloseDot(onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(Glass).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Close, "Close", tint = Dim, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun IconTap(icon: ImageVector, size: Dp, tint: Color = Color.White, onClick: () -> Unit) =
    Icon(icon, null, tint = tint, modifier = Modifier.size(size + 12.dp).clip(CircleShape).clickable(onClick = onClick).padding(6.dp))

private fun label() = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp)
private fun big(size: Int) = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = size.sp, lineHeight = (size * 1.15f).sp)
