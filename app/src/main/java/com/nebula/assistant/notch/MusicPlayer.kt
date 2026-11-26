package com.nebula.assistant.notch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The music player.
 *
 * Compact: the cover on the left (it flips over to the new cover when the song changes), live bars in the
 * song's own colours on the right, and a hairline of progress along the bottom of the pill.
 *
 * Open: the song's colours become the card — two soft lights drift under a dark glass, no live blur — with the
 * cover on a lifted tile, the title and artist, a scrubber you can tap or drag to seek, and the controls centred
 * underneath: open the app · previous · play/pause (a solid disc that morphs) · next · audio output.
 */

@Composable
internal fun MusicCompact(m: NotchBus.Media, h: Dp) = Box(Modifier.fillMaxSize()) {
    val th = MusicTheme.of(m.art)
    val a by animateColorAsState(th.accent, tween(900), label = "ma")
    val b by animateColorAsState(th.blobs.getOrElse(1) { th.accent }, tween(900), label = "mb")
    val pos = rememberPosition(m)
    // the song's light seeping in from both ends
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to a.copy(alpha = 0.38f), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to b.copy(alpha = 0.38f))))
    Compact({ FlipArt(m, h - 12.dp, 8.dp) }, { Bars(m.playing, listOf(a.light(0.2f), b), Modifier.size(width = 22.dp, height = 14.dp).rise(1)) })
    // hairline of progress
    if (m.duration > 0) Canvas(Modifier.fillMaxSize()) {
        val y = size.height - 1.5.dp.toPx(); val inset = size.height / 2
        drawLine(Color.White.copy(alpha = 0.1f), Offset(inset, y), Offset(size.width - inset, y), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(Brush.horizontalGradient(listOf(a, b)), Offset(inset, y), Offset(inset + (size.width - inset * 2) * pos.frac, y), 1.5.dp.toPx(), StrokeCap.Round)
    }
}

