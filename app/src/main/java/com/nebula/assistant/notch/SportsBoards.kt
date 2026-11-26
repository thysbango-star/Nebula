package com.nebula.assistant.notch

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.assistant.sports.Sports
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/*
 * Nebula's scoreboards, applied from the design boards:
 *
 *  - The scorebar: one pill split down the middle, each half in its team's kit colour (dark at the top, bright at
 *    the bottom), the two halves overlapping at a rounded seam with the competition's logo — in white — sitting on
 *    it; each team's ESPN logo on a black disc with a ring in its colour; the scores big and heavy, in black on
 *    bright kits and white on dark ones. It lives inside the black island, under the camera row.
 *  - Competition intro ("a logo for every competition"): a black bar, the competition logo in the middle and both
 *    teams either side — at kick-off, the second-half restart and full time.
 *  - Added time: the orange → violet → orange bar with the competition's logo and "+4 Minutes Of Added Time".
 *  - Stats brought to you by ESPN: a red ESPN panel that drops down under the scorebar.
 *  - Lineups: the team's colours, its logo breaking out of the bar, "No.6 / SIYA KOLISI", then the bench.
 *  - VAR (purple, silver call) / TMO (blue, orange call), then the decision — and which team it favours.
 *  - Scores: five looks per team taking turns, the reference being the TRY board — the team's colours, its logo
 *    breaking out, the big word, and the word outlined over and over behind it.
 *
 * Premier League method, applied: the board is always one object that transforms (it never cuts to a new
 * graphic); the teams are re-introduced now and then; the clock carries an added-time tab once it's announced;
 * stats slide out under the board on a rotation, with the data partner credited on the panel itself; every
 * score is a club-specific moment inside the board, after which the number rolls over.
 */

private val Strip = 28.dp          // the camera row at the top of the island
private val BarH = 80.dp           // the scorebar
private val MomentBarH = 104.dp    // the bar during a moment (it grows for the big ones)
private val EspnRed = Color(0xFFD7171F)
private val CardYellow = Color(0xFFFFD60A)
private val CardRed = Color(0xFFE5172F)
private val Orange = Color(0xFFFF9150)
private val Purple = Color(0xFF8B55FF)
private val VarPurple = Color(0xFF2E0B3A)
private val TmoOrange = Color(0xFFFF7A1A)

private val FANCY = setOf("cannons", "gulls", "magpies", "eagles", "cinematic", "wordwall", "logopeek", "burst")

private fun kit(s: Sports.Side) = Color(s.color)
private fun isRugby(g: Sports.Game) = g.league.kind == Sports.Kind.RUGBY
private fun isCricket(g: Sports.Game) = g.league.kind == Sports.Kind.CRICKET

// ============================================================================ sizes & timing

private fun infoKinds(g: Sports.Game): List<String> = buildList {
    if (g.stats.isNotEmpty()) add("stats")
    if (g.lastPlay.isNotBlank()) add("play")
    if (g.cards.isNotEmpty()) add("cards")
    add("names")
}

internal fun sportsOpenGeo(g: Sports.Game): Geo = Geo(372.dp, Strip + BarH + 8.dp + 78.dp + 10.dp, 34.dp)

internal fun momentGeo(s: Sports.Score): Geo = when (s.kind) {
    "lineup" -> Geo(372.dp, Strip + 112.dp + 12.dp, 34.dp)
    "added" -> Geo(372.dp, Strip + 74.dp + 12.dp, 34.dp)
    "intro", "fulltime" -> Geo(372.dp, Strip + 86.dp + 12.dp, 34.dp)
    "check", "decision" -> Geo(372.dp, Strip + 86.dp + 12.dp, 34.dp)
    "sub" -> Geo(372.dp, Strip + (54 + 40 * s.subs.size.coerceIn(1, 3)).dp + 12.dp, 34.dp)
    "yellow", "red", "red20" -> Geo(372.dp, Strip + 92.dp + 12.dp, 34.dp)
    else -> Geo(372.dp, Strip + MomentBarH + 12.dp, 34.dp)
}

private fun wordAt(s: Sports.Score) = if (s.chant.isNotEmpty()) 1.55f else 0.75f
private fun holdFor(s: Sports.Score) = if (s.motif in FANCY) 3.0f else 2.2f

internal fun momentDuration(s: Sports.Score): Long = when (s.kind) {
    "sub" -> 5200L + 900L * (s.subs.size - 1).coerceAtLeast(0)
    "added" -> 5200L
    "check" -> 5200L
    "decision" -> 5800L
    "intro" -> 5000L
    "fulltime" -> 6200L
    "lineup" -> lineupDuration(s)
    "yellow", "red", "red20" -> 5600L
    else -> ((wordAt(s) + holdFor(s) + 2.3f) * 1000).toLong()
}

private fun lineupDuration(s: Sports.Score): Long {
    val st = s.players.count { it.starter }; val b = s.players.size - st
    return 1500L + st * 850L + (if (b > 0) 700L + b * 500L else 0L) + 600L
}

/** The moment's buzz: three quick pulses and a cheer for a score; a sharp double tap for a card; a soft tick for the rest. */
internal fun momentBuzz(ctx: android.content.Context, kind: String) {
    runCatching {
        val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(android.os.Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= 26) v?.vibrate(when (kind) {
            "score" -> android.os.VibrationEffect.createWaveform(longArrayOf(0, 40, 70, 40, 70, 120), intArrayOf(0, 200, 0, 200, 0, 255), -1)
            "yellow", "red", "red20", "decision" -> android.os.VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), intArrayOf(0, 255, 0, 255), -1)
            else -> android.os.VibrationEffect.createOneShot(30, 120)
        })
    }
    runCatching {
        com.nebula.assistant.audio.Sounds.play(ctx, when (kind) {
            "score" -> com.nebula.assistant.audio.NebulaSound.GOAL
            "sub", "lineup", "added", "check", "intro" -> com.nebula.assistant.audio.NebulaSound.SOFT
            else -> com.nebula.assistant.audio.NebulaSound.NOTIFICATION
        })
    }
}

// ============================================================================ building blocks

/** A team's ESPN logo on a black disc with a ring in the team's colour (falls back to the short name). */
@Composable
internal fun TeamBadge(s: Sports.Side, size: Dp, ring: Color = kit(s).light(0.25f), modifier: Modifier = Modifier) {
    val img = rememberLogo(s.logo)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xFF060607))
            drawCircle(ring, size.toPx() / 2 - 3.6.dp.toPx(), style = Stroke(1.8.dp.toPx()))
        }
        if (img != null) androidx.compose.foundation.Image(img, s.name, contentScale = ContentScale.Fit, modifier = Modifier.size(size * 0.6f))
        else Text(s.abbr.take(4), color = ring, maxLines = 1, style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.24f).sp))
    }
}

