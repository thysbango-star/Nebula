package com.nebula.assistant.notch

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.assistant.R
import com.nebula.assistant.voice.VoiceState
import kotlin.math.abs
import kotlin.math.sin

/*
 * The Notch's shared kit: one palette, one type scale, one way of arriving.
 *
 * Every view in the island is built from these pieces so the whole thing reads as one object:
 *  - the Nebula gradient (ember → flame → ruby) is the signature, used for rims, rings and accents;
 *  - content never just "appears": each row rises in on a short cascade ([rise]) after the shape has morphed;
 *  - nothing is blurred live (blur was the main cause of dropped frames), depth comes from gradients instead.
 */

internal val Ember = Color(0xFFF2A31B)
internal val Flame = Color(0xFFE8531E)
internal val Ruby = Color(0xFFC7301F)
internal val Mint = Color(0xFF4ADE80)
internal val Lime = Color(0xFFB8FF3D)
internal val Sky = Color(0xFF5AA9FF)
internal val Violet = Color(0xFF9B7BFF)
internal val Red = Color(0xFFEF4444)
internal val Dim = Color(0x99FFFFFF)
internal val Faint = Color(0x59FFFFFF)
internal val Glass = Color(0x24FFFFFF)
internal val Ink = Color(0xFF0B0B0D)
internal val NotchGradient = Brush.linearGradient(listOf(Ember, Flame, Ruby))
internal val NebulaColors = listOf(Ember, Flame, Ruby)

// Static cuts of the variable fonts (made from the OFL originals): a variable font with no axis applied falls back
// to its lightest weight on some renderers, which is why the island's type could look thin.
internal val Display = FontFamily(Font(R.font.space_grotesk_medium, FontWeight.Medium), Font(R.font.space_grotesk_semibold, FontWeight.SemiBold))
internal val Condensed = FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)))
internal val Body = FontFamily(Font(R.font.manrope_medium, FontWeight.Medium), Font(R.font.manrope_semibold, FontWeight.SemiBold), Font(R.font.manrope_bold, FontWeight.Bold))
/** The broadcast weight, for scores and big words. */
internal val Heavy = FontFamily(Font(R.font.manrope_extrabold, FontWeight.ExtraBold))

internal fun label() = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp)
internal fun big(size: Int) = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = size.sp, lineHeight = (size * 1.15f).sp)
/** Broadcast type: heavy, tight, for scores and big words. */
internal fun heavy(size: Int, italic: Boolean = false) = TextStyle(fontFamily = Heavy, fontWeight = FontWeight.ExtraBold, fontSize = size.sp, lineHeight = (size * 1.02f).sp,
    letterSpacing = (-0.02 * size).sp, fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal)
internal fun caps(size: Float = 10f) = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = (size * 0.16f).sp)

internal fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

internal fun easeOut(x: Float): Float { val c = x.coerceIn(0f, 1f); return 1f - (1f - c) * (1f - c) * (1f - c) }
internal fun easeInOut(x: Float): Float { val c = x.coerceIn(0f, 1f); return if (c < 0.5f) 4 * c * c * c else 1 - (-2 * c + 2).let { it * it * it } / 2 }
internal fun backOut(x: Float): Float { val c = x.coerceIn(0f, 1f) - 1f; return 1f + 2.4f * c * c * c + 1.4f * c * c }
/** 0→1 over [from]..[to] seconds of [t], eased. */
internal fun seg(t: Float, from: Float, to: Float, ease: (Float) -> Float = ::easeOut) = ease(((t - from) / (to - from)).coerceIn(0f, 1f))

/** Darker / lighter versions of a team or theme colour. */
internal fun Color.deep(k: Float = 0.45f) = lerp(this, Color.Black, k)
internal fun Color.light(k: Float = 0.35f) = lerp(this, Color.White, k)
/** Black or white, whichever reads on this colour (the scoreboards use black on bright kits, like the reference). */
internal fun inkOn(c: Color) = if (c.luminance() > 0.1f) Ink else Color.White

// ------------------------------------------------------------------ arrival cascade

/** How far the current view has arrived (0 → 1), shared by every row in it. */
internal val LocalAppear = compositionLocalOf<State<Float>> { mutableFloatStateOf(1f) }

/** Rows rise in one after another: [i] is the row's place in the cascade. */
@Composable
internal fun Modifier.rise(i: Int, distance: Dp = 10.dp): Modifier {
    val a = LocalAppear.current
    return this.graphicsLayer {
        val p = easeOut(((a.value * 1.6f) - i * 0.12f).coerceIn(0f, 1f))
        alpha = p; translationY = (1f - p) * distance.toPx(); val k = 0.96f + 0.04f * p; scaleX = k; scaleY = k
    }
}

/** Seconds since this composable (or [key]) appeared, ticking every frame — for scripted moments. */
@Composable
internal fun rememberClock(key: Any?): State<Float> = produceState(0f, key) {
    val st = withFrameNanos { it }
    while (true) withFrameNanos { value = (it - st) / 1e9f }
}