/** The cover; when the song changes it turns over like a card to the new one. */
@Composable
private fun FlipArt(m: NotchBus.Media, size: Dp, radius: Dp) {
    var shown by remember { mutableStateOf(m.art) }
    val flip = remember { Animatable(0f) }
    LaunchedEffect(m.art) {
        if (shown === m.art) return@LaunchedEffect
        flip.animateTo(90f, tween(160, easing = FastOutLinearInEasing)); shown = m.art
        flip.snapTo(-90f); flip.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 380f))
    }
    val img = remember(shown) { shown?.asImageBitmap() }
    Box(Modifier.size(size).graphicsLayer { rotationY = flip.value; cameraDistance = 10f * density }.clip(RoundedCornerShape(radius))) {
        if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(NotchGradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** Little bars, bouncing in the song's colours (still and low when paused). */
@Composable
private fun Bars(playing: Boolean, colors: List<Color>, modifier: Modifier, n: Int = 4) {
    val lite = LocalLite.current
    val t by if (playing && !lite) rememberClock(Unit) else remember { mutableFloatStateOf(0f) }
    val rest by animateFloatAsState(if (playing) 1f else 0f, tween(400), label = "rest")
    Canvas(modifier) {
        val gap = size.width / (n * 2 - 1)
        for (i in 0 until n) {
            val wob = 0.35f + 0.65f * ((sin(t * (6.2f + i * 2.3f) + i * 1.7f) + 1) / 2) * ((sin(t * (2.1f + i) + i) + 3) / 4)
            val amp = 0.22f + (wob - 0.22f).coerceAtLeast(0f) * rest
            val hh = size.height * amp
            drawRoundRect(Brush.verticalGradient(colors), Offset(i * gap * 2, (size.height - hh) / 2), Size(gap, hh), CornerRadius(gap / 2))
        }
    }
}

internal class Position(val ms: Long, val frac: Float)

@Composable
private fun rememberPosition(m: NotchBus.Media): Position {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(m.playing, m.updatedAt) { while (m.playing) { delay(500); now = System.currentTimeMillis() } }
    val pos = (if (m.playing) m.position + (now - m.updatedAt).coerceAtLeast(0) else m.position).let { if (m.duration > 0) it.coerceIn(0, m.duration) else it.coerceAtLeast(0) }
    return Position(pos, if (m.duration > 0) pos.toFloat() / m.duration else 0f)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MusicOpen(m: NotchBus.Media) {
    val ctx = LocalContext.current
    val lite = LocalLite.current
    val tc = m.controller?.transportControls
    val th = MusicTheme.of(m.art)
    val c1 by animateColorAsState(th.blobs.getOrElse(0) { th.accent }, tween(900), label = "c1")
    val c2 by animateColorAsState(th.blobs.getOrElse(1) { th.accent }, tween(900), label = "c2")
    val bg1 by animateColorAsState(th.bg1, tween(900), label = "bg1")
    val bg2 by animateColorAsState(th.bg2, tween(900), label = "bg2")
    val accent by animateColorAsState(th.accent, tween(900), label = "ac")
    val t by if (lite) remember { mutableFloatStateOf(0f) } else rememberClock(Unit)
    val bloom = remember(m.title) { Animatable(0f) }
    LaunchedEffect(m.title) { bloom.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 200f)) }
    val pos = rememberPosition(m)
    var seekFrac by remember { mutableStateOf<Float?>(null) }
    val frac by animateFloatAsState(seekFrac ?: pos.frac, if (seekFrac != null) snap<Float>() else tween<Float>(480, easing = LinearEasing), label = "frac")
    val art = remember(m.art) { m.art?.asImageBitmap() }

    Box(Modifier.fillMaxSize()) {
        // 1. the song's light: its two colours drifting under dark glass
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRect(Brush.linearGradient(listOf(bg1, bg2), Offset.Zero, Offset(w, h)))
            val a1 = Offset(w * (0.2f + 0.08f * sin(t * 0.5f)), h * (0.35f + 0.2f * cos(t * 0.4f)))
            val a2 = Offset(w * (0.82f + 0.08f * cos(t * 0.45f)), h * (0.7f + 0.18f * sin(t * 0.55f)))
            drawCircle(Brush.radialGradient(listOf(c1.copy(alpha = 0.75f), Color.Transparent), a1, w * 0.5f), w * 0.5f, a1)
            drawCircle(Brush.radialGradient(listOf(c2.copy(alpha = 0.6f), Color.Transparent), a2, w * 0.45f), w * 0.45f, a2)
            drawRect(Color.Black.copy(alpha = 0.34f))
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.2f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.25f)))
            // sheen
            val sx = ((t * 0.08f) % 1f) * (w + 500f) - 250f
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent), Offset(sx - 120f, 0f), Offset(sx + 60f, h)))
        }
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
                // 2. the cover, lifted, blooming in on a new song
                Box(Modifier.size(66.dp).graphicsLayer {
                    val b = bloom.value; val k = 0.82f + 0.18f * b; scaleX = k; scaleY = k; alpha = b.coerceIn(0f, 1f)
                    rotationZ = (1f - b) * -6f
                    shadowElevation = 16.dp.toPx(); shape = RoundedCornerShape(16.dp); clip = true
                }) {
                    if (art != null) Image(art, m.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c1, c2))), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(28.dp))
                    }
                    Canvas(Modifier.fillMaxSize()) { drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent), Offset.Zero, Offset(size.width * 0.6f, size.height * 0.6f))) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    AnimatedContent(m.title, transitionSpec = { (slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 300f)) { it / 3 } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(200)) { -it / 4 } + fadeOut(tween(160))) }, label = "title") { title ->
                        Text(title, color = Color.White, maxLines = 1, modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2400, repeatDelayMillis = 2600, velocity = 28.dp),
                            style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 21.sp))
                    }
                    Text(m.artist, color = Color.White.copy(alpha = 0.68f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.sp))
                }
                Spacer(Modifier.width(10.dp))
                LiveBars(m.playing && !lite, accent, Modifier.size(width = 30.dp, height = 24.dp))
            }
            Spacer(Modifier.weight(1f))
            // 3. scrubber: tap or drag to seek
            Column(Modifier.rise(1)) {
                Canvas(Modifier.fillMaxWidth().height(18.dp)
                    .pointerInput(m.duration) {
                        if (m.duration <= 0) return@pointerInput
                        detectTapGestures { o -> tc?.seekTo((o.x / size.width).coerceIn(0f, 1f).let { (it * m.duration).toLong() }) }
                    }
                    .pointerInput(m.duration) {
                        if (m.duration <= 0) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { o -> seekFrac = (o.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { seekFrac?.let { tc?.seekTo((it * m.duration).toLong()) }; seekFrac = null },
                            onDragCancel = { seekFrac = null },
                        ) { ch, _ -> seekFrac = (ch.position.x / size.width).coerceIn(0f, 1f) }
                    }) {
                    val y = size.height / 2; val r = (if (seekFrac != null) 3.dp else 2.dp).toPx(); val x = size.width * frac
                    drawRoundRect(Color.White.copy(alpha = 0.2f), Offset(0f, y - r), Size(size.width, r * 2), CornerRadius(r))
                    drawRoundRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.8f), Color.White)), Offset(0f, y - r), Size(x, r * 2), CornerRadius(r))
                    drawCircle(accent.copy(alpha = 0.35f), 8.dp.toPx(), Offset(x, y))
                    drawCircle(Color.White, (if (seekFrac != null) 6.dp else 4.5.dp).toPx(), Offset(x, y))
                }
                Row(Modifier.fillMaxWidth()) {
                    val ms = seekFrac?.let { (it * m.duration).toLong() } ?: pos.ms
                    Text(clock(ms), color = Color.White.copy(alpha = 0.6f), style = label().copy(fontSize = 10.sp))
                    Spacer(Modifier.weight(1f))
                    if (m.duration > 0) Text("-" + clock(m.duration - ms), color = Color.White.copy(alpha = 0.6f), style = label().copy(fontSize = 10.sp))
                }
            }
            // 4. controls, centred
            Row(Modifier.fillMaxWidth().padding(top = 2.dp).rise(2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconTap(Icons.Rounded.OpenInNew, 18.dp, Color.White.copy(alpha = 0.7f)) {
                    runCatching { ctx.packageManager.getLaunchIntentForPackage(m.pkg)?.let { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PressIcon(Icons.Rounded.SkipPrevious, 30.dp) { tc?.skipToPrevious() }
                    Spacer(Modifier.width(18.dp))
                    Box(Modifier.size(48.dp).graphicsLayer { shadowElevation = 10.dp.toPx(); shape = CircleShape; clip = true }.background(Color.White)
                        .clickable { if (m.playing) tc?.pause() else tc?.play() }, contentAlignment = Alignment.Center) {
                        AnimatedContent(m.playing, transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f), 0.4f) + fadeIn()) togetherWith (scaleOut(tween(120), 0.4f) + fadeOut(tween(120))) }, label = "pp") { playing ->
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = bg2.deep(0.3f), modifier = Modifier.size(28.dp))
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    PressIcon(Icons.Rounded.SkipNext, 30.dp) { tc?.skipToNext() }
                }
                IconTap(Icons.Rounded.SpeakerGroup, 18.dp, Color.White.copy(alpha = 0.7f)) {
                    runCatching {
                        ctx.startActivity(android.content.Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", m.pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }
}

/** An icon that dips when pressed and springs back. */
@Composable
private fun PressIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp, onClick: () -> Unit) {
    val k = remember { Animatable(1f) }
    var tap by remember { mutableStateOf(0) }
    LaunchedEffect(tap) { if (tap > 0) { k.animateTo(0.8f, tween(70)); k.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f)) } }
    Icon(icon, null, tint = Color.White, modifier = Modifier.size(size + 12.dp).graphicsLayer { scaleX = k.value; scaleY = k.value }
        .clip(CircleShape).clickable { tap++; onClick() }.padding(6.dp))
}