/** A score that rolls over when it changes (old number slides out the top, new one rises in). */
@Composable
private fun ScoreNum(value: String, style: TextStyle, color: Color, from: String? = null, rollAfterMs: Long = 0, key: Any? = null, modifier: Modifier = Modifier) {
    var shownValue by remember(key) { mutableStateOf(from ?: value) }
    LaunchedEffect(value, key) { if (shownValue != value) { delay(rollAfterMs); shownValue = value } }
    AnimatedContent(shownValue, modifier = modifier, transitionSpec = {
        (slideInVertically(spring(dampingRatio = 0.55f, stiffness = 300f)) { it } + fadeIn(tween(200))) togetherWith
            (slideOutVertically(tween(260)) { -it } + fadeOut(tween(200)))
    }, label = "score") { n -> Text(n, color = color, maxLines = 1, style = style) }
}

/** Draws the two kit-coloured halves of the scorebar, overlapping at a rounded seam. [fill] grows them out from the seam. */
private fun DrawScope.halves(home: Color, away: Color, fillHome: Float = 1f, fillAway: Float = 1f, seam: Float = 0.5f) {
    val w = size.width; val h = size.height; val r = h / 2; val lap = 6.dp.toPx(); val sr = 16.dp.toPx()
    val mid = w * seam
    fun grad(c: Color) = Brush.verticalGradient(listOf(c.deep(0.42f), c.light(0.06f)))
    // right half first (the left one laps over it)
    if (fillAway > 0f) {
        val left = mid - lap
        val right = left + (w - left) * fillAway
        val p = Path().apply { addRoundRect(RoundRect(left, 0f, right, h, CornerRadius(0f), CornerRadius(r), CornerRadius(r), CornerRadius(0f))) }
        drawPath(p, grad(away))
    }
    if (fillHome > 0f) {
        val right = mid + lap
        val left = right - right * fillHome
        val p = Path().apply { addRoundRect(RoundRect(left, 0f, right, h, CornerRadius(r), CornerRadius(sr), CornerRadius(sr), CornerRadius(r))) }
        // a soft shadow where the left half laps over the right
        drawRoundRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent), right - 2f, right + 14.dp.toPx()),
            Offset(right - sr, 0f), Size(sr + 14.dp.toPx(), h), CornerRadius(sr))
        drawPath(p, grad(home))
    }
    // gloss along the top
    drawRoundRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.35f to Color.Transparent), Offset.Zero, Size(w, h), CornerRadius(r))
}

/**
 * The scorebar. During a moment it starts from [roll]'s old score and rolls the scorer's number over after
 * [rollAfterMs]. [names] swaps the badges' neighbours for the teams' names (the periodic team reveal).
 */
@Composable
internal fun Scorebar(g: Sports.Game, modifier: Modifier, roll: Sports.Score? = null, rollAfterMs: Long = 0, seamLogo: Boolean = true, fill: Float = 1f, badgeSize: Dp = 56.dp) {
    val hc = kit(g.home); val ac = kit(g.away)
    val cricket = isCricket(g)
    val done = g.state == "post"
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) { halves(hc, ac, fill, fill) }
        val numStyle = heavy(if (cricket) 30 else 50)
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp).graphicsLayer { alpha = fill.coerceIn(0f, 1f) }, verticalAlignment = Alignment.CenterVertically) {
            TeamBadge(g.home, badgeSize, kit(g.home).light(0.35f))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ScoreNum(g.home.text, numStyle, inkOn(hc).copy(alpha = if (done && g.home.score < g.away.score && !cricket) 0.55f else 1f),
                        from = roll?.takeIf { it.home && it.kind == "score" }?.before, rollAfterMs = rollAfterMs, key = roll?.at)
                    if (cricket && g.home.sub.isNotBlank()) Text(g.home.sub, color = inkOn(hc).copy(alpha = 0.7f), style = label().copy(fontSize = 10.sp))
                }
            }
            Spacer(Modifier.width(if (seamLogo) 52.dp else 20.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ScoreNum(g.away.text, numStyle, inkOn(ac).copy(alpha = if (done && g.away.score < g.home.score && !cricket) 0.55f else 1f),
                        from = roll?.takeIf { !it.home && it.kind == "score" }?.before, rollAfterMs = rollAfterMs, key = roll?.at)
                    if (cricket && g.away.sub.isNotBlank()) Text(g.away.sub, color = inkOn(ac).copy(alpha = 0.7f), style = label().copy(fontSize = 10.sp))
                }
            }
            TeamBadge(g.away, badgeSize, kit(g.away).light(0.35f))
        }
        if (seamLogo && g.leagueLogo.isNotBlank()) LogoImage(g.leagueLogo, 58.dp, Modifier.align(Alignment.Center).graphicsLayer { alpha = fill.coerceIn(0f, 1f) }, tint = Color.White)
    }
}

/** The camera row: competition on the left, the clock (and its added-time tab) on the right. */
@Composable
private fun StripRow(g: Sports.Game, left: String = g.league.name.uppercase(), right: @Composable RowScope.() -> Unit = { ClockTab(g) }) {
    // Two halves either side of the camera, so nothing ever runs under it.
    Row(Modifier.fillMaxWidth().height(Strip).padding(start = 22.dp, end = 18.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, style = caps(9.5f), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(40.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = right)
    }
}

@Composable
private fun RowScope.ClockTab(g: Sports.Game) {
    if (g.live) LiveDot()
    Text(if (g.state == "pre") g.clock else g.clock.take(10), color = Color.White, maxLines = 1, style = label().copy(fontFamily = Display, fontSize = 12.sp), modifier = Modifier.padding(start = 5.dp))
    if (g.added.isNotBlank()) Text("+${g.added}", color = Color.White, style = caps(9.5f), modifier = Modifier.padding(start = 5.dp)
        .clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(listOf(Orange, Purple))).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
private fun LiveDot() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(6.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Red))
}

@Composable
private fun MiniCard(kind: String, h: Dp = 11.dp) =
    Box(Modifier.size(h * 0.72f, h).graphicsLayer { rotationZ = -8f }.clip(RoundedCornerShape(2.dp)).background(if (kind == "yellow") CardYellow else CardRed))

@Composable
private fun Countdown(until: Long, color: Color, size: Int) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) { while (now < until) { delay(500); now = System.currentTimeMillis() } }
    val left = ((until - now) / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(left / 60, left % 60), color = color, maxLines = 1, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = size.sp))
}

