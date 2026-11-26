package com.nebula.assistant.notch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.assistant.mood.MoodPlay
import com.nebula.assistant.mood.MoodUi
import com.nebula.assistant.mood.Tv
import kotlinx.coroutines.delay
import kotlin.math.sin

/*
 * Mood PLAY in the notch: the Mood look (slate glass, the orange pill), TVs found on your Wi-Fi as tiles,
 * "Allow on your TV" while it opens, the short address + QR for any other screen, and — once it's on the TV —
 * the theme picker, sound-on-TV and Stop. Only TVs that actually answered are listed.
 */

private val Slate1 = Color(0xFF8E95A6)
private val Slate2 = Color(0xFF262A36)
private val PillA = Color(0xFFFF3A2A)
private val PillB = Color(0xFFFF8F3F)
private val Faded = Color(0xB3FFFFFF)

internal fun moodGeo(ui: MoodUi, full: Dp): Geo = when (ui) {
    MoodUi.Idle -> Geo(1.dp, 1.dp, 1.dp)
    is MoodUi.Finding -> Geo(full, 214.dp, 34.dp)
    is MoodUi.Opening -> Geo(full, 136.dp, 34.dp)
    is MoodUi.Manual -> Geo(full, 290.dp, 36.dp)
    is MoodUi.Live -> Geo(full, 206.dp, 34.dp)
}

@Composable
internal fun MoodView(ui: MoodUi) {
    val t by rememberClock(Unit)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { // slate glass with a soft warm light from the pill colours
            drawRect(Brush.verticalGradient(listOf(Slate1, Slate2)))
            val o = Offset(size.width * (0.82f + 0.06f * sin(t * 0.5f)), size.height * 0.2f)
            drawCircle(Brush.radialGradient(listOf(PillB.copy(alpha = 0.32f), Color.Transparent), o, size.width * 0.5f), size.width * 0.5f, o)
            drawRect(Color.Black.copy(alpha = 0.28f))
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.25f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.2f)))
        }
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 32.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
                MoodLogo()
                Spacer(Modifier.weight(1f))
                if (ui is MoodUi.Finding) { Chip(Icons.Rounded.Refresh) { MoodPlay.find() }; Spacer(Modifier.width(6.dp)) }
                Chip(Icons.Rounded.Close) { MoodPlay.hide() }
            }
            Spacer(Modifier.height(10.dp))
            when (ui) {
                MoodUi.Idle -> {}
                is MoodUi.Finding -> Finding(ui, t)
                is MoodUi.Opening -> Opening(ui, t)
                is MoodUi.Manual -> Manual(ui)
                is MoodUi.Live -> LiveNow(ui, t)
            }
        }
    }
}

/** m(pill)od PLAY */
@Composable
internal fun MoodLogo(size: Float = 17f, ink: Color = Color.White) {
    val st = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = (-0.6).sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("m", color = ink, style = st)
        Box(Modifier.padding(top = (size * 0.18f).dp).size((size * 1.3f).dp, (size * 0.62f).dp).clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(PillA, PillB))).border((size * 0.08f).dp, ink, RoundedCornerShape(50)))
        Text("od", color = ink, style = st)
        Text(" PLAY", color = ink, style = st.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp))
    }
}

@Composable
private fun Finding(s: MoodUi.Finding, t: Float) {
    Text(when { !s.wifi -> "Connect to Wi-Fi — the TV has to be on the same network"; s.tvs.isEmpty() && s.searching -> "Looking for TVs on your Wi-Fi…"
        s.tvs.isEmpty() -> "No TVs answered. Use the address on any TV or computer"; else -> "Choose a TV" },
        color = Color.White, style = label().copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(1))
    Spacer(Modifier.height(10.dp))
    Row(Modifier.rise(2).height(84.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        s.tvs.forEachIndexed { i, tv -> TvTile(tv, i) }
        if (s.searching && s.tvs.isEmpty()) repeat(2) { i ->
            val p = ((sin(t * 2.4f - i * 0.8f) + 1) / 2)
            Box(Modifier.size(74.dp, 74.dp).graphicsLayer { alpha = 0.25f + 0.3f * p }.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.16f)))
        }
        if (s.wifi) Tile(Icons.Rounded.Language, "Other TV", "or computer") { MoodPlay.manual() }
    }
}

@Composable
private fun TvTile(tv: Tv, i: Int) {
    val pop = remember(tv.id) { Animatable(0f) }
    LaunchedEffect(tv.id) { delay(i * 90L); pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 320f)) }
    Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) }) {
        Tile(Icons.Rounded.Tv, tv.name, when (tv.kind) { "samsung" -> "Samsung"; "lg" -> "LG"; else -> "Browser" }) { MoodPlay.connect(tv) }
    }
}

