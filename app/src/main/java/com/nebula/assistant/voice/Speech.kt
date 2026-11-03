package com.nebula.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nebula.assistant.data.NebulaPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/** Hearing (Groq Whisper) for the in-Notch voice session. */
object Hearing {
    /** 16 kHz WAV → text. Throws with a speakable message on failure. */
    suspend fun transcribe(prefs: NebulaPrefs, wav: ByteArray, attempt: Int = 0): String = withContext(Dispatchers.IO) {
        val key = NebulaPrefs.cleanKey(prefs.groqKey)
        if (key.isEmpty()) throw IllegalStateException("I need my AI key first. Add your Groq key in Nebula's settings.")
        var last: Exception? = null
        for (model in listOf("whisper-large-v3-turbo", "whisper-large-v3")) {
            try {
                val boundary = "nebula${System.nanoTime()}"
                val body = ByteArrayOutputStream()
                fun field(name: String, value: String) = body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                field("model", model); field("response_format", "json"); field("temperature", "0")
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                body.write(wav); body.write("\r\n--$boundary--\r\n".toByteArray())
                val c = (URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                c.disconnect()
                if (code in 200..299) return@withContext JSONObject(text).optString("text").trim()
                if ((code == 401 || code == 429 && "per day" in text) && attempt < prefs.allGroqKeys.size - 1 && prefs.nextGroqKey()) {
                    return@withContext transcribe(prefs, wav, attempt + 1) // try with the next key
                }
                last = IllegalStateException(if (code == 401) "My Groq key isn't working." else if (code == 429) "Groq is busy, try again in a moment." else "I couldn't understand that ($code).")
                if (code != 404) break
            } catch (e: java.net.UnknownHostException) { throw IllegalStateException("I can't reach the internet right now.")
            } catch (e: IllegalStateException) { throw e
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("I couldn't understand that.")
    }
}

/**
 * Nebula's voice. "groq" = Orpheus (natural, free with the Groq key), "eleven" = ElevenLabs
 * (needs their free key), "system" = the phone's voice. Falls back to the phone voice on any error.
 */
class Speaker(private val context: Context) : TextToSpeech.OnInitListener {
    private val prefs = NebulaPrefs(context)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    @Volatile private var stopped = false
    /** Last cloud-voice problem, shown in Settings so the user knows why it fell back. */
    var lastProblem: String? = null; private set
    @Volatile private var lastCode = 0

    fun init() { if (tts == null) tts = TextToSpeech(context, this) }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.getDefault())
            tts?.setSpeechRate(1.03f); ttsReady = true
        }
    }

    fun stop() { stopped = true; runCatching { player?.stop() }; player?.release(); player = null; tts?.stop() }
    fun release() { stop(); tts?.shutdown(); tts = null }

    /** Speaks [text]; [onLevel] gets a gentle 0..1 pulse for animations. Returns when finished. */
    suspend fun speak(text: String, onLevel: (Float) -> Unit = {}) {
        stopped = false
        if (text.isBlank()) return
        val engine = prefs.voiceEngine
        val ok = when (engine) {
            "groq" -> cloud(text, onLevel) { groqChunk(it) }
            "eleven" -> cloud(text, onLevel) { elevenChunk(it) }
            else -> false
        }
        if (!ok && !stopped) system(text)
    }

    private suspend fun cloud(text: String, onLevel: (Float) -> Unit, fetch: suspend (String) -> Pair<ByteArray, String>?): Boolean {
        val chunks = chunk(text, if (prefs.voiceEngine == "groq") 190 else 900)
        // Fetch the next chunk while the current one plays, so there's no gap.
        var next: Pair<ByteArray, String>? = fetch(chunks.first()) ?: return false
        for (i in chunks.indices) {
            if (stopped) return true
            val cur = next ?: return i > 0
            next = null
            val prefetch = if (i + 1 < chunks.size) kotlinx.coroutines.coroutineScope {
                val d = async(Dispatchers.IO) { fetch(chunks[i + 1]) }
                play(cur, onLevel); d.await()
            } else { play(cur, onLevel); null }
            next = prefetch
            if (i + 1 < chunks.size && next == null) return true // spoke what we could
        }
        return true
    }

