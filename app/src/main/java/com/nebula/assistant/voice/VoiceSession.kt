package com.nebula.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.nebula.assistant.audio.NebulaSound
import com.nebula.assistant.audio.Sounds
import com.nebula.assistant.brain.AgentBrain
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.game.R3cket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * "Hey Nebula" inside the Notch: listen → Whisper → task engine → natural voice → (listen again
 * if Nebula asked something) → close. Runs inside the microphone foreground service.
 */
class VoiceSession(private val context: Context, private val scope: CoroutineScope, private val onFinished: () -> Unit) {

    private val prefs = NebulaPrefs(context)
    private val speaker = Speaker(context).also { it.init() }
    private val brain = AgentBrain(context)
    private var job: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var finishListening = false

    val running get() = job?.isActive == true

    fun start() {
        if (running) { finishListening = true; return } // second trigger = "I'm done talking"
        stopRequested = false
        VoiceState.onStop = { stop() }
        VoiceState.onAgain = { if (!running) start() else finishListening = true }
        job = scope.launch { runTurns() }
    }

    fun stop() {
        stopRequested = true
        speaker.stop()
        job?.cancel()
        end()
    }

    private fun end() {
        VoiceState.reset()
        onFinished()
    }

    private suspend fun runTurns() {
        val gaming = R3cket.inGame.value
        VoiceState.update { VoiceState.Session(phase = VoiceState.Phase.LISTENING, gaming = gaming, startedAt = System.currentTimeMillis()) }
        try {
            delay(350) // let the wake-word mic close and the chime finish before we record
            var again = true
            var followUp = false
            var asked = false
            var quietEnd = false
            while (again && coroutineContext.isActive && !stopRequested) {
                again = false
                VoiceState.update { it.copy(phase = VoiceState.Phase.LISTENING, transcript = "", level = 0f, followUp = followUp) }
                if (followUp) delay(250) // let the last word of Nebula's voice fade before listening
                // A follow-up only waits a few seconds for you to start talking (longer if Nebula asked you something).
                // First turn: the wake sound is still ringing for ~1.2 s, so don't treat it as you talking.
                val u = record(if (!followUp) 7 else if (asked) 8 else 5, ignoreMs = if (!followUp) 1200 else 0) ?: break
                if (u.empty) { if (followUp) { quietEnd = true; break } else { fail("I didn't catch that."); return } }
                Sounds.play(context, NebulaSound.LISTEN_END)
                VoiceState.update { it.copy(phase = VoiceState.Phase.THINKING, level = 0f, followUp = false) }
                Sounds.play(context, NebulaSound.THINKING)
                val text = try { Hearing.transcribe(prefs, u.wav()) } catch (e: Exception) { fail(e.message ?: "I couldn't hear that."); return }
                if (text.isBlank() || text.trim('.', ' ').isEmpty()) { fail("I didn't catch that."); return }
                val heard = Names.fixHeard(prefs, text)
                VoiceState.update { it.copy(transcript = heard) }
                val reply = brain.respond(heard)
                VoiceState.update { it.copy(phase = VoiceState.Phase.SPEAKING, reply = reply.text, sources = reply.sources, taskId = reply.taskId) }
                if (prefs.speakReplies) speaker.speak(Names.forSpeech(prefs, reply.text)) { VoiceState.level(it) }
                // Conversation mode: keep listening for a follow-up instead of needing a button.
                asked = reply.expectsAnswer
                again = asked || prefs.followUps
                followUp = true
            }
            if (stopRequested) return
            VoiceState.update { it.copy(phase = VoiceState.Phase.DONE, level = 0f, followUp = false) }
            // You already had time to read while Nebula listened for a follow-up, so close soon after silence.
            delay(if (quietEnd) 900L else (5000L + VoiceState.session.value.reply.length * 35L).coerceAtMost(20_000L))
        } finally {
            if (!stopRequested) end()
        }
    }

    private suspend fun fail(msg: String) {
        com.nebula.assistant.support.Reporter.note("voice: $msg")
        VoiceState.update { it.copy(phase = VoiceState.Phase.ERROR, reply = msg, level = 0f) }
        Sounds.play(context, NebulaSound.FAILURE)
        delay(6500) // long enough to tap "Report to Claude"
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(waitSeconds: Int = 7, ignoreMs: Int = 0): Utterance? = withContext(Dispatchers.IO) {
        finishListening = false
        val min = AudioRecord.getMinBufferSize(Audio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Audio.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Audio.RATE / 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null.also { fail("The microphone is busy.") } }
        val u = Utterance(waitSeconds = waitSeconds + ignoreMs / 1000)
        val startedAt = System.currentTimeMillis()
        val chunk = ShortArray(Audio.RATE / 20) // 50 ms
        try {
            rec.startRecording()
            while (coroutineContext.isActive && !stopRequested) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                VoiceState.level(Audio.level(Audio.rms(chunk, n)))
                if (finishListening) { u.finishNow(); break }
                if (System.currentTimeMillis() - startedAt < ignoreMs) { u.preroll(chunk, n); continue }
                if (u.push(chunk, n)) break
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        if (stopRequested) null else u
    }

    fun release() { speaker.release() }
}