@Composable
private fun Tile(icon: ImageVector, name: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.width(96.dp).height(84.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.12f))
        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Text(name, color = Color.White, style = label().copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(sub, color = Faded, style = label().copy(fontSize = 9.sp), maxLines = 1)
    }
}

/** The TV with Wi-Fi arcs breathing out of it — the Mood PLAY mark. */
@Composable
private fun TvWaves(t: Float, size: Dp, live: Boolean) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val sw = w * 0.07f
        // screen
        drawRoundRect(Color.White, Offset(w * 0.06f, h * 0.34f), Size(w * 0.88f, h * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = Stroke(sw))
        drawLine(Color.White, Offset(w * 0.36f, h * 0.94f), Offset(w * 0.64f, h * 0.94f), sw, StrokeCap.Round)
        // arcs, pulsing upward while it reaches the TV, steady once it's on
        for (k in 0..2) {
            val f = if (live) 1f else ((t * 1.4f - k * 0.35f) % 1.4f).coerceIn(0f, 1f)
            val r = w * (0.16f + 0.13f * k)
            drawArc(Color.White.copy(alpha = if (live) 0.95f - k * 0.2f else (0.3f + 0.7f * f)), 215f, 110f, false, Offset(w / 2 - r, h * 0.36f - r), Size(r * 2, r * 2), style = Stroke(sw * 0.8f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun Opening(s: MoodUi.Opening, t: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(1)) {
        TvWaves(t, 52.dp, live = false)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(if (s.asking) "Look at ${s.tv.name}" else "Opening on ${s.tv.name}…", color = Color.White, style = label().copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (s.asking) "Press Allow with the TV remote — only needed the first time" else "Asking the TV to open Mood PLAY", color = Faded, style = label().copy(fontSize = 12.sp), maxLines = 2)
        }
    }
}

@Composable
private fun Manual(s: MoodUi.Manual) {
    val qr = remember(s.qr) { s.qr.asImageBitmap() }
    val addr = s.url.removePrefix("http://").substringBefore('/')
    val code = s.url.trimEnd('/').substringAfterLast('/')
    Text(s.why, color = Faded, style = label().copy(fontSize = 12.sp), maxLines = 2, modifier = Modifier.rise(1))
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(2)) {
        Box(Modifier.size(118.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(4.dp)) { Image(qr, "QR code", modifier = Modifier.fillMaxSize()) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("TYPE IN THE TV'S BROWSER", color = Faded, style = caps(9f))
            Text(addr, color = Color.White, style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 19.sp), maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Text("THEN THIS CODE", color = Faded, style = caps(9f))
            Text(code, color = PillB, style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 4.sp))
        }
    }
    Spacer(Modifier.weight(1f))
    Text("Over your Wi-Fi only — not encrypted, and it ends when you press Stop.", color = Faded, style = label().copy(fontSize = 10.sp), modifier = Modifier.rise(3))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).rise(3), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Wide(Icons.Rounded.Check, "It's on the TV", Modifier.weight(1f)) { MoodPlay.manualDone() }
        Wide(Icons.Rounded.Stop, "Stop", Modifier.weight(1f)) { MoodPlay.stop() }
    }
}

@Composable
private fun LiveNow(s: MoodUi.Live, t: Float) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(1)) {
        TvWaves(t, 44.dp, live = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Playing on ${s.tv}", color = Color.White, style = label().copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (s.viewers > 0) "Your TV remote controls the music" else "Waiting for the TV to load…", color = Faded, style = label().copy(fontSize = 12.sp), maxLines = 1)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("TV THEME", color = Faded, style = caps(9f), modifier = Modifier.rise(2))
    Row(Modifier.padding(top = 5.dp).horizontalScroll(rememberScrollState()).rise(2), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MoodPlay.themes.forEach { (id, name) ->
            val on = id == s.theme
            Text(name, color = if (on) Slate2 else Color.White, style = label().copy(fontSize = 12.sp),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.White.copy(alpha = 0.14f))
                    .clickable { MoodPlay.setTheme(id) }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth().rise(3), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Wide(Icons.Rounded.SpeakerGroup, "Sound on TV", Modifier.weight(1f)) { MoodPlay.soundPicker(ctx, com.nebula.assistant.notch.NotchBus.media.value?.pkg) }
        Wide(Icons.Rounded.Stop, "Stop", Modifier.weight(1f)) { MoodPlay.stop() }
    }
}

@Composable
private fun Wide(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.height(40.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
        .clickable(onClick = onClick), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, style = label().copy(fontSize = 13.sp))
    }
}

@Composable
private fun Chip(icon: ImageVector, onClick: () -> Unit) =
    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
