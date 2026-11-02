package com.nebula.assistant.voice

import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Small audio helpers: loudness, end-of-speech detection, WAV encoding. */
object Audio {
    const val RATE = 16_000

    fun rms(s: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = s[i] / 32768.0; sum += v * v }
        return sqrt(sum / max(1, n))
    }

    /** -60 dBFS → 0, -10 dBFS → 1 */
    fun level(rms: Double) = ((20 * log10(rms + 1e-9) + 60) / 50).toFloat().coerceIn(0f, 1f)

    fun wav(pcm: ShortArray, n: Int, rate: Int = RATE): ByteArray {
        val out = ByteArrayOutputStream(44 + n * 2)
        fun i32(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun i16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + n * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(n * 2)
        for (i in 0 until n) i16(pcm[i].toInt())
        return out.toByteArray()
    }
}

/**
 * Collects one spoken request: waits for speech, keeps ~400 ms before it, and stops after a
 * short pause. Adapts to the room's noise level.
 */
class Utterance(private val maxSeconds: Int = 15, private val waitSeconds: Int = 7, private val silenceMs: Int = 900) {
    private val buf = ShortArray(Audio.RATE * maxSeconds)
    private var n = 0
    private val pre = ArrayDeque<ShortArray>()
    private var heard = false
    private var quiet = 0
    private var total = 0
    private val noise = mutableListOf<Double>()
    var done = false; private set
    var empty = false; private set

    fun push(chunk: ShortArray, len: Int): Boolean {
        if (done) return true
        val r = Audio.rms(chunk, len)
        total += len
        if (!heard && noise.size < 3) noise += r
        val floor = if (noise.isEmpty()) 0.005 else noise.average()
        val thr = max(0.014, floor * 2.8)
        if (!heard) {
            pre.addLast(chunk.copyOf(len)); if (pre.size > 4) pre.removeFirst()
            if (r > thr) { heard = true; pre.forEach { append(it, it.size) }; pre.clear() }
            else if (total > Audio.RATE * waitSeconds) { done = true; empty = true }
            return done
        }
        append(chunk, len)
        quiet = if (r < thr * 0.8) quiet + len else 0
        if (quiet >= Audio.RATE * silenceMs / 1000 || n >= buf.size) done = true
        return done
    }

    fun finishNow() { done = true; empty = !heard }

    private fun append(c: ShortArray, len: Int) {
        val k = minOf(len, buf.size - n); System.arraycopy(c, 0, buf, n, k); n += k
    }

    fun wav(): ByteArray = Audio.wav(buf, n)
    val seconds get() = n / Audio.RATE.toFloat()
}
