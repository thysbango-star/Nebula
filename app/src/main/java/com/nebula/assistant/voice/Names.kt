package com.nebula.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.nebula.assistant.agent.GroqClient
import com.nebula.assistant.data.NebulaPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Name coach: Nebula learns how to say (and hear) your name.
 *  - [forSpeech] swaps your name for its "say it like" spelling before the voice speaks.
 *  - [fixHeard] turns the ways speech-to-text mishears your name back into your name.
 */
object Names {
    private val COMMON = "the a an and or but if then this that these those there their they them is are was were be been am i you he she it we me my your his her our its to of in on at for with from by as so not no yes do does did have has had can could will would should may might just like what when where who why how all any some one two three hi hey hello okay ok please thanks thank good great nice well now here see say said tell ask make made get got go going come want need know think look time day today night play open call text send".split(' ').toSet()

    fun forSpeech(prefs: NebulaPrefs, text: String): String {
        val name = prefs.userName; val say = prefs.nameSayAs
        if (name.isBlank() || say.isBlank() || say.equals(name, true)) return text
        return text.replace(Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE), say)
    }

    fun fixHeard(prefs: NebulaPrefs, text: String): String {
        val name = prefs.userName
        if (name.isBlank()) return text
        var out = text
        for (h in prefs.nameHeardAs) {
            if (h.length < 3 || h.equals(name, true) || h.lowercase() in COMMON) continue
            // Only a capitalised word mid-sentence (how Whisper writes a name), never a sentence start.
            val cap = h.replaceFirstChar { it.uppercase() }
            out = out.replace(Regex("(?<![.!?]\\s|^)\\b${Regex.escape(cap)}\\b"), name)
        }
        return out
    }

    /** Records one short take of the user saying their name (up to ~4 s). Null if nothing was said. */
    @SuppressLint("MissingPermission")
    suspend fun recordTake(): ByteArray? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(Audio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Audio.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Audio.RATE / 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val u = Utterance(maxSeconds = 4, waitSeconds = 5, silenceMs = 700)
        val chunk = ShortArray(Audio.RATE / 20)
        try {
            rec.startRecording()
            while (coroutineContext.isActive) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                VoiceState.level(Audio.level(Audio.rms(chunk, n)))
                if (u.push(chunk, n)) break
            }
        } finally { runCatching { rec.stop() }; rec.release() }
        if (u.empty) null else u.wav()
    }

    /** Asks the AI for spellings that make a text-to-speech voice say the name like the user does. */
    suspend fun suggest(prefs: NebulaPrefs, name: String, heard: List<String>): List<String> {
        val groq = GroqClient(prefs)
        val msg = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "You help a text-to-speech voice pronounce a person's name correctly. Reply with JSON only: {\"spellings\":[...]} " +
                    "with 4 different phonetic respellings in plain English letters (no IPA, no hyphens unless needed), most likely first."))
            .put(JSONObject().put("role", "user").put("content",
                "The name is written \"$name\". When the person said it 5 times, speech recognition heard: ${heard.joinToString(", ") { "\"$it\"" }}. " +
                    "Give respellings that an English voice would read aloud the way the person says it."))
        val r = groq.chat(msg, null, effort = "low")
        val text = r.message.optString("content")
        val json = Regex("\\{[\\s\\S]*\\}").find(text)?.value ?: return listOf(name)
        val arr = runCatching { JSONObject(json).getJSONArray("spellings") }.getOrNull() ?: return listOf(name)
        return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() && it.length < 40 }.distinct().take(4)
            .ifEmpty { listOf(name) }
    }
}
