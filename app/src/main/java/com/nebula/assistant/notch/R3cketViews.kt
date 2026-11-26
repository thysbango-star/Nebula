package com.nebula.assistant.notch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.assistant.game.R3cket
import kotlinx.coroutines.delay

/*
 * R3cket in the island, reworked so it never gets in the way of the game:
 *  - no big card when a game starts: the boost runs inside the slim HUD pill (a short ticker says what it did);
 *  - the HUD is the notch's own height: game time on the left, ping and the phone's heat state on the right;
 *  - tapping it opens a low, wide strip (about a third of the old panel's height) with the tools in one row;
 *  - in lite mode the island stops its decorative animations while you play.
 */

internal fun r3cketGeo(editing: Boolean, pillH: Dp) = Geo(372.dp, if (editing) 112.dp else 108.dp, 30.dp)

private fun pingColor(p: Int?) = when { p == null -> Dim; p < 60 -> Mint; p < 120 -> Ember; else -> Red }

/** Android's own thermal state for the phone: what actually decides whether the game gets throttled. */
private fun heat(status: Int): Pair<String, Color> = when (status) {
    0 -> "Cool" to Mint
    1 -> "Warm" to Lime
    2 -> "Hot" to Ember
    else -> "Throttling" to Red
}

@Composable
private fun rememberThermal(): Int {
    val ctx = LocalContext.current
    var st by remember { mutableIntStateOf(R3cket.thermal(ctx)) }
    LaunchedEffect(Unit) { while (true) { delay(5000); st = R3cket.thermal(ctx) } }
    return st
}

/** The HUD: the notch's own size. Tap for the tools. */
@Composable
internal fun GameHud(s: IslandState, h: Dp) {
    val g = s.game
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val thermal = rememberThermal()
    val fresh = g != null && (g.boosting || now - g.startedAt < 9000)
    Compact({
        Icon(Icons.Rounded.Bolt, null, tint = Lime, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        AnimatedContent(if (fresh) (if (g?.boosting == true) "Boosting…" else boostLine(g)) else clock(now - (g?.startedAt ?: now)),
            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "hud") { txt ->
            Text(txt, color = if (fresh) Lime else Color.White, maxLines = 1, style = label().copy(fontFamily = Display))
        }
    }, {
        Text(s.ping?.let { "${it}ms" } ?: "—", color = pingColor(s.ping), style = label().copy(fontFamily = Display))
        Spacer(Modifier.width(8.dp))
        val (_, hc) = heat(thermal)
        Box(Modifier.size(7.dp).clip(CircleShape).background(hc))
    })
}

private fun boostLine(g: R3cket.Session?): String = when {
    g == null -> ""
    g.freedMb > 0 -> "Freed ${g.freedMb} MB"
    g.closedApps > 0 -> "${g.closedApps} apps paused"
    else -> "Ready"
}

/** The tools: one low strip — stats on the left, the four tools on the right. */
@Composable
internal fun R3cketPanel(s: IslandState, c: NotchHost, onPanel: (Boolean) -> Unit) {
    val ctx = c.context
    val g = s.game
    val k = s.clicker
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var batt by remember { mutableStateOf(R3cket.battery(ctx)) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); batt = R3cket.battery(ctx); delay(1000) } }
    val thermal = rememberThermal()
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(0)) {
            Icon(Icons.Rounded.Bolt, null, tint = Lime, modifier = Modifier.size(15.dp))
            Text(if (k.editing) "Tap where Nebula should tap" else "R3CKET · ${g?.game ?: "Game mode"}", color = Lime, style = caps(10f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp).weight(1f))
            Text(clock(now - (g?.startedAt ?: now)), color = Color.White, style = label().copy(fontFamily = Display, fontSize = 13.sp))
            Spacer(Modifier.width(8.dp))
            CloseDot { onPanel(false); if (k.editing) R3cket.setEditing(false) }
        }
        Spacer(Modifier.weight(1f))
        if (k.editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.rise(1)) {
                ActionPill("Clear", Modifier.weight(1f)) { R3cket.clearPoints(); R3cket.setEditing(true) }
                ActionPill("Done · start", Modifier.weight(1f), Lime) { R3cket.setEditing(false); R3cket.startClicker(ctx, centerIfEmpty = false) }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(1)) {
            val (heatText, heatColor) = heat(thermal)
            Stat("Ping", s.ping?.let { "$it" } ?: "—", pingColor(s.ping))
            Stat("Heat", heatText, heatColor)
            Stat("Batt", "${batt.first}%", if (batt.first <= 15) Red else Color.White)
            if (k.running) Stat("Taps", "${k.clicks}", Lime)
            Spacer(Modifier.weight(1f))
            Tool(if (k.running) Icons.Rounded.Pause else Icons.Rounded.TouchApp, k.running) {
                if (k.running) R3cket.stopClicker() else if (k.points.isEmpty()) R3cket.setEditing(true) else R3cket.startClicker(ctx)
            }
            Tool(Icons.Rounded.GpsFixed, g?.crosshair == true) { R3cket.toggleCrosshair(ctx) }
            Tool(Icons.Rounded.DoNotDisturbOn, g?.dnd == true) { R3cket.toggleDnd(ctx) }
            Tool(Icons.Rounded.CameraAlt, false) { onPanel(false); c.later(450) { R3cket.screenshot() } }
        }
    }
}

@Composable
private fun Stat(name: String, value: String, color: Color) {
    Column(Modifier.padding(end = 14.dp)) {
        Text(name.uppercase(), color = Faint, style = caps(8.5f))
        Text(value, color = color, style = label().copy(fontFamily = Display, fontSize = 14.sp), maxLines = 1)
    }
}

@Composable
private fun Tool(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val k by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "tool")
    Box(Modifier.padding(start = 6.dp).size(40.dp).graphicsLayer { val sc = 1f + 0.06f * k; scaleX = sc; scaleY = sc }
        .clip(CircleShape).background(if (on) Lime else Glass).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(19.dp))
    }
}
