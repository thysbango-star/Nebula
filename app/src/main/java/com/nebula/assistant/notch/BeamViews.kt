package com.nebula.assistant.notch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.assistant.beam.BeamController
import com.nebula.assistant.beam.BeamDevice
import com.nebula.assistant.beam.BeamTarget
import com.nebula.assistant.beam.BeamUi
import com.nebula.assistant.beam.humanSize
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Nebula Beam, back to the brief: Beam is the pink → violet → navy glass, everywhere — the whole island becomes
 * that gradient the moment you double-tap, with two soft lights drifting under the glass.
 *
 * And it's alive: rings pulse out of the camera while it looks around, real devices pop in as bubbles, a paper
 * plane flies an arc from you to them (riding the real progress), "Sent" draws its check and throws a handful of
 * little planes, the QR turns over into view with a scan line reading it, and every download bumps the counter.
 * Nothing is ever shown that didn't happen — no made-up devices, no fake percentages.
 */

private val Pink = Color(0xFFFF5FA2)
private val BViolet = Color(0xFF8B5CF6)
private val Navy = Color(0xFF1E2A78)
private val Ice = Color(0xFFBFE8FF)
private val Soft = Color(0xCCFFFFFF)
private val BeamBrush = Brush.linearGradient(listOf(Pink, BViolet, Navy))

internal fun beamGeo(ui: BeamUi, full: Dp): Geo = when (ui) {
    BeamUi.Idle -> Geo(1.dp, 1.dp, 1.dp)
    is BeamUi.Scanning -> Geo(full, (if (ui.devices.isEmpty()) 196 else 222).dp, 34.dp)
    is BeamUi.Preparing -> Geo(300.dp, 64.dp, 32.dp)
    is BeamUi.Connecting, is BeamUi.Waiting, is BeamUi.Sending, is BeamUi.Receiving -> Geo(full, 132.dp, 34.dp)
    is BeamUi.Sent -> Geo(320.dp, 72.dp, 36.dp)
    is BeamUi.Failed -> Geo(full, 158.dp, 34.dp)
    is BeamUi.Link -> Geo(full, 384.dp, 38.dp)
    is BeamUi.Incoming -> Geo(full, (214 + if (ui.offer.text != null) 36 else 0).dp, 34.dp)
    is BeamUi.Received -> Geo(full, 150.dp, 34.dp)
}

@Composable
internal fun BeamView(ui: BeamUi) {
    Box(Modifier.fillMaxSize()) {
        BeamGlass()
        when (ui) {
            BeamUi.Idle -> {}
            is BeamUi.Scanning -> Scanning(ui)
            is BeamUi.Preparing -> Preparing(ui.what)
            is BeamUi.Connecting -> Flying(ui.to, null, "Connecting…", "")
            is BeamUi.Waiting -> Flying(ui.to, null, "Waiting for them to accept", ui.code)
            is BeamUi.Sending -> Flying(ui.to, ui.progress, if (ui.count == 1) "1 item" else "${ui.count} items", ui.code)
            is BeamUi.Receiving -> Flying(ui.from, ui.progress, "Receiving ${if (ui.count == 1) "1 item" else "${ui.count} items"}", "", incoming = true)
            is BeamUi.Sent -> Sent(ui)
            is BeamUi.Failed -> Failed(ui)
            is BeamUi.Link -> LinkView(ui)
            is BeamUi.Incoming -> Incoming(ui)
            is BeamUi.Received -> Received(ui)
        }
    }
}

/** The Beam glass: the gradient, two lights drifting underneath, a bright top edge and a slow sheen. */
@Composable
private fun BeamGlass() {
    val lite = LocalLite.current
    val t by if (lite) remember { mutableFloatStateOf(0f) } else rememberClock(Unit)
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(Brush.linearGradient(listOf(Pink, BViolet, Navy), Offset(0f, 0f), Offset(w, h * 1.4f)))
        val a = Offset(w * (0.25f + 0.1f * sin(t * 0.5f)), h * (0.3f + 0.15f * cos(t * 0.4f)))
        val b = Offset(w * (0.8f + 0.08f * cos(t * 0.35f)), h * (0.75f + 0.12f * sin(t * 0.45f)))
        drawCircle(Brush.radialGradient(listOf(Pink.light(0.3f).copy(alpha = 0.55f), Color.Transparent), a, w * 0.45f), w * 0.45f, a)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF3B82F6).copy(alpha = 0.45f), Color.Transparent), b, w * 0.4f), w * 0.4f, b)
        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.2f), 0.18f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.18f)))
        val sx = ((t * 0.1f) % 1f) * (w + 400f) - 200f
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.1f), Color.Transparent), Offset(sx - 100f, 0f), Offset(sx + 60f, h)))
    }
}

