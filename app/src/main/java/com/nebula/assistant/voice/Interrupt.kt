package com.nebula.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.nebula.assistant.data.NebulaPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Voice Interrupt: talk over Nebula and it stops and listens.
 *
 * States (what [VoiceSession] goes through):
 *  SPEAKING → INTERRUPT_DETECTED (TTS stopped at once) → CAPTURING_INTERRUPT (your words, until you pause)
 *  → PROCESSING_INTERRUPT (Whisper + what kind of interruption) → RESUMING (the same conversation, new direction)
 *  or CANCELLED (you said "stop").
 *
 * Detection is deliberately light: no speech model runs while Nebula talks — only a voice-activity check on the mic
 * (with the phone's echo canceller and noise suppressor on), which needs sustained, voice-loud sound well above the
 * echo of Nebula's own voice. Only once that fires does Whisper run on what you said.
 */
object Interrupt {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING, INTERRUPT_DETECTED, CAPTURING_INTERRUPT, PROCESSING_INTERRUPT, RESUMING, CANCELLED }
    enum class Kind { STOP, SHORTEN, EXPAND, VISUALISE, CLARIFY, REDIRECT }

    @Volatile var state: State = State.IDLE

    fun headphones(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET) || (android.os.Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }.getOrDefault(false)

    fun enabled(ctx: Context, prefs: NebulaPrefs) = prefs.interruptOn && prefs.speakReplies && (!prefs.interruptHeadphonesOnly || headphones(ctx))

    /** Thresholds from the sensitivity setting; stricter on the loudspeaker, where Nebula's own voice leaks back in. */
    data class Tuning(val ratio: Double, val minRms: Double, val frames: Int)
    fun tuning(sens: Float, speaker: Boolean): Tuning {
        val s = sens.coerceIn(0f, 1f).toDouble()
        val ratio = 4.2 - 2.0 * s            // how far above the room/echo level your voice has to be
        val min = 0.030 - 0.016 * s          // and an absolute floor (≈ -30 to -40 dBFS)
        val frames = (9 - 4 * s).toInt()     // sustained for 450 → 250 ms (50 ms frames)
        return if (speaker) Tuning(ratio * 1.35, min * 1.4, frames + 1) else Tuning(ratio, min, frames)
    }

    /**
     * Pure detector, fed 50 ms frames of mic level. Learns the echo/room floor from frames that aren't speech, and fires
     * after [Tuning.frames] loud frames in a row (one quieter frame is forgiven, as between syllables).
     */
    class Detector(private val t: Tuning, private val warmupFrames: Int = 8) {
        private var floor = -1.0
        private var run = 0; private var gap = 0; private var seen = 0
        fun push(rms: Double): Boolean {
            seen++
            if (floor < 0) floor = rms
            val thr = max(t.minRms, floor * t.ratio)
            if (seen <= warmupFrames) { floor = floor * 0.8 + rms * 0.2; return false } // TTS starting up: just learn
            if (rms > thr) { run++; gap = 0 }
            else {
                if (run > 0 && gap == 0 && rms > thr * 0.6) gap = 1 else { run = 0; gap = 0 }
                floor = floor * 0.95 + rms * 0.05
            }
            return run >= t.frames
        }
    }

    /**
     * Listens while Nebula speaks. Returns your interruption (already including the frames that set it off), or null
     * if Nebula finished first ([done] turned true) or the session ended. [onDetected] fires the moment speech is
     * detected, so TTS can be stopped before you've finished your sentence.
     */
    @SuppressLint("MissingPermission")
    suspend fun listen(ctx: Context, prefs: NebulaPrefs, done: () -> Boolean, stopped: () -> Boolean, onDetected: () -> Unit): Utterance? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(Audio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_COMMUNICATION turns on the phone's own echo cancellation on most devices.
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, Audio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Audio.RATE / 2)) }.getOrNull()
            ?: return@withContext null
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val det = Detector(tuning(prefs.interruptSens, !headphones(ctx)))
        val chunk = ShortArray(Audio.RATE / 20)
        val recent = ArrayDeque<ShortArray>()
        try {
            rec.startRecording()
            while (coroutineContext.isActive && !stopped()) {
                if (done()) return@withContext null
                val n = rec.read(chunk, 0, chunk.size); if (n <= 0) continue
                recent.addLast(chunk.copyOf(n)); if (recent.size > 12) recent.removeFirst()
                if (det.push(Audio.rms(chunk, n))) {
                    state = State.INTERRUPT_DETECTED
                    onDetected()
                    state = State.CAPTURING_INTERRUPT
                    // Keep recording until you pause; your first words (the frames that triggered this) are kept.
                    val u = Utterance(maxSeconds = 12, waitSeconds = 3, silenceMs = 800)
                    recent.forEach { u.push(it, it.size) }
                    while (coroutineContext.isActive && !stopped()) {
                        val k = rec.read(chunk, 0, chunk.size); if (k <= 0) continue
                        VoiceState.level(Audio.level(Audio.rms(chunk, k)))
                        if (u.push(chunk, k)) break
                    }
                    return@withContext u
                }
            }
            null
        } finally {
            runCatching { rec.stop() }; rec.release(); runCatching { aec?.release() }; runCatching { ns?.release() }
        }
    }

    /** What kind of interruption this is. Short phrases only — anything else is a new direction. */
    fun classify(text: String): Kind {
        val t = " " + text.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val words = t.trim().split(' ').size
        fun has(vararg p: String) = p.any { t.contains(" $it ") }
        return when {
            words <= 4 && has("stop", "that's enough", "thats enough", "enough", "quiet", "be quiet", "cancel", "shut up", "never mind", "nevermind", "okay stop", "ok stop") -> Kind.STOP
            has("shorter", "quickly", "short version", "one sentence", "tldr", "tl dr", "in short", "summarise", "summarize", "keep it short", "too long", "get to the point") -> Kind.SHORTEN
            has("explain more", "go deeper", "more detail", "the detail", "details", "tell me more", "elaborate", "expand", "keep going", "go on") -> Kind.EXPAND
            has("show me", "draw it", "draw that", "visualise", "visualize", "give me an example", "an example", "diagram", "picture") -> Kind.VISUALISE
            has("what do you mean", "explain that bit", "why", "what does that mean", "how so", "wait what", "which one", "what's that") && words <= 8 -> Kind.CLARIFY
            else -> Kind.REDIRECT
        }
    }

    /** What Nebula is asked to do next, in the same conversation (so it keeps all the context). */
    fun followUp(kind: Kind, heard: String, saying: String, ack: Boolean): String {
        val cut = saying.trim().takeLast(220)
        val a = if (ack) " Begin with a short, natural acknowledgement (like \"Got you —\" or \"Sure —\"), never a robotic one." else ""
        return when (kind) {
            Kind.STOP -> heard
            Kind.SHORTEN -> "$heard\n(I cut in while you were answering. Give me the short version of your last answer: one or two sentences.$a)"
            Kind.EXPAND -> "$heard\n(I cut in: go deeper on your last answer with more detail.$a)"
            Kind.VISUALISE -> "$heard\n(I cut in: show it visually if you can — e.g. a concrete worked example, or make a document with a table or diagram — otherwise walk me through an example.$a)"
            Kind.CLARIFY -> "$heard\n(I cut in right after you said: \"$cut\". Answer exactly that point, briefly.$a)"
            Kind.REDIRECT -> "$heard\n(I interrupted your last answer — follow what I just said instead.$a)"
        }
    }
}