private fun DrawScope.image(i: ImageBitmap, cx: Float, cy: Float, box: Float, alpha: Float = 1f, filter: ColorFilter? = null) {
    val k = box / maxOf(i.width, i.height).toFloat()
    val w = i.width * k; val h = i.height * k
    drawImage(i, IntOffset.Zero, IntSize(i.width, i.height), IntOffset((cx - w / 2).toInt(), (cy - h / 2).toInt()), IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
        alpha = alpha.coerceIn(0f, 1f), colorFilter = filter)
}

// ============================================================================ compact

/**
 * The resting score: the scorebar shrunk to the notch — kit-coloured halves either side of the camera, badges and
 * scores. Every so often (and whenever it first appears) it re-introduces the teams by name, like the broadcast bug.
 */
@Composable
internal fun SportsCompact(g: Sports.Game, h: Dp) {
    val hc = kit(g.home); val ac = kit(g.away)
    var names by remember(g.id) { mutableStateOf(true) }
    LaunchedEffect(g.id) { delay(3200); names = false; while (true) { delay(45_000); names = true; delay(3200); names = false } }
    val cricket = isCricket(g)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            drawRect(Brush.horizontalGradient(0f to hc.light(0.05f), 0.36f to hc.deep(0.55f).copy(alpha = 0.0f), 0.64f to ac.deep(0.55f).copy(alpha = 0f), 1f to ac.light(0.05f)))
            drawRect(Brush.horizontalGradient(0f to hc.copy(alpha = 0.55f), 0.4f to Color.Transparent, 0.6f to Color.Transparent, 1f to ac.copy(alpha = 0.55f)))
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent)), size = Size(w, size.height))
        }
        Compact({
            TeamBadge(g.home, h - 8.dp, hc.light(0.35f))
            Spacer(Modifier.width(7.dp))
            AnimatedContent(names, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "hn") { n ->
                if (n) Text(g.home.abbr.uppercase(), color = Color.White, style = caps(12f))
                else ScoreNum(g.home.text, heavy(if (cricket) 15 else 19), Color.White)
            }
        }, {
            val timed = g.cards.lastOrNull { it.until > System.currentTimeMillis() }
            when {
                timed != null -> Row(Modifier.padding(end = 7.dp), verticalAlignment = Alignment.CenterVertically) { MiniCard(timed.kind); Spacer(Modifier.width(4.dp)); Countdown(timed.until, Dim, 10) }
                g.note.contains("to come") -> Text("CONV", color = Ember, style = caps(9f), modifier = Modifier.padding(end = 7.dp))
                g.added.isNotBlank() -> Text("+${g.added}", color = Color.White, style = caps(9f), modifier = Modifier.padding(end = 7.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(Orange, Purple))).padding(horizontal = 4.dp))
                !cricket && g.live -> Text(g.clock.take(6), color = Dim, style = label().copy(fontSize = 10.sp), modifier = Modifier.padding(end = 7.dp))
            }
            AnimatedContent(names, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "an") { n ->
                if (n) Text(g.away.abbr.uppercase(), color = Color.White, style = caps(12f))
                else ScoreNum(g.away.text, heavy(if (cricket) 15 else 19), Color.White)
            }
            Spacer(Modifier.width(7.dp))
            TeamBadge(g.away, h - 8.dp, ac.light(0.35f))
        })
    }
}

// ============================================================================ open board

/** The open board: camera row, the scorebar, and underneath it the panels on rotation (stats by ESPN, last play, cards, names). */
@Composable
internal fun SportsOpen(g: Sports.Game, more: Int = 0, index: Int = 0) {
    val kinds = infoKinds(g)
    var i by remember(g.id) { mutableStateOf(0) }
    LaunchedEffect(g.id, kinds.size) { while (true) { delay(3600); i++ } }
    Column(Modifier.fillMaxSize()) {
        StripRow(g, left = g.league.name.uppercase() + if (more > 1) "  ·  ${index + 1}/$more" else "")
        Scorebar(g, Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(BarH).rise(0))
        Box(Modifier.fillMaxWidth().weight(1f).padding(start = 30.dp, end = 30.dp, bottom = 10.dp).rise(1)) {
            AnimatedContent(kinds[i % kinds.size], transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 260f)) { -it / 2 } + fadeIn(tween(220))) togetherWith (fadeOut(tween(160)))
            }, label = "info") { k ->
                when (k) {
                    "stats" -> EspnStats(g)
                    "play" -> InfoPanel(Color(0x1FFFFFFF)) {
                        Text((when (g.league.kind) { Sports.Kind.SOCCER -> "⚽  "; Sports.Kind.RUGBY -> "🏉  "; Sports.Kind.CRICKET -> "🏏  "; else -> "" }) + g.lastPlay,
                            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp))
                        if (g.note.isNotBlank()) Text(g.note.uppercase(), color = Ember, style = caps(9f), modifier = Modifier.padding(top = 3.dp))
                    }
                    "cards" -> InfoPanel(Color(0x1FFFFFFF)) {
                        g.cards.takeLast(2).forEach { cd ->
                            val side = if (cd.home) g.home else g.away
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                MiniCard(cd.kind, 13.dp); Spacer(Modifier.width(8.dp))
                                Text("${cd.player} · ${side.abbr}", color = Color.White, style = label().copy(fontSize = 12.sp))
                                if (cd.until > System.currentTimeMillis()) { Spacer(Modifier.width(8.dp)); Countdown(cd.until, if (cd.kind == "yellow") CardYellow else CardRed, 13) }
                                else if (cd.kind == "red") Text("  off", color = Dim, style = label().copy(fontSize = 11.sp))
                            }
                        }
                    }
                    else -> Names(g)
                }
            }
        }
    }
}

/** A panel that hangs under the scorebar (square-ish top, rounded bottom). */
@Composable
private fun InfoPanel(bg: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp)).background(bg).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)
}