// ------------------------------------------------------------------ scanning: rings from the camera, devices as bubbles

@Composable
private fun Scanning(s: BeamUi.Scanning) {
    val t by rememberClock(Unit)
    Box(Modifier.fillMaxSize()) {
        // rings pulsing out of the camera
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, 14.dp.toPx())
            for (k in 0..2) {
                val f = ((t * 0.45f) + k / 3f) % 1f
                drawCircle(Color.White.copy(alpha = 0.32f * (1f - f)), size.width * 0.75f * f, c, style = Stroke((1.6f * (1f - f) + 0.4f).dp.toPx()))
            }
        }
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 34.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
                Column(Modifier.weight(1f)) {
                    Text("BEAM", color = Ice, style = caps(10f))
                    Text(when { !s.wifi -> "Wi-Fi is off — Beam Link can use a hotspot"; s.devices.isEmpty() -> s.note.ifBlank { "Looking for Nebula devices…" }
                        else -> "Choose where to send" }, color = Color.White, style = label().copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Round(Icons.Rounded.Refresh) { BeamController.rescan() }
                Spacer(Modifier.width(6.dp))
                Round(Icons.Rounded.Close) { BeamController.close() }
            }
            Spacer(Modifier.height(12.dp))
            if (s.devices.isEmpty()) {
                Row(Modifier.rise(1).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    // three ghost bubbles breathing while it looks
                    repeat(3) { i ->
                        val p = ((sin(t * 2.4f - i * 0.7f) + 1) / 2)
                        Box(Modifier.padding(end = 8.dp).size(34.dp).graphicsLayer { alpha = 0.25f + 0.35f * p; val k = 0.9f + 0.1f * p; scaleX = k; scaleY = k }
                            .clip(CircleShape).background(Color.White.copy(alpha = 0.18f)))
                    }
                    Text("Open Beam on the other device so it shows up here.", color = Soft, style = label().copy(fontSize = 12.sp), maxLines = 2)
                }
            } else {
                Row(Modifier.rise(1).height(74.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    s.devices.take(4).forEachIndexed { i, d -> DeviceBubble(d, i) }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.rise(2)) {
                Route(Icons.Rounded.IosShare, "Quick Share", "& your apps") { BeamController.pick(BeamTarget.System) }
                Route(Icons.Rounded.QrCode2, "Beam Link", "QR code · no app") { BeamController.pick(BeamTarget.Link) }
            }
        }
    }
}

