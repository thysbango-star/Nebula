package com.nebula.assistant.agent

import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Talks to Groq's OpenAI-compatible API: chat with function tools, web search, model fallback,
 * and patient handling of the free tier's per-minute rate limits.
 */
class GroqClient(private val prefs: NebulaPrefs) {


    class ApiError(val code: Int, val msg: String, val raw: String, val retryAfterSec: Double? = null) : Exception(msg)
    class Spoken(val spoken: String, val detail: String? = null) : Exception(spoken)

    data class Reply(val message: JSONObject, val sources: List<Source>)

    /** Called while waiting out a rate limit, so the UI can say so. */
    var onWait: ((String) -> Unit)? = null

    suspend fun chat(messages: JSONArray, tools: JSONArray?, effort: String = "medium"): Reply = withContext(Dispatchers.IO) {
        val key = NebulaPrefs.cleanKey(prefs.groqKey)
        if (key.isEmpty()) throw Spoken("I need my AI key first. Open Nebula, go to Settings and paste your Groq key.")
        var model = prefs.groqModel
        val tried = mutableSetOf<String>()
        var rateRetries = 0
        while (true) {
            tried += model
            val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.3)
                .put("max_completion_tokens", 2048)
            if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", effort)
            if (tools != null && tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto").put("parallel_tool_calls", false)
            try {
                val json = JSONObject(request("POST", "/chat/completions", key, body.toString()))
                val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?: throw Spoken("The AI returned an empty answer.")
                if (prefs.groqModel != model) prefs.groqModel = model
                return@withContext Reply(msg, sourcesFrom(msg))
            } catch (e: ApiError) {
                when {
                    e.code == 429 && rateRetries < 4 && !e.msg.contains("per day", true) -> {
                        val wait = (e.retryAfterSec ?: Regex("try again in ([0-9.]+)s").find(e.msg)?.groupValues?.get(1)?.toDoubleOrNull() ?: 6.0)
                            .coerceIn(1.0, 30.0)
                        onWait?.invoke("Groq's free tier is busy, waiting ${wait.toInt()}s…")
                        delay((wait * 1000).toLong() + 300)
                        rateRetries++
                    }
                    isToolUnsupported(e) && tools != null -> throw e
                    isModelGone(e) -> model = pickModel(key, tried) ?: throw toSpoken(e)
                    else -> throw toSpoken(e)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
    }

    /** Answers a question using Groq's built-in browser search; returns text + sources. */
    /** Asks a vision model about a screenshot (JPEG base64). */
    suspend fun vision(question: String, jpegB64: String): String = withContext(Dispatchers.IO) {
        val key = NebulaPrefs.cleanKey(prefs.groqKey)
        if (key.isEmpty()) throw Spoken("I need my AI key first.")
        var last: Exception? = null
        for (model in VISION_MODELS) {
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", "This is a screenshot of the user's Android phone. " +
                    "Answer precisely and briefly. If you list items, list every one you can see, top to bottom. Question: $question"))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegB64")))
            val body = JSONObject().put("model", model).put("temperature", 0.1).put("max_completion_tokens", 900)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            var tries = 0
            while (true) {
                try {
                    val json = JSONObject(request("POST", "/chat/completions", key, body.toString()))
                    return@withContext json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                        .replace(Regex("(?s)<think>.*?</think>"), "").trim().ifBlank { "I couldn't make out anything." }
                } catch (e: ApiError) {
                    last = e
                    if (e.code == 429 && tries++ < 3) { onWait?.invoke("Waiting for Groq's rate limit…"); delay(((e.retryAfterSec ?: 5.0) * 1000).toLong()); continue }
                    break
                }
            }
            if (last is ApiError && !isModelGone(last as ApiError) && (last as ApiError).code != 400) break
        }
        "Vision isn't available right now (${(last as? ApiError)?.msg ?: last?.message}). Use read_screen instead."
    }

    suspend fun webSearch(query: String): Pair<String, List<Source>> = withContext(Dispatchers.IO) {
        val key = NebulaPrefs.cleanKey(prefs.groqKey)
        val model = prefs.groqModel.takeIf { it.startsWith("openai/gpt-oss") } ?: "openai/gpt-oss-120b"
        val body = JSONObject().put("model", model).put("temperature", 0.2).put("max_completion_tokens", 1200)
            .put("reasoning_effort", "low")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "Search the web and answer factually and concisely with concrete facts, numbers and dates. Today is ${java.util.Date()}."))
                .put(JSONObject().put("role", "user").put("content", query)))
            .put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
        var tries = 0
        while (true) {
            try {
                val json = JSONObject(request("POST", "/chat/completions", key, body.toString()))
                val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
                return@withContext msg.optString("content") to sourcesFrom(msg)
            } catch (e: ApiError) {
                if (e.code == 429 && tries++ < 3) { onWait?.invoke("Waiting for Groq's rate limit…"); delay(((e.retryAfterSec ?: 6.0) * 1000).toLong()); continue }
                return@withContext "Web search isn't available right now (${e.msg})." to emptyList()
            }
        }
        @Suppress("UNREACHABLE_CODE") "" to emptyList()
    }

    suspend fun test(rawKey: String): String = withContext(Dispatchers.IO) {
        val key = NebulaPrefs.cleanKey(rawKey.ifBlank { prefs.groqKey })
        if (key.length < 20) return@withContext "That doesn't look like a full key (${key.length} characters)."
        try {
            val body = JSONObject().put("model", prefs.groqModel).put("max_completion_tokens", 60)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Say hello in five words.")))
            val json = JSONObject(request("POST", "/chat/completions", key, body.toString()))
            "Connected with ${prefs.groqModel}. Nebula says: " +
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
        } catch (e: ApiError) {
            val s = toSpoken(e); s.spoken + (s.detail?.let { "\n\nGroq says: $it" } ?: "")
        } catch (e: Spoken) { e.spoken }
    }

    // ---------------------------------------------------------------- HTTP

    private fun request(method: String, path: String, key: String, body: String?): String {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 12_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) return text
            val msg = runCatching { (JSONTokener(text).nextValue() as JSONObject).getJSONObject("error").optString("message") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"
            throw ApiError(code, msg, text, conn.getHeaderField("retry-after")?.toDoubleOrNull())
        } catch (e: ApiError) { throw e
        } catch (e: UnknownHostException) { throw Spoken("I can't reach the internet right now.")
        } catch (e: SocketTimeoutException) { throw Spoken("The AI took too long to answer. Try again.")
        } catch (e: IOException) { throw Spoken("I couldn't connect to my AI brain. Check your connection.", e.message)
        } finally { conn.disconnect() }
    }

    private fun pickModel(key: String, exclude: Set<String>): String? = runCatching {
        val data = JSONObject(request("GET", "/models", key, null)).optJSONArray("data") ?: JSONArray()
        val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
            .filter { id -> id !in exclude && listOf("whisper", "guard", "tts", "orpheus", "playai", "embed", "compound").none { it in id } }
        PREFERRED.firstOrNull { it in ids } ?: ids.firstOrNull { "70b" in it } ?: ids.firstOrNull()
    }.getOrNull()

    private fun isModelGone(e: ApiError): Boolean {
        val t = (e.msg + " " + e.raw.take(600)).lowercase()
        return e.code == 404 || "decommissioned" in t || "model_not_found" in t ||
            ("model" in t && ("does not exist" in t || "not found" in t || "no longer" in t))
    }

    private fun isToolUnsupported(e: ApiError) = e.code == 400 && e.msg.contains("tool", true) && e.msg.contains("support", true)

    fun toSpoken(e: ApiError): Spoken {
        val t = e.msg.lowercase(); val d = "${e.msg} (HTTP ${e.code})"
        return when {
            e.code == 401 || "invalid api key" in t -> Spoken("My Groq key isn't working. Check it in Settings.", d)
            e.code == 403 -> Spoken("My Groq key doesn't have access to that.", d)
            e.code == 413 || "too large" in t || "reduce the length" in t -> Spoken("That was too much for the free AI tier in one go. Try a shorter request.", d)
            e.code == 429 && "per day" in t -> Spoken("I've used up Groq's free daily limit. It resets within 24 hours.", d)
            e.code == 429 -> Spoken("Groq's free tier is rate-limiting me. Try again in a minute.", d)
            isModelGone(e) -> Spoken("That AI model isn't available.", d)
            e.code >= 500 -> Spoken("Groq is having trouble right now. Try again shortly.", d)
            else -> Spoken("The AI returned an error (${e.code}).", d)
        }
    }

    private fun sourcesFrom(m: JSONObject): List<Source> {
        val out = mutableListOf<Source>()
        fun add(a: JSONArray?) { if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
            val url = o.optString("url")
            if (url.startsWith("http") && out.none { it.url == url }) out += Source(o.optString("title").ifBlank { runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("Source") }, url)
        } }
        val tools = m.optJSONArray("executed_tools") ?: return out
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            when (val sr = t.opt("search_results")) { is JSONArray -> add(sr); is JSONObject -> add(sr.optJSONArray("results")) }
            add(t.optJSONArray("browser_results"))
        }
        return out.take(4)
    }

    companion object {
        const val BASE = "https://api.groq.com/openai/v1"
        /** Image-capable Groq models, newest first. */
        val VISION_MODELS = listOf("qwen/qwen3.8-27b", "meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct")
        val PREFERRED = listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b", "llama-3.1-8b-instant")
    }
}
