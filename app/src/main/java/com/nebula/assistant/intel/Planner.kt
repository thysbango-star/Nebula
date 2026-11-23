package com.nebula.assistant.intel

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nebula.assistant.R
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.NebulaTask
import com.nebula.assistant.data.Store
import com.nebula.assistant.data.TaskStatus
import com.nebula.assistant.ui.MainActivity
import org.json.JSONObject
import java.util.Calendar

/**
 * Nebula's push notifications: event reminders, daily briefings and reminders (tasks with a due time).
 *
 * How it stays honest and quiet:
 *  - Everything is planned from real data each time (app start, boot, time/zone change, calendar edits, every few
 *    hours, after each alert), so moved or deleted events lose their alert and new ones get one.
 *  - Each alert has a key (event id + start + lead). Keys already shown are remembered: never sent twice, even
 *    after a restart or reboot. An alarm re-checks the calendar when it fires, so an edit since planning wins.
 *  - Alarms are "allow while idle" (no exact-alarm permission). Android may deliver them a few minutes late in deep
 *    sleep; the settings say so instead of promising exact timing.
 *  - Notifications go through their own channels, so Do Not Disturb and per-category switches work as usual.
 */
object Planner {
    const val CH_EVENTS = "intel_events"; const val CH_BRIEF = "intel_brief"; const val CH_REMIND = "intel_reminders"
    private const val ACTION_FIRE = "com.nebula.assistant.intel.FIRE"
    private const val ACTION_REPLAN = "com.nebula.assistant.intel.REPLAN"
    const val ACTION_SNOOZE = "com.nebula.assistant.intel.SNOOZE"
    const val ACTION_DONE = "com.nebula.assistant.intel.DONE"
    const val ACTION_DISMISS = "com.nebula.assistant.intel.DISMISS"

