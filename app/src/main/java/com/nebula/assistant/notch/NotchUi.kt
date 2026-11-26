package com.nebula.assistant.notch

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nebula.assistant.beam.BeamController
import com.nebula.assistant.beam.BeamUi
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.NebulaTask
import com.nebula.assistant.data.Store
import com.nebula.assistant.data.TaskStatus
import com.nebula.assistant.game.R3cket
import com.nebula.assistant.sports.Sports
import com.nebula.assistant.voice.VoiceState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** What the island needs from whoever hosts it (the overlay service, or a preview/recording stage). */
interface NotchHost {
    val context: android.content.Context
    val notchPrefs: NebulaPrefs
    val layout: NotchController.Layout
    val unlock: NotchController.UnlockPhase?
    val volume: Pair<Float, Boolean>?
    val phoneInCall: Boolean
    val charge: NotchBus.Charge?
    val ringer: NotchBus.Ringer?
    fun cutoutHeightPx(): Int
    fun reserve(w: Int, h: Int)
    fun fit(w: Int, h: Int, afterMs: Long)
    fun talk()
    fun openApp(tab: String?, taskId: String?)
    fun later(ms: Long, f: () -> Unit)
}

/** Everything live that the island can show, in one snapshot. */
data class IslandState(
    val media: NotchBus.Media? = null,
    val call: NotchBus.Call? = null,
    val message: NotchBus.Message? = null,
    val preview: Boolean = false,
    val tasks: List<NebulaTask> = emptyList(),
    val voice: VoiceState.Session = VoiceState.Session(),
    val gaming: Boolean = false,
    val clicker: R3cket.Clicker = R3cket.Clicker(),
    val game: R3cket.Session? = null,
    val panel: Boolean = false,
    val ping: Int? = null,
    val device: NotchBus.Device? = null,
    val games: List<Sports.Game> = emptyList(),
    val lives: List<NotchBus.Live> = emptyList(),
    val goal: Sports.Score? = null,
    val beam: BeamUi = BeamUi.Idle,
)

internal enum class Mode { NONE, PREVIEW, UNLOCK, CALL, VOICE, VOLUME, CHARGE, RINGER, DEVICE, R3CKET, MESSAGE, TASK, MUSIC, MUSIC_OPEN, GAME, SPORTS, SPORTS_OPEN, GOAL, LIVE, LIVE_OPEN, BEAM }

/** Exact size and corner of the island for a view. Fixed sizes = no measuring, no jitter. */
internal data class Geo(val w: Dp, val h: Dp, val r: Dp)

/** True while a game runs with R3cket's lite mode: the island drops its heavy effects. */
internal val LocalLite = compositionLocalOf { false }

/** The Nebula Notch, fed by the live system state. */
@Composable
fun NotchUi(c: NotchController) {
    val state = IslandState(
        media = NotchBus.media.collectAsState().value,
        call = NotchBus.call.collectAsState().value,
        message = NotchBus.message.collectAsState().value,
        preview = NotchBus.preview.collectAsState().value,
        tasks = Store.tasks.collectAsState().value,
        voice = VoiceState.session.collectAsState().value,
        gaming = R3cket.inGame.collectAsState().value,
        clicker = R3cket.clicker.collectAsState().value,
        game = R3cket.session.collectAsState().value,
        panel = R3cket.panel.collectAsState().value,
        ping = R3cket.ping.collectAsState().value,
        device = NotchBus.device.collectAsState().value,
        games = Sports.games.collectAsState().value,
        lives = NotchBus.live.collectAsState().value,
        goal = Sports.goal.collectAsState().value,
        beam = BeamController.ui.collectAsState().value,
    )
    Island(state, c, onGoalDone = { Sports.clearGoal() }, onPanel = { R3cket.panel.value = it })
}

