package com.nebula.assistant.notch

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
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
import kotlin.math.sin

/*
 * Nebula Beam in the Notch. The look follows the brief: a pink → violet → navy glass pill that reads
 * "Flying to <device>" while it's really sending (the bar is the real fraction sent), then "Sent to <device>"
 * with a check. Nothing is ever shown that didn't happen — no made-up devices, no fake percentages.
 */

private val Pink = Color(0xFFFF5FA2)
private val Violet = Color(0xFF8B5CF6)
private val Navy = Color(0xFF1E2A78)
private val Sky = Color(0xFF7FD4FF)
private val Soft = Color(0xFFA9ADBB)
private val BeamBrush = Brush.horizontalGradient(listOf(Pink, Violet, Navy))

/** Size of the island for each Beam state (fixed sizes, like every other notch view). */
internal fun beamSize(ui: BeamUi, full: Dp): Pair<Dp, Dp> = when (ui) {
    BeamUi.Idle -> 1.dp to 1.dp
    is BeamUi.Scanning -> full to (150 + 54 * ui.devices.size.coerceIn(0, 4) + (if (ui.devices.isEmpty()) 28 else 0)).dp
    is BeamUi.Connecting, is BeamUi.Waiting -> full to 112.dp
    is BeamUi.Sending -> full to 112.dp
    is BeamUi.Sent -> 320.dp to 76.dp
    is BeamUi.Failed -> full to 150.dp
    is BeamUi.Link -> full to 330.dp
    is BeamUi.Incoming -> full to (196 + if (ui.offer.text != null) 40 else 0).dp
    is BeamUi.Receiving -> full to 112.dp
    is BeamUi.Received -> full to 140.dp
}

@Composable
internal fun BeamView(ui: BeamUi) {
    when (ui) {
        BeamUi.Idle -> {}
        is BeamUi.Scanning -> Scanning(ui)
        is BeamUi.Connecting -> Flying(ui.to, null, "Connecting…", "")
        is BeamUi.Waiting -> Flying(ui.to, null, "Waiting for them to accept", ui.code)
        is BeamUi.Sending -> Flying(ui.to, ui.progress, if (ui.count == 1) "1 item" else "${ui.count} items", ui.code)
        is BeamUi.Sent -> Sent(ui)
        is BeamUi.Failed -> Failed(ui)
        is BeamUi.Link -> LinkView(ui)
        is BeamUi.Incoming -> Incoming(ui)
        is BeamUi.Receiving -> Flying(ui.from, ui.progress, "Receiving ${if (ui.count == 1) "1 item" else "${ui.count} items"}", "", incoming = true)
        is BeamUi.Received -> Received(ui)
    }
}

// ------------------------------------------------------------------ scanning: a radar, real devices, two honest routes

@Composable
private fun Scanning(s: BeamUi.Scanning) = Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Radar(Modifier.size(28.dp))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("BEAM", color = Sky, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 2.sp))
            Text(when { !s.wifi -> "Wi-Fi is off — Nebula devices need Wi-Fi"; s.devices.isEmpty() -> s.note.ifBlank { "Looking for Nebula devices…" }
                else -> "Choose where to send" }, color = Color.White, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Round(Icons.Rounded.Refresh) { BeamController.rescan() }
        Spacer(Modifier.width(6.dp))
        Round(Icons.Rounded.Close) { BeamController.close() }
    }
    Spacer(Modifier.height(10.dp))
    s.devices.take(4).forEach { DeviceRow(it) }
    if (s.devices.isEmpty()) Text("Open Beam on the other device (double-tap its notch) so it shows up here.", color = Soft,
        style = TextStyle(fontSize = 12.sp), modifier = Modifier.padding(bottom = 8.dp), maxLines = 2)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Route(Icons.Rounded.IosShare, "Quick Share & apps", "Android sharing") { BeamController.pick(BeamTarget.System) }
        Route(Icons.Rounded.QrCode2, "Beam Link", "QR · same Wi-Fi") { BeamController.pick(BeamTarget.Link) }
    }
}

