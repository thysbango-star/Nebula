package com.nebula.assistant.notch

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Nebula's own club moments — all drawn here in code, nothing copied from a broadcast package.

private fun ease(x: Float): Float { val c = x.coerceIn(0f, 1f); return 1f - (1f - c) * (1f - c) * (1f - c) }
private fun frac(x: Float) = x - kotlin.math.floor(x)

/** The open score card's background: both team colours drifting like a slow lava lamp, with a passing sheen. */
@Composable
internal fun LiveGradient(home: Color, away: Color, modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "live-gradient")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "p")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Brush.horizontalGradient(0f to home.copy(alpha = 0.85f), 0.5f to Color(0xFF0D0D10), 1f to away.copy(alpha = 0.85f)))
        val a = (p * 2 * PI).toFloat()
        val hc = Offset(w * (0.14f + 0.07f * sin(a)), h * (0.5f + 0.28f * cos(a)))
        val ac = Offset(w * (0.86f + 0.07f * sin(a + 2.1f)), h * (0.5f + 0.28f * cos(a + 2.1f)))
        drawCircle(Brush.radialGradient(listOf(lerp(home, Color.White, 0.15f).copy(alpha = 0.55f), Color.Transparent), hc, w * 0.42f), w * 0.42f, hc)
        drawCircle(Brush.radialGradient(listOf(lerp(away, Color.White, 0.15f).copy(alpha = 0.55f), Color.Transparent), ac, w * 0.42f), w * 0.42f, ac)
        // where the two colours meet, a soft blend that breathes
        val mid = Offset(w * (0.5f + 0.05f * sin(a * 2)), h * 0.5f)
        drawCircle(Brush.radialGradient(listOf(lerp(home, away, 0.5f).copy(alpha = 0.18f), Color.Transparent), mid, w * 0.3f), w * 0.3f, mid)
        val sx = frac(p * 1.5f) * (w + 300f) - 150f
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent), Offset(sx - 90f, 0f), Offset(sx + 90f, h)))
        drawRect(Brush.verticalGradient(listOf(Color(0x59000000), Color.Transparent, Color(0x8C000000))))
    }
}

// ------------------------------------------------------------------ cannons (Arsenal)

private val CANNON_FIRES = listOf(0.55f, 0.75f, 0.95f, 1.75f, 1.95f, 2.15f) // two volleys, left to right
private val CANNON_AT = listOf(0.16f, 0.5f, 0.84f)
private const val CANNON_TILT = 34f

/** Little screen shake after each shot. */
internal fun cannonShake(t: Float, u: Float): Offset {
    var x = 0f; var y = 0f
    for (f in CANNON_FIRES) { val k = t - f; if (k in 0f..0.16f) { val a = (1f - k / 0.16f) * 3.2f * u; x += a * sin(k * 140f); y += a * cos(k * 120f) } }
    return Offset(x, y)
}

