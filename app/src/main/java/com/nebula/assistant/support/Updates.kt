package com.nebula.assistant.support

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nebula.assistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Knows when a newer Nebula is out: compares this app's build number with the latest GitHub release
 * (tag v2.0.N). Recommends updating — a banner in the app and one notification per new version — but the
 * user can always say "Later" or skip that version.
 */
object Updates {
    data class Info(val build: Int, val name: String, val notes: String, val apk: String)

    private val _available = MutableStateFlow<Info?>(null)
    val available: StateFlow<Info?> = _available
    private const val APK = "https://github.com/${Reporter.REPO}/releases/latest/download/Nebula.apk"

    fun currentBuild(ctx: Context): Int = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    suspend fun check(ctx: Context, notify: Boolean = false): Info? = withContext(Dispatchers.IO) {
        val c = (URL("https://api.github.com/repos/${Reporter.REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000; setRequestProperty("Accept", "application/vnd.github+json")
        }
        val j = try { if (c.responseCode !in 200..299) return@withContext null; JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        val tag = j.optString("tag_name")
        val build = Regex("v?2\\.0\\.(\\d+)").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
        val sp = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (build <= currentBuild(ctx) || build == sp.getInt("skipped", -1)) { _available.value = null; return@withContext null }
        val notes = j.optString("body").lineSequence().filter { it.trimStart().startsWith("- ") }.take(6).joinToString("\n")
        val info = Info(build, j.optString("name").ifBlank { "Nebula 2.0.$build" }, notes, APK)
        _available.value = info
        if (notify && sp.getInt("notified", -1) != build) { sp.edit().putInt("notified", build).apply(); post(ctx, info) }
        info
    }

    fun skip(ctx: Context, info: Info) { ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putInt("skipped", info.build).apply(); _available.value = null }
    fun later() { _available.value = null }
    fun open(ctx: Context, info: Info) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apk)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    private fun post(ctx: Context, info: Info) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("updates", "Nebula updates", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 7, Intent(Intent.ACTION_VIEW, Uri.parse(info.apk)), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(7001, NotificationCompat.Builder(ctx, "updates").setSmallIcon(R.drawable.ic_stat_nebula)
                .setContentTitle("A Nebula update is ready").setContentText("${info.name} — tap to download. Recommended, but you can skip it.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOf("${info.name} — tap to download. Recommended, but you can skip it.", info.notes).filter { it.isNotBlank() }.joinToString("\n")))
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }
}