/**
 * The island itself.
 *
 * One black shape morphs between views like the Dynamic Island: width leads when it grows and height leads when
 * it shrinks, on springs with a touch of overshoot, and the whole shape gives a little jelly squash on every
 * change. The old content leaves quickly; the new content arrives as a cascade once the shape is on its way.
 * A rim of the Nebula gradient lights up as it morphs (and stays on while Nebula listens). Every view has a fixed
 * size, so nothing is measured mid-animation and the overlay window only resizes twice per change.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Island(s: IslandState, c: NotchHost, onGoalDone: () -> Unit, onPanel: (Boolean) -> Unit) {
    val prefs = c.notchPrefs
    val base = LocalDensity.current
    val ctx = LocalContext.current

    // Messages show for a few seconds, then tuck away.
    var shownMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.message?.key, s.message?.at) {
        val m = s.message ?: return@LaunchedEffect
        shownMessage = m.key; delay(5500); if (shownMessage == m.key) shownMessage = null
    }
    // A finished task shows its result briefly.
    val active = s.tasks.firstOrNull { it.isActive && !it.isReminder }
    var recentId by remember { mutableStateOf<String?>(null) }
    val newest = s.tasks.firstOrNull { !it.isActive && !it.isReminder && System.currentTimeMillis() - it.updatedAt < 4000 }
    LaunchedEffect(newest?.id, newest?.updatedAt) { if (newest != null) { recentId = newest.id; delay(4000); recentId = null } }
    val recent = s.tasks.firstOrNull { it.id == recentId }
    var musicOpen by remember { mutableStateOf(false) }
    LaunchedEffect(musicOpen) { if (musicOpen) { delay(8000); musicOpen = false } }

    // Sports: tap the score to open the board (tap again for your next game); a moment plays, then settles.
    var sportsOpen by remember { mutableStateOf(false) }
    var sportsIdx by remember { mutableStateOf(0) }
    LaunchedEffect(sportsOpen, sportsIdx) { if (sportsOpen) { delay(9000); sportsOpen = false } }
    LaunchedEffect(s.goal?.at) {
        val g = s.goal ?: return@LaunchedEffect
        if (!prefs.sportsGoals || g.label.isEmpty()) { onGoalDone(); return@LaunchedEffect }
        momentBuzz(ctx, g.kind)
        delay(momentDuration(g))
        onGoalDone()
    }
    var liveOpen by remember { mutableStateOf(false) }
    LaunchedEffect(liveOpen) { if (liveOpen) { delay(8000); liveOpen = false } }
    val liveAct = if (prefs.notchLive) s.lives.firstOrNull() else null
    val liveGame = if (prefs.sportsOn) s.games.getOrNull(if (s.games.isEmpty()) 0 else sportsIdx % s.games.size) else null

    val quiet = s.gaming && prefs.r3cketQuiet
    val lite = s.gaming && prefs.r3cketLite
    val task = if (prefs.notchTasks) active ?: recent else null
    val charge = c.charge.takeIf { prefs.notchCharging }
    val ringer = c.ringer.takeIf { prefs.notchRinger }
    val mode = when {
        s.preview -> Mode.PREVIEW
        s.voice.active -> Mode.VOICE // Hey Nebula always shows, even with the notch's extras switched off
        !prefs.notchEnabled -> Mode.NONE
        c.unlock != null -> Mode.UNLOCK
        s.beam !is BeamUi.Idle -> Mode.BEAM
        s.call != null && prefs.notchCalls && (c.phoneInCall || s.call.ringing) -> Mode.CALL
        c.volume != null -> Mode.VOLUME
        ringer != null && !quiet -> Mode.RINGER
        s.device != null -> Mode.DEVICE
        charge != null && !s.gaming -> Mode.CHARGE
        s.clicker.editing || s.panel -> Mode.R3CKET
        s.goal != null && s.goal.label.isNotEmpty() && prefs.sportsGoals && !quiet -> Mode.GOAL
        sportsOpen && liveGame != null -> Mode.SPORTS_OPEN
        !quiet && s.message != null && shownMessage == s.message.key && prefs.notchMessages -> Mode.MESSAGE
        task != null && !quiet -> Mode.TASK
        liveOpen && liveAct != null -> Mode.LIVE_OPEN
        liveGame != null && !quiet -> Mode.SPORTS
        liveAct != null && !quiet -> Mode.LIVE
        s.media != null && s.media.playing && prefs.notchMusic && !quiet -> if (musicOpen) Mode.MUSIC_OPEN else Mode.MUSIC
        s.gaming && s.game != null && prefs.r3cketHud -> Mode.GAME
        else -> Mode.NONE
    }

    // Size: the camera-hole height × the user's height setting; everything else scales with Size.
    val L = c.layout
    val pillDp = with(base) { (c.cutoutHeightPx().toDp() - 4.dp).coerceIn(30.dp, 44.dp) }.value * L.height
    CompositionLocalProvider(LocalDensity provides Density(base.density * L.scale, base.fontScale), LocalLite provides lite) {
        val d = LocalDensity.current
        val pillH = pillDp.dp
        val target = geoFor(mode, pillH, s, liveGame)

        val w = remember { Animatable(target.w.value) }
        val h = remember { Animatable(target.h.value) }
        val r = remember { Animatable(target.r.value) }
        val shapeAlpha = remember { Animatable(if (mode == Mode.NONE) 0f else 1f) }
        val contentAlpha = remember { Animatable(1f) }
        val appear = remember { Animatable(1f) }
        val squish = remember { Animatable(0f) }
        val rim = remember { Animatable(0f) }
        var shown by remember { mutableStateOf(mode) }
        var growDir by remember { mutableStateOf(1f) }

        LaunchedEffect(target, mode) {
            val growing = target.w.value * target.h.value >= w.value * h.value
            growDir = if (growing) 1f else -1f
            with(d) { c.reserve(maxOf(w.value, target.w.value).dp.roundToPx() + 8, (maxOf(h.value, target.h.value) + 10f).dp.roundToPx()) }
            coroutineScope {
                // The content always ends visible: if a change interrupts a fade, the next run finishes it
                // (an interrupted fade used to leave an empty black island until the view changed again).
                launch {
                    if (shown != mode) {
                        contentAlpha.animateTo(0f, tween(if (growing) 80 else 110))
                        shown = mode
                        appear.snapTo(0f)
                        delay(if (growing) 70 else 30)
                    }
                    launch { contentAlpha.animateTo(1f, tween(180)) }
                    if (appear.value < 1f) appear.animateTo(1f, tween(if (lite) 260 else 620, easing = LinearEasing))
                }
                if (mode != Mode.NONE && !lite) launch { squish.snapTo(1f); squish.animateTo(0f, spring(dampingRatio = 0.32f, stiffness = 260f)) }
                if (mode != Mode.NONE && !lite) launch { rim.animateTo(1f, tween(140)); rim.animateTo(0f, tween(900, 200)) }
                val big = spring<Float>(dampingRatio = if (growing) 0.74f else 0.88f, stiffness = if (growing) 320f else 480f)
                launch { shapeAlpha.animateTo(if (mode == Mode.NONE) 0f else 1f, tween(if (mode == Mode.NONE) 240 else 110, if (mode == Mode.NONE) 140 else 0)) }
                // Width leads when growing (it stretches out of the camera, then drops down); height leads when shrinking.
                launch { if (!growing) delay(45); w.animateTo(target.w.value, big) }
                launch { if (growing) delay(45); h.animateTo(target.h.value, big) }
                launch { r.animateTo(target.r.value, big) }
            }
            with(d) {
                if (mode == Mode.NONE) { if (prefs.beamDoubleTap) c.fit(target.w.roundToPx(), (target.h + 8.dp).roundToPx(), 60) else c.fit(1, 1, 60) }
                else c.fit(target.w.roundToPx() + 8, (target.h + 10.dp).roundToPx(), 60)
            }
        }

        val accent by animateColorAsState(accentFor(shown, s, task), tween(400), label = "accent")
        val expanded = target.r.value > pillH.value / 2 + 1
        val glowAlpha by animateFloatAsState(if (expanded && !lite) 1f else 0f, tween(450), label = "glow")
        // The rim spins only while it's visible — an idle island never redraws.
        val listening = shown == Mode.VOICE && s.voice.phase == VoiceState.Phase.LISTENING && !lite
        val rimBase by animateFloatAsState(if (listening) 0.85f else 0f, tween(400), label = "rimBase")
        val spin = remember { Animatable(0f) }
        val rimOn = rim.value > 0.01f || rimBase > 0.01f
        LaunchedEffect(rimOn) { if (rimOn) while (true) { spin.animateTo(spin.value + 360f, tween(2400, easing = LinearEasing)) } }

        Box(Modifier.fillMaxSize().wrapContentSize(Alignment.TopCenter, unbounded = true)) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(w.value.dp, h.value.dp)
                    .graphicsLayer {
                        alpha = shapeAlpha.value
                        val q = squish.value * growDir
                        scaleX = 1f + 0.045f * q; scaleY = 1f - 0.035f * q
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .clip(RoundedCornerShape(r.value.coerceAtMost(h.value / 2).dp))
                    .background(Color.Black)
                    .drawWithContent {
                        drawContent()
                        val rr = r.value.coerceAtMost(h.value / 2).dp.toPx()
                        // Liquid glass once it opens up: a bright top edge and a bevelled rim.
                        if (glowAlpha > 0.01f) {
                            val a = glowAlpha
                            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.09f * a), 0.16f to Color.Transparent, 0.84f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.2f * a)))
                            val sw = 1.dp.toPx()
                            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f * a), Color.White.copy(alpha = 0.05f * a), Color.White.copy(alpha = 0.14f * a))),
                                Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(rr), style = Stroke(sw))
                        }
                        // The Nebula rim: a comet of ember → flame → ruby running round the edge.
                        val ra = maxOf(rim.value, rimBase)
                        if (ra > 0.01f) {
                            val sw = 1.6.dp.toPx()
                            // the gradient turns, not the shape
                            val deg = spin.value; val cx = size.width / 2; val cy = size.height / 2
                            val comet = object : ShaderBrush() {
                                override fun createShader(size: Size): Shader = android.graphics.SweepGradient(cx, cy,
                                    intArrayOf(0, 0, Ember.toArgb(), Flame.toArgb(), Ruby.toArgb(), 0), floatArrayOf(0f, 0.55f, 0.72f, 0.86f, 0.96f, 1f))
                                    .apply { setLocalMatrix(android.graphics.Matrix().apply { setRotate(deg, cx, cy) }) }
                            }
                            drawRoundRect(comet, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(rr), alpha = ra, style = Stroke(sw))
                        }
                    }
                    .combinedClickable(
                        enabled = shown in setOf(Mode.MUSIC, Mode.TASK, Mode.VOLUME, Mode.GAME, Mode.SPORTS, Mode.SPORTS_OPEN, Mode.GOAL, Mode.LIVE, Mode.LIVE_OPEN, Mode.CHARGE) ||
                            (shown == Mode.NONE && prefs.beamDoubleTap),
                        onLongClick = { c.talk() }, // hold the island to talk to Nebula
                        // Double-tap the notch: Nebula Beam. Only from the resting notch or a compact view.
                        onDoubleClick = if (prefs.beamDoubleTap && shown in setOf(Mode.NONE, Mode.MUSIC, Mode.SPORTS, Mode.LIVE, Mode.TASK))
                            ({ BeamController.open() }) else null,
                    ) {
                        when (shown) {
                            Mode.MUSIC -> musicOpen = true
                            Mode.TASK -> c.openApp("tasks", task?.id)
                            Mode.GAME -> onPanel(true)
                            Mode.SPORTS -> sportsOpen = true
                            Mode.SPORTS_OPEN -> if (s.games.size > 1) sportsIdx++ else sportsOpen = false
                            Mode.GOAL -> onGoalDone()
                            Mode.LIVE -> liveOpen = true
                            Mode.LIVE_OPEN -> { runCatching { liveAct?.open?.send() }; liveOpen = false }
                            else -> {}
                        }
                    },
            ) {
                // The card's own light rising from its bottom edge (like a Live Activity).
                if (glowAlpha > 0.01f && accent.alpha > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = glowAlpha }.background(
                    Brush.verticalGradient(0.5f to Color.Transparent, 0.84f to accent.copy(alpha = 0.14f), 1f to accent.copy(alpha = 0.5f))))
                // Content is laid out at its final size, so it never reflows while the shape morphs.
                val g = geoFor(shown, pillH, s, liveGame)
                CompositionLocalProvider(LocalAppear provides appear.asState()) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .requiredSize(g.w, g.h)
                            .graphicsLayer { alpha = contentAlpha.value }
                            .clip(RoundedCornerShape(g.r.coerceAtMost(g.h / 2))),
                    ) {
                        when (shown) {
                            Mode.PREVIEW -> PreviewView()
                            Mode.UNLOCK -> UnlockView(c.unlock == NotchController.UnlockPhase.SUCCESS, pillH)
                            Mode.CALL -> s.call?.let { CallView(it) }
                            Mode.VOICE -> if (s.voice.gaming) VoiceCompact(s.voice, pillH) else VoiceView(s.voice, c)
                            Mode.VOLUME -> c.volume?.let { VolumeView(it.first, it.second, pillH) }
                            Mode.CHARGE -> charge?.let { ChargeView(it, pillH) }
                            Mode.RINGER -> ringer?.let { RingerView(it, pillH) }
                            Mode.DEVICE -> s.device?.let { DeviceView(it, pillH) }
                            Mode.R3CKET -> R3cketPanel(s, c, onPanel)
                            Mode.MESSAGE -> s.message?.let { MessageView(it) { shownMessage = null } }
                            Mode.TASK -> task?.let { TaskView(it, pillH) }
                            Mode.MUSIC -> s.media?.let { MusicCompact(it, pillH) }
                            Mode.MUSIC_OPEN -> s.media?.let { MusicOpen(it) }
                            Mode.GAME -> GameHud(s, pillH)
                            Mode.SPORTS -> liveGame?.let { SportsCompact(it, pillH) }
                            Mode.LIVE -> liveAct?.let { LiveCompact(it, pillH) }
                            Mode.LIVE_OPEN -> liveAct?.let { LiveCard(it) }
                            Mode.SPORTS_OPEN -> liveGame?.let { SportsOpen(it, more = s.games.size, index = sportsIdx % s.games.size.coerceAtLeast(1)) }
                            Mode.GOAL -> s.goal?.let { Moment(it) }
                            Mode.BEAM -> BeamView(s.beam)
                            Mode.NONE -> {}
                        }
                    }
                }
            }
        }
    }
}

private fun geoFor(mode: Mode, pillH: Dp, s: IslandState, sport: Sports.Game?): Geo {
    val card = 30.dp
    val full = 372.dp
    val v = s.voice
    return when (mode) {
        Mode.NONE -> Geo(pillH * 2.6f, pillH, pillH / 2)
        Mode.PREVIEW -> Geo(232.dp, pillH, pillH / 2)
        Mode.UNLOCK -> Geo(196.dp, pillH, pillH / 2)
        Mode.VOLUME -> Geo(240.dp, pillH, pillH / 2)
        Mode.CHARGE -> Geo(250.dp, pillH, pillH / 2)
        Mode.RINGER -> Geo(214.dp, pillH, pillH / 2)
        Mode.TASK -> Geo(268.dp, pillH, pillH / 2)
        Mode.MUSIC -> Geo(236.dp, pillH, pillH / 2)
        Mode.GAME -> Geo(if (s.game?.boosting == true || s.game?.let { System.currentTimeMillis() - it.startedAt < 9000 } == true) 300.dp else 266.dp, pillH, pillH / 2)
        Mode.SPORTS -> Geo(256.dp, pillH, pillH / 2)
        Mode.LIVE -> Geo(236.dp, pillH, pillH / 2)
        Mode.LIVE_OPEN -> Geo(full, 150.dp, card)
        Mode.BEAM -> beamGeo(s.beam, full)
        Mode.SPORTS_OPEN -> sport?.let { sportsOpenGeo(it) } ?: Geo(full, 130.dp, card)
        Mode.GOAL -> s.goal?.let { momentGeo(it) } ?: Geo(full, 150.dp, card)
        Mode.DEVICE -> Geo(312.dp, pillH + 18.dp, (pillH + 18.dp) / 2)
        Mode.CALL -> Geo(full, 158.dp, card)
        Mode.MESSAGE -> Geo(full, 172.dp, card)
        Mode.MUSIC_OPEN -> Geo(full, 212.dp, 38.dp)
        Mode.R3CKET -> r3cketGeo(s.clicker.editing, pillH)
        Mode.VOICE -> if (v.gaming) Geo(340.dp, pillH + 6.dp, (pillH + 6.dp) / 2) else {
            val text = if ((v.phase == VoiceState.Phase.LISTENING && !(v.followUp && v.transcript.isBlank())) || v.phase == VoiceState.Phase.THINKING) v.transcript else v.reply
            val lines = ceil(text.length / 34f).toInt().coerceIn(1, 9)
            val extra = if (v.sources.isNotEmpty() && v.phase != VoiceState.Phase.LISTENING) 36 else 0
            Geo(full, (120 + lines * 25 + extra).coerceIn(156, 420).dp, card)
        }
    }
}

private fun accentFor(mode: Mode, s: IslandState, t: NebulaTask?): Color = when (mode) {
    Mode.VOICE -> when (s.voice.phase) { VoiceState.Phase.ERROR -> Red; VoiceState.Phase.LISTENING -> Sky; else -> Flame }
    Mode.CALL -> if (s.call?.ringing == true) Mint else Sky
    Mode.MESSAGE -> Sky
    Mode.BEAM, Mode.MUSIC_OPEN, Mode.SPORTS_OPEN, Mode.GOAL -> Color.Transparent // they bring their own colours
    Mode.R3CKET, Mode.GAME -> Lime
    Mode.TASK -> when (t?.status) { TaskStatus.COMPLETED -> Mint; TaskStatus.FAILED -> Red; else -> Ember }
    else -> Flame
}