/** A found device pops in as a bubble with its name underneath. */
@Composable
private fun DeviceBubble(d: BeamDevice, i: Int) {
    val pop = remember(d.id) { Animatable(0f) }
    LaunchedEffect(d.id) { delay(i * 90L); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 320f)) }
    Column(Modifier.width(64.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) }
        .clip(RoundedCornerShape(16.dp)).clickable { BeamController.pick(BeamTarget.Nebula(d)) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Ice)))
            .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (d.type == "windows" || d.type == "linux" || d.type == "pc") Icons.Rounded.Computer else Icons.Rounded.PhoneAndroid, null, tint = BViolet, modifier = Modifier.size(24.dp))
        }
        Text(d.name, color = Color.White, style = label().copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun RowScope.Route(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) = Row(Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(18.dp))
    .background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
    Column(Modifier.padding(start = 9.dp)) {
        Text(title, color = Color.White, style = label().copy(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Soft, style = label().copy(fontSize = 10.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun Preparing(what: String) {
    val t by rememberClock(Unit)
    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(24.dp)) {
            rotate(t * 300f) { drawArc(Color.White, 0f, 260f, false, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)) }
        }
        Text(what, color = Color.White, style = label().copy(fontSize = 14.sp), modifier = Modifier.padding(start = 12.dp).rise(0))
    }
}

// ------------------------------------------------------------------ flying: you → them, a plane on an arc riding the real progress

@Composable
private fun Flying(to: String, progress: Float?, sub: String, code: String, incoming: Boolean = false) {
    val t by rememberClock(Unit)
    val shown by animateFloatAsState(progress ?: 0f, spring(stiffness = 120f), label = "prog")
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 14.dp, top = 30.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
            Column(Modifier.weight(1f)) {
                Text(if (incoming) "Arriving from $to" else "Flying to $to", color = Color.White, style = label().copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(buildString { append(sub); if (progress != null) append(" · ${(progress * 100).toInt()}%"); if (code.isNotEmpty()) append(" · code $code") },
                    color = Soft, style = label().copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!incoming) Round(Icons.Rounded.Close) { BeamController.close() }
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp).rise(1)) {
            val w = size.width; val h = size.height
            val a = Offset(18.dp.toPx(), h * 0.62f); val b = Offset(w - 18.dp.toPx(), h * 0.62f)
            val ctrl = Offset(w / 2, -h * 0.25f)
            fun at(p: Float): Offset { val q = 1f - p; return Offset(q * q * a.x + 2 * q * p * ctrl.x + p * p * b.x, q * q * a.y + 2 * q * p * ctrl.y + p * p * b.y) }
            // dotted arc
            val dots = 28
            for (i in 0..dots) { val p = i / dots.toFloat(); drawCircle(Color.White.copy(alpha = 0.35f), 1.4.dp.toPx(), at(p)) }
            // ends: you and them
            drawCircle(Color.White.copy(alpha = 0.95f), 11.dp.toPx(), if (incoming) b else a)
            drawCircle(Color.White.copy(alpha = 0.95f), 11.dp.toPx(), if (incoming) a else b)
            val pulse = (t * 1.2f) % 1f
            drawCircle(Color.White.copy(alpha = 0.5f * (1f - pulse)), (11 + 12 * pulse).dp.toPx(), if (incoming) a else b, style = Stroke(1.5.dp.toPx()))
            // the plane: the real progress when there is one, otherwise a gentle loop
            val raw = progress?.let { shown.coerceIn(0f, 1f) } ?: ((t * 0.35f) % 1f)
            val p = if (incoming) 1f - raw else raw
            val pos = at(p.coerceIn(0.03f, 0.97f)); val ahead = at((p + if (incoming) -0.02f else 0.02f).coerceIn(0f, 1f))
            val ang = kotlin.math.atan2(ahead.y - pos.y, ahead.x - pos.x) * 180f / PI.toFloat()
            // trail
            for (i in 1..6) { val tp = (p - (if (incoming) -1 else 1) * i * 0.025f).coerceIn(0f, 1f); drawCircle(Color.White.copy(alpha = 0.3f * (1f - i / 7f)), (3f - i * 0.35f).dp.toPx(), at(tp)) }
            plane(pos, ang, 15.dp.toPx())
        }
    }
}

/** A small folded paper plane, drawn (not an emoji), pointing along [deg]. */
private fun DrawScope.plane(at: Offset, deg: Float, s: Float) {
    translate(at.x, at.y) { rotate(deg, Offset.Zero) {
        val body = Path().apply { moveTo(s, 0f); lineTo(-s * 0.8f, -s * 0.62f); lineTo(-s * 0.35f, 0f); lineTo(-s * 0.8f, s * 0.62f); close() }
        drawPath(body, Color.White)
        val fold = Path().apply { moveTo(s, 0f); lineTo(-s * 0.35f, 0f); lineTo(-s * 0.55f, s * 0.3f); close() }
        drawPath(fold, Ice)
    } }
}

// ------------------------------------------------------------------ sent / received / failed

@Composable
private fun Sent(s: BeamUi.Sent) {
    val t by rememberClock(s)
    Box(Modifier.fillMaxSize()) {
        // a handful of little planes thrown out from the check
        Canvas(Modifier.fillMaxSize()) {
            val o = Offset(30.dp.toPx(), size.height / 2)
            val k = seg(t, 0.35f, 1.6f)
            if (k in 0.001f..0.999f) for (i in 0 until 7) {
                val a = -70f + i * 23f
                val r = (12 + 150 * k).dp.toPx()
                val p = Offset(o.x + cos(a * PI.toFloat() / 180f) * r, o.y + sin(a * PI.toFloat() / 180f) * r * 0.5f)
                drawContext.canvas.save()
                plane(p, a, 5.dp.toPx() * (1f - k * 0.5f))
                drawContext.canvas.restore()
            }
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(30.dp)) {
                drawCircle(Color.White, size.minDimension / 2 * seg(t, 0f, 0.35f, ::backOut))
                val d = seg(t, 0.25f, 0.6f)
                val path = Path().apply { moveTo(size.width * 0.28f, size.height * 0.52f); lineTo(size.width * 0.44f, size.height * 0.68f); lineTo(size.width * 0.74f, size.height * 0.36f) }
                val m = PathMeasure().apply { setPath(path, false) }
                val part = Path(); m.getSegment(0f, m.length * d, part, true)
                drawPath(part, BViolet, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Text(if (s.how == "system") "Handed to Android sharing" else "Sent to ${s.to}", color = Color.White, style = label().copy(fontSize = 15.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).rise(0))
        }
    }
}

@Composable
private fun Received(r: BeamUi.Received) = Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 34.dp, bottom = 14.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = BViolet, modifier = Modifier.size(18.dp)) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("From ${r.from}", color = Color.White, style = label().copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.text?.take(80) ?: "${r.count} saved to Downloads › Nebula Beam", color = Soft, style = label().copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Round(Icons.Rounded.Close) { BeamController.close() }
    }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.rise(1)) {
        if (r.saved.isNotEmpty()) Chip("Open", true, Modifier.weight(1f)) { BeamController.openReceived(r.saved.first()); BeamController.close() }
        Chip("Done", false, Modifier.weight(1f)) { BeamController.close() }
    }
}

