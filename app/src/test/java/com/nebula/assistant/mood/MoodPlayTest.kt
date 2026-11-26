package com.nebula.assistant.mood

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/** Mood PLAY on the phone: the TV server, its codes and commands, and opening the page on a (fake) Samsung TV. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MoodPlayTest {
    private fun req(url: String, method: String = "GET"): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.instanceFollowRedirects = false; c.connectTimeout = 3000; c.readTimeout = 3000
        val code = c.responseCode
        val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        return code to body
    }

    @Test
    fun serverCodesStateAndCommands() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cmds = mutableListOf<String>()
        var title = "GNX"
        val code = MoodServer.newCode()
        assertTrue(code.matches(Regex("[A-HJ-NP-Z2-9]{6}")))
        val s = MoodServer(ctx, code, { JSONObject().put("now", 1).put("media", JSONObject().put("title", title)).toString() }, { "png".toByteArray() }, { cmds += it })
        val port = s.start()
        try {
            val base = "http://127.0.0.1:$port"
            assertTrue(req("$base/").second.contains("6-letter code"))
            assertEquals(404, req("$base/WRONG1/").first)
            val page = req("$base/$code/")
            assertEquals(200, page.first); assertTrue("the shared TV page is served", page.second.contains("Mood PLAY") && page.second.contains("t-waves"))
            assertEquals(302, req("$base/$code").first)
            assertEquals("GNX", JSONObject(req("$base/${code.lowercase()}/state").second).getJSONObject("media").getString("title"))
            assertEquals("png", req("$base/$code/art?v=1").second)
            assertEquals(200, req("$base/$code/f/manrope_bold.ttf").first)
            assertEquals(404, req("$base/$code/f/secret.txt").first)
            assertEquals(405, req("$base/$code/cmd?c=next").first)
            assertEquals(204, req("$base/$code/cmd?c=next", "POST").first)
            assertEquals(400, req("$base/$code/cmd?c=format", "POST").first)
            assertEquals(listOf("next"), cmds)
            // live updates: the first event straight away, then whatever is pushed
            val c = URL("$base/$code/events").openConnection() as HttpURLConnection; c.readTimeout = 4000
            val r = c.inputStream.bufferedReader()
            var first = ""; while (true) { val l = r.readLine() ?: break; if (l.startsWith("data: ")) { first = l; break } }
            assertTrue(first.contains("GNX"))
            title = "Ordinary People"; Thread.sleep(200); s.push("{\"media\":{\"title\":\"$title\"}}")
            var second = ""; while (true) { val l = r.readLine() ?: break; if (l.startsWith("data: ")) { second = l; break } }
            assertTrue(second.contains("Ordinary People"))
            c.disconnect()
            // 20 wrong codes in a minute: that address is stopped
            repeat(20) { req("$base/ZZZZZZ/") }
            assertEquals(429, req("$base/$code/state").first)
        } finally { s.stop() }
    }

    @Test
    fun opensOnSamsungAfterAllow() {
        // A fake Samsung TV on 8001: waits (the "Allow?" prompt), then says yes and receives the browser launch.
        val tv = ServerSocket(8001)
        var launched: JSONObject? = null; var path = ""
        val t = thread {
            tv.accept().use { s ->
                val inp = s.getInputStream(); val out = s.getOutputStream()
                val head = StringBuilder(); while (!head.endsWith("\r\n\r\n")) head.append(inp.read().toChar())
                path = head.lineSequence().first().split(' ')[1]
                val key = Regex("Sec-WebSocket-Key: (.*)\r").find(head)!!.groupValues[1]
                val acc = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $acc\r\n\r\n".toByteArray())
                Thread.sleep(3200)
                val msg = """{"event":"ms.channel.connect","data":{"token":"TOKEN123"}}""".toByteArray()
                out.write(byteArrayOf(0x81.toByte(), msg.size.toByte())); out.write(msg); out.flush()
                val b0 = inp.read(); val b1 = inp.read()
                var len = b1 and 0x7f; if (len == 126) len = (inp.read() shl 8) or inp.read()
                val mask = ByteArray(4).also { inp.read(it) }
                val data = ByteArray(len).also { var n = 0; while (n < len) n += inp.read(it, n, len - n) }
                for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                if (b0 and 0x0f == 1) launched = JSONObject(String(data))
            }
        }
        var asked = false
        val r = runBlocking { TvLauncher.open(Tv("samsung:127.0.0.1", "Living Room", "samsung", "127.0.0.1"), "http://192.168.1.5:8765/ABC234/", null) { asked = true } }
        t.join(5000); tv.close()
        assertTrue("asked to press Allow", asked)
        assertEquals("TOKEN123", (r as TvLauncher.Result.Opened).token)
        assertTrue(path.contains("samsung.remote.control?name="))
        val data = launched!!.getJSONObject("params").getJSONObject("data")
        assertEquals("org.tizen.browser", data.getString("appId"))
        assertEquals("http://192.168.1.5:8765/ABC234/", data.getString("metaTag"))
    }

    @Test
    fun onlyLanAddresses() {
        assertTrue(WsClient.isLan("192.168.1.4")); assertTrue(WsClient.isLan("10.0.0.2"))
        assertTrue(!WsClient.isLan("8.8.8.8"))
        assertNotNull(MoodServer.CODE_PAGE)
    }
}
