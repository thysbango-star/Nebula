package com.nebula.assistant.mood

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The little web server on the phone that the TV's browser talks to, on your own Wi-Fi only.
 *
 *   GET  /CODE/          the Mood PLAY screen (shared/moodplay/tv.html)
 *   GET  /CODE/events    live updates (Server-Sent Events) — the song, the position, play/pause
 *   GET  /CODE/state     the same, once (for TV browsers without EventSource)
 *   GET  /CODE/art?v=…   the cover
 *   POST /CODE/cmd?c=…   the TV remote: toggle, next, prev, repeat, shuffle
 *   GET  /               a page that asks for the code, for typing the short address on a TV remote
 *
 * The 6-letter code is random for each session; wrong codes are rate-limited, and only private (LAN)
 * addresses are served. It's not encrypted (plain http on your Wi-Fi), and the panel says so.
 */
class MoodServer(private val ctx: Context, val code: String, private val state: () -> String,
                 private val art: (String) -> ByteArray?, private val onCmd: (String) -> Unit) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val streams = CopyOnWriteArrayList<OutputStream>()
    private val misses = HashMap<String, MutableList<Long>>()
    var port = 0; private set

    fun start(): Int {
        val s = ServerSocket(); s.reuseAddress = true
        port = runCatching { s.bind(InetSocketAddress(8765)); 8765 }.getOrElse { s.bind(InetSocketAddress(0)); s.localPort }
        server = s
        pool.execute { while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; pool.execute { handle(c) } } }
        pool.execute { while (server != null) { Thread.sleep(15_000); push(null) } } // keep-alive for the TV's connection
        return port
    }

    fun stop() {
        runCatching { server?.close() }; server = null
        streams.forEach { runCatching { it.close() } }; streams.clear()
        pool.shutdownNow()
    }

    val viewers: Int get() = streams.size

    /** Send the latest state to every TV that's watching (null = just a keep-alive). */
    fun push(json: String?) {
        val bytes = (if (json == null) "event: ping\ndata: 1\n\n" else "data: $json\n\n").toByteArray() // a real event, so the TV knows it's still connected
        for (o in streams) runCatching { synchronized(o) { o.write(bytes); o.flush() } }.onFailure { streams.remove(o); runCatching { o.close() } }
    }

    private fun limited(ip: String): Boolean = synchronized(misses) {
        val now = System.currentTimeMillis()
        val l = misses.getOrPut(ip) { mutableListOf() }; l.removeAll { now - it > 60_000 }
        l.size >= 20
    }
    private fun miss(ip: String) = synchronized(misses) { misses.getOrPut(ip) { mutableListOf() }.add(System.currentTimeMillis()) }

    private fun handle(c: Socket) {
        var keep = false
        try {
            val ip = c.inetAddress.hostAddress ?: ""
            if (!c.inetAddress.isSiteLocalAddress && !c.inetAddress.isLinkLocalAddress && !c.inetAddress.isLoopbackAddress) return
            c.soTimeout = 10_000
            val r = BufferedReader(InputStreamReader(c.getInputStream()))
            val line = r.readLine() ?: return
            while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }
            val parts = line.split(' '); if (parts.size < 2) return
            val method = parts[0]; val target = parts[1]
            val path = target.substringBefore('?'); val query = target.substringAfter('?', "")
            val out = c.getOutputStream()
            val seg = path.split('/').filter { it.isNotEmpty() }
            if (seg.isEmpty()) return send(out, 200, "text/html; charset=utf-8", CODE_PAGE.toByteArray())
            if (limited(ip)) return send(out, 429, "text/plain", "Too many tries".toByteArray())
            if (!seg[0].equals(code, ignoreCase = true)) { miss(ip); return send(out, 404, "text/html; charset=utf-8", CODE_PAGE.replace("<!--msg-->", "That code didn't match. Check the code on your phone.").toByteArray()) }
            if (seg.size == 1 && path.endsWith("/").not()) return send(out, 302, "text/plain", ByteArray(0), "Location: /${seg[0]}/\r\n")
            when (seg.getOrNull(1)) {
                null -> send(out, 200, "text/html; charset=utf-8", ctx.assets.open("moodplay/tv.html").use { it.readBytes() })
                "state" -> send(out, 200, "application/json", state().toByteArray())
                "f" -> seg.getOrNull(2)?.takeIf { it.matches(Regex("[a-z_]+\\.ttf")) }?.let { name ->
                    runCatching { ctx.assets.open("moodplay/fonts/$name").use { it.readBytes() } }.getOrNull()
                }?.let { send(out, 200, "font/ttf", it, "Cache-Control: max-age=604800\r\n") } ?: send(out, 404, "text/plain", ByteArray(0))
                "art" -> art(query)?.let { send(out, 200, "image/png", it, "Cache-Control: max-age=86400\r\n") } ?: send(out, 404, "text/plain", ByteArray(0))
                "cmd" -> if (method == "POST") {
                    val cmd = Regex("(?:^|&)c=([a-z]+)").find(query)?.groupValues?.get(1).orEmpty()
                    if (cmd in setOf("toggle", "next", "prev", "repeat", "shuffle")) { onCmd(cmd); send(out, 204, "text/plain", ByteArray(0)) }
                    else send(out, 400, "text/plain", ByteArray(0))
                } else send(out, 405, "text/plain", ByteArray(0))
                "events" -> {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: keep-alive\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\nretry: 2000\n\ndata: ${state()}\n\n").toByteArray()); out.flush()
                    c.soTimeout = 0; streams.add(out); keep = true
                }
                else -> send(out, 404, "text/plain", ByteArray(0))
            }
        } catch (_: Exception) {
        } finally { if (!keep) runCatching { c.close() } }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, extra: String = "") {
        val msg = mapOf(200 to "OK", 204 to "No Content", 302 to "Found", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed", 429 to "Too Many Requests")[code] ?: "OK"
        out.write("HTTP/1.1 $code $msg\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n".toByteArray())
        out.write(body); out.flush()
    }

    companion object {
        private const val ABC = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no 0/O, 1/I/L — easy to type on a remote
        fun newCode(): String { val r = SecureRandom(); return (1..6).map { ABC[r.nextInt(ABC.length)] }.joinToString("") }

        /** For typing on a TV: just the address, then the code with the remote. */
        val CODE_PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Mood PLAY</title>
<style>html,body{height:100%;margin:0;background:linear-gradient(180deg,#a7adba,#2c2f3b);font-family:"Open Sans","Samsung One",Roboto,Arial,sans-serif;color:#111;display:grid;place-items:center}
.b{text-align:center}h1{font-size:6vw;margin:0 0 2vw;letter-spacing:-.2vw}h1 i{display:inline-block;width:5.6vw;height:2.6vw;border:.35vw solid #111;border-radius:2vw;background:linear-gradient(90deg,#ff3a2a,#ff8f3f);vertical-align:-.2vw}
p{font-size:2.2vw;margin:0 0 2vw;opacity:.75}input{font-size:5vw;width:24vw;text-align:center;letter-spacing:1.2vw;text-transform:uppercase;border:0;border-radius:2vw;padding:1vw;background:#fff}
button{font-size:2.4vw;margin-left:1vw;padding:1.2vw 2.4vw;border:0;border-radius:2vw;background:#111;color:#fff}.m{color:#a00;font-weight:700}</style></head>
<body><div class="b"><h1>m<i></i>od PLAY</h1><p>Type the 6-letter code shown on your phone</p><p class="m"><!--msg--></p>
<form onsubmit="location.href='/'+document.getElementById('c').value.trim().toUpperCase()+'/';return false"><input id="c" maxlength="6" autofocus autocomplete="off"><button>Open</button></form></div></body></html>"""
    }
}