@Composable
private fun DeviceRow(d: BeamDevice) = Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).height(48.dp).clip(RoundedCornerShape(16.dp))
    .background(Color(0x14FFFFFF)).clickable { BeamController.pick(BeamTarget.Nebula(d)) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(BeamBrush), contentAlignment = Alignment.Center) {
        Icon(if (d.type == "windows" || d.type == "linux" || d.type == "pc") Icons.Rounded.Computer else Icons.Rounded.PhoneAndroid, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
    Column(Modifier.padding(start = 10.dp).weight(1f)) {
        Text(d.name, color = Color.White, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Nebula · nearby", color = Soft, style = TextStyle(fontSize = 11.sp))
    }
}

@Composable
private fun RowScope.Route(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) = Row(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(16.dp))
    .background(Color(0x0FFFFFFF)).clickable(onClick = onClick).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Sky, modifier = Modifier.size(18.dp))
    Column(Modifier.padding(start = 8.dp)) {
        Text(title, color = Color.White, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Soft, style = TextStyle(fontSize = 10.sp), maxLines = 1)
    }
}

@Composable
private fun Radar(m: Modifier) {
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")
    Canvas(m) {
        val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2
        for (k in 0..1) { val f = (p + k * 0.5f) % 1f; drawCircle(Violet.copy(alpha = (1f - f) * 0.8f), r * f, c, style = Stroke(1.5.dp.toPx())) }
        drawCircle(Brush.radialGradient(listOf(Pink, Violet), c, r * 0.45f), r * 0.32f, c)
    }
}

// ------------------------------------------------------------------ flying: the brief's gradient pill, with the real progress

@Composable
private fun Flying(to: String, progress: Float?, sub: String, code: String, incoming: Boolean = false) =
    Box(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 38.dp, bottom = 10.dp)) {
        val t = rememberInfiniteTransition(label = "fly")
        val sheen by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "sheen")
        val shown by animateFloatAsState(progress ?: 0f, spring(stiffness = 120f), label = "prog")
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(BeamBrush)) {
            // A soft moving sheen so it feels alive even while waiting — it isn't a progress bar.
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * sheen
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent), x - size.width * 0.25f, x + size.width * 0.25f))
                if (progress != null) {
                    val y = size.height - 3.dp.toPx(); val sw = 3.dp.toPx()
                    drawLine(Color.White.copy(alpha = 0.22f), Offset(size.height / 2, y), Offset(size.width - size.height / 2, y), sw, StrokeCap.Round)
                    drawLine(Color.White, Offset(size.height / 2, y), Offset(size.height / 2 + (size.width - size.height) * shown.coerceIn(0f, 1f), y), sw, StrokeCap.Round)
                }
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Paper(incoming)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(if (incoming) "Arriving from $to" else "Flying to $to", color = Color.White, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(buildString { append(sub); if (progress != null) append(" · ${(progress * 100).toInt()}%"); if (code.isNotEmpty()) append(" · code $code") },
                        color = Color.White.copy(alpha = 0.78f), style = TextStyle(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!incoming) Round(Icons.Rounded.Close, tint = Color.White) { BeamController.close() }
            }
        }
    }

/** A little paper plane that bobs along (drawn, not an emoji). */
@Composable
private fun Paper(incoming: Boolean) {
    val t = rememberInfiniteTransition(label = "plane")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "b")
    Canvas(Modifier.size(26.dp)) {
        val dy = sin(b * 2 * PI).toFloat() * 2.dp.toPx(); val w = size.width; val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            if (incoming) { moveTo(w * 0.1f, h * 0.5f + dy); lineTo(w * 0.9f, h * 0.15f + dy); lineTo(w * 0.62f, h * 0.88f + dy); lineTo(w * 0.48f, h * 0.6f + dy); close() }
            else { moveTo(w * 0.9f, h * 0.5f + dy); lineTo(w * 0.1f, h * 0.15f + dy); lineTo(w * 0.38f, h * 0.88f + dy); lineTo(w * 0.52f, h * 0.6f + dy); close() }
        }
        drawPath(path, Color.White)
    }
}

// ------------------------------------------------------------------ sent / received / failed

