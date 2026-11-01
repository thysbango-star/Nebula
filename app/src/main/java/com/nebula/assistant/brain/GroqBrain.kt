package com.nebula.assistant.brain

import com.nebula.assistant.data.NebulaPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
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
 * Nebula's AI brain on Groq: very fast open models through Groq's OpenAI-compatible API.
 *
 * - Default model: openai/gpt-oss-120b, with Groq's browser search tool when the key allows it.
 * - If search isn't allowed, answers without it (and remembers).
 * - If a model is retired, picks the best available one from the key's model list.
 * - Remembers the conversation while the panel is open, so follow-ups work.
 */
class GroqBrain(private val prefs: NebulaPrefs) : NebulaBrain {

    private val history = mutableListOf<JSONObject>()

    override suspend fun respond(input: String): BrainReply = withContext(Dispatchers.IO) {
        val key = NebulaPrefs.cleanKey(prefs.groqKey)
        if (key.isEmpty()) {
            return@withContext BrainReply(
                "I need my AI key before I can answer that. Open Nebula and paste your Groq key under AI brain."
            )
        }
        val userTurn = JSONObject().put("role", "user").put("content", input)
        history += userTurn
        try {
            val result = call(key, prefs.groqModel, history)
            if (result.text.isBlank()) {
                history.remove(userTurn)
                return@withContext BrainReply("I don't have a good answer for that one.")
            }
            history += JSONObject().put("role", "assistant").put("content", result.text)
            while (history.size > MAX_TURNS) history.removeAt(0)
            BrainReply(result.text, sources = result.sources)
        } catch (e: GroqException) {
            history.remove(userTurn)
            BrainReply(e.spoken)
        }
    }

    /** One-off check used by the "Test" button in settings. */
    suspend fun test(key: String, model: String): String = withContext(Dispatchers.IO) {
        val clean = NebulaPrefs.cleanKey(key)
        if (clean.length < 20) return@withContext "That doesn't look like a full key (${clean.length} characters). Paste the whole key."
        try {
            val r = call(clean, model.ifBlank { prefs.groqModel }, listOf(JSONObject().put("role", "user").put("content", "Say hello in five words.")))
            val used = prefs.groqModel
            val search = if (prefs.groqNoSearchModel == used) "off (not available for this model/key)" else "on"
            "Connected with $used. Web search: $search.\nNebula says: ${r.text}"
        } catch (e: GroqException) {
            e.spoken + (e.detail?.let { "\n\nGroq says: $it" } ?: "") +
                "\n(Key starts with “${clean.take(4)}…”, model ${model.ifBlank { prefs.groqModel }})"
        }
    }

    // ------------------------------------------------------------------ calls

    private data class Result(val text: String, val sources: List<Source>)
    private class GroqException(val spoken: String, val detail: String? = null) : Exception(spoken)
    private class ApiError(val code: Int, val msg: String, val raw: String) : Exception(msg)

    private fun call(key: String, model: String, history: List<JSONObject>): Result {
        var current = model
        val tried = mutableSetOf<String>()
        while (true) {
            tried += current
            try {
                val r = callWithSearchFallback(key, current, history)
                if (prefs.groqModel != current) prefs.groqModel = current
                return r
            } catch (e: ApiError) {
                if (isModelGone(e)) {
                    val next = pickAvailableModel(key, exclude = tried)
                    if (next != null) {
                        current = next
                        continue
                    }
                }
                throw GroqException(explain(e), "${e.msg} (HTTP ${e.code})")
            }
        }
    }

    private fun callWithSearchFallback(key: String, model: String, history: List<JSONObject>): Result {
        val searchAllowed = prefs.groqNoSearchModel != model && supportsSearch(model)
        if (!searchAllowed) return chat(key, model, history, search = false)
        return try {
            chat(key, model, history, search = true)
        } catch (e: ApiError) {
            val text = (e.msg + " " + e.raw.take(800)).lowercase()
            val searchProblem = e.code in setOf(400, 403, 404, 422, 429) &&
                listOf("browser", "search", "tool").any { it in text } && !isModelGone(e)
            if (!searchProblem) throw e
            prefs.groqNoSearchModel = model
            chat(key, model, history, search = false)
        }
    }

