package com.nebula.assistant.widgets

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.nebula.assistant.R
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.Store
import com.nebula.assistant.data.TaskStatus
import com.nebula.assistant.intel.CalendarIntel
import com.nebula.assistant.intel.Planner
import com.nebula.assistant.intel.Reminders
import com.nebula.assistant.notch.MusicTheme
import com.nebula.assistant.notch.NotchBus
import com.nebula.assistant.sports.Sports
import com.nebula.assistant.ui.MainActivity
import com.nebula.assistant.ui.TalkActivity
import com.nebula.assistant.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nebula Widgets — native home-screen widgets (RemoteViews, so they work on every launcher from Android 10).
 * They show Nebula's own live data (the media session the Notch uses, the real task list, ESPN fixtures, the real
 * calendar) and refresh on events — a track change, a task update, a score — plus a slow, battery-friendly tick
 * for the clock-based ones. Nothing here is made up: no data means the widget says so.
 */
object Widgets {
    data class Info(val kind: String, val title: String, val description: String, val provider: Class<out AppWidgetProvider>)
    val all = listOf(
        Info("voice", "Nebula Voice", "Tap to talk — Nebula starts listening straight away.", VoiceWidget::class.java),
        Info("music", "Nebula Music", "Album art, title and controls for whatever's playing.", MusicWidget::class.java),
        Info("ask", "Ask Nebula", "Type a question; it's answered as soon as the app opens.", AskWidget::class.java),
        Info("tasks", "Nebula Tasks", "Reminders and what needs you, with tick-to-complete.", TasksWidget::class.java),
        Info("sports", "Nebula Sports", "Your teams' next fixture, live score or result.", SportsWidget::class.java),
        Info("daily", "Nebula Daily", "Date, next event, tasks and your next match.", DailyWidget::class.java),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    const val ACTION = "com.nebula.assistant.widgets.ACTION"

    fun placed(ctx: Context, kind: String): Boolean {
        val info = all.firstOrNull { it.kind == kind } ?: return false
        return runCatching { AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, info.provider)).isNotEmpty() }.getOrDefault(false)
    }

    /** Re-draw the given widget kinds (all if none given). Cheap when none are on the home screen. */
    fun refresh(ctx: Context, vararg kinds: String) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        for (info in all) {
            if (kinds.isNotEmpty() && info.kind !in kinds) continue
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, info.provider)) }.getOrNull() ?: continue
            for (id in ids) runCatching { mgr.updateAppWidget(id, render(ctx, info.kind, mgr.getAppWidgetOptions(id))) }
        }
    }

    /** Listen to Nebula's live data and refresh only what changed. */
    @OptIn(FlowPreview::class)
    fun watch(app: Context) {
        scope.launch { NotchBus.media.map { m -> m?.let { listOf(it.title, it.artist, it.playing, it.art?.generationId, it.pkg, it.duration) } }
            .distinctUntilChanged().debounce(400).collect { refresh(app, "music") } }
        scope.launch { VoiceState.session.map { it.phase == VoiceState.Phase.LISTENING }.distinctUntilChanged().collect { refresh(app, "voice") } }
        scope.launch { Store.tasks.map { l -> l.map { listOf(it.id, it.status, it.dueAt) } }.distinctUntilChanged().debounce(500).collect { refresh(app, "tasks", "daily") } }
        scope.launch { Sports.games.map { g -> g.map { listOf(it.id, it.state, it.home.text, it.away.text, it.clock) } }.distinctUntilChanged().debounce(800).collect { refresh(app, "sports", "daily") } }
        scope.launch { Sports.fixtures.collect { refresh(app, "sports", "daily") } }
        schedule(app)
    }

    /** The slow tick for Sports and Daily (only while one is on the home screen, never waking the phone). */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 9200, Intent(ctx, WidgetReceiver::class.java).setAction("$ACTION.TICK"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!placed(ctx, "sports") && !placed(ctx, "daily")) { am.cancel(pi); return }
        val every = NebulaPrefs(ctx).widgetRefresh * 60_000L
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + every, every, pi)
        tick(ctx)
    }

    fun tick(ctx: Context) {
        scope.launch(Dispatchers.IO) {
            val prefs = NebulaPrefs(ctx)
            val f = Sports.fixtures.value
            // Fixtures change slowly; refresh more often only when a game is on or about to start.
            val soon = f.any { it.game.live || (it.game.state == "pre" && it.game.start - System.currentTimeMillis() < 2 * 3_600_000L) }
            runCatching { Sports.refreshFixtures(prefs, if (soon) 10 * 60_000L else 3 * 3_600_000L) }
            launch(Dispatchers.Main) { refresh(ctx, "sports", "daily") }
        }
    }

    fun pin(ctx: Context, kind: String): Boolean {
        val info = all.firstOrNull { it.kind == kind } ?: return false
        val mgr = AppWidgetManager.getInstance(ctx)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return runCatching { mgr.requestPinAppWidget(ComponentName(ctx, info.provider), null, null) }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ rendering

    private fun render(ctx: Context, kind: String, opts: Bundle?): RemoteViews {
        val w = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val h = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        return when (kind) {
            "voice" -> voice(ctx, w)
            "music" -> music(ctx)
            "ask" -> ask(ctx, h)
            "tasks" -> tasks(ctx, h)
            "sports" -> sports(ctx, h)
            else -> daily(ctx)
        }
    }

    private fun talk(ctx: Context): PendingIntent {
        // Starting the mic needs something of ours on screen: TalkActivity is that (invisible) step. Without the
        // microphone permission, open Nebula so the user can grant it the normal way.
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val i = if (mic) Intent(ctx, TalkActivity::class.java) else Intent(ctx, MainActivity::class.java)
        return PendingIntent.getActivity(ctx, 9301, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun app(ctx: Context, code: Int, tab: String? = null, prompt: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).setData(Uri.parse("nebula://w/$code"))
        tab?.let { i.putExtra(MainActivity.EXTRA_TAB, it) }; prompt?.let { i.putExtra(MainActivity.EXTRA_PROMPT, it) }
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun askBox(ctx: Context, code: Int, prefill: String = ""): PendingIntent =
        PendingIntent.getActivity(ctx, code, Intent(ctx, AskActivity::class.java).putExtra("prefill", prefill).setData(Uri.parse("nebula://ask/$code"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun action(ctx: Context, what: String, extra: String = ""): PendingIntent =
        PendingIntent.getBroadcast(ctx, (what + extra).hashCode(), Intent(ctx, WidgetReceiver::class.java).setAction("$ACTION.$what").putExtra("x", extra)
            .setData(Uri.parse("nebula://wa/$what/" + Uri.encode(extra))), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun voice(ctx: Context, w: Int): RemoteViews {
        val listening = VoiceState.session.value.phase == VoiceState.Phase.LISTENING
        val v = RemoteViews(ctx.packageName, if (w in 1 until 150) R.layout.widget_voice_small else R.layout.widget_voice_large)
        v.setInt(R.id.w_orb, "setBackgroundResource", if (listening) R.drawable.w_orb_live else R.drawable.w_orb)
        v.setTextViewText(R.id.w_label, if (listening) "Listening…" else "Ask Nebula")
        if (w !in 1 until 150) v.setTextViewText(R.id.w_sub, if (listening) "Go ahead, I'm listening" else "Tap and talk")
        v.setOnClickPendingIntent(R.id.w_root, talk(ctx))
        return v
    }

    private fun music(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_music)
        val m = NotchBus.media.value
        val th = MusicTheme.of(m?.art)
        v.setImageViewBitmap(R.id.w_bg, glassBg(ctx, th.bg1.toArgb(), th.bg2.toArgb()))
        if (m == null) {
            v.setTextViewText(R.id.w_title, "Nothing playing"); v.setTextViewText(R.id.w_artist, "Play something in any app")
            v.setImageViewResource(R.id.w_art, R.mipmap.ic_launcher); v.setViewVisibility(R.id.w_progress, View.GONE); v.setViewVisibility(R.id.w_bars, View.GONE)
            v.setImageViewResource(R.id.w_play_icon, R.drawable.ic_w_play_dark)
        } else {
            v.setTextViewText(R.id.w_title, m.title); v.setTextViewText(R.id.w_artist, m.artist)
            m.art?.let { v.setImageViewBitmap(R.id.w_art, rounded(it, 180, 28f)) } ?: v.setImageViewResource(R.id.w_art, R.mipmap.ic_launcher)
            v.setImageViewResource(R.id.w_play_icon, if (m.playing) R.drawable.ic_w_pause_dark else R.drawable.ic_w_play_dark)
            if (m.duration > 0) {
                val pos = (m.position + if (m.playing) System.currentTimeMillis() - m.updatedAt else 0L).coerceIn(0, m.duration)
                v.setViewVisibility(R.id.w_progress, View.VISIBLE); v.setProgressBar(R.id.w_progress, 1000, (pos * 1000 / m.duration).toInt(), false)
            } else v.setViewVisibility(R.id.w_progress, View.GONE)
            if (m.playing) { v.setViewVisibility(R.id.w_bars, View.VISIBLE); v.setImageViewBitmap(R.id.w_bars, bars(m.title.hashCode(), th.accent.toArgb())) }
            else v.setViewVisibility(R.id.w_bars, View.GONE)
            ctx.packageManager.getLaunchIntentForPackage(m.pkg)?.let { li ->
                v.setOnClickPendingIntent(R.id.w_art, PendingIntent.getActivity(ctx, 9310, li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }
        v.setOnClickPendingIntent(R.id.w_play, action(ctx, "MEDIA", "playpause"))
        v.setOnClickPendingIntent(R.id.w_next, action(ctx, "MEDIA", "next"))
        v.setOnClickPendingIntent(R.id.w_prev, action(ctx, "MEDIA", "prev"))
        return v
    }

    private fun ask(ctx: Context, h: Int): RemoteViews {
        val large = h >= 100
        val v = RemoteViews(ctx.packageName, if (large) R.layout.widget_ask_large else R.layout.widget_ask_small)
        v.setOnClickPendingIntent(R.id.w_field, askBox(ctx, 9320))
        v.setOnClickPendingIntent(R.id.w_mic, talk(ctx))
        if (large) {
            v.setOnClickPendingIntent(R.id.w_chip1, app(ctx, 9321, prompt = "What should I do today?"))
            v.setOnClickPendingIntent(R.id.w_chip2, askBox(ctx, 9322, "Explain "))
            v.setOnClickPendingIntent(R.id.w_chip3, askBox(ctx, 9323, "Summarise "))
            v.setOnClickPendingIntent(R.id.w_chip4, askBox(ctx, 9324, "Help me study "))
        }
        return v
    }

    private fun whenText(t: Long): String {
        val now = System.currentTimeMillis(); val hm = CalendarIntel.time(t)
        return when {
            t < now -> "Overdue · $hm"
            t < CalendarIntel.dayStart(now, 1) -> "Today · $hm"
            t < CalendarIntel.dayStart(now, 2) -> "Tomorrow · $hm"
            else -> SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(t))
        }
    }

    private fun tasks(ctx: Context, h: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_tasks)
        val all = Store.tasks.value
        // Prioritised: what needs you, then reminders by due time, then what Nebula is working on.
        val needs = all.filter { it.status == TaskStatus.REQUIRES_INPUT }
        val rems = all.filter { it.isReminder && it.isActive }.sortedBy { if (it.dueAt > 0) it.dueAt else Long.MAX_VALUE }
        val running = all.filter { it.status == TaskStatus.IN_PROGRESS && !it.isReminder }
        val list = needs + rems + running
        val todayRems = rems.count { it.dueAt in 1 until CalendarIntel.dayStart(System.currentTimeMillis(), 1) }
        v.setTextViewText(R.id.w_count, when {
            list.isEmpty() -> "Nothing to do"
            needs.isNotEmpty() -> "${needs.size} need${if (needs.size == 1) "s" else ""} you · ${rems.size} reminder${if (rems.size == 1) "" else "s"}"
            else -> "${list.size} to do${if (todayRems > 0) " · $todayRems today" else ""}"
        })
        val rows = listOf(Triple(R.id.w_row1, R.id.w_check1, R.id.w_text1 to R.id.w_sub1), Triple(R.id.w_row2, R.id.w_check2, R.id.w_text2 to R.id.w_sub2),
            Triple(R.id.w_row3, R.id.w_check3, R.id.w_text3 to R.id.w_sub3), Triple(R.id.w_row4, R.id.w_check4, R.id.w_text4 to R.id.w_sub4))
        val max = if (h >= 220) 4 else if (h >= 150) 3 else 2
        rows.forEachIndexed { i, (row, check, txt) ->
            val t = list.getOrNull(i)
            if (t == null || i >= max) { v.setViewVisibility(row, View.GONE); return@forEachIndexed }
            v.setViewVisibility(row, View.VISIBLE)
            v.setTextViewText(txt.first, t.title)
            v.setTextViewText(txt.second, when {
                t.status == TaskStatus.REQUIRES_INPUT -> "Needs your answer"
                t.isReminder -> if (t.dueAt > 0) whenText(t.dueAt) else "Reminder"
                else -> t.steps.lastOrNull()?.label ?: "Working on it…"
            })
            v.setImageViewResource(check, if (t.isReminder) R.drawable.ic_w_circle else R.drawable.ic_w_event)
            v.setOnClickPendingIntent(check, if (t.isReminder) action(ctx, "DONE", t.id) else app(ctx, ("o" + t.id).hashCode(), "tasks"))
            v.setOnClickPendingIntent(row, PendingIntent.getActivity(ctx, ("r" + t.id).hashCode(), Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, "tasks").putExtra(MainActivity.EXTRA_TASK, t.id).setData(Uri.parse("nebula://wt/" + t.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        v.setViewVisibility(R.id.w_empty, if (list.isEmpty()) View.VISIBLE else View.GONE)
        v.setOnClickPendingIntent(R.id.w_add, askBox(ctx, 9330, "Remind me to "))
        v.setOnClickPendingIntent(R.id.w_head, app(ctx, 9331, "tasks"))
        return v
    }

    private fun fixtureLines(g: Sports.Game, mine: Sports.Team?): Triple<String, String, String> {
        val homeMine = mine != null && (mine.id == g.home.id || mine.name.equals(g.home.name, true))
        val title = if (mine == null || homeMine) "${g.home.name} vs ${g.away.name}" else "${g.away.name} at ${g.home.name}"
        val day = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(g.start))
        val sub = g.league.name + " · " + when (g.state) { "in" -> "Live ${g.clock}"; "post" -> g.clock; else -> day }
        val right = when (g.state) { "pre" -> CalendarIntel.time(g.start); else -> "${g.home.text}–${g.away.text}" }
        return Triple(title, sub, right)
    }

    private fun sports(ctx: Context, h: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_sports)
        val prefs = NebulaPrefs(ctx)
        val teams = prefs.sportsTeams.mapNotNull(Sports.Team::decode)
        val fx = Sports.merged().map { it.game to it.team }
        // Live games the score watcher knows about (e.g. cricket) that have no schedule entry.
        val live = Sports.games.value.filter { g -> fx.none { it.first.id == g.id } }.map { it to null }
        val list = (live + fx).sortedBy { (g, _) -> if (g.live) 0L else if (g.state == "pre") g.start else Long.MAX_VALUE }.distinctBy { it.first.id }
        val ids = listOf(Triple(R.id.w_fx1, R.id.w_fxt1, R.id.w_fxs1 to R.id.w_fxr1), Triple(R.id.w_fx2, R.id.w_fxt2, R.id.w_fxs2 to R.id.w_fxr2), Triple(R.id.w_fx3, R.id.w_fxt3, R.id.w_fxs3 to R.id.w_fxr3))
        val max = if (h >= 150) 3 else if (h >= 100) 2 else 1
        ids.forEachIndexed { i, (row, t, sr) ->
            val p = list.getOrNull(i)
            if (p == null || i >= max) { v.setViewVisibility(row, View.GONE); return@forEachIndexed }
            val (a, b, c) = fixtureLines(p.first, p.second)
            v.setViewVisibility(row, View.VISIBLE); v.setTextViewText(t, a); v.setTextViewText(sr.first, b); v.setTextViewText(sr.second, c)
        }
        v.setViewVisibility(R.id.w_live, if (list.any { it.first.live }) View.VISIBLE else View.GONE)
        val empty = when { !prefs.sportsOn || teams.isEmpty() -> "Pick your teams in Nebula › Settings › Sports"; list.isEmpty() -> "No fixtures found for your teams right now"; else -> null }
        v.setViewVisibility(R.id.w_empty, if (empty != null) View.VISIBLE else View.GONE); empty?.let { v.setTextViewText(R.id.w_empty, it) }
        v.setOnClickPendingIntent(R.id.w_root, app(ctx, 9340))
        return v
    }

    private fun daily(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_daily)
        val now = System.currentTimeMillis()
        v.setTextViewText(R.id.w_day, SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now)).uppercase())
        v.setTextViewText(R.id.w_date, SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(now)))
        val prefs = NebulaPrefs(ctx)
        val ev = if (CalendarIntel.connected(ctx)) CalendarIntel.next(ctx) else null
        v.setTextViewText(R.id.w_event_text, when {
            !CalendarIntel.connected(ctx) -> "Connect your calendar in Settings › Rovian Intelligence"
            ev == null -> "Nothing else on your calendar this week"
            else -> {
                val name = if (prefs.calDetail == "full") ev.title else "Next event"
                val mins = (ev.start - now) / 60_000
                val whenS = if (ev.start < CalendarIntel.dayStart(now, 1)) (if (mins in 0..90) "in $mins min" else "at ${CalendarIntel.time(ev.start)}") else whenText(ev.start)
                "$name · $whenS"
            }
        })
        v.setOnClickPendingIntent(R.id.w_event, if (CalendarIntel.connected(ctx)) Planner.calendarDay(ctx, ev?.start ?: now) else app(ctx, 9351))
        val all = Store.tasks.value
        val today = all.count { it.isReminder && it.isActive && it.dueAt in 1 until CalendarIntel.dayStart(now, 1) }
        val needs = all.count { it.status == TaskStatus.REQUIRES_INPUT }
        v.setTextViewText(R.id.w_tasks_text, listOfNotNull(
            if (today > 0) "$today reminder${if (today > 1) "s" else ""} today" else "No reminders today",
            if (needs > 0) "$needs need${if (needs == 1) "s" else ""} you" else null).joinToString(" · "))
        v.setOnClickPendingIntent(R.id.w_tasks, app(ctx, 9352, "tasks"))
        val next = (Sports.games.value.firstOrNull { it.live }?.let { it to null } ?: Sports.merged().firstOrNull { it.game.state != "post" }?.let { it.game to it.team })
        if (next == null) v.setViewVisibility(R.id.w_sport, View.GONE) else {
            val (a, b, _) = fixtureLines(next.first, next.second)
            v.setViewVisibility(R.id.w_sport, View.VISIBLE); v.setTextViewText(R.id.w_sport_text, "$a\n$b")
        }
        v.setOnClickPendingIntent(R.id.w_mic, talk(ctx))
        return v
    }

    // ------------------------------------------------------------------ small bitmaps (kept tiny for RemoteViews)

    private fun glassBg(ctx: Context, c1: Int, c2: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (300 * d / 2).toInt().coerceAtMost(600); val h = (150 * d / 2).toInt().coerceAtMost(300)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val r = 26 * d / 2
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), c1, c2, Shader.TileMode.CLAMP) }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP); c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = d / 2; p.color = 0x40FFFFFF
        c.drawRoundRect(RectF(p.strokeWidth, p.strokeWidth, w - p.strokeWidth, h - p.strokeWidth), r, r, p)
        return b
    }

    private fun rounded(src: Bitmap, size: Int, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, p)
        return out
    }

    /** Visualizer styling: a still row of bars in the song's accent colour (widgets can't animate). */
    private fun bars(seed: Int, color: Int): Bitmap {
        val n = 28; val w = 280; val h = 24
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val rnd = java.util.Random(seed.toLong())
        val bw = w / n.toFloat()
        for (i in 0 until n) { val bh = h * (0.25f + 0.75f * rnd.nextFloat()); c.drawRoundRect(RectF(i * bw + 2, h - bh, (i + 1) * bw - 2, h.toFloat()), 3f, 3f, p) }
        return b
    }

    // ------------------------------------------------------------------ actions

    internal fun media(ctx: Context, cmd: String) {
        val tc = NotchBus.media.value?.controller?.transportControls
        if (tc != null) {
            when (cmd) { "next" -> tc.skipToNext(); "prev" -> tc.skipToPrevious(); else -> if (NotchBus.media.value?.playing == true) tc.pause() else tc.play() }
            return
        }
        // Nebula's process was asleep: send a media key to whatever's playing.
        val code = when (cmd) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}

class WidgetReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Store.init(ctx.applicationContext)
        when (i.action) {
            "${Widgets.ACTION}.MEDIA" -> Widgets.media(ctx, i.getStringExtra("x").orEmpty())
            "${Widgets.ACTION}.DONE" -> { i.getStringExtra("x")?.let { Reminders.complete(ctx, it) }; Widgets.refresh(ctx, "tasks", "daily") }
            "${Widgets.ACTION}.TICK" -> Widgets.tick(ctx)
        }
    }
}

abstract class NebulaWidget(private val kind: String) : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { Store.init(ctx.applicationContext); Widgets.refresh(ctx, kind) }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) { Store.init(ctx.applicationContext); Widgets.refresh(ctx, kind) }
    override fun onEnabled(ctx: Context) { if (kind == "sports" || kind == "daily") Widgets.schedule(ctx) }
    override fun onDisabled(ctx: Context) { if (kind == "sports" || kind == "daily") Widgets.schedule(ctx) }
}
class VoiceWidget : NebulaWidget("voice")
class MusicWidget : NebulaWidget("music")
class AskWidget : NebulaWidget("ask")
class TasksWidget : NebulaWidget("tasks")
class SportsWidget : NebulaWidget("sports")
class DailyWidget : NebulaWidget("daily")