@Composable
private fun Failed(f: BeamUi.Failed) = Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 34.dp, bottom = 14.dp)) {
    val t by rememberClock(f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(28.dp).graphicsLayer { translationX = sin(t * 40f) * 4.dp.toPx() * (1f - seg(t, 0f, 0.5f)) })
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("Beam didn't go through", color = Color.White, style = label().copy(fontSize = 15.sp))
            Text(f.reason, color = Soft, style = label().copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.rise(1)) {
        if (f.canHotspot) Chip("Use hotspot", true, Modifier.weight(1.3f)) { BeamController.useHotspot() }
        if (f.canRetry) Chip("Retry", !f.canHotspot, Modifier.weight(1f)) { BeamController.retry() }
        if (f.canLink) Chip("Beam Link", !f.canRetry && !f.canHotspot, Modifier.weight(1f)) { BeamController.useLink() }
        Chip("Close", false, Modifier.weight(1f)) { BeamController.close() }
    }
}

// ------------------------------------------------------------------ incoming: the receiver always decides

@Composable
private fun Incoming(i: BeamUi.Incoming) {
    val o = i.offer
    val t by rememberClock(o.id)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, 14.dp.toPx())
            for (k in 0..1) { val f = ((t * 0.6f) + k / 2f) % 1f; drawCircle(Color.White.copy(alpha = 0.3f * (1f - f)), size.width * 0.6f * f, c, style = Stroke(1.5.dp.toPx())) }
        }
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 34.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
                Box(Modifier.size(40.dp).graphicsLayer { val k = seg(t, 0f, 0.6f, ::backOut); scaleX = k; scaleY = k; translationY = (1f - k) * -20.dp.toPx() }
                    .clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SouthWest, null, tint = BViolet, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("${o.from} wants to Beam you", color = Color.White, style = label().copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (o.text != null) "Text" else "${o.items.size} item${if (o.items.size == 1) "" else "s"} · ${humanSize(o.total)}", color = Soft, style = label().copy(fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
            }
            Text(if (o.text != null) o.text.take(120) else o.items.take(3).joinToString(", ") { it.name } + if (o.items.size > 3) " +${o.items.size - 3}" else "",
                color = Color.White.copy(alpha = 0.85f), style = TextStyle(fontFamily = Body, fontSize = 12.sp), maxLines = if (o.text != null) 3 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp).rise(1))
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp).rise(2),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Check this code matches theirs", color = Soft, style = label().copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                Text(o.code.chunked(3).joinToString(" "), color = Color.White, style = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 2.sp))
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.rise(3)) {
                Chip("Accept", true, Modifier.weight(1f)) { BeamController.accept() }
                Chip("Decline", false, Modifier.weight(1f)) { BeamController.decline() }
            }
        }
    }
}

// ------------------------------------------------------------------ Beam Link: the QR turns over into view, a scan line reads it