/** "Stats brought to you by ESPN": the red panel, faint pitch markings, three stats with split bars, the credit. */
@Composable
private fun EspnStats(g: Sports.Game) {
    val grow = remember(g.id) { Animatable(0f) }
    LaunchedEffect(g.id) { grow.animateTo(1f, tween(700, 150, FastOutSlowInEasing)) }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(EspnRed.deep(0.12f), EspnRed)))
            val line = Color.Black.copy(alpha = 0.12f); val sw = 1.dp.toPx()
            drawCircle(line, size.height * 0.9f, Offset(size.width * 0.5f, size.height * 0.95f), style = Stroke(sw))
            drawLine(line, Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height), sw)
            drawLine(line, Offset(0f, size.height * 0.2f), Offset(size.width * 0.3f, size.height), sw)
            drawCircle(line, size.height * 0.4f, Offset(size.width * 0.93f, size.height * 0.2f), style = Stroke(sw))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 7.dp)) {
            g.stats.take(3).forEach { st ->
                Row(Modifier.fillMaxWidth().height(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(st.home, color = Color.White, style = label().copy(fontSize = 11.sp, fontFamily = Display), modifier = Modifier.width(30.dp))
                    val tot = (st.h + st.a).takeIf { it > 0f } ?: 1f
                    Canvas(Modifier.weight(1f).height(4.dp)) {
                        val mid = size.width * (st.h / tot); val r = size.height / 2
                        drawRoundRect(Color.Black.copy(alpha = 0.2f), cornerRadius = CornerRadius(r))
                        drawRoundRect(Color.White, Offset(mid - mid * grow.value, 0f), Size((mid * grow.value - 1.dp.toPx()).coerceAtLeast(0f), size.height), CornerRadius(r))
                        drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(mid + 1.dp.toPx(), 0f), Size(((size.width - mid) * grow.value - 1.dp.toPx()).coerceAtLeast(0f), size.height), CornerRadius(r))
                    }
                    Text(st.label, color = Color.White, maxLines = 1, textAlign = TextAlign.Center, style = label().copy(fontSize = 10.sp), modifier = Modifier.width(76.dp))
                    Spacer(Modifier.weight(1f))
                    Text(st.away, color = Color.White, style = label().copy(fontSize = 11.sp, fontFamily = Display), textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("STATS BROUGHT TO YOU BY ", color = Color.White.copy(alpha = 0.85f), style = caps(7.5f))
                Text("ESPN", color = Color.White, style = heavy(11, italic = true))
            }
        }
    }
}

/** The teams by name — the occasional reminder of who's playing. */
@Composable
private fun Names(g: Sports.Game) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        listOf(g.home, g.away).forEachIndexed { k, sd ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(sd.name.uppercase(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = heavy(15))
                Box(Modifier.padding(top = 4.dp).size(28.dp, 3.dp).clip(CircleShape).background(kit(sd).light(0.2f)))
            }
            if (k == 0) Text("v", color = Faint, style = label())
        }
    }
}

// ============================================================================ moments

@Composable
internal fun Moment(s: Sports.Score) {
    when (s.kind) {
        "intro", "fulltime" -> IntroBoard(s)
        "added" -> AddedTime(s)
        "check", "decision" -> Review(s)
        "lineup" -> LineupReel(s)
        "yellow", "red", "red20" -> CardMoment(s)
        "sub" -> SubBoard(s)
        else -> ScoreMoment(s)
    }
}

/** Inside the island, under the camera row: where the bar sits during a moment. */
@Composable
private fun MomentFrame(g: Sports.Game, top: String, right: @Composable RowScope.() -> Unit = { ClockTab(g) }, bar: @Composable BoxScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        StripRow(g, top, right)
        Box(Modifier.fillMaxWidth().weight(1f).padding(start = 8.dp, end = 8.dp, bottom = 12.dp), content = bar)
    }
}

// ---- competition intro / full time

/**
 * "A logo for every competition": a black bar; the competition's logo arrives in the middle with a flare, both
 * teams slide out from behind it to their ends and are named; then the kit colours pour out from the seam and the
 * bar becomes the scorebar. Full time is the same bookend, with the final score and the winner lit.
 */
@Composable
private fun IntroBoard(s: Sports.Score) {
    val g = s.game
    val t by rememberClock(s.at)
    val ft = s.kind == "fulltime"
    val logo = rememberLogo(g.leagueLogo)
    MomentFrame(g, "${g.league.name.uppercase()}  ·  ${s.label}") {
        val pour = seg(t, 2.7f, 3.5f, ::easeInOut)
        Box(Modifier.fillMaxSize()) {
            // the black bar
            Canvas(Modifier.fillMaxSize().graphicsLayer { val k = 0.9f + 0.1f * seg(t, 0f, 0.45f, ::backOut); scaleX = k; alpha = seg(t, 0f, 0.25f) }) {
                val r = size.height / 2
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF1A1A1C), Color(0xFF050506))), cornerRadius = CornerRadius(r))
                drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = CornerRadius(r), style = Stroke(1.dp.toPx()))
                if (pour > 0f) halves(kit(g.home), kit(g.away), pour, pour)
            }
            // the badges slide out from behind the logo
            val out = seg(t, 0.55f, 1.15f, ::backOut)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val travel = maxWidth / 2 - 44.dp
                listOf(g.home to -1f, g.away to 1f).forEach { (sd, dir) ->
                    val win = ft && ((dir < 0 && g.home.score > g.away.score) || (dir > 0 && g.away.score > g.home.score))
                    Column(Modifier.align(Alignment.Center).graphicsLayer {
                        translationX = dir * travel.toPx() * out; alpha = out.coerceIn(0f, 1f); rotationZ = (1f - out) * 20f * dir
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        TeamBadge(sd, 54.dp, if (win) Ember else kit(sd).light(0.35f))
                    }
                    // names under the logo side, then the score once the colours pour
                    val nameA = seg(t, 1.1f, 1.6f) * (1f - pour)
                    Text(sd.name.uppercase(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = heavy(14),
                        modifier = Modifier.align(Alignment.Center).width(96.dp).graphicsLayer {
                            translationX = dir * (travel.toPx() * 0.48f); alpha = nameA; translationY = (1f - seg(t, 1.1f, 1.6f)) * 8.dp.toPx()
                        }, textAlign = TextAlign.Center)
                    val c = kit(sd)
                    Text(sd.text, color = inkOn(c), maxLines = 1, style = heavy(if (isCricket(g)) 26 else 44),
                        modifier = Modifier.align(Alignment.Center).graphicsLayer {
                            translationX = dir * (travel.toPx() * 0.5f); alpha = pour; val k = 0.7f + 0.3f * pour; scaleX = k; scaleY = k
                        })
                }
            }
            // the competition logo with its flare
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val a = seg(t, 0.05f, 0.5f)
                val ring = seg(t, 0.15f, 1.0f)
                if (ring in 0.01f..0.99f) drawCircle(Color.White.copy(alpha = 0.6f * (1f - ring)), (20 + 80 * ring).dp.toPx(), c, style = Stroke((2.5f * (1f - ring) + 0.5f).dp.toPx()))
                logo?.let { img ->
                    val box = (64 * (0.4f + 0.6f * seg(t, 0f, 0.55f, ::backOut))).dp.toPx()
                    image(img, c.x, c.y, box, a, if (pour > 0.5f) ColorFilter.tint(Color.White, BlendMode.SrcIn) else null)
                }
            }
            if (logo == null) Text(g.league.name.uppercase(), color = Color.White, style = heavy(12), modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = seg(t, 0.05f, 0.5f) })
        }
    }
}

