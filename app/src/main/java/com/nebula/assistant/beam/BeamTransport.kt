package com.nebula.assistant.beam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Nebula-to-Nebula transfer over the local network (TCP), encrypted with [BeamSecurity].
 *
 * Sender → receiver:  {"v":1,"from","device","pub","items":[{name,size,mime}],"text"}\n
 * Receiver → sender:  {"ok":true,"pub"}\n  (only after the person taps Accept)  or {"ok":false,"reason"}\n
 * Then, per item: encrypted frames, a zero frame ends each file. Receiver finishes with {"done":true,"saved":n}\n
 */
object BeamTransport {

    private fun readLine(inp: DataInputStream): String {
        val b = StringBuilder()
        while (true) { val c = inp.read(); if (c < 0) error("closed"); if (c == '\n'.code) break; b.append(c.toChar()); if (b.length > 200_000) error("header too long") }
        return b.toString()
    }
    private fun writeLine(out: DataOutputStream, s: String) { out.write((s + "\n").toByteArray()); out.flush() }

    class SendResult(val ok: Boolean, val reason: String = "", val saved: Int = 0)

    /** Sends [items] to [d]. [onCode] fires once the receiver accepts (shared code), [onProgress] with the real fraction. */
    suspend fun send(ctx: Context, d: BeamDevice, from: String, device: String, items: List<BeamItem>,
                     onWaiting: (String) -> Unit, onProgress: (Float) -> Unit): SendResult = withContext(Dispatchers.IO) {
        val kp = BeamSecurity.keyPair()
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(d.host, d.port), 6000)
            sock.soTimeout = 75_000 // the receiver has a minute to decide
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 1 shl 16))
            val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val files = items.filter { it.uri != null }
            val header = JSONObject().put("v", 1).put("from", from).put("device", device).put("pub", BeamSecurity.pub(kp))
                .put("items", JSONArray(files.map { JSONObject().put("name", it.name).put("size", it.size).put("mime", it.mime) }))
            items.firstOrNull { it.text != null }?.let { header.put("text", it.text) }
            writeLine(out, header.toString())
            onWaiting("")
            val reply = JSONObject(readLine(inp))
            if (!reply.optBoolean("ok")) return@withContext SendResult(false, reply.optString("reason").ifBlank { "Transfer declined" })
            val session = BeamSecurity.Session(kp, reply.getString("pub"))
            onWaiting(session.code)
            sock.soTimeout = 30_000
            val total = files.sumOf { it.size }.coerceAtLeast(1)
            var sent = 0L
            val buf = ByteArray(64 * 1024)
            for (f in files) {
                ctx.contentResolver.openInputStream(f.uri!!)?.use { src ->
                    while (true) {
                        val n = src.read(buf); if (n <= 0) break
                        session.writeFrame(out, buf, n); sent += n
                        onProgress((sent.toFloat() / total).coerceAtMost(1f))
                    }
                } ?: error("Couldn't read ${f.name}")
                session.writeFrame(out, buf, 0)
            }
            out.flush()
            val done = JSONObject(readLine(inp))
            SendResult(done.optBoolean("done"), done.optString("reason"), done.optInt("saved"))
        } catch (e: java.net.ConnectException) { SendResult(false, "Recipient unavailable")
        } catch (e: java.net.SocketTimeoutException) { SendResult(false, "Recipient didn't answer")
        } catch (e: Exception) { SendResult(false, if (e.message == "closed") "Connection lost" else "Connection lost (${e.message ?: e.javaClass.simpleName})")
        } finally { runCatching { sock.close() } }
    }

    // ------------------------------------------------------------------ receiver

    class Receiver(private val ctx: Context, private val onOffer: suspend (Offer) -> Boolean,
                   private val onProgress: (String, Float, Int) -> Unit, private val onDone: (String, List<Uri>, String?) -> Unit,
                   private val onError: (String) -> Unit) {
        private var server: ServerSocket? = null
        val port get() = server?.localPort ?: 0
        @Volatile private var busy = false

        fun start(): Int {
            if (server != null) return port
            val s = ServerSocket(0); server = s
            Thread({
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    if (busy) { runCatching { c.close() }; continue } // one Beam at a time
                    busy = true
                    Thread({ try { handle(c) } finally { busy = false; runCatching { c.close() } } }, "beam-in").start()
                }
            }, "beam-listen").apply { isDaemon = true }.start()
            return s.localPort
        }

        fun stop() { runCatching { server?.close() }; server = null }

        private fun handle(c: Socket) {
            c.soTimeout = 90_000
            val inp = DataInputStream(BufferedInputStream(c.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(c.getOutputStream()))
            val h = JSONObject(readLine(inp))
            val itemsJ = h.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsJ.length()).map { itemsJ.getJSONObject(it).let { o -> OfferItem(o.optString("name").take(120).ifBlank { "file" }, o.optLong("size"), o.optString("mime")) } }
            val maxBytes = com.nebula.assistant.data.NebulaPrefs(ctx).beamMaxMb * 1024L * 1024L
            if (items.sumOf { it.size } > maxBytes) { writeLine(out, JSONObject().put("ok", false).put("reason", "Too big for this device's Beam limit").toString()); return }
            val kp = BeamSecurity.keyPair()
            val session = BeamSecurity.Session(kp, h.getString("pub"))
            val from = h.optString("from").take(60).ifBlank { "Someone nearby" }
            val offer = Offer(BeamSecurity.token(), from, h.optString("device"), items, session.code, h.optString("text").takeIf { it.isNotBlank() }?.take(20_000))
            val ok = kotlinx.coroutines.runBlocking { withTimeoutOrNull(60_000) { onOffer(offer) } } == true
            if (!ok) { writeLine(out, JSONObject().put("ok", false).put("reason", "Transfer declined").toString()); return }
            writeLine(out, JSONObject().put("ok", true).put("pub", BeamSecurity.pub(kp)).toString())
            c.soTimeout = 30_000
            val saved = ArrayList<Uri>()
            val total = items.sumOf { it.size }.coerceAtLeast(1); var got = 0L
            try {
                for (it in items) {
                    val (uri, sink) = sinkFor(it)
                    sink.use { o -> while (true) { val f = session.readFrame(inp) ?: break; o.write(f); got += f.size; onProgress(from, (got.toFloat() / total).coerceAtMost(1f), items.size) } }
                    finish(uri); saved += uri
                }
                writeLine(out, JSONObject().put("done", true).put("saved", saved.size).toString())
                onDone(from, saved, offer.text)
            } catch (e: Exception) {
                saved.forEach { u -> runCatching { ctx.contentResolver.delete(u, null, null) } }
                onError("Connection lost")
            }
        }

        /** Saves into Downloads/Nebula Beam (photos and videos also show in the gallery). */
        private fun sinkFor(it: OfferItem): Pair<Uri, java.io.OutputStream> {
            val safe = it.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val r = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe); put(MediaStore.MediaColumns.MIME_TYPE, it.mime.ifBlank { "application/octet-stream" })
                if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nebula Beam"); put(MediaStore.MediaColumns.IS_PENDING, 1) }
            }
            val coll = if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
            val uri = if (Build.VERSION.SDK_INT >= 29) r.insert(coll, v) else null
            if (uri != null) return uri to (r.openOutputStream(uri) ?: error("can't save"))
            val f = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), safe)
            return Uri.fromFile(f) to f.outputStream()
        }
        private fun finish(uri: Uri) {
            if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") runCatching { ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
        }
    }
}
