package com.nebula.assistant.beam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Beam Link: the phone itself serves a one-time page on your Wi-Fi, so an iPhone, Mac, PC or any Android can
 * receive in its browser by scanning a QR code — no app, no cloud. The QR holds only the address with an
 * unguessable 128-bit token, never the files.
 *
 *  - Same Wi-Fi only; plain http on the local network (not encrypted in transit — the page says so).
 *  - Expires after [ttlMs] (10 minutes) or when the sender cancels; each file can be downloaded up to 3 times.
 *  - The receiver sees names, sizes and previews first and taps Download themselves.
 *  - Light rate limiting per address; wrong tokens get a plain 404. Nothing is copied or kept on a server.
 */
class BeamLink(private val ctx: Context, private val items: List<BeamItem>, private val from: String,
               private val onDownload: (Int) -> Unit, private val ttlMs: Long = 10 * 60_000L) {
    val token = BeamSecurity.token()
    val expiresAt = System.currentTimeMillis() + ttlMs
    private var server: ServerSocket? = null
    private val downloads = IntArray(items.size)
    private val hits = HashMap<String, MutableList<Long>>()
    var url = ""; private set
    @Volatile var total = 0; private set

    /** Starts serving; returns the URL, or null when there's no Wi-Fi address to serve on. */
    fun start(): String? {
        val ip = wifiIp() ?: return null
        val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(ip, 0)); server = s
        url = "http://$ip:${s.localPort}/b/$token"
        Thread({
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ runCatching { handle(c) }; runCatching { c.close() } }, "beam-link").apply { isDaemon = true }.start()
            }
        }, "beam-link-listen").apply { isDaemon = true }.start()
        return url
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun wifiIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun limited(ip: String): Boolean = synchronized(hits) {
        val now = System.currentTimeMillis()
        val l = hits.getOrPut(ip) { mutableListOf() }; l.removeAll { now - it > 60_000 }; l += now
        l.size > 60
    }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val r = BufferedReader(InputStreamReader(c.getInputStream()))
        val line = r.readLine() ?: return
        while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }
        val path = line.split(" ").getOrNull(1).orEmpty()
        val out = c.getOutputStream()
        val ip = c.inetAddress.hostAddress.orEmpty()
        if (limited(ip)) return status(out, 429, "Too many requests")
        if (System.currentTimeMillis() > expiresAt) return status(out, 410, "This Beam Link has expired. Ask the sender for a new one.")
        val base = "/b/$token"
        when {
            path == base || path == "$base/" -> page(out)
            path.startsWith("$base/f/") || path.startsWith("$base/p/") -> {
                val i = path.substringAfterLast('/').toIntOrNull() ?: return status(out, 404, "Not found")
                val it = items.getOrNull(i)?.takeIf { x -> x.uri != null } ?: return status(out, 404, "Not found")
                val preview = path.startsWith("$base/p/")
                if (!preview) { if (downloads[i] >= 3) return status(out, 410, "This file was already downloaded"); downloads[i]++; total++; onDownload(total) }
                else if (!it.mime.startsWith("image/")) return status(out, 404, "Not found")
                file(out, it, attachment = !preview)
            }
            else -> status(out, 404, "Not found")
        }
    }

    private fun headers(out: OutputStream, code: Int, type: String, len: Long, extra: String = "") {
        out.write(("HTTP/1.1 $code OK\r\nContent-Type: $type\r\n" + (if (len >= 0) "Content-Length: $len\r\n" else "") +
            "Cache-Control: no-store\r\nX-Robots-Tag: noindex, nofollow\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n$extra\r\n").toByteArray())
    }
    private fun status(out: OutputStream, code: Int, msg: String) {
        val b = "<!doctype html><meta name=viewport content='width=device-width'><body style='font:16px system-ui;background:#0b0c10;color:#eee;padding:40px'>$msg</body>".toByteArray()
        headers(out, code, "text/html; charset=utf-8", b.size.toLong()); out.write(b); out.flush()
    }
    private fun file(out: OutputStream, it: BeamItem, attachment: Boolean) {
        val name = it.name.replace("\"", "")
        headers(out, 200, it.mime.ifBlank { "application/octet-stream" }, it.size.takeIf { s -> s > 0 } ?: -1,
            if (attachment) "Content-Disposition: attachment; filename=\"$name\"\r\n" else "")
        ctx.contentResolver.openInputStream(it.uri!!)?.use { s -> s.copyTo(out, 64 * 1024) }
        out.flush()
    }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(out: OutputStream) {
        val base = "/b/$token"
        val files = items.withIndex().filter { it.value.uri != null }.joinToString("") { (i, f) ->
            val prev = if (f.mime.startsWith("image/")) "<img src='$base/p/$i' alt=''>" else "<div class=ic>${esc(f.name.substringAfterLast('.', "file").take(4).uppercase())}</div>"
            "<div class=f>$prev<div class=t><b>${esc(f.name)}</b><span>${humanSize(f.size)}</span></div><a class=btn href='$base/f/$i' download>Download</a></div>"
        }
        val text = items.firstOrNull { it.text != null }?.text?.let { "<div class=txt id=tx>${esc(it)}</div><button class=btn onclick=\"navigator.clipboard.writeText(document.getElementById('tx').innerText);this.textContent='Copied'\">Copy text</button>" }.orEmpty()
        val mins = ((expiresAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
        val html = """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><meta name=robots content="noindex,nofollow"><title>Nebula Beam</title>
<style>:root{color-scheme:dark}body{margin:0;font:15px/1.45 -apple-system,system-ui,Segoe UI,Roboto,sans-serif;background:radial-gradient(120% 60% at 50% -10%,#1b2a5a,#0b0c10 60%);color:#f2f3f7;min-height:100vh}
main{max-width:560px;margin:0 auto;padding:28px 18px 40px}h1{font-size:24px;margin:6px 0 2px}.sub{color:#a9adbb;margin:0 0 18px}.f{display:flex;align-items:center;gap:12px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.1);border-radius:16px;padding:10px;margin:10px 0}
.f img,.ic{width:56px;height:56px;border-radius:12px;object-fit:cover;flex:none}.ic{display:grid;place-items:center;background:#22263a;font-weight:700;font-size:12px;color:#8fd3ff}.t{flex:1;min-width:0}.t b{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.t span{color:#a9adbb;font-size:13px}
.btn{background:#4d8dff;color:#fff;border:0;border-radius:999px;padding:9px 16px;font-weight:600;text-decoration:none;font-size:14px;flex:none;cursor:pointer}.txt{white-space:pre-wrap;background:rgba(255,255,255,.06);border-radius:14px;padding:14px;margin:10px 0}
.note{color:#8a8fa0;font-size:12.5px;margin-top:22px}.tip{color:#c7cad6;font-size:13px;margin-top:14px}</style></head><body><main>
<div style="color:#8fd3ff;font-weight:700;letter-spacing:.12em;font-size:12px">NEBULA BEAM</div><h1>${esc(from)} is sending you ${items.size} item${if (items.size == 1) "" else "s"}</h1>
<p class=sub>Tap Download to accept. Nothing downloads until you do.</p>$files$text
<p class=tip><b>On iPhone:</b> photos and videos land in Files → Downloads — open one and tap Share → Save Image / Save Video to put it in Photos.</p>
<p class=note>Sent directly from ${esc(from)}'s phone over your Wi-Fi — no cloud. This local link isn't encrypted (http), expires in about $mins min and each file can be downloaded 3 times.</p>
</main></body></html>"""
        val b = html.toByteArray()
        headers(out, 200, "text/html; charset=utf-8", b.size.toLong()); out.write(b); out.flush()
    }

    companion object {
        fun qr(text: String, size: Int = 512): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