// ---- added time

/** "+4 Minutes Of Added Time": the bar unrolls from the left in orange → violet → orange, the number flips up, the words wipe in. */
@Composable
private fun AddedTime(s: Sports.Score) {
    val g = s.game
    val n = s.extra.toIntOrNull() ?: 0
    val t by rememberClock(s.at)
    MomentFrame(g, g.league.name.uppercase()) {
        val unroll = seg(t, 0f, 0.6f, ::backOut)
        val leave = seg(t, 4.4f, 5.0f, ::easeInOut)
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = (unroll * (1f - leave)).coerceAtLeast(0.001f); transformOrigin = TransformOrigin(0f, 0.5f) }) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val x = (t * 0.25f % 1f) * w * 2
                drawRoundRect(Brush.horizontalGradient(listOf(Orange, Purple, Orange, Purple, Orange), -2 * w + x, 2 * w + x, TileMode.Repeated), cornerRadius = CornerRadius(size.height / 2))
                drawRoundRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.2f), 0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.15f)), cornerRadius = CornerRadius(size.height / 2))
            }
            Row(Modifier.fillMaxSize().padding(start = 22.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (g.leagueLogo.isNotBlank()) LogoImage(g.leagueLogo, 50.dp, Modifier.graphicsLayer { val k = seg(t, 0.3f, 0.8f, ::backOut); scaleX = k; scaleY = k; rotationZ = (1f - k) * -30f }, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                val shownN = (n * seg(t, 0.6f, 1.3f)).toInt().coerceAtMost(n)
                Text("+$shownN", color = Color.White, style = heavy(36), modifier = Modifier.graphicsLayer { translationY = (1f - seg(t, 0.5f, 0.9f, ::backOut)) * 20.dp.toPx(); alpha = seg(t, 0.5f, 0.8f) })
                Spacer(Modifier.width(8.dp))
                val wipe = seg(t, 0.9f, 1.7f)
                Text("Minutes Of Added Time", color = Color.White, maxLines = 1, style = heavy(20), modifier = Modifier.drawWithContent {
                    clipRect(right = size.width * wipe) { this@drawWithContent.drawContent() }
                })
            }
        }
    }
}

// ---- VAR / TMO

/**
 * Checking: VAR on deep purple with the call in silver, TMO on blue with the call in orange — a scan line reads
 * across. Decision: the bar flips over to the favoured team's colours with their badge, the call, and a stamp.
 */