@Composable
private fun LinkView(l: BeamUi.Link) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(l.expiresAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val left = ((l.expiresAt - now) / 1000).coerceAtLeast(0)
    val joining = l.step == 0 && l.joinQr != null
    val t by rememberClock(l.step)
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 34.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
            Column(Modifier.weight(1f)) {
                Text(if (l.joinQr != null) "BEAM LINK · STEP ${if (joining) 1 else 2} OF 2" else "BEAM LINK", color = Ice, style = caps(10f))
                Text(if (joining) "Scan to join ${l.ssid}" else "Scan with the other device's camera", color = Color.White, style = label().copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Round(Icons.Rounded.Close) { BeamController.close() }
        }
        Spacer(Modifier.height(10.dp))
        val qr = if (joining) l.joinQr!! else l.qr
        val img = remember(qr) { qr.asImageBitmap() }
        val flip = seg(t, 0f, 0.7f, ::backOut)
        Box(Modifier.size(184.dp).graphicsLayer { rotationY = (1f - flip) * 90f; cameraDistance = 12f * density; alpha = seg(t, 0f, 0.2f) }
            .clip(RoundedCornerShape(22.dp)).background(Color.White).padding(8.dp)) {
            Image(img, if (joining) "QR code to join the hotspot" else "QR code for the Beam Link", Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            // the scan line, and corners that breathe
            Canvas(Modifier.fillMaxSize()) {
                val y = ((t * 0.5f) % 1f) * size.height
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, BViolet.copy(alpha = 0.28f), Color.Transparent), y - 18f, y + 18f), Offset(0f, y - 18f), Size(size.width, 36f))
                val c = 18.dp.toPx(); val sw = 3.dp.toPx(); val inset = -2.dp.toPx() + sin(t * 3f) * 1.5f
                val col = Brush.linearGradient(listOf(Pink, BViolet))
                for ((x, y0, dx, dy) in listOf(listOf(inset, inset, 1f, 1f), listOf(size.width - inset, inset, -1f, 1f), listOf(inset, size.height - inset, 1f, -1f), listOf(size.width - inset, size.height - inset, -1f, -1f))) {
                    drawLine(col, Offset(x, y0), Offset(x + c * dx, y0), sw, StrokeCap.Round)
                    drawLine(col, Offset(x, y0), Offset(x, y0 + c * dy), sw, StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (joining) {
            Text("Wi-Fi ${l.ssid}  ·  password ${l.pass}", color = Color.White, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp), maxLines = 1, modifier = Modifier.rise(1))
            Spacer(Modifier.weight(1f))
            Chip("They've joined — show the link", true, Modifier.fillMaxWidth().rise(2)) { BeamController.linkStep(1) }
        } else {
            Text(l.url.removePrefix("http://"), color = Color.White.copy(alpha = 0.8f), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(1))
            Row(Modifier.padding(top = 6.dp).rise(2), verticalAlignment = Alignment.CenterVertically) {
                Text("${l.count} item${if (l.count == 1) "" else "s"} · %d:%02d left · ".format(left / 60, left % 60), color = Soft, style = label().copy(fontSize = 11.sp, fontWeight = FontWeight.Medium))
                AnimatedContent(l.downloads, transitionSpec = { (scaleIn(spring(dampingRatio = 0.4f, stiffness = 500f), 1.6f) + fadeIn()) togetherWith fadeOut() }, label = "dl") { n ->
                    Text("$n download${if (n == 1) "" else "s"}", color = Color.White, style = label().copy(fontSize = 11.sp))
                }
            }
            Spacer(Modifier.weight(1f))
            if (l.joinQr == null) Text("Not on the same Wi-Fi? Use my hotspot", color = Color.White, style = label().copy(fontSize = 12.sp),
                modifier = Modifier.rise(3).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).clickable { BeamController.useHotspot() }.padding(horizontal = 14.dp, vertical = 8.dp))
            else Text("Back to step 1", color = Soft, style = label().copy(fontSize = 12.sp),
                modifier = Modifier.rise(3).clip(RoundedCornerShape(50)).clickable { BeamController.linkStep(0) }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

// ------------------------------------------------------------------ bits

@Composable
private fun Round(icon: ImageVector, onClick: () -> Unit) =
    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }

@Composable
private fun Chip(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Box(modifier.height(40.dp).clip(RoundedCornerShape(50)).background(if (primary) Color.White else Color.White.copy(alpha = 0.16f))
        .clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) BViolet.deep(0.25f) else Color.White, style = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 13.sp), maxLines = 1)
    }