@Composable
private fun Sent(s: BeamUi.Sent) = Box(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 30.dp, bottom = 6.dp)) {
    Row(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(BeamBrush).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        val pop by animateFloatAsState(1f, spring(dampingRatio = 0.45f, stiffness = 380f), label = "pop")
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = Violet, modifier = Modifier.size((16 * pop).dp))
        }
        Text(if (s.how == "system") "Handed to Android sharing" else "Sent to ${s.to}", color = Color.White, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun Received(r: BeamUi.Received) = Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(BeamBrush), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("From ${r.from}", color = Color.White, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.text?.take(80) ?: "${r.count} saved to Downloads › Nebula Beam", color = Soft, style = TextStyle(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Round(Icons.Rounded.Close) { BeamController.close() }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r.saved.isNotEmpty()) Chip("Open", true) { BeamController.openReceived(r.saved.first()); BeamController.close() }
        Chip("Done", false) { BeamController.close() }
    }
}

@Composable
private fun Failed(f: BeamUi.Failed) = Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Pink, modifier = Modifier.size(26.dp))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("Beam didn't go through", color = Color.White, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp))
            Text(f.reason, color = Soft, style = TextStyle(fontSize = 12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (f.canRetry) Chip("Retry", true) { BeamController.retry() }
        if (f.canLink) Chip("Use Beam Link", !f.canRetry) { BeamController.useLink() }
        Chip("Close", false) { BeamController.close() }
    }
}

// ------------------------------------------------------------------ incoming: the receiver always decides

@Composable
private fun Incoming(i: BeamUi.Incoming) = Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 12.dp)) {
    val o = i.offer
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(BeamBrush), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SouthWest, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("${o.from} wants to Beam you", color = Color.White, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (o.text != null) "Text" else "${o.items.size} item${if (o.items.size == 1) "" else "s"} · ${humanSize(o.total)}", color = Soft, style = TextStyle(fontSize = 12.sp))
        }
    }
    Text(if (o.text != null) o.text.take(120) else o.items.take(3).joinToString(", ") { it.name } + if (o.items.size > 3) " +${o.items.size - 3}" else "",
        color = Color(0xFFC7CAD6), style = TextStyle(fontSize = 12.sp), maxLines = if (o.text != null) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Check this code matches theirs", color = Soft, style = TextStyle(fontSize = 11.sp), modifier = Modifier.weight(1f))
        Text(o.code.chunked(3).joinToString(" "), color = Color.White, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 2.sp))
    }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Accept", true, Modifier.weight(1f)) { BeamController.accept() }
        Chip("Decline", false, Modifier.weight(1f)) { BeamController.decline() }
    }
}

// ------------------------------------------------------------------ Beam Link: QR on the phone, nothing in the cloud

@Composable
private fun LinkView(l: BeamUi.Link) = Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(l.expiresAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val left = ((l.expiresAt - now) / 1000).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("BEAM LINK", color = Sky, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 2.sp))
            Text("Scan on the other device", color = Color.White, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
        }
        Round(Icons.Rounded.Close) { BeamController.close() }
    }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.size(170.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(8.dp)) {
        Image(l.qr.asImageBitmap(), "QR code for the Beam Link", Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
    }
    Text("${l.count} item${if (l.count == 1) "" else "s"} · expires in %d:%02d · ${l.downloads} download${if (l.downloads == 1) "" else "s"}".format(left / 60, left % 60),
        color = Soft, style = TextStyle(fontSize = 11.sp), modifier = Modifier.padding(top = 8.dp))
    Text("Same Wi-Fi only · not encrypted like Nebula-to-Nebula", color = Color(0xFF6E7385), style = TextStyle(fontSize = 10.sp), modifier = Modifier.padding(top = 2.dp))
}

// ------------------------------------------------------------------ bits

@Composable
private fun Round(icon: ImageVector, tint: Color = Soft, onClick: () -> Unit) =
    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0x1FFFFFFF)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
    }

@Composable
private fun Chip(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Box(modifier.height(38.dp).clip(RoundedCornerShape(50)).background(if (primary) BeamBrush else Brush.linearGradient(listOf(Color(0x1FFFFFFF), Color(0x1FFFFFFF))))
        .clickable(onClick = onClick).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
    }
