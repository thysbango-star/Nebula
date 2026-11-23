package com.nebula.assistant.notch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds a song's cover when the playing app doesn't hand one over (YouTube, browsers, some radio apps):
 *  1. the artwork links the app put in its media info (content:// or https://),
 *  2. the iTunes Search API, by artist + title (free, no key),
 *  3. Deezer's public search, as a second opinion.
 * Results are cached, misses are remembered, so each song is looked up once.
 */
object CoverFinder {
    private val cache = LruCache<String, Bitmap>(40)
    private val missing = HashSet<String>()

    /** "Video • Shimza & Elderbrook • 90K views" → "Shimza & Elderbrook"; "Artist - Topic" → "Artist". */
    fun cleanArtist(a: String): String {
        val parts = a.split(" • ", " · ").map { it.trim() }.filter { p ->
            p.isNotEmpty() && !Regex("^(video|song|music|podcast|episode|live)$", RegexOption.IGNORE_CASE).matches(p) &&
                !Regex("^[\\d.,]+\\s*[KMB]?\\s*(views?|watching|plays?)$", RegexOption.IGNORE_CASE).matches(p) &&
                !Regex("^\\d+\\s+(years?|months?|weeks?|days?|hours?)\\s+ago$", RegexOption.IGNORE_CASE).matches(p)
        }
        return (parts.firstOrNull() ?: a).removeSuffix(" - Topic").replace(Regex("\\s*VEVO$", RegexOption.IGNORE_CASE), "").trim()
    }

    /** Strips video dressing from a title so the search finds the song. */
    fun cleanTitle(t: String): String = t
        .replace(Regex("[\\(\\[][^\\)\\]]*(official|video|audio|lyric|visuali[sz]er|music video|hd|4k|remaster)[^\\)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+(ft\\.?|feat\\.?)\\s+.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s{2,}"), " ").trim()

    fun cached(title: String, artist: String): Bitmap? = cache.get(key(title, artist))
    private fun key(t: String, a: String) = "${cleanTitle(t).lowercase()}|${cleanArtist(a).lowercase()}"

    suspend fun find(ctx: Context, title: String, artist: String, uris: List<String>): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(title, artist)
        cache.get(k)?.let { return@withContext it }
        if (k in missing) return@withContext null
        val bmp = uris.firstNotNullOfOrNull { load(ctx, it) }
            ?: runCatching { itunes(title, artist) }.getOrNull()?.let { load(ctx, it) }
            ?: runCatching { deezer(title, artist) }.getOrNull()?.let { load(ctx, it) }
        if (bmp != null) cache.put(k, bmp) else missing += k
        bmp
    }

    private fun load(ctx: Context, src: String): Bitmap? = runCatching {
        val bytes = if (src.startsWith("http")) {
            val c = (URL(src).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 8000 }
            try { if (c.responseCode !in 200..299) null else c.inputStream.readBytes() } finally { c.disconnect() }
        } else ctx.contentResolver.openInputStream(Uri.parse(src))?.use { it.readBytes() }
        bytes ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (o.outWidth / (s * 2) >= 320) s *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    private fun get(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 8000 }
        try { return JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }

    private fun itunes(title: String, artist: String): String? {
        val term = URLEncoder.encode("${cleanArtist(artist)} ${cleanTitle(title)}".trim(), "UTF-8")
        val r = get("https://itunes.apple.com/search?term=$term&entity=song&limit=3").optJSONArray("results") ?: return null
        val t = cleanTitle(title).lowercase()
        val best = (0 until r.length()).mapNotNull { r.optJSONObject(it) }
            .firstOrNull { it.optString("trackName").lowercase().let { n -> n.contains(t) || t.contains(n) } } ?: r.optJSONObject(0) ?: return null
        return best.optString("artworkUrl100").takeIf { it.isNotBlank() }?.replace("100x100bb", "600x600bb")
    }

    private fun deezer(title: String, artist: String): String? {
        val q = URLEncoder.encode("${cleanArtist(artist)} ${cleanTitle(title)}".trim(), "UTF-8")
        val d = get("https://api.deezer.com/search?q=$q&limit=1").optJSONArray("data")?.optJSONObject(0) ?: return null
        return d.optJSONObject("album")?.optString("cover_xl")?.takeIf { it.isNotBlank() }
    }
}