/** A battery of three field cannons along the bottom, firing two volleys: recoil, muzzle flash, smoke, balls arcing over. */
internal fun DrawScope.drawCannons(t: Float, dir: Float) {
    val w = size.width; val h = size.height; val u = 1.dp.toPx()
    val appear = ease((t - 0.25f) / 0.35f); val leave = ease((t - 3.4f) / 0.4f)
    val drop = (1f - appear + leave) * 56 * u
    val rad = Math.toRadians(CANNON_TILT.toDouble()).toFloat()
    val vx = cos(rad) * dir; val vy = -sin(rad)
    CANNON_AT.forEachIndexed { i, at ->
        val cx = w * at; val baseY = h - 12 * u + drop
        val shots = listOf(CANNON_FIRES[i], CANNON_FIRES[i + 3])
        var recoil = 0f
        for (f in shots) { val k = t - f; if (k in 0f..0.35f) recoil = maxOf(recoil, sin(k / 0.35f * PI.toFloat()) * 6 * u) }
        // the gun
        withTransform({ translate(cx, baseY); if (dir < 0) scale(-1f, 1f, Offset.Zero) }) {
            rotate(-CANNON_TILT, Offset.Zero) {
                val bx = -12 * u - recoil
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF6B6F77), Color(0xFF2A2C31), Color(0xFF15161A)), -7 * u, 7 * u),
                    Offset(bx, -7 * u), Size(42 * u, 14 * u), CornerRadius(7 * u))
                drawRoundRect(Color(0xFF15161A), Offset(bx + 38 * u, -8.5f * u), Size(6 * u, 17 * u), CornerRadius(2.5f * u))
                drawRect(Color(0xFFC9A227), Offset(bx + 9 * u, -7.5f * u), Size(3 * u, 15 * u))
                drawRect(Color(0xFFC9A227), Offset(bx + 26 * u, -7f * u), Size(2.5f * u, 14 * u))
                drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(bx + 4 * u, -5 * u), Size(30 * u, 2.5f * u), CornerRadius(1.2f * u))
            }
            val carriage = Path().apply { moveTo(-16 * u, 2 * u); lineTo(14 * u, 2 * u); lineTo(8 * u, -7 * u); lineTo(-10 * u, -7 * u); close() }
            drawPath(carriage, Color(0xFF4A3220))
            for (wx in listOf(-8f, 8f)) {
                val c = Offset(wx * u, 4 * u)
                drawCircle(Color(0xFF2A1C12), 8 * u, c); drawCircle(Color(0xFF6E4A2C), 8 * u, c, style = Stroke(2 * u))
                for (k in 0 until 6) { val a = k * PI.toFloat() / 3 + t * 0.0f; drawLine(Color(0xFF6E4A2C), c, c + Offset(cos(a) * 7 * u, sin(a) * 7 * u), 1.2f * u) }
                drawCircle(Color(0xFFC9A227), 1.8f * u, c)
            }
        }
        // muzzle (in screen space)
        val mx = cx + vx * (31 * u - recoil); val my = baseY + vy * (31 * u - recoil)
        for (f in shots) {
            val k = t - f
            if (k in 0f..0.16f) {
                val r = 10 * u + 26 * u * (k / 0.16f)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), Color(0xFFFFC24A).copy(alpha = 0.8f), Color(0xFFFF5A1F).copy(alpha = 0f)), Offset(mx, my), r), r, Offset(mx, my))
            }
            if (k in 0f..1.5f) for (j in 0 until 6) { // smoke rolling out of the barrel and rising
                val kk = (k - j * 0.03f).coerceAtLeast(0f)
                val px = mx + vx * kk * (34 + j * 9) * u + sin(j * 2.3f + kk * 3) * 3 * u
                val py = my + vy * kk * (30 + j * 6) * u - kk * 14 * u
                val r = (5 + 15 * kk) * u * (0.7f + j * 0.12f)
                drawCircle(Color(0xFFE6E2DA).copy(alpha = (0.55f * (1f - kk / 1.5f)).coerceAtLeast(0f)), r, Offset(px, py))
            }
            if (k in 0f..1.2f) { // the ball: fast, arcing over the word
                val sp = w * 0.95f; val g = h * 2.2f
                val bx = mx + vx * sp * k; val by = my + vy * sp * k + 0.5f * g * k * k
                val tx = mx + vx * sp * (k - 0.06f).coerceAtLeast(0f); val ty = my + vy * sp * (k - 0.06f).coerceAtLeast(0f) + 0.5f * g * (k - 0.06f).coerceAtLeast(0f).let { it * it }
                drawLine(Color.White.copy(alpha = 0.35f), Offset(tx, ty), Offset(bx, by), 3 * u, StrokeCap.Round)
                drawCircle(Color(0xFF1A1A1E), 4.8f * u, Offset(bx, by))
                drawCircle(Color.White.copy(alpha = 0.5f), 1.5f * u, Offset(bx - 1.5f * u, by - 1.5f * u))
            }
        }
    }
}

// ------------------------------------------------------------------ birds (Brighton gulls, Newcastle magpies, Palace eagles)

internal data class BirdLook(val wing: Color, val patch: Color?, val body: Color, val head: Color, val big: Float)

internal fun birdLook(motif: String) = when (motif) {
    "magpies" -> BirdLook(Color(0xFF121316), Color.White, Color(0xFF121316), Color(0xFF121316), 1f)
    "eagles" -> BirdLook(Color(0xFF5B3A1E), null, Color(0xFF4A2E17), Color(0xFFF4F1EA), 1.35f)
    else -> BirdLook(Color(0xFFF6F7F9), Color(0xFF3B3F46), Color(0xFFF6F7F9), Color(0xFFF6F7F9), 1f) // gulls: white, dark wingtips
}

