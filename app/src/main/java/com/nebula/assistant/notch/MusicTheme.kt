package com.nebula.assistant.notch

import android.graphics.Bitmap
import android.media.audiofx.Visualizer
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Song themes: each track dresses the notch in its album art.
 *  - "mono"  — black-and-white covers: brushed silver with dark type and a slow spotlight + film grain,
 *  - "vivid" — colourful covers: the cover's two strongest colours, with soft colour blobs drifting,
 *  - "night" — dark covers: deep tones with a glow and floating light specks.
 */
object MusicTheme {
    data class Theme(val style: String, val bg1: Color, val bg2: Color, val slab: Color, val accent: Color, val text: Color, val sub: Color, val blobs: List<Color>)

    val DEFAULT = Theme("night", Color(0xFF1C1430), Color(0xFF0B0B10), Color(0x22FFFFFF), Color(0xFFA55CFF), Color.White, Color(0xB3FFFFFF),
        listOf(Color(0xFFA55CFF), Color(0xFF4D8DFF)))

    private var lastKey = 0
    private var last = DEFAULT

    fun of(art: Bitmap?): Theme {
        if (art == null) return DEFAULT
        val key = System.identityHashCode(art) * 31 + art.generationId
        if (key == lastKey) return last
        last = runCatching { compute(art) }.getOrDefault(DEFAULT); lastKey = key
        return last
    }

    private fun compute(src: Bitmap): Theme {
        val b = Bitmap.createScaledBitmap(src, 24, 24, true)
        val hsv = FloatArray(3)
        var satSum = 0f; var lumSum = 0f; var n = 0
        val buckets = Array(12) { FloatArray(5) } // weight, r, g, b, count
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val bl = (c and 255) / 255f
            android.graphics.Color.colorToHSV(c, hsv)
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * bl
            satSum += hsv[1] * hsv[2]; lumSum += lum; n++
            if (hsv[1] > 0.25f && hsv[2] > 0.2f) {
                val k = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                val w = hsv[1] * hsv[2]
                buckets[k][0] += w; buckets[k][1] += r * w; buckets[k][2] += g * w; buckets[k][3] += bl * w; buckets[k][4] += 1f
            }
        }
        if (b !== src) b.recycle()
        val avgSat = satSum / n; val avgLum = lumSum / n
        fun col(k: Int) = buckets[k].let { Color(it[1] / it[0], it[2] / it[0], it[3] / it[0]) }
        val ranked = (0 until 12).filter { buckets[it][0] > 0f }.sortedByDescending { buckets[it][0] }
        if (avgSat < 0.12f || ranked.isEmpty()) {
            return if (avgLum > 0.32f) Theme("mono", Color(0xFFEDEDED), Color(0xFF8E8E8E), Color(0x66FFFFFF), Color(0xFF111111), Color(0xFF0E0E0E), Color(0xCC1A1A1A),
                listOf(Color.White, Color(0xFFBDBDBD)))
            else Theme("mono", Color(0xFF3A3A3D), Color(0xFF0E0E10), Color(0x22FFFFFF), Color.White, Color.White, Color(0xB3FFFFFF), listOf(Color(0xFF8E8E8E), Color(0xFF4A4A4A)))
        }
        val main = col(ranked[0])
        val second = ranked.drop(1).firstOrNull { abs(it - ranked[0]).let { d -> minOf(d, 12 - d) } >= 2 }?.let(::col)
            ?: androidx.compose.ui.graphics.lerp(main, Color.Black, 0.5f)
        val dark = avgLum < 0.2f
        val bg1 = androidx.compose.ui.graphics.lerp(main, Color.Black, if (dark) 0.55f else 0.3f)
        val bg2 = androidx.compose.ui.graphics.lerp(second, Color.Black, if (dark) 0.7f else 0.5f)
        val accent = androidx.compose.ui.graphics.lerp(main, Color.White, 0.25f)
        return Theme(if (dark) "night" else "vivid", bg1, bg2, Color(0x1FFFFFFF), accent, Color.White, Color(0xC8FFFFFF), listOf(main, second, accent))
    }

    // ---------------------------------------------------------------- live audio bars

    private val _bands = MutableStateFlow(FloatArray(16))
    /** 16 bands, 0..1, from what's actually playing (needs the microphone permission Nebula already has). */
    val bands: StateFlow<FloatArray> = _bands
    @Volatile private var viz: Visualizer? = null
    private var users = 0

    @Synchronized fun startBars(): Boolean {
        users++
        if (viz != null) return true
        return runCatching {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                    fft ?: return
                    val out = FloatArray(16); val bins = fft.size / 2
                    for (i in 0 until 16) {
                        // log-spaced bands, so the bass doesn't swallow everything
                        val a = (Math.pow(bins.toDouble(), i / 16.0)).toInt().coerceIn(1, bins - 1)
                        val e = (Math.pow(bins.toDouble(), (i + 1) / 16.0)).toInt().coerceIn(a + 1, bins)
                        var m = 0f
                        for (k in a until e) m = maxOf(m, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
                        out[i] = (ln(1f + m) / 5f).coerceIn(0f, 1f)
                    }
                    _bands.value = out
                }
            }, (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(10000), false, true)
            v.enabled = true
            viz = v
            true
        }.getOrDefault(false)
    }

    @Synchronized fun stopBars() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { runCatching { viz?.enabled = false; viz?.release() }; viz = null; _bands.value = FloatArray(16) }
    }
}
