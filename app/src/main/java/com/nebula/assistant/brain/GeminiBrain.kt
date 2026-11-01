package com.nebula.assistant.brain

import com.nebula.assistant.data.NebulaPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Nebula's AI brain: Google Gemini with Google Search grounding, so it can answer
 * about news, weather, scores and anything else current.
 *
 * Remembers the conversation while the panel is open, so follow-ups like
 * "and tomorrow?" work.
 */
class GeminiBrain(private val prefs: NebulaPrefs) : NebulaBrain {

    private val history = mutableListOf<JSONObject>()

    override suspend fun respond(input: String): BrainReply = withContext(Dispatchers.IO) {
        val key = NebulaPrefs.cleanKey(prefs.geminiKey)
        if (key.isEmpty()) {
            return@withContext BrainReply(
                "I need my AI key before I can answer that. Open Nebula and paste your Gemini key under AI brain."
            )
        }

        val userTurn = turn("user", input)
        history += userTurn
        try {
            val result = call(key, prefs.geminiModel.ifBlank { NebulaPrefs.DEFAULT_MODEL }, history)
            if (result.text.isBlank()) {
                history.remove(userTurn)
                return@withContext BrainReply("I don't have a good answer for that one.")
            }
            history += turn("model", result.text)
            while (history.size > MAX_TURNS) history.removeAt(0)
            BrainReply(result.text, sources = result.sources)
        } catch (e: GeminiException) {
            history.remove(userTurn)
            BrainReply(e.spoken)
        }
    }

    /** One-off check used by the "Test" button in settings. */
    suspend fun test(key: String, model: String): String = withContext(Dispatchers.IO) {
        try {
            val clean = NebulaPrefs.cleanKey(key)
            if (clean.length < 20) return@withContext "That doesn't look like a full key (${clean.length} characters). Paste the whole key."
            val r = call(clean, model.ifBlank { NebulaPrefs.DEFAULT_MODEL }, listOf(turn("user", "Say hello in five words.")))
            "Connected. Nebula says: ${r.text}"
        } catch (e: GeminiException) {
            // Show Google's own words too, so problems are easy to diagnose.
            val starts = NebulaPrefs.cleanKey(key).take(3)
            e.spoken + (e.detail?.let { "\n\nGoogle says: $it" } ?: "") + "\n(Key starts with “$starts…”, model $model)"
        }
    }

    // ------------------------------------------------------------------ HTTP

    private data class Result(val text: String, val sources: List<Source>)

    private class GeminiException(val spoken: String, val detail: String? = null) : Exception(spoken)

    private fun call(key: String, model: String, contents: List<JSONObject>): Result {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", JSONArray(contents))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 1024))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 40_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).getJSONObject("error").optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                throw GeminiException(explain(code, text), "$detail (HTTP $code)")
            }
            return parse(JSONObject(text))
        } catch (e: GeminiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw GeminiException("I can't reach the internet right now.")
        } catch (e: SocketTimeoutException) {
            throw GeminiException("That took too long. Try asking again.")
        } catch (e: IOException) {
            throw GeminiException("I couldn't connect to my AI brain. Check your connection.")
        } catch (e: Exception) {
            throw GeminiException("Something went wrong talking to my AI brain.")
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: JSONObject): Result {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw GeminiException("I can't help with that one.")

        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                append(p.optString("text"))
            }
        }

        val sources = mutableListOf<Source>()
        val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) for (i in 0 until chunks.length()) {
            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val uri = web.optString("uri")
            if (uri.isNotBlank() && sources.none { it.title == web.optString("title") }) {
                sources += Source(web.optString("title").ifBlank { "Source" }, uri)
            }
        }
        return Result(cleanForSpeech(text), sources.take(3))
    }

    private fun explain(code: Int, body: String): String {
        val message = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrDefault("")
        return when {
            (code == 400 || code == 401) && ("API key" in message || "credential" in message.lowercase() ||
                "authentication" in message.lowercase()) -> "My Gemini key isn't working. Check it in Nebula's settings."
            code == 403 -> "My Gemini key doesn't have access. Check it in Nebula's settings."
            code == 404 -> "That AI model wasn't found. Check the model name in Nebula's settings."
            code == 429 -> "I've hit the free limit for now. Try again in a little while."
            code >= 500 -> "Gemini is having trouble right now. Try again in a moment."
            else -> "My AI brain returned an error ($code)."
        }
    }

    // ------------------------------------------------------------------ prompt

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        val tz = TimeZone.getDefault().id
        return """
            You are Nebula, a warm, quick-witted voice assistant on the user's Samsung Galaxy phone.
            Your replies are read aloud, so:
            - Answer in one to three short sentences unless the user asks for more detail.
            - Write plain conversational text: no markdown, lists, emojis, URLs or citations.
            - Say numbers, times and units the way a person would speak them.
            Use Google Search for anything current or factual you're not certain of: news, weather,
            sports, prices, opening hours, people's current roles, recent releases.
            It is now $now, time zone $tz. Use this for "today", "tonight", "this weekend" and for
            local answers; if a question needs the user's city and you can't tell it, ask briefly.
            The phone itself handles timers, alarms, the flashlight, battery level and opening apps.
            If the user asks for one of those in a way that didn't trigger it, tell them the phrase to use,
            like "set a timer for 10 minutes" or "open WhatsApp".
            If you can't do something, say so in one sentence.
        """.trimIndent()
    }

    private fun turn(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    /** Strip leftover markdown so text-to-speech doesn't read symbols aloud. */
    private fun cleanForSpeech(s: String): String = s
        .replace(Regex("\\*\\*|__|`|#+ "), "")
        .replace(Regex("^\\s*[-*•]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
        .replace(Regex("\\s*\\n\\s*"), " ")
        .trim()

    private companion object {
        const val MAX_TURNS = 12
    }
}