/** A flock flapping across the card from the scorer's side, near birds bigger and faster than far ones. */
internal fun DrawScope.drawBirds(t: Float, dir: Float, look: BirdLook, count: Int = 10) {
    val w = size.width; val h = size.height; val u = 1.dp.toPx()
    val birds = (0 until count).map { i -> Triple(i, 0.55f + 0.45f * frac(i * 0.37f + 0.11f), 0.14f + 0.72f * frac(i * 0.618f)) }.sortedBy { it.second }
    for ((i, depth, lane) in birds) {
        val delay = 0.3f + i * 0.11f; val dur = 2.9f - depth * 1.0f
        val p = (t - delay) / dur
        if (p !in 0f..1f) continue
        var x = -50 * u + (w + 100 * u) * p
        if (dir < 0) x = w - x
        val y = h * lane + sin(t * 3f + i) * 5 * u - p * 14 * u
        val s = (0.55f + 0.75f * depth) * look.big * u
        val flap = sin(t * (13f - depth * 3f) + i * 1.7f)
        val a = 0.55f + 0.45f * depth
        withTransform({ translate(x, y); if (dir < 0) scale(-1f, 1f, Offset.Zero) }) {
            // wings: a gull "M" whose tips beat up and down
            val tipY = -flap * 11 * s; val midY = -flap * 4 * s - 3 * s
            val wing = Path().apply {
                moveTo(-18 * s, tipY); quadraticBezierTo(-10 * s, midY - 2 * s, -2 * s, 0f)
                quadraticBezierTo(0f, -1.5f * s, 2 * s, 0f); quadraticBezierTo(10 * s, midY - 2 * s, 18 * s, tipY)
            }
            drawPath(wing, look.wing.copy(alpha = a), style = Stroke(3.4f * s, cap = StrokeCap.Round))
            look.patch?.let { pc -> // wingtips (gulls) or white panels (magpies)
                val tips = Path().apply { moveTo(-18 * s, tipY); lineTo(-13 * s, tipY * 0.7f + midY * 0.3f); moveTo(18 * s, tipY); lineTo(13 * s, tipY * 0.7f + midY * 0.3f) }
                drawPath(tips, pc.copy(alpha = a), style = Stroke(3.6f * s, cap = StrokeCap.Round))
            }
            drawOval(look.body.copy(alpha = a), Offset(-4 * s, -1.6f * s), Size(12 * s, 4.6f * s))
            drawCircle(look.head.copy(alpha = a), 2.4f * s, Offset(8 * s, -0.6f * s))
            drawLine(Color(0xFFF2B01E).copy(alpha = a), Offset(10 * s, -0.4f * s), Offset(12.8f * s, 0.4f * s), 1.3f * s, StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------------ cinematic (rugby nations)

/**
 * Film treatment: letterbox bars close in, the team's logo looms out of the far corner (cut off by the frame,
 * slowly pushing in), a lens flare sweeps across, with a little grain and vignette.
 */
internal fun DrawScope.drawCinematic(t: Float, logo: ImageBitmap?, scorerOnLeft: Boolean, tint: Color) {
    val w = size.width; val h = size.height; val u = 1.dp.toPx()
    // vignette
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), Offset(w / 2, h / 2), w * 0.62f))
    logo?.let { img ->
        val appear = ease((t - 0.3f) / 0.7f); val leave = ease((t - 3.5f) / 0.5f)
        val sz = h * 1.45f * (1f + 0.1f * (t / 4.5f).coerceIn(0f, 1f))
        val right = scorerOnLeft // the logo sits on the far side from the scorer's badge
        val cx = (if (right) w * 0.9f else w * 0.1f) + (1f - appear + leave) * sz * 0.45f * (if (right) 1 else -1)
        val cy = h * 0.72f
        withTransform({ translate(cx, cy); rotate(if (right) -14f else 14f, Offset.Zero) }) {
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset((-sz / 2).toInt(), (-sz / 2).toInt()), IntSize(sz.toInt(), sz.toInt()),
                alpha = 0.88f * appear * (1f - leave))
        }
    }
    // lens flare sweeping across
    val fp = (t - 0.75f) / 0.9f
    if (fp in 0f..1f) {
        val fx = (if (scorerOnLeft) fp else 1f - fp) * (w + 200 * u) - 100 * u; val fy = h * 0.42f
        val a = sin(fp * PI.toFloat())
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f * a), Color.Transparent), fx - 160 * u, fx + 160 * u), Offset(0f, fy - 1 * u), Size(w, 2 * u))
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f * a), lerp(tint, Color.White, 0.5f).copy(alpha = 0.25f * a), Color.Transparent), Offset(fx, fy), 26 * u), 26 * u, Offset(fx, fy))
        for (k in 1..3) { val gx = w / 2 + (w / 2 - fx) * (0.35f * k); drawCircle(Color.White.copy(alpha = 0.08f * a), (5 + k * 4) * u, Offset(gx, fy + (k * 6) * u)) }
    }
    // grain
    val rnd = java.util.Random((t * 24).toLong())
    repeat(140) { drawCircle(Color.White.copy(alpha = 0.05f), 0.7f * u, Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)) }
    // letterbox
    val bar = 14 * u * ease((t - 0.1f) / 0.35f) * (1f - ease((t - 3.6f) / 0.4f))
    if (bar > 0.5f) { drawRect(Color.Black, Offset.Zero, Size(w, bar)); drawRect(Color.Black, Offset(0f, h - bar), Size(w, bar)) }
}
