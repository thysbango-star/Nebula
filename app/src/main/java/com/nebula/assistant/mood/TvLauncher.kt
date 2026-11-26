package com.nebula.assistant.mood

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Opens the Mood PLAY page in the TV's own web browser, using the remote-control channel the TV makers
 * publish for phone apps:
 *  - Samsung (Tizen, 2016+): the "samsung.remote.control" socket on 8002/8001 → launch the browser with our URL.
 *  - LG (webOS): the SSAP socket on 3001/3000 → system.launcher/open with our URL.
 * The first time, the TV asks "Allow Nebula?" on screen; the answer (a token / client key) is remembered.
 * Anything else: the user opens the short address on the TV themselves (shown with a QR code).
 */
object TvLauncher {
    sealed class Result {
        data class Opened(val token: String?) : Result()
        object Declined : Result()
        data class Failed(val why: String) : Result()
    }

    suspend fun open(tv: Tv, url: String, savedToken: String?, onAsk: () -> Unit): Result = withContext(Dispatchers.IO) {
        when (tv.kind) {
            "samsung" -> samsung(tv, url, savedToken, onAsk)
            "lg" -> lg(tv, url, savedToken, onAsk)
            else -> Result.Failed("This TV can't be opened from the phone")
        }
    }

    // ---------------------------------------------------------------- Samsung
    private fun samsung(tv: Tv, url: String, token: String?, onAsk: () -> Unit): Result {
        val name = Base64.encodeToString("Nebula".toByteArray(), Base64.NO_WRAP)
        var last: String = "No answer from the TV"
        for ((scheme, port) in listOf("wss" to 8002, "ws" to 8001)) {
            val ws = WsClient("$scheme://${tv.ip}:$port/api/v2/channels/samsung.remote.control?name=$name" + (token?.let { "&token=$it" } ?: ""), lanTls = true)
            try {
                ws.connect()
                var newToken: String? = token
                var asked = false
                val until = System.currentTimeMillis() + 32_000
                while (System.currentTimeMillis() < until) {
                    val msg = ws.receive(if (asked) 30_000 else 2_500)
                    if (msg == null) { if (!asked) { asked = true; onAsk(); continue } else break }
                    val j = runCatching { JSONObject(msg) }.getOrNull() ?: continue
                    when (j.optString("event")) {
                        "ms.channel.connect" -> {
                            j.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let { newToken = it }
                            ws.send(JSONObject().put("method", "ms.channel.emit").put("params", JSONObject()
                                .put("event", "ed.apps.launch").put("to", "host")
                                .put("data", JSONObject().put("appId", "org.tizen.browser").put("action_type", "NATIVE_LAUNCH").put("metaTag", url))).toString())
                            Thread.sleep(600)
                            return Result.Opened(newToken)
                        }
                        "ms.channel.unauthorized" -> return Result.Declined
                        "ms.channel.timeOut" -> return Result.Failed("The TV stopped waiting for an answer")
                    }
                }
                last = "The TV didn't answer"
            } catch (e: Exception) { last = e.message ?: "Couldn't reach the TV" }
            finally { ws.close() }
        }
        return Result.Failed(last)
    }

    // ---------------------------------------------------------------- LG
    private fun lg(tv: Tv, url: String, key: String?, onAsk: () -> Unit): Result {
        var last = "No answer from the TV"
        for ((scheme, port) in listOf("wss" to 3001, "ws" to 3000)) {
            val ws = WsClient("$scheme://${tv.ip}:$port/", lanTls = true)
            try {
                ws.connect()
                val perms = JSONArray(listOf("LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO", "CONTROL_INPUT_MEDIA_PLAYBACK"))
                val reg = JSONObject().put("type", "register").put("id", "reg0").put("payload", JSONObject()
                    .put("forcePairing", false).put("pairingType", "PROMPT").apply { key?.let { put("client-key", it) } }
                    .put("manifest", JSONObject().put("manifestVersion", 1).put("appVersion", "1.0")
                        .put("signed", JSONObject().put("appId", "com.nebula.moodplay").put("vendorId", "com.nebula").put("localizedAppNames", JSONObject().put("", "Nebula")).put("permissions", perms).put("serial", "nebula"))
                        .put("permissions", perms)))
                ws.send(reg.toString())
                var clientKey: String? = key
                val until = System.currentTimeMillis() + 40_000
                while (System.currentTimeMillis() < until) {
                    val msg = ws.receive(35_000) ?: break
                    val j = runCatching { JSONObject(msg) }.getOrNull() ?: continue
                    when (j.optString("type")) {
                        "response" -> if (j.optJSONObject("payload")?.optString("pairingType") == "PROMPT") onAsk()
                        "registered" -> {
                            j.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotBlank() }?.let { clientKey = it }
                            ws.send(JSONObject().put("type", "request").put("id", "open1").put("uri", "ssap://system.launcher/open")
                                .put("payload", JSONObject().put("target", url)).toString())
                            val ok = ws.receive(6000)?.let { runCatching { JSONObject(it).optJSONObject("payload")?.optBoolean("returnValue", true) }.getOrNull() } ?: true
                            return if (ok) Result.Opened(clientKey) else Result.Failed("The TV wouldn't open the page")
                        }
                        "error" -> return if (j.optString("error").contains("rejected", true) || j.optString("error").contains("denied", true)) Result.Declined
                            else Result.Failed(j.optString("error").ifBlank { "The TV said no" })
                    }
                }
                last = "The TV didn't answer"
            } catch (e: Exception) { last = e.message ?: "Couldn't reach the TV" }
            finally { ws.close() }
        }
        return Result.Failed(last)
    }
}