    private var observer: ContentObserver? = null

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        channels(app)
        replan(app)
        // Calendar edits while Nebula is running: re-plan (debounced).
        if (observer == null && CalendarIntel.granted(app)) runCatching {
            val h = Handler(Looper.getMainLooper()); val r = Runnable { replan(app) }
            observer = object : ContentObserver(h) { override fun onChange(self: Boolean) { h.removeCallbacks(r); h.postDelayed(r, 4000) } }
            app.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer!!)
        }
        // A light safety net: re-plan every few hours even if nothing told us to.
        val am = app.getSystemService(AlarmManager::class.java)
        am?.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR, 3 * AlarmManager.INTERVAL_HOUR,
            PendingIntent.getBroadcast(app, 9100, Intent(app, IntelReceiver::class.java).setAction(ACTION_REPLAN), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    private fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Calendar events", NotificationManager.IMPORTANCE_HIGH).apply { description = "Before your events start" })
        nm.createNotificationChannel(NotificationChannel(CH_BRIEF, "Daily briefing", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Your day at a glance" })
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { description = "Reminders you asked Nebula for" })
    }

    private data class Plan(val key: String, val at: Long, val kind: String, val eventId: Long = 0, val start: Long = 0, val taskId: String = "")

    /** Work out every alert for the next ~26 hours from real data, and (re)schedule exactly those. */
    @Synchronized fun replan(ctx: Context) {
        val prefs = NebulaPrefs(ctx); val now = System.currentTimeMillis()
        val want = ArrayList<Plan>()
        if (CalendarIntel.connected(ctx) && prefs.calNotify) {
            for (e in CalendarIntel.events(ctx, now, now + 26 * 3_600_000L)) {
                if (e.allDay) continue
                if (prefs.calUseOwn && e.hasAlarm) continue // the calendar already reminds you: no double alerts
                val at = e.start - prefs.calLead * 60_000L
                if (at > now - 60_000) want += Plan("ev:${e.id}:${e.start}:${prefs.calLead}", maxOf(at, now + 2000), "event", e.id, e.start)
            }
            if (prefs.calFreeAlerts) {
                // "You have 30 minutes free before your next commitment" — once per gap, when a gap of 30–90 min opens.
                val today = CalendarIntel.events(ctx, now, CalendarIntel.dayStart(now, 1)).filter { !it.allDay }
                for (i in 0 until today.size - 1) {
                    val gap = today[i + 1].start - today[i].end
                    if (gap in 30 * 60_000L..90 * 60_000L && today[i].end > now) want += Plan("free:${today[i].id}:${today[i].end}", today[i].end, "free", today[i + 1].id, today[i + 1].start)
                }
            }
        }
        if (CalendarIntel.connected(ctx)) {
            fun daily(kind: String, minutes: Int, on: Boolean) {
                if (!on) return
                var t = CalendarIntel.dayStart(now) + minutes * 60_000L
                if (t <= now) t = CalendarIntel.dayStart(now, 1) + minutes * 60_000L
                want += Plan("$kind:${CalendarIntel.dayStart(t)}", t, kind)
            }
            daily("brief", prefs.briefTime, prefs.briefMorning)
            daily("afternoon", 13 * 60, prefs.briefAfternoon)
            daily("tomorrow", 20 * 60, prefs.briefTomorrow)
        }
        if (prefs.notifyReminders) for (t in Store.tasks.value) if (t.isReminder && t.isActive && t.dueAt > 0 && t.dueAt < now + 26 * 3_600_000L)
            want += Plan("rem:${t.id}:${t.dueAt}", maxOf(t.dueAt, now + 2000), "reminder", taskId = t.id)

        val sent = sent(prefs)
        val desired = want.filter { it.key !in sent }.associateBy { it.key }
        val old = runCatching { JSONObject(prefs.intelScheduled) }.getOrDefault(JSONObject())
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        // Cancel alerts for events that moved or were deleted (and anything switched off).
        old.keys().forEach { k -> if (k !in desired) am.cancel(pi(ctx, k, null)) }
        val next = JSONObject()
        for (p in desired.values) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi(ctx, p.key, p)) }
            next.put(p.key, p.at)
        }
        prefs.intelScheduled = next.toString()
        com.nebula.assistant.widgets.Widgets.refresh(ctx, "daily", "tasks")
    }

    private fun pi(ctx: Context, key: String, p: Plan?): PendingIntent {
        val i = Intent(ctx, IntelReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("nebula://intel/" + Uri.encode(key)))
        if (p != null) i.putExtra("key", p.key).putExtra("kind", p.kind).putExtra("event", p.eventId).putExtra("start", p.start).putExtra("task", p.taskId)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun sent(prefs: NebulaPrefs): Set<String> {
        val o = runCatching { JSONObject(prefs.intelSent) }.getOrDefault(JSONObject()); val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
        return o.keys().asSequence().filter { o.optLong(it) > cutoff }.toSet()
    }
    private fun markSent(prefs: NebulaPrefs, key: String) {
        val o = runCatching { JSONObject(prefs.intelSent) }.getOrDefault(JSONObject()); val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
        val keep = JSONObject(); o.keys().forEach { if (o.optLong(it) > cutoff) keep.put(it, o.optLong(it)) }
        keep.put(key, System.currentTimeMillis()); prefs.intelSent = keep.toString()
    }
    fun clearHistory(ctx: Context) { NebulaPrefs(ctx).intelSent = "{}"; replan(ctx) }

    // ------------------------------------------------------------------ firing

    internal fun fire(ctx: Context, i: Intent) {
        val prefs = NebulaPrefs(ctx); val key = i.getStringExtra("key") ?: return
        if (key in sent(prefs)) return
        val canPost = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val now = System.currentTimeMillis()
        when (i.getStringExtra("kind")) {
            "event" -> {
                // Check the calendar again: the event may have moved or gone since we planned.
                val e = CalendarIntel.event(ctx, i.getLongExtra("event", 0), i.getLongExtra("start", 0))
                if (e == null || e.start != i.getLongExtra("start", 0) || e.start < now - 5 * 60_000L) { replan(ctx); return }
                val full = prefs.calDetail == "full"
                val mins = ((e.start - now + 30_000) / 60_000).coerceAtLeast(0)
                val title = if (mins <= 0) (if (full) "${e.title} is starting" else "Your event is starting") else if (full) "${e.title} in $mins min" else "Your next event starts in $mins minutes"
                val body = "${CalendarIntel.time(e.start)}–${CalendarIntel.time(e.end)}" + if (full && e.location.isNotBlank()) " · ${e.location}" else ""
                if (canPost) post(ctx, CH_EVENTS, key, title, body, NotificationCompat.CATEGORY_EVENT,
                    open = calendarEvent(ctx, e), actions = listOf(
                        "View day" to calendarDay(ctx, e.start),
                        "Snooze 5 min" to action(ctx, ACTION_SNOOZE, key, i, 5),
                        "Ask Nebula" to ask(ctx, key, "Tell me about my next event and how much time I have before it.")))
            }
            "free" -> {
                val nextE = CalendarIntel.event(ctx, i.getLongExtra("event", 0), i.getLongExtra("start", 0)) ?: run { replan(ctx); return }
                val mins = (nextE.start - now) / 60_000
                if (mins >= 20 && canPost) post(ctx, CH_EVENTS, key, "You have $mins minutes free", "Before ${if (prefs.calDetail == "full") nextE.title else "your next event"} at ${CalendarIntel.time(nextE.start)}",
                    NotificationCompat.CATEGORY_EVENT, open = calendarDay(ctx, nextE.start), actions = listOf("Ask Nebula" to ask(ctx, key, "I have $mins minutes free. What should I do?")))
            }
            "brief", "afternoon", "tomorrow" -> {
                val tomorrow = i.getStringExtra("kind") == "tomorrow"
                val text = CalendarIntel.briefing(ctx, tomorrow) ?: return
                val taskLine = Store.tasks.value.count { it.isReminder && it.isActive && it.dueAt in CalendarIntel.dayStart(now, if (tomorrow) 1 else 0) until CalendarIntel.dayStart(now, if (tomorrow) 2 else 1) }
                    .takeIf { it > 0 }?.let { " $it reminder${if (it > 1) "s" else ""} due${if (tomorrow) " tomorrow" else " today"}." } ?: ""
                if (canPost) post(ctx, CH_BRIEF, key, if (tomorrow) "Tomorrow's preview" else "Your day", text + taskLine, NotificationCompat.CATEGORY_REMINDER,
                    open = calendarDay(ctx, CalendarIntel.dayStart(now, if (tomorrow) 1 else 0) + 8 * 3_600_000L), big = true,
                    actions = listOf("Ask Nebula" to ask(ctx, key, if (tomorrow) "What do I have tomorrow?" else "What's my day looking like?")))
            }
            "reminder" -> {
                val t = Store.task(i.getStringExtra("task")) ?: return
                if (!t.isActive) return
                if (canPost) post(ctx, CH_REMIND, key, t.title, "Reminder · ${CalendarIntel.time(t.dueAt)}", NotificationCompat.CATEGORY_REMINDER,
                    open = openTask(ctx, t), actions = listOf("Done" to action(ctx, ACTION_DONE, key, i, 0), "Snooze 10 min" to action(ctx, ACTION_SNOOZE, key, i, 10)))
            }
        }
        markSent(prefs, key)
        replan(ctx)
    }

    internal fun snooze(ctx: Context, i: Intent) {
        val key = i.getStringExtra("key") ?: return
        nm(ctx).cancel(key.hashCode())
        val minutes = i.getIntExtra("minutes", 5)
        if (i.getStringExtra("kind") == "reminder") {
            Store.updateTask(i.getStringExtra("task") ?: return) { it.copy(dueAt = System.currentTimeMillis() + minutes * 60_000L, updatedAt = System.currentTimeMillis()) }
            replan(ctx); return
        }
        // An event snooze is its own alert key, so it is shown once too.
        val k2 = "$key:snz${System.currentTimeMillis() / 60_000}"
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = Plan(k2, System.currentTimeMillis() + minutes * 60_000L, i.getStringExtra("kind") ?: "event", i.getLongExtra("event", 0), i.getLongExtra("start", 0), i.getStringExtra("task").orEmpty())
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi(ctx, p.key, p)) }
    }

    internal fun done(ctx: Context, i: Intent) {
        i.getStringExtra("key")?.let { nm(ctx).cancel(it.hashCode()) }
        Reminders.complete(ctx, i.getStringExtra("task") ?: return)
    }

    // ------------------------------------------------------------------ notification building

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun post(ctx: Context, ch: String, key: String, title: String, body: String, category: String, open: PendingIntent,
                     actions: List<Pair<String, PendingIntent>>, big: Boolean = false) {
        val b = NotificationCompat.Builder(ctx, ch).setSmallIcon(R.drawable.ic_stat_nebula).setColor(0xFF4D8DFF.toInt())
            .setContentTitle(title).setContentText(body).setCategory(category).setAutoCancel(true).setContentIntent(open)
            .setPriority(if (ch == CH_BRIEF) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setSubText("Nebula").setOnlyAlertOnce(true)
            .setDeleteIntent(PendingIntent.getBroadcast(ctx, key.hashCode(), Intent(ctx, IntelReceiver::class.java).setAction(ACTION_DISMISS), PendingIntent.FLAG_IMMUTABLE))
        if (big || body.length > 40) b.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        actions.take(3).forEach { (l, p) -> b.addAction(0, l, p) }
        runCatching { nm(ctx).notify(key.hashCode(), b.build()) }
    }

    private fun action(ctx: Context, act: String, key: String, src: Intent, minutes: Int): PendingIntent {
        val i = Intent(ctx, IntelReceiver::class.java).setAction(act).setData(Uri.parse("nebula://intel/$act/" + Uri.encode(key)))
            .putExtras(src).putExtra("minutes", minutes)
        return PendingIntent.getBroadcast(ctx, (act + key).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun activity(ctx: Context, code: Int, i: Intent) = PendingIntent.getActivity(ctx, code, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun calendarEvent(ctx: Context, e: CalendarIntel.Event) = activity(ctx, ("e" + e.id).hashCode(),
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end))
    fun calendarDay(ctx: Context, t: Long) = activity(ctx, ("d" + t / 86_400_000L).hashCode(),
        Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, t) }.build()))
    fun ask(ctx: Context, key: String, prompt: String) = activity(ctx, ("a" + key).hashCode(),
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PROMPT, prompt).setData(Uri.parse("nebula://ask/" + Uri.encode(key))))
    private fun openTask(ctx: Context, t: NebulaTask) = activity(ctx, ("t" + t.id).hashCode(),
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "tasks").putExtra(MainActivity.EXTRA_TASK, t.id).setData(Uri.parse("nebula://task/" + t.id)))
}