@Composable
private fun Review(s: Sports.Score) {
    val g = s.game
    val tmo = s.extra == "TMO" || s.label == "TMO"
    val decision = s.kind == "decision"
    val t by rememberClock(s.at)
    val favoured = when (s.favour) { 1 -> g.home; 0 -> g.away; else -> null }
    MomentFrame(g, if (decision) "${if (tmo) "TMO" else "VAR"} · CHECK COMPLETE" else "${if (tmo) "TMO" else "VAR"} · CHECKING" + ".".repeat(1 + (t * 3).toInt() % 3)) {
        // decision: the board flips over
        val flip = if (decision) seg(t, 0.15f, 0.75f, ::easeInOut) else 1f
        val face = if (decision && flip < 0.5f) 0 else 1
        Box(Modifier.fillMaxSize().graphicsLayer {
            rotationX = if (decision) (if (flip < 0.5f) flip * 180f else (flip - 1f) * 180f) else 0f
            cameraDistance = 12f * density
            val drop = seg(t, 0f, 0.45f, ::backOut); translationY = if (decision) 0f else (1f - drop) * -24.dp.toPx(); alpha = if (decision) 1f else seg(t, 0f, 0.2f)
        }) {
            if (!decision || face == 0) {
                // the check face
                Canvas(Modifier.fillMaxSize()) {
                    val r = CornerRadius(size.height / 2)
                    if (tmo) drawRoundRect(Brush.linearGradient(listOf(Color(0xFF2B5BE6), Color(0xFF1A1A64), Color(0xFF1A1A64), Color(0xFF2B5BE6)), Offset.Zero, Offset(size.width, size.height)), cornerRadius = r)
                    else drawRoundRect(VarPurple, cornerRadius = r)
                    val x = ((t * 0.7f) % 1f) * (size.width + 200f) - 100f
                    drawRoundRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent), x - 70f, x + 70f), cornerRadius = r)
                    drawRoundRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.12f), 0.4f to Color.Transparent), cornerRadius = r)
                }
                val reason = if (decision) "Checking" else s.who
                Row(Modifier.fillMaxSize().padding(start = 26.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (tmo) "TMO" else "VAR", color = if (tmo) TmoOrange else Color.White, style = heavy(38, italic = !tmo).copy(letterSpacing = 2.sp))
                    Spacer(Modifier.width(20.dp))
                    val wipe = seg(t, 0.35f, 1.0f)
                    Text(reason, maxLines = 1, overflow = TextOverflow.Ellipsis, style = heavy(if (reason.length > 16) 22 else if (reason.length > 11) 25 else 32).copy(
                        brush = if (tmo) SolidColor(TmoOrange) else Brush.verticalGradient(listOf(Color.White, Color(0xFFB3B3BC), Color(0xFFE6E6EA)))),
                        modifier = Modifier.drawWithContent { clipRect(right = size.width * wipe) { this@drawWithContent.drawContent() } })
                }
            } else {
                // the decision face
                val fc = favoured?.let { kit(it) } ?: (if (tmo) Color(0xFF2B5BE6) else VarPurple)
                val fromLeft = s.favour != 0
                val pour = seg(t, 0.75f, 1.35f)
                Canvas(Modifier.fillMaxSize()) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(if (tmo) Color(0xFF1A1A64) else VarPurple, cornerRadius = r)
                    clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r)) }) {
                        val w = size.width * pour
                        drawRect(Brush.verticalGradient(listOf(fc.deep(0.4f), fc.light(0.05f))), Offset(if (fromLeft) 0f else size.width - w, 0f), Size(w, size.height))
                    }
                    drawRoundRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.18f), 0.4f to Color.Transparent), cornerRadius = r)
                }
                val ink = if (pour > 0.5f) inkOn(fc) else Color.White
                Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (favoured != null) TeamBadge(favoured, 56.dp, fc.light(0.35f), Modifier.graphicsLayer { val k = seg(t, 0.9f, 1.4f, ::backOut); scaleX = k; scaleY = k; alpha = k.coerceIn(0f, 1f) })
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(s.who, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = heavy(if (s.who.length > 14) 22 else 30))
                        if (favoured != null) Text("IN FAVOUR OF ${favoured.name.uppercase()}", color = ink.copy(alpha = 0.8f), maxLines = 1, style = caps(9.5f),
                            modifier = Modifier.graphicsLayer { alpha = seg(t, 1.2f, 1.6f) })
                    }
                    Box(Modifier.size(42.dp).graphicsLayer { val k = seg(t, 1.3f, 1.8f, ::backOut); scaleX = k; scaleY = k; rotationZ = (1f - k) * -40f }
                        .clip(CircleShape).background(if (s.good) Mint else Red), contentAlignment = Alignment.Center) {
                        Icon(if (s.good) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

// ---- lineups

/** Lineup: the team's colours, its logo breaking out of the bar, then each player — "No.6 / SIYA KOLISI" — and the bench. */
@Composable
private fun LineupReel(s: Sports.Score) {
    val g = s.game
    val side = if (s.home) g.home else g.away
    val tc = kit(side)
    val starters = s.players.filter { it.starter }; val bench = s.players.filter { !it.starter }
    var idx by remember(s.at) { mutableStateOf(-1) }
    LaunchedEffect(s.at) {
        delay(900)
        for (i in s.players.indices) { idx = i; delay(if (s.players[i].starter) 850 else 500); if (i == starters.size - 1 && bench.isNotEmpty()) delay(200) }
    }
    val t by rememberClock(s.at)
    val logo = rememberLogo(side.logo)
    val p = s.players.getOrNull(idx)
    val onBench = p != null && !p.starter
    val dark by animateFloatAsState(if (onBench) 0.28f else 0f, tween(600), label = "bench")
    Column(Modifier.fillMaxSize()) {
        StripRow(g, listOf(if (onBench) "BENCH" else s.label, side.name.uppercase(), s.extra).filter { it.isNotBlank() }.joinToString("  ·  ")) {
            if (p != null) Text(if (onBench) "${idx - starters.size + 1} / ${bench.size}" else "${idx + 1} / ${starters.size}", color = Dim, style = label().copy(fontFamily = Display, fontSize = 12.sp))
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
            val unroll = seg(t, 0f, 0.6f, ::backOut)
            Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = unroll.coerceAtLeast(0.01f); transformOrigin = TransformOrigin(0f, 0.5f) }) {
                val r = CornerRadius(size.height / 2); val edge = tc.light(0.42f)
                val drift = sin(t * 0.6f) * size.width * 0.08f
                drawRoundRect(Brush.horizontalGradient(listOf(edge, tc, tc.light(0.08f), tc, edge), drift, size.width + drift), cornerRadius = r)
                drawRoundRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.18f), 0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.12f)), cornerRadius = r)
                if (dark > 0f) drawRoundRect(Color.Black.copy(alpha = dark), cornerRadius = r)
            }
            // the logo breaks out of the bar, over the left end
            if (logo != null) Canvas(Modifier.fillMaxSize()) {
                val k = seg(t, 0.25f, 0.95f, ::backOut)
                image(logo, 58.dp.toPx() - (1f - k) * 80.dp.toPx(), size.height / 2 - 4.dp.toPx(), 136.dp.toPx() * (0.85f + 0.15f * k), k)
            } else TeamBadge(side, 64.dp, tc.light(0.35f), Modifier.align(Alignment.CenterStart).padding(start = 18.dp))
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 22.dp).width(200.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(idx, transitionSpec = {
                    (slideInVertically(spring(dampingRatio = 0.72f, stiffness = 380f)) { it / 2 } + fadeIn(tween(160))) togetherWith (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(140)))
                }, label = "player") { i ->
                    val pl = s.players.getOrNull(i)
                    val ink = Color.White
                    val wipe by produceState(0f, i) { val st = withFrameNanos { it }; while (value < 1f) withFrameNanos { value = ((it - st) / 4.2e8f).coerceAtMost(1f) } }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (pl == null) side.name.uppercase() else "No.${pl.no}", color = ink, maxLines = 1,
                            style = heavy(if (pl == null) 28 else 54).copy(shadow = Shadow(Color.Black.copy(alpha = 0.25f), Offset(0f, 3f), 8f)))
                        if (pl != null) Text(pl.name.uppercase(), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = heavy(19, italic = true).copy(letterSpacing = 0.5.sp, shadow = Shadow(Color.Black.copy(alpha = 0.25f), Offset(0f, 2f), 6f)),
                            modifier = Modifier.drawWithContent { clipRect(right = size.width * easeOut(wipe)) { this@drawWithContent.drawContent() } })
                    }
                }
            }
        }
    }
}

// ---- cards & substitutions

/** A card: the bar goes dark with the card's colour glowing from the team's end; the card turns over into view; the player; rugby's clock. */
@Composable
private fun CardMoment(s: Sports.Score) {
    val g = s.game
    val side = if (s.home) g.home else g.away
    val yellow = s.kind == "yellow"
    val cc = if (yellow) CardYellow else CardRed
    val rugby = isRugby(g)
    val t by rememberClock(s.at)
    MomentFrame(g, "${side.name.uppercase()}  ·  ${s.minute}") {
        Canvas(Modifier.fillMaxSize()) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF1C1C20), Color(0xFF0A0A0C))), cornerRadius = r)
            clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r)) }) {
                val o = Offset(size.width * 0.12f, size.height * 0.5f)
                drawCircle(Brush.radialGradient(listOf(cc.copy(alpha = 0.55f * seg(t, 0.1f, 0.6f)), Color.Transparent), o, size.width * 0.55f), size.width * 0.55f, o)
                val gap = 24.dp.toPx(); var x = -size.height + (t * 0.6f % 1f) * gap
                while (x < size.width + size.height) { drawLine(cc.copy(alpha = 0.07f), Offset(x, size.height), Offset(x + size.height, 0f), 8.dp.toPx()); x += gap }
            }
            drawRoundRect(cc.copy(alpha = 0.5f * seg(t, 0.1f, 0.6f)), cornerRadius = r, style = Stroke(1.2.dp.toPx()))
        }
        Row(Modifier.fillMaxSize().padding(start = 26.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            val f = seg(t, 0.15f, 0.85f, ::backOut)
            Box(Modifier.size(42.dp, 58.dp).graphicsLayer {
                rotationY = (1f - f) * -110f; rotationZ = -8f + (1f - f) * -24f; cameraDistance = 14f * density
                val k = 0.6f + 0.4f * f; scaleX = k; scaleY = k; alpha = f.coerceIn(0f, 1f)
            }.clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(cc.light(0.25f), cc, cc.deep(0.2f))))) {
                Canvas(Modifier.fillMaxSize()) {
                    val sx = seg(t, 0.9f, 1.6f) * (size.width + size.height * 2) - size.height
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.6f), Color.Transparent), Offset(sx - 30f, 0f), Offset(sx + 30f, size.height)))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).graphicsLayer { val w = seg(t, 0.4f, 0.9f); alpha = w; translationX = (1f - w) * 24.dp.toPx() }) {
                Text(s.label, color = cc, maxLines = 1, style = heavy(28))
                if (s.who.isNotBlank()) Text(s.who, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = label().copy(fontSize = 15.sp))
                val tag = when { rugby && yellow -> "SIN BIN"; rugby && s.kind == "red20" -> "REPLACEMENT IN"; rugby -> "OFF FOR THE MATCH"; !yellow -> "SENT OFF"; else -> "" }
                if (tag.isNotEmpty()) Text(tag, color = Faint, style = caps(9f), modifier = Modifier.padding(top = 2.dp))
            }
            if (s.until > 0) Countdown(s.until, cc, 26) else TeamBadge(side, 46.dp)
        }
    }
}