    private fun chat(key: String, model: String, history: List<JSONObject>, search: Boolean): Result {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(search)))
        history.forEach { messages.put(it) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.6)
            .put("max_completion_tokens", 1024)
        if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low")
        if (search) body.put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))

        val json = JSONObject(request("POST", "$BASE/chat/completions", key, body))
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw GroqException("I can't help with that one.")
        return Result(cleanForSpeech(message.optString("content")), sourcesFrom(message))
    }

    /** Pulls web sources out of Groq's executed tool results, whichever shape they come in. */
    private fun sourcesFrom(message: JSONObject): List<Source> {
        val out = mutableListOf<Source>()
        fun addFrom(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.startsWith("http") && out.none { it.url == url }) {
                    out += Source(o.optString("title").ifBlank { URL(url).host.removePrefix("www.") }, url)
                }
            }
        }
        val tools = message.optJSONArray("executed_tools") ?: return out
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val sr = t.opt("search_results")
            when (sr) {
                is JSONArray -> addFrom(sr)
                is JSONObject -> addFrom(sr.optJSONArray("results"))
            }
            addFrom(t.optJSONArray("browser_results"))
        }
        return out.take(3)
    }

    // ------------------------------------------------------------------ models

    private fun supportsSearch(model: String) = model.startsWith("openai/gpt-oss")

    private fun isModelGone(e: ApiError): Boolean {
        val t = (e.msg + " " + e.raw.take(600)).lowercase()
        return e.code == 404 || "decommissioned" in t || "model_not_found" in t ||
            ("model" in t && ("does not exist" in t || "not found" in t || "no longer" in t))
    }

    /** Asks Groq which models this key can use and picks the best one we haven't tried. */
    private fun pickAvailableModel(key: String, exclude: Set<String>): String? {
        val ids = runCatching {
            val data = JSONObject(request("GET", "$BASE/models", key, null)).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
        }.getOrDefault(emptyList())
        val usable = ids.filter { id ->
            id !in exclude && listOf("whisper", "guard", "tts", "orpheus", "playai", "embed").none { it in id }
        }
        return PREFERRED.firstOrNull { it in usable }
            ?: usable.firstOrNull { "gpt-oss-120b" in it }
            ?: usable.firstOrNull { "70b" in it }
            ?: usable.firstOrNull()
    }

    // ------------------------------------------------------------------ HTTP

    private fun request(method: String, address: String, key: String, body: JSONObject?): String {
        val conn = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) return text
            val msg = runCatching {
                (JSONTokener(text).nextValue() as JSONObject).getJSONObject("error").optString("message")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"
            throw ApiError(code, msg, text)
        } catch (e: ApiError) {
            throw e
        } catch (e: GroqException) {
            throw e
        } catch (e: UnknownHostException) {
            throw GroqException("I can't reach the internet right now.")
        } catch (e: SocketTimeoutException) {
            throw GroqException("That took too long. Try asking again.")
        } catch (e: IOException) {
            throw GroqException("I couldn't connect to my AI brain. Check your connection.")
        } catch (e: Exception) {
            throw GroqException("Something went wrong talking to my AI brain.", e.message)
        } finally {
            conn.disconnect()
        }
    }

    private fun explain(e: ApiError): String {
        val t = e.msg.lowercase()
        return when {
            e.code == 401 || "invalid api key" in t || "api key" in t -> "My Groq key isn't working. Check it in Nebula's settings."
            e.code == 403 -> "My Groq key doesn't have access to that. Check it in Nebula's settings."
            e.code == 413 || "too large" in t -> "That conversation got too long. Close me and ask again."
            e.code == 429 -> "I've hit Groq's free limit for now. Try again in a little while."
            isModelGone(e) -> "That AI model isn't available. Check the model name in Nebula's settings."
            e.code >= 500 -> "Groq is having trouble right now. Try again in a moment."
            else -> "My AI brain returned an error (${e.code})."
        }
    }

    // ------------------------------------------------------------------ prompt

    private fun systemPrompt(search: Boolean): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        val tz = TimeZone.getDefault().id
        val web = if (search) {
            "Use browser search for anything current or factual you're not certain of: news, weather, sports, " +
                "prices, opening hours, people's current roles, recent releases."
        } else {
            "You can't browse the web right now. For live things like today's weather, scores or breaking news, " +
                "give your best general answer and say briefly that you can't check live info."
        }
        return """
            You are Nebula, a warm, quick-witted voice assistant on the user's Samsung Galaxy phone.
            Your replies are read aloud, so:
            - Answer in one to three short sentences unless the user asks for more detail.
            - Write plain conversational text: no markdown, lists, tables, emojis, URLs or citations.
            - Say numbers, times and units the way a person would speak them.
            $web
            It is now $now, time zone $tz. Use this for "today", "tonight", "this weekend" and for
            local answers; if a question needs the user's city and you can't tell it, ask briefly.
            The phone itself handles timers, alarms, the flashlight, battery level and opening apps.
            If the user asks for one of those in a way that didn't trigger it, tell them the phrase to use,
            like "set a timer for 10 minutes" or "open WhatsApp".
            If you can't do something, say so in one sentence.
        """.trimIndent()
    }

    /** Strip markdown and citation marks so text-to-speech doesn't read symbols aloud. */
    private fun cleanForSpeech(s: String): String = s
        .replace(Regex("【[^】]*】"), "")
        .replace(Regex("\\[\\d+(,\\s*\\d+)*]"), "")
        .replace(Regex("\\*\\*|__|`|#+ "), "")
        .replace(Regex("^\\s*[-*•]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
        .replace(Regex("\\s*\\n\\s*"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    private companion object {
        const val MAX_TURNS = 12
        const val BASE = "https://api.groq.com/openai/v1"
        val PREFERRED = listOf(
            "openai/gpt-oss-120b",
            "llama-3.3-70b-versatile",
            "openai/gpt-oss-20b",
            "llama-3.1-8b-instant",
        )
    }
}
