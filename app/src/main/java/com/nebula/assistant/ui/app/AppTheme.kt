package com.nebula.assistant.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nebula.assistant.R

/** Nebula 2.0 colour tokens — warm soft grey + an ember gradient. */
@Immutable
data class NebulaPalette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val raised: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val line: Color,
) {
    val gold = Color(0xFFFFC23D)
    val ember = Color(0xFFF2A31B)
    val flame = Color(0xFFE8531E)
    val ruby = Color(0xFFC7301F)
    val ok = Color(0xFF2FB36B)
    val warn = Color(0xFFF2A31B)
    val bad = Color(0xFFE5484D)
    val violet = Color(0xFF9B6BFF)
    val pink = Color(0xFFEC5FA0)
    val orange = Color(0xFFF59E4B)
    val teal = Color(0xFF26A99A)
    val blue = Color(0xFF4C8DF6)
    val gradient: Brush get() = Brush.linearGradient(listOf(ember, flame, ruby))
    val gradientSoft: Brush get() = Brush.linearGradient(listOf(gold.copy(alpha = 0.9f), flame.copy(alpha = 0.9f)))
}

val LightPalette = NebulaPalette(false, Color(0xFFE7E5E2), Color(0xFFF1EFEC), Color(0xFFDDDAD6), Color(0xFFFAF9F7),
    Color(0xFF262422), Color(0xFF6F6A65), Color(0xFFA19B95), Color(0x14000000))
val DarkPalette = NebulaPalette(true, Color(0xFF121110), Color(0xFF1C1A18), Color(0xFF282522), Color(0xFF221F1D),
    Color(0xFFF3EFEA), Color(0xFFA8A19A), Color(0xFF6E6862), Color(0x17FFFFFF))

val LocalPalette = staticCompositionLocalOf { LightPalette }

@OptIn(ExperimentalTextApi::class)
private fun vf(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Display = FontFamily(vf(R.font.space_grotesk, 400), vf(R.font.space_grotesk, 500), vf(R.font.space_grotesk, 600), vf(R.font.space_grotesk, 700))
val Body = FontFamily(vf(R.font.manrope, 400), vf(R.font.manrope, 500), vf(R.font.manrope, 600), vf(R.font.manrope, 700), vf(R.font.manrope, 800))

private val Type = Typography(
    displayMedium = TextStyle(fontFamily = Display, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, lineHeight = 42.sp),
    displaySmall = TextStyle(fontFamily = Display, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, lineHeight = 35.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Display, fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = Body, fontSize = 16.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontFamily = Body, fontSize = 14.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = Body, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontFamily = Body, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontFamily = Body, fontSize = 13.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontFamily = Body, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontFamily = Body, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
)

@Composable
fun NebulaAppTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(primary = p.flame, onPrimary = Color.White, background = p.bg, surface = p.surface,
        onBackground = p.text, onSurface = p.text, surfaceVariant = p.surface2, outline = p.line)
    else lightColorScheme(primary = p.flame, onPrimary = Color.White, background = p.bg, surface = p.surface,
        onBackground = p.text, onSurface = p.text, surfaceVariant = p.surface2, outline = p.line)
    MaterialTheme(colorScheme = scheme, typography = Type) {
        CompositionLocalProvider(LocalPalette provides p, LocalContentColor provides p.text, content = content)
    }
}
