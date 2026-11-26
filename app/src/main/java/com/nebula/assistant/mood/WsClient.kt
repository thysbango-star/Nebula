package com.nebula.assistant.mood

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * A tiny WebSocket client — just enough to talk to a TV's remote-control socket on your own Wi-Fi
 * (Samsung's on 8001/8002, LG's on 3000/3001). Text frames only, no compression.
 *
 * TVs use self-signed certificates on their secure ports, so [lanTls] accepts any certificate. That's only
 * ever used for a private (LAN) address of a TV the user picked — never for anything on the internet.
 */
class WsClient(private val url: String, private val lanTls: Boolean = false) : AutoCloseable {
    private var sock: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val rnd = SecureRandom()

    fun connect(timeoutMs: Int = 4000) {
        val u = URI(url)
        val secure = u.scheme == "wss"
        val host = u.host; val port = if (u.port > 0) u.port else if (secure) 443 else 80
        require(isLan(host)) { "Only TVs on your own network" }
        val raw = Socket().apply { connect(InetSocketAddress(host, port), timeoutMs); soTimeout = timeoutMs }
        val s = if (secure) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, if (lanTls) arrayOf(TrustAll) else null, rnd)
            (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply { startHandshake() }
        } else raw
        sock = s; input = s.getInputStream(); output = s.getOutputStream()
        val key = Base64.encodeToString(ByteArray(16).also(rnd::nextBytes), Base64.NO_WRAP)
        val path = (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
        output.write(("GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nOrigin: http://$host\r\n\r\n").toByteArray())
        output.flush()
        val head = readHeaders()
        if (!head.startsWith("HTTP/1.1 101")) throw IllegalStateException("TV refused the connection (${head.lineSequence().firstOrNull()})")
    }

    private fun readHeaders(): String {
        val b = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val c = input.read(); if (c < 0) break
            b.write(c); last4 = (last4 shl 8) or c
            if (last4 == 0x0D0A0D0A || b.size() > 16384) break
        }
        return b.toString("UTF-8")
    }

    @Synchronized fun send(text: String) = frame(0x1, text.toByteArray())

    private fun frame(op: Int, data: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(0x80 or op)
        when {
            data.size < 126 -> out.write(0x80 or data.size)
            data.size < 65536 -> { out.write(0x80 or 126); out.write(data.size shr 8); out.write(data.size and 0xff) }
            else -> { out.write(0x80 or 127); for (i in 7 downTo 0) out.write(((data.size.toLong() shr (8 * i)) and 0xff).toInt()) }
        }
        val mask = ByteArray(4).also(rnd::nextBytes); out.write(mask)
        for (i in data.indices) out.write(data[i].toInt() xor mask[i % 4].toInt())
        output.write(out.toByteArray()); output.flush()
    }

    /** The next text message, or null on close/timeout. Answers pings on its own. */
    fun receive(timeoutMs: Int): String? {
        sock?.soTimeout = timeoutMs
        return runCatching {
            while (true) {
                val b0 = input.read(); if (b0 < 0) return null
                val b1 = input.read(); if (b1 < 0) return null
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(it) } else null
                val data = ByteArray(len.toInt()).also { readFully(it) }
                if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (b0 and 0x0f) {
                    0x1 -> return String(data)
                    0x8 -> return null
                    0x9 -> frame(0xA, data)
                    else -> {}
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }.getOrNull()
    }

    private fun readFully(b: ByteArray) { var n = 0; while (n < b.size) { val r = input.read(b, n, b.size - n); if (r < 0) throw java.io.EOFException(); n += r } }

    override fun close() { runCatching { frame(0x8, ByteArray(0)) }; runCatching { sock?.close() } }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    companion object {
        /** Private addresses only: 10.x, 172.16–31.x, 192.168.x, link-local (and this device, for tests). */
        fun isLan(host: String): Boolean = runCatching {
            val a = java.net.InetAddress.getByName(host)
            a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress
        }.getOrDefault(false)
    }
}
