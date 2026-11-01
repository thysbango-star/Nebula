package com.nebula.assistant.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/** Nebula palette: deep space, violet core, magenta and cyan gas. */
object NebulaColors {
    val Void = Color(0xFF07060F)
    val Ink = Color(0xFF110D24)
    val Violet = Color(0xFF7B5CFF)
    val Lilac = Color(0xFFC28BFF)
    val Magenta = Color(0xFFFF5FA2)
    val Cyan = Color(0xFF3FD5FF)
    val Mint = Color(0xFF5CF2B0)
    val Amber = Color(0xFFFFC266)
    val Text = Color(0xFFF4F1FF)
    val TextDim = Color(0xA6F4F1FF)
    val TextFaint = Color(0x66F4F1FF)
    val Hairline = Color(0x1AFFFFFF)
}

/** Fine film grain that gives the glass its matte, frosted finish. */
val LocalGrain = staticCompositionLocalOf<Brush> { SolidColor(Color.Transparent) }

@Composable
private fun rememberGrainBrush(): Brush = remember {
    val size = 160
    val rnd = Random(7)
    val pixels = IntArray(size * size) {
        val v = rnd.nextInt(256)
        android.graphics.Color.argb(rnd.nextInt(34), v, v, v)
    }
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, size, 0, 0, size, size)
    ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

private val NebulaType = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun NebulaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NebulaColors.Violet,
            onPrimary = Color.White,
            secondary = NebulaColors.Magenta,
            tertiary = NebulaColors.Cyan,
            background = NebulaColors.Void,
            onBackground = NebulaColors.Text,
            surface = NebulaColors.Ink,
            onSurface = NebulaColors.Text,
        ),
        typography = NebulaType,
    ) {
        CompositionLocalProvider(
            LocalGrain provides rememberGrainBrush(),
            LocalContentColor provides NebulaColors.Text,
            content = content,
        )
    }
}
