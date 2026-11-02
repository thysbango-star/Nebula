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
            while (again && coroutineContext.isActive && !stopRequested) {
                again = false
                VoiceState.update { it.copy(phase = VoiceState.Phase.LISTENING, transcript = "", level = 0f) }
                val u = record() ?: break
                if (u.empty) { fail("I didn't catch that."); return }
                Sounds.play(context, NebulaSound.LISTEN_END)
                VoiceState.update { it.copy(phase = VoiceState.Phase.THINKING, level = 0f) }
                val text = try { Hearing.transcribe(prefs, u.wav()) } catch (e: Exception) { fail(e.message ?: "I couldn't hear that."); return }
                if (text.isBlank() || text.trim('.', ' ').isEmpty()) { fail("I didn't catch that."); return }
                VoiceState.update { it.copy(transcript = text) }
                val reply = brain.respond(text)
                VoiceState.update { it.copy(phase = VoiceState.Phase.SPEAKING, reply = reply.text, sources = reply.sources, taskId = reply.taskId) }
                if (prefs.speakReplies) speaker.speak(reply.text) { VoiceState.level(it) }
                again = reply.expectsAnswer
            }
            if (stopRequested) return
            VoiceState.update { it.copy(phase = VoiceState.Phase.DONE, level = 0f) }
            delay((5000L + VoiceState.session.value.reply.length * 35L).coerceAtMost(20_000L))
        } finally {
            if (!stopRequested) end()
        }
    }

    private suspend fun fail(msg: String) {
        VoiceState.update { it.copy(phase = VoiceState.Phase.ERROR, reply = msg, level = 0f) }
        Sounds.play(context, NebulaSound.SOFT)
        delay(3500)
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(): Utterance? = withContext(Dispatchers.IO) {
        finishListening = false
        val min = AudioRecord.getMinBufferSize(Audio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Audio.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Audio.RATE / 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null.also { fail("The microphone is busy.") } }
        val u = Utterance()
        val chunk = ShortArray(Audio.RATE / 20) // 50 ms
        try {
            rec.startRecording()
            while (coroutineContext.isActive && !stopRequested) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                VoiceState.level(Audio.level(Audio.rms(chunk, n)))
                if (finishListening) { u.finishNow(); break }
                if (u.push(chunk, n)) break
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        if (stopRequested) null else u
    }

    fun release() { speaker.release() }
}
