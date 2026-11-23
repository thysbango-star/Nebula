package com.nebula.assistant.sports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Team logos from ESPN, kept small in memory and cached on disk so each one downloads once. */
object Logos {
    private val mem = LruCache<String, Bitmap>(48)
    private val failed = HashSet<String>()

    fun cached(url: String): Bitmap? = if (url.isBlank()) null else mem.get(url)

    suspend fun get(ctx: Context, url: String): Bitmap? {
        if (url.isBlank() || url in failed) return null
        mem.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "logos").apply { mkdirs() }
                val f = File(dir, MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".png")
                if (!f.exists() || f.length() == 0L) {
                    val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000 }
                    try {
                        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                        c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    } finally { c.disconnect() }
                }
                // ESPN logos are 500 px; decode at ~1/4 so the notch stays light.
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var sample = 1; while (o.outWidth / (sample * 2) >= 128) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { mem.put(url, it) }
            }.getOrNull().also { if (it == null) failed += url }
        }
    }
}