// ------------------------------------------------------------------ shared pieces

@Composable
internal fun Compact(left: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        left(); Spacer(Modifier.weight(1f)); right()
    }
}

/** An image from ESPN (team or competition logo), cached; [tint] turns it into a silhouette (white lion on the board). */
@Composable
internal fun LogoImage(url: String, size: Dp, modifier: Modifier = Modifier, tint: Color? = null) {
    val ctx = LocalContext.current
    val bmp by produceState(com.nebula.assistant.sports.Logos.cached(url), url) {
        if (value == null && url.isNotBlank()) value = com.nebula.assistant.sports.Logos.get(ctx, url)
    }
    val img = remember(bmp) { bmp?.asImageBitmap() }
    if (img != null) Image(img, null, contentScale = ContentScale.Fit, modifier = modifier.size(size),
        colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.SrcIn) })
    else Spacer(modifier.size(size))
}

@Composable
internal fun rememberLogo(url: String): ImageBitmap? {
    val ctx = LocalContext.current
    val bmp by produceState(com.nebula.assistant.sports.Logos.cached(url), url) {
        if (value == null && url.isNotBlank()) value = com.nebula.assistant.sports.Logos.get(ctx, url)
    }
    return remember(bmp) { bmp?.asImageBitmap() }
}

/** Nebula's living orb: breathes when listening (with your voice), spins while thinking. */
@Composable
internal fun Orb(phase: VoiceState.Phase, level: Float, dim: Dp) {
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
internal fun LiveWave(level: Float, modifier: Modifier, bars: Int = 28) {
    val hist = remember { FloatArray(bars) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(level) { System.arraycopy(hist, 1, hist, 0, bars - 1); hist[bars - 1] = level; frame++ }
    val t = rememberInfiniteTransition(label = "wave")
    val ph by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ph")
    Canvas(modifier) {
        frame.let { }
        val gap = size.width / (bars * 2f)
        for (i in 0 until bars) {
            val centre = 1f - abs(i - (bars - 1) / 2f) / (bars / 2f) * 0.55f
            val idle = 0.14f + 0.08f * ((sin(ph + i * 0.5f) + 1) / 2)
            val a = (idle + hist[i] * 0.95f * centre).coerceIn(0.1f, 1f)
            val hh = size.height * a
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF9BE7FF), Color(0xFFFFB3D9))),
                Offset(i * gap * 2 + gap / 2, (size.height - hh) / 2), Size(gap, hh), CornerRadius(gap / 2))
        }
    }
}

@Composable
internal fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p")
    Canvas(Modifier.size(width = 34.dp, height = 14.dp)) {
        for (i in 0 until 3) {
            val k = ((sin((p - i * 0.18f) * 6.283f) + 1) / 2)
            drawCircle(NotchGradient, 3.dp.toPx(), Offset(size.width * (0.2f + i * 0.3f), size.height / 2 - k * 3.dp.toPx()), alpha = 0.3f + 0.7f * k)
        }
    }
}

/** A gradient ring that fills to [progress] (0..1) with a soft comet head. */
@Composable
internal fun GradientRing(progress: Float, modifier: Modifier, stroke: Dp = 2.6.dp, colors: List<Color> = NebulaColors, track: Color = Color(0x2EFFFFFF)) {
    Canvas(modifier) {
        val s = stroke.toPx()
        val inset = s / 2
        val sz = Size(size.width - s, size.height - s)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(s))
        rotate(-90f) { drawArc(Brush.sweepGradient(colors + colors.first()), 0f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), sz, style = Stroke(s, cap = StrokeCap.Round)) }
    }
}

@Composable
internal fun Avatar(bmp: android.graphics.Bitmap?, letter: String, size: Dp) {
    if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(NotchGradient), contentAlignment = Alignment.Center) {
        Text(letter.uppercase(), color = Color.White, style = label().copy(fontSize = (size.value * 0.4f).sp))
    }
}

/** Big rounded button. */
@Composable
internal fun ActionPill(text: String, modifier: Modifier = Modifier, tint: Color? = null, brush: Brush? = null, onClick: () -> Unit) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
        .then(if (brush != null) Modifier.background(brush) else Modifier.background(tint?.copy(alpha = 0.92f) ?: Glass))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = if (tint == Lime || tint == Mint) Color.Black else Color.White, style = label().copy(fontSize = 14.sp))
    }
}

@Composable
internal fun CloseDot(onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(Glass).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Close, "Close", tint = Dim, modifier = Modifier.size(15.dp))
    }
}

@Composable
internal fun IconTap(icon: ImageVector, size: Dp, tint: Color = Color.White, onClick: () -> Unit) =
    Icon(icon, null, tint = tint, modifier = Modifier.size(size + 12.dp).clip(CircleShape).clickable(onClick = onClick).padding(6.dp))