/** Alarms, notification buttons, boot and clock changes. */
class IntelReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val pending = goAsync()
        Thread {
            try {
                com.nebula.assistant.data.Store.init(ctx.applicationContext)
                when (i.action) {
                    "com.nebula.assistant.intel.FIRE" -> Planner.fire(ctx, i)
                    Planner.ACTION_SNOOZE -> Planner.snooze(ctx, i)
                    Planner.ACTION_DONE -> Planner.done(ctx, i)
                    Planner.ACTION_DISMISS -> {}
                    else -> Planner.replan(ctx) // boot, app update, time or time zone change, periodic re-plan
                }
            } finally { pending.finish() }
        }.start()
    }
}

/** Reminders are ordinary Nebula tasks (kind "reminder") with a due time — the same task list the app and widget show. */
object Reminders {
    fun create(ctx: Context, text: String, dueAt: Long): NebulaTask {
        val conv = com.nebula.assistant.brain.AgentBrain.focusConversationId ?: Store.conversations.value.firstOrNull()?.id ?: Store.newConversation().id
        val t = NebulaTask(title = text.trim().replaceFirstChar { it.uppercase() }.take(120), conversationId = conv, kind = "reminder", dueAt = dueAt,
            summary = "Reminder")
        Store.upsertTask(t)
        Planner.replan(ctx)
        return t
    }
    fun complete(ctx: Context, id: String) {
        Store.updateTask(id) { it.copy(status = TaskStatus.COMPLETED, summary = "Done", updatedAt = System.currentTimeMillis()) }
        Planner.replan(ctx)
    }