/** Substitution / replacement board: an LED board in the bar's shape; numbers flicker on — green arrow on, red arrow off. */
@Composable
private fun SubBoard(s: Sports.Score) {
    val g = s.game
    val side = if (s.home) g.home else g.away
    val t by rememberClock(s.at)
    val green = Color(0xFF3DFF7A); val red = Color(0xFFFF4D4D)
    fun led(c: Color) = Shadow(c.copy(alpha = 0.8f), Offset.Zero, 14f)
    MomentFrame(g, "${s.label}  ·  ${side.name.uppercase()}", right = { Text(s.minute, color = Ember, style = label().copy(fontFamily = Display)) }) {
        val rise = seg(t, 0f, 0.6f, ::backOut)
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = (1f - rise) * 40.dp.toPx(); alpha = rise.coerceIn(0f, 1f) }.clip(RoundedCornerShape(28.dp)).background(Color(0xFF040506))) {
            Canvas(Modifier.fillMaxSize()) {
                val st = 4.dp.toPx(); var y = st / 2
                while (y < size.height) { var x = st / 2; while (x < size.width) { drawCircle(Color.White.copy(alpha = 0.045f), 0.7.dp.toPx(), Offset(x, y)); x += st }; y += st }
                drawRoundRect(kit(side).copy(alpha = 0.5f), cornerRadius = CornerRadius(28.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            }
            Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamBadge(side, 44.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    s.subs.forEachIndexed { i, (on, off) ->
                        val v = seg(t, 0.6f + i * 0.7f, 0.9f + i * 0.7f)
                        val flick = if (v < 1f) (if (sin(t * 90f + i) > 0f) 1f else 0.25f) * v else 1f
                        Column(Modifier.padding(vertical = 2.dp).graphicsLayer { alpha = flick }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("▲", color = green, style = TextStyle(fontSize = 12.sp, shadow = led(green))); Spacer(Modifier.width(8.dp))
                                Text(on.uppercase(), color = green, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 18.sp, letterSpacing = 1.sp, shadow = led(green)))
                            }
                            if (off.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("▼", color = red, style = TextStyle(fontSize = 12.sp, shadow = led(red))); Spacer(Modifier.width(8.dp))
                                Text(off.uppercase(), color = red, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 18.sp, letterSpacing = 1.sp, shadow = led(red)))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- the score moment

/**
 * GOAL / TRY / SIX… inside the board:
 *  1. a flash runs across the bar and it grows;
 *  2. the scorer's colours sweep across from their end on a slanted edge;
 *  3. their logo breaks out of the bar with a shockwave (and a motion trail);
 *  4. the team's look plays — its own moment (cannons, birds, cinematic), the word wall from the TRY board,
 *     the logo peeking in, a burst, or its logo tiled — taking turns so two scores never look the same;
 *  5. the supporters' word flies through (BOKKE, COYS…), then the big word lands letter by letter, scorer under it;
 *  6. the colour sweeps off the far end, the scorebar is back, and the number rolls over.
 */
@Composable
private fun ScoreMoment(s: Sports.Score) {
    val g = s.game
    val side = if (s.home) g.home else g.away
    val tc = kit(side)
    val light = tc.light(0.4f)
    val t by rememberClock(s.at)
    val wAt = wordAt(s); val hold = holdFor(s)
    val outAt = wAt + hold
    val logo = rememberLogo(side.logo)
    val motif = if (s.motif == "logopeek" && side.logo.isBlank()) "burst" else s.motif
    val dir = if (s.home) 1f else -1f
    val tm = rememberTextMeasurer()
    val ink = inkOn(tc)
    val wall = remember(s.label, ink) { tm.measure(s.label, heavy(20).copy(drawStyle = Stroke(2.2f))) }
    val u1 = with(LocalDensity.current) { 1.dp.toPx() }
    val shake = if (motif == "cannons") cannonShake(t, u1) else Offset.Zero
    MomentFrame(g, "${side.name.uppercase()}  ·  ${s.label}") {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = shake.x; translationY = shake.y }) {
            // the board underneath; its number rolls after the colour has gone
            Scorebar(g, Modifier.fillMaxSize(), roll = s, rollAfterMs = ((outAt + 0.7f) * 1000).toLong(), badgeSize = 64.dp)
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val r = CornerRadius(h / 2)
                val pill = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, r)) }
                val slant = h * 0.45f
                val span = w + slant * 2
                val lead = seg(t, 0.12f, 0.62f, ::easeInOut) * span - slant
                val tail = seg(t, outAt, outAt + 0.6f, ::easeInOut) * span - slant
                fun x(v: Float) = if (dir > 0) v else w - v
                if (lead > tail) clipPath(pill) {
                    val path = Path().apply {
                        moveTo(x(tail + slant), 0f); lineTo(x(lead + slant), 0f); lineTo(x(lead), h); lineTo(x(tail), h); close()
                    }
                    clipPath(path) {
                        drawRect(Brush.horizontalGradient(listOf(tc.light(0.38f), tc, tc.light(0.1f), tc, tc.light(0.38f))))
                        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.18f), 0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.15f)))
                        when (motif) {
                            "wordwall" -> drawWordScatter(wall, t, ink)
                            "logos", "" -> logo?.let { tileLogo(it, t, dir) }
                            "cinematic" -> drawCinematic(t, logo, s.home, tc)
                            "logopeek" -> logo?.let { drawLogoPeek(t, it, s.home) }
                            else -> {}
                        }
                        // speed streaks
                        for (i in 0 until 4) {
                            val sy = h * (0.18f + i * 0.21f)
                            val sx = ((t * 0.9f + i * 0.27f) % 1f) * (w + 160f) - 80f
                            drawLine(Color.White.copy(alpha = 0.16f), Offset(x(sx) - 60f * dir, sy), Offset(x(sx) + 60f * dir, sy), 2.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    // the club's own moment over the colour
                    when (motif) {
                        "cannons" -> drawCannons(t, dir)
                        "gulls", "magpies", "eagles" -> drawBirds(t, dir, birdLook(motif), if (motif == "eagles") 6 else 10)
                        "burst" -> drawBurst(t, Offset(if (s.home) 64.dp.toPx() else w - 64.dp.toPx(), h / 2), tc, light)
                    }
                }
                // the flash that starts it
                val fl = seg(t, 0f, 0.3f)
                if (fl < 1f) clipPath(pill) {
                    val fx = fl * (w + 200f) - 100f
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f * (1f - fl)), Color.Transparent), fx - 90f, fx + 90f))
                }
                // shockwave round the logo
                val ring = seg(t, 0.4f, 1.2f)
                val bx = if (s.home) 64.dp.toPx() else w - 64.dp.toPx()
                if (ring in 0.01f..0.99f) drawCircle(Color.White.copy(alpha = 0.75f * (1f - ring)), (30 + 120 * ring).dp.toPx(), Offset(bx, h / 2), style = Stroke((3f * (1f - ring) + 0.5f).dp.toPx()))
            }
            // the logo breaks out of the bar (it's bigger than the bar), with a trail
            if (logo != null && motif != "logopeek") Canvas(Modifier.fillMaxSize()) {
                val b = seg(t, 0.35f, 0.85f, ::backOut)
                val leave = seg(t, outAt, outAt + 0.5f)
                val cx = if (s.home) 64.dp.toPx() else size.width - 64.dp.toPx(); val cy = size.height / 2
                val box = 118.dp.toPx() * (1.5f - 0.5f * b)
                for (i in 1..3) if (b < 0.98f) image(logo, cx - dir * i * 26.dp.toPx() * (1f - b), cy, box, 0.28f * (1f - b) / i)
                rotate((1f - b) * -14f * dir, Offset(cx, cy)) { image(logo, cx - dir * leave * 60.dp.toPx(), cy, box, b * (1f - leave)) }
            }
            // the words
            Box(Modifier.fillMaxSize().padding(start = if (s.home) 120.dp else 16.dp, end = if (s.home) 16.dp else 120.dp)
                .graphicsLayer { val leave = seg(t, outAt, outAt + 0.45f); alpha = 1f - leave; translationX = dir * leave * 60.dp.toPx() }, contentAlignment = Alignment.Center) {
                // supporters' word flies through first
                if (s.chant.isNotEmpty()) {
                    val c = seg(t, 0.55f, 0.85f) + seg(t, 1.3f, 1.55f)
                    if (c in 0.001f..1.999f) {
                        val x = if (c < 1f) (1f - c) * -1f else (c - 1f)
                        Text(s.chant, color = ink, maxLines = 1, modifier = Modifier.graphicsLayer {
                            translationX = x * dir * 240.dp.toPx(); alpha = 1f - abs(x) * 0.8f; scaleX = 1f + abs(x) * 0.25f
                        }, style = heavy(if (s.chant.length > 8) 30 else 42, italic = true).copy(letterSpacing = 3.sp))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row {
                        // fits the space beside the logo: TRY / GOAL big, CONVERTED / PENALTY smaller
                        val fs = minOf(64, (212f / (s.label.length * 0.68f)).toInt())
                        s.label.forEachIndexed { i, ch ->
                            val v = seg(t, wAt + i * 0.06f, wAt + i * 0.06f + 0.4f, ::backOut)
                            Text(ch.toString(), color = ink, modifier = Modifier.graphicsLayer {
                                alpha = v.coerceIn(0f, 1f); translationY = (1f - v) * 36.dp.toPx(); val k = 0.6f + 0.4f * v; scaleX = k; scaleY = k
                            }, style = heavy(fs).copy(letterSpacing = 1.sp))
                        }
                    }
                    if (s.who.isNotBlank()) Text(s.who.uppercase(), color = ink.copy(alpha = seg(t, wAt + 0.35f, wAt + 0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = caps(10.5f), modifier = Modifier.graphicsLayer { translationY = (1f - seg(t, wAt + 0.35f, wAt + 0.7f)) * 8.dp.toPx() })
                }
            }
        }
    }
}

