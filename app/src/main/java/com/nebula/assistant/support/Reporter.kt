package com.nebula.assistant.support

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.net.URLEncoder

/**
 * "Report to Claude": turns a problem into a GitHub issue on the Nebula repo (label `nebula-report`), pre-filled
 * with what went wrong, the app version and the phone model. The user sees it in their browser and presses Submit
 * themselves — nothing is sent silently. A scheduled Claude session reads new reports, fixes them and ships an
 * update, which Nebula then offers (see [Updates]).
 *
 * Keys and anything that looks like a secret are scrubbed before the report leaves the phone.
 */
object Reporter {
    const val REPO = "thysbango-star/Nebula"
    private val log = ArrayDeque<String>()

    /** Keep a short trail of recent errors to attach to a report (in memory only). */
    @Synchronized fun note(line: String) { log.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $line".take(300)); while (log.size > 30) log.removeFirst() }

    fun scrub(s: String) = s
        .replace(Regex("gsk_[A-Za-z0-9]{10,}"), "gsk_••••")
        .replace(Regex("(sk|pk|ghp|gho|github_pat)_[A-Za-z0-9_]{10,}"), "••••")
        .replace(Regex("(?i)(api[_-]?key|token|authorization|bearer)\\s*[:=]\\s*\\S+"), "$1: ••••")
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "(email)")

    fun issueUrl(ctx: Context, what: String, detail: String = ""): String {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val trail = synchronized(this) { log.toList() }.takeLast(12).joinToString("\n")
        val body = buildString {
            appendLine("### What happened"); appendLine(scrub(what)); appendLine()
            if (detail.isNotBlank()) { appendLine("### Details"); appendLine("```"); appendLine(scrub(detail).take(3000)); appendLine("```"); appendLine() }
            if (trail.isNotBlank()) { appendLine("### Recent errors"); appendLine("```"); appendLine(scrub(trail)); appendLine("```"); appendLine() }
            appendLine("### Device")
            appendLine("- Nebula $version (Android app)")
            appendLine("- ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(); appendLine("_Sent with **Report to Claude** from inside Nebula._")
        }
        val title = "[Report] " + scrub(what).lineSequence().first().take(80)
        fun e(x: String) = URLEncoder.encode(x, "UTF-8")
        return "https://github.com/$REPO/issues/new?labels=nebula-report&title=${e(title)}&body=${e(body.take(6000))}"
    }

    fun open(ctx: Context, what: String, detail: String = "") {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl(ctx, what, detail))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ---- crashes: saved on the way down, offered for reporting on the next launch

    private fun crashFile(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    fun installCrashCatcher(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile(app).writeText("${e.javaClass.name}: ${e.message}\n" + e.stackTraceToString().lineSequence().take(40).joinToString("\n")) }
            previous?.uncaughtException(t, e)
        }
    }

    fun lastCrash(ctx: Context): String? = crashFile(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    fun clearCrash(ctx: Context) { crashFile(ctx).delete() }
}
