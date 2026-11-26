package com.nebula.assistant.mood

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** A TV that answered on your Wi-Fi. [kind]: "samsung", "lg" or "tv" (any other screen that speaks DIAL). */
data class Tv(val id: String, val name: String, val kind: String, val ip: String, val model: String = "")

/**
 * Finds real TVs on the same Wi-Fi with SSDP (the discovery TVs themselves answer to). Nothing is invented:
 * a TV is listed only when it replied and its description says it's a screen (Samsung, LG, or a DIAL screen
 * like Google TV, Fire TV or Roku). Speakers and other media renderers are left out — they have no screen.
 */
class TvFinder(private val ctx: Context) {
    private val searches = listOf(
        "urn:samsung.com:device:RemoteControlReceiver:1",
        "urn:lge-com:service:webos-second-screen:1",
        "urn:dial-multiscreen-org:service:dial:1",
    )

    suspend fun scan(seconds: Int = 4, onFound: (Tv) -> Unit) = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("nebula-moodplay").apply { setReferenceCounted(false); acquire() }
        val seen = HashMap<String, Tv>()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { // UDP can drop: ask twice
                    for (st in searches) {
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                        sock.send(DatagramPacket(msg, msg.size, group, 1900))
                    }
                }
                val end = System.currentTimeMillis() + seconds * 1000L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    val ip = p.address.hostAddress ?: continue
                    if (!WsClient.isLan(ip)) continue
                    val st = header(text, "ST"); val loc = header(text, "LOCATION")
                    val kindHint = when { st.contains("samsung", true) -> "samsung"; st.contains("lge", true) -> "lg"; else -> null }
                    val prev = seen[ip]
                    if (prev != null && (kindHint == null || prev.kind == kindHint)) continue
                    val tv = describe(ip, loc, kindHint) ?: continue
                    if (prev == null || prev.kind == "tv") { seen[ip] = tv; onFound(tv) }
                }
            }
        } finally { runCatching { lock.release() } }
    }

    /** Reads the device description to get its real name; returns null for things that aren't screens. */
    private fun describe(ip: String, location: String, hint: String?): Tv? {
        var name = ""; var maker = ""; var model = ""; var type = ""
        if (location.startsWith("http")) runCatching {
            val u = URL(location)
            if (u.host != ip) return@runCatching // descriptions must come from the TV that answered
            val c = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 1500; readTimeout = 1500 }
            val xml = c.inputStream.bufferedReader().use { it.readText().take(200_000) }
            name = tag(xml, "friendlyName"); maker = tag(xml, "manufacturer"); model = tag(xml, "modelName"); type = tag(xml, "deviceType")
        }
        val kind = hint ?: when {
            maker.contains("samsung", true) -> "samsung"
            maker.contains("lg", true) && (model.contains("webos", true) || type.contains("tv", true) || name.contains("tv", true)) -> "lg"
            else -> "tv"
        }
        // A plain DIAL reply with a name is a screen (TVs, Chromecast, Fire TV, Roku); anything nameless is skipped.
        if (name.isBlank()) name = when (kind) { "samsung" -> "Samsung TV"; "lg" -> "LG TV"; else -> return null }
        if (kind == "tv" && Regex("speaker|soundbar|sonos|audio|receiver", RegexOption.IGNORE_CASE).containsMatchIn("$name $model $type")) return null
        return Tv(id = "$kind:$ip", name = clean(name), kind = kind, ip = ip, model = model)
    }

    private fun clean(s: String) = s.replace("&amp;", "&").replace(Regex("^\\[TV]\\s*"), "").trim().take(40)
    private fun header(text: String, h: String) = text.lineSequence().firstOrNull { it.startsWith("$h:", true) }?.substringAfter(':')?.trim().orEmpty()
    private fun tag(xml: String, t: String) = Regex("<$t>([^<]*)</$t>").find(xml)?.groupValues?.get(1)?.trim().orEmpty()
}