/** The TRY board: the word outlined over and over, scattered and tilted, drifting slowly behind the big word. */
private fun DrawScope.drawWordScatter(word: androidx.compose.ui.text.TextLayoutResult, t: Float, ink: Color) {
    val rnd = java.util.Random(7L)
    val n = 46
    val ww = word.size.width.toFloat(); val wh = word.size.height.toFloat()
    for (i in 0 until n) {
        val x = rnd.nextFloat() * (size.width + ww) - ww / 2 + sin(t * 0.8f + i) * 4f - t * 10f % 40f
        val y = rnd.nextFloat() * (size.height + wh) - wh / 2
        val appear = seg(t, 0.4f + i * 0.012f, 0.7f + i * 0.012f)
        if (appear <= 0f) continue
        translate(x, y) { rotate(-24f + rnd.nextFloat() * 10f, Offset(ww / 2, wh / 2)) {
            drawText(word, color = ink.copy(alpha = 0.5f * appear))
        } }
    }
}

/** The team's logo tiled at an angle, sliding with the colour. */
private fun DrawScope.tileLogo(img: ImageBitmap, t: Float, dir: Float) {
    val ts = 34.dp.toPx(); val gap = 58.dp.toPx(); val drift = ((t * 0.6f) % 1f) * gap * dir
    var row = 0; var yy = -gap * 0.4f
    while (yy < size.height + gap) {
        var xx = -gap + drift + if (row % 2 == 0) 0f else gap / 2
        while (xx < size.width + gap) { image(img, xx, yy, ts, 0.18f); xx += gap }
        yy += gap * 0.62f; row++
    }
}