    private suspend fun play(audio: Pair<ByteArray, String>, onLevel: (Float) -> Unit) {
        val f = File(context.cacheDir, "nebula_voice_${System.nanoTime()}.${audio.second}")
        f.writeBytes(audio.first)
        try {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                mp.setDataSource(f.path)
                mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
                cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
            }
        } finally {
            onLevel(0f); runCatching { player?.release() }; player = null; f.delete()
        }
    }

    private fun groqChunk(text: String, retry: Boolean = true): Pair<ByteArray, String>? {
        val key = NebulaPrefs.cleanKey(prefs.groqKey).ifEmpty { return null }
        val body = JSONObject().put("model", prefs.groqVoiceModel).put("input", text).put("voice", prefs.groqVoice).put("response_format", "wav")
        val r = post("https://api.groq.com/openai/v1/audio/speech", mapOf("Authorization" to "Bearer $key"), body)?.let { it to "wav" }
        if (r == null && retry && lastCode in setOf(401, 429) && prefs.nextGroqKey()) return groqChunk(text, false)
        return r
    }

    private fun elevenChunk(text: String): Pair<ByteArray, String>? {
        val key = prefs.elevenKey.trim().ifEmpty { lastProblem = "Add your ElevenLabs key in Settings."; return null }
        val body = JSONObject().put("text", text).put("model_id", "eleven_flash_v2_5")
            .put("voice_settings", JSONObject().put("stability", 0.45).put("similarity_boost", 0.8))
        return post("https://api.elevenlabs.io/v1/text-to-speech/${prefs.elevenVoice}?output_format=mp3_44100_128", mapOf("xi-api-key" to key), body)?.let { it to "mp3" }
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): ByteArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 8_000; readTimeout = 25_000
            setRequestProperty("Content-Type", "application/json"); headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        lastCode = code
        if (code in 200..299) c.inputStream.use { it.readBytes() }.also { lastProblem = null; c.disconnect() }
        else {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty(); c.disconnect()
            lastProblem = when {
                "terms" in err.lowercase() -> "Groq needs you to accept the Orpheus terms once: open console.groq.com/playground, pick the Orpheus model and accept."
                code == 401 -> "The voice service rejected the key."
                code == 429 -> "Voice service limit reached for now; using the phone voice."
                code == 404 || "model" in err.lowercase() && "not" in err.lowercase() -> "Groq doesn't offer the voice model “${prefs.groqVoiceModel}” to this key. Groq says: ${groqMsg(err)}"
                else -> "Voice service error $code. ${groqMsg(err)}".trim()
            }
            null
        }
    } catch (e: Exception) { lastProblem = "Voice service unreachable."; null }

    private fun groqMsg(raw: String) = runCatching { JSONObject(raw).getJSONObject("error").optString("message") }.getOrNull().orEmpty().take(220)

    private suspend fun system(text: String) {
        init()
        var waited = 0
        while (!ttsReady && waited < 3000) { delay(100); waited += 100 }
        val engine = tts ?: return
        if (!ttsReady) return
        suspendCancellableCoroutine { cont ->
            val id = "n${System.nanoTime()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(u: String?) {}
                override fun onDone(u: String?) { if (cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java") override fun onError(u: String?) { if (cont.isActive) cont.resume(Unit) }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    companion object {
        /** Splits into sentence-sized pieces under [max] characters. */
        fun chunk(text: String, max: Int): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (s in text.split(Regex("(?<=[.!?;:])\\s+"))) {
                if (sb.length + s.length + 1 > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                if (s.length > max) {
                    s.split(Regex("(?<=,)\\s+|\\s+")).forEach { w -> if (sb.length + w.length + 1 > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }; sb.append(w).append(' ') }
                } else sb.append(s).append(' ')
            }
            if (sb.isNotBlank()) out += sb.toString()
            return out.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