    /** The AI's remind tool: an exact time, minutes from now, or minutes before the next calendar event. */
    fun tool(ctx: Context, a: JSONObject): String {
        val now = System.currentTimeMillis()
        val text = a.optString("text").ifBlank { return JSONObject().put("ok", false).put("result", "What should the reminder say?").toString() }
        val due: Long = when {
            a.has("minutes_before_next_event") -> {
                val e = CalendarIntel.next(ctx) ?: return JSONObject().put("ok", false).put("result",
                    if (!CalendarIntel.connected(ctx)) "Calendar Intelligence isn't connected, so I can't see the next event. Ask for a time instead, or suggest connecting it in Settings › Rovian Intelligence." else "There's no upcoming event in the next week.").toString()
                e.start - a.optInt("minutes_before_next_event", 10) * 60_000L
            }
            a.has("minutes_from_now") -> now + a.optInt("minutes_from_now").coerceIn(1, 60 * 24 * 60) * 60_000L
            a.optString("at").isNotBlank() -> parse(a.optString("at")) ?: return JSONObject().put("ok", false).put("result", "Couldn't read that time. Use HH:mm or yyyy-MM-dd HH:mm.").toString()
            else -> return JSONObject().put("ok", false).put("result", "When? Ask the user for a time.").toString()
        }
        if (due < now - 60_000) return JSONObject().put("ok", false).put("result", "That time has already passed.").toString()
        val t = create(ctx, text, due)
        val canPost = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return JSONObject().put("ok", true).put("verified", true).put("task_id", t.id)
            .put("result", "Reminder saved for ${java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(due))}." +
                if (!canPost) " Notifications are off for Nebula, so tell the user to allow them or it won't alert." else " Android may deliver it a minute or two late if the phone is in deep sleep.").toString()
    }

    private fun parse(s: String): Long? {
        val t = s.trim()
        for (f in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss")) runCatching { return java.text.SimpleDateFormat(f, java.util.Locale.ENGLISH).parse(t)!!.time }
        Regex("^(\\d{1,2}):(\\d{2})$").find(t)?.let { m ->
            val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(Calendar.MINUTE, m.groupValues[2].toInt()); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            if (c.timeInMillis < System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
        return null
    }
}