/** The real audio (Android's visualizer) as five rounded bars; falls back to a gentle idle when it isn't allowed. */
@Composable
private fun LiveBars(on: Boolean, color: Color, modifier: Modifier) {
    DisposableEffect(on) {
        val started = on && MusicTheme.startBars()
        onDispose { if (started) MusicTheme.stopBars() }
    }
    val bands by MusicTheme.bands.collectAsState()
    val smooth = remember { FloatArray(5) }
    val t by if (on) rememberClock(Unit) else remember { mutableFloatStateOf(0f) }
    Canvas(modifier) {
        val n = 5; val gap = size.width / (n * 2 - 1)
        for (i in 0 until n) {
            val raw = if (on) bands.getOrElse(i * 3) { 0f }.let { if (it <= 0.01f) 0.25f + 0.25f * ((sin(t * 7f + i) + 1) / 2) else it } else 0.15f
            smooth[i] += (raw.coerceIn(0.12f, 1f) - smooth[i]) * 0.35f
            val hh = size.height * smooth[i].coerceIn(0.12f, 1f)
            drawRoundRect(Brush.verticalGradient(listOf(color.light(0.4f), color)), Offset(i * gap * 2, (size.height - hh) / 2), Size(gap, hh), CornerRadius(gap / 2))
        }
    }
}
