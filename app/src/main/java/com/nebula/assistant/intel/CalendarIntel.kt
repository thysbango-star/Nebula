package com.nebula.assistant.intel

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.nebula.assistant.data.NebulaPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar Intelligence: reads the user's real Android calendar (only after they connect it, only the calendars they
 * chose). Never invents events. Everything here is a read; Nebula doesn't change the calendar.
 */
object CalendarIntel {
    data class Cal(val id: Long, val name: String, val account: String, val color: Int)
    data class Event(val id: Long, val calId: Long, val title: String, val start: Long, val end: Long, val allDay: Boolean,
                     val location: String, val hasAlarm: Boolean)

    fun granted(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun connected(ctx: Context) = NebulaPrefs(ctx).calOn && granted(ctx)

    fun calendars(ctx: Context): List<Cal> {
        if (!granted(ctx)) return emptyList()
        val out = ArrayList<Cal>()
        runCatching {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.VISIBLE), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getInt(4) != 0) out += Cal(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3))
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Real events overlapping [from, to), from the calendars the user allowed. Cancelled and declined events are left out. */
    fun events(ctx: Context, from: Long, to: Long): List<Event> {
        if (!connected(ctx)) return emptyList()
        val allowed = NebulaPrefs(ctx).calIds.mapNotNull { it.toLongOrNull() }.toSet()
        val visible = calendars(ctx).map { it.id }.toSet()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val out = ArrayList<Event>()
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.HAS_ALARM, CalendarContract.Instances.STATUS,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val cal = c.getLong(1)
                    if (cal !in visible || (allowed.isNotEmpty() && cal !in allowed)) continue
                    if (!c.isNull(8) && c.getInt(8) == CalendarContract.Instances.STATUS_CANCELED) continue
                    if (!c.isNull(9) && c.getInt(9) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val allDay = c.getInt(5) != 0
                    // All-day events are stored in UTC days: move them onto the local day.
                    val begin = if (allDay) utcDayToLocal(c.getLong(3)) else c.getLong(3)
                    val end = if (allDay) utcDayToLocal(c.getLong(4)) else c.getLong(4)
                    out += Event(c.getLong(0), cal, c.getString(2).orEmpty().ifBlank { "(No title)" }, begin, end, allDay, c.getString(6).orEmpty(), c.getInt(7) != 0)
                }
            }
        }
        return out.filter { it.end > from && it.start < to }.sortedBy { it.start }
    }

    private fun utcDayToLocal(utc: Long): Long {
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
        return Calendar.getInstance().apply { clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH)) }.timeInMillis
    }

    fun next(ctx: Context, now: Long = System.currentTimeMillis()): Event? =
        events(ctx, now, now + 7 * 86_400_000L).firstOrNull { !it.allDay && it.start >= now - 60_000 }

    fun event(ctx: Context, id: Long, around: Long): Event? = events(ctx, around - 86_400_000L, around + 86_400_000L).firstOrNull { it.id == id && it.start == around }
        ?: events(ctx, around - 86_400_000L, around + 3 * 86_400_000L).firstOrNull { it.id == id }

    fun dayStart(t: Long, plusDays: Int = 0): Long = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, plusDays) }.timeInMillis

    /** Free windows of at least [minMinutes] between [from] and [to], around the timed events. */
    fun free(events: List<Event>, from: Long, to: Long, minMinutes: Int = 30): List<Pair<Long, Long>> {
        val busy = events.filter { !it.allDay && it.end > from && it.start < to }.sortedBy { it.start }
        val out = ArrayList<Pair<Long, Long>>(); var cursor = from
        for (e in busy) { if (e.start - cursor >= minMinutes * 60_000L) out += cursor to e.start; cursor = maxOf(cursor, e.end) }
        if (to - cursor >= minMinutes * 60_000L) out += cursor to to
        return out
    }

    private val hm get() = SimpleDateFormat("HH:mm", Locale.getDefault())
    fun time(t: Long): String = hm.format(Date(t))

    /** What the AI may see of an event — titles and places only when the user allows it. */
    fun json(ctx: Context, e: Event): JSONObject {
        val full = NebulaPrefs(ctx).calDetail == "full"
        val day = SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(Date(e.start))
        return JSONObject().put("title", if (full) e.title else "Busy").put("day", day).apply {
            if (e.allDay) put("all_day", true) else put("start", time(e.start)).put("end", time(e.end)).put("minutes", (e.end - e.start) / 60_000)
            if (full && e.location.isNotBlank()) put("location", e.location)
            put("id", e.id).put("start_ms", e.start)
        }
    }

    /** The calendar tool for the AI: real events, free time and conflicts for a range of days. */
    fun tool(ctx: Context, a: JSONObject): String {
        if (!granted(ctx) || !NebulaPrefs(ctx).calOn) return JSONObject().put("ok", false)
            .put("result", "Calendar Intelligence isn't connected. Tell the user they can connect it in Nebula › Settings › Rovian Intelligence. Do not guess their schedule.").toString()
        val now = System.currentTimeMillis()
        val startDay = a.optInt("from_day", 0).coerceIn(-7, 60)
        val days = a.optInt("days", 1).coerceIn(1, 14)
        val from = if (startDay == 0) dayStart(now) else dayStart(now, startDay)
        val to = dayStart(from, days)
        val list = events(ctx, from, to)
        val arr = JSONArray(list.take(60).map { json(ctx, it) })
        val dayEnd = a.optInt("day_end_hour", 22).coerceIn(12, 24)
        val freeArr = JSONArray()
        for (d in 0 until days) {
            val ds = dayStart(from, d); val s = maxOf(ds + 7 * 3_600_000L, if (d == 0 && startDay == 0) now else 0L); val e = ds + dayEnd * 3_600_000L
            if (e > s) free(list, s, e, 30).forEach { (f, t) -> freeArr.put(JSONObject().put("day", SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(Date(ds))).put("from", time(f)).put("to", time(t))) }
        }
        val timed = list.filter { !it.allDay }
        val conflicts = JSONArray(); for (i in timed.indices) for (j in i + 1 until timed.size) if (timed[j].start < timed[i].end) conflicts.put("${json(ctx, timed[i]).optString("title")} overlaps ${json(ctx, timed[j]).optString("title")}")
        val nx = list.firstOrNull { !it.allDay && it.start > now }
        return JSONObject().put("ok", true).put("now", SimpleDateFormat("EEE d MMM HH:mm", Locale.ENGLISH).format(Date(now)))
            .put("events", arr).put("count", list.size).put("free_windows", freeArr).put("conflicts", conflicts)
            .put("next_event", nx?.let { json(ctx, it).put("starts_in_minutes", (it.start - now) / 60_000) } ?: JSONObject.NULL)
            .put("note", "These are the user's real calendar events. Only mention what is here; if it's empty, say they have nothing scheduled.").toString()
    }

    // ------------------------------------------------------------------ the daily briefing (spoken-style text)

    fun briefing(ctx: Context, tomorrow: Boolean, now: Long = System.currentTimeMillis()): String? {
        if (!connected(ctx)) return null
        val ds = dayStart(now, if (tomorrow) 1 else 0)
        val list = events(ctx, ds, dayStart(ds, 1))
        val full = NebulaPrefs(ctx).calDetail == "full"
        val timed = list.filter { !it.allDay && (tomorrow || it.end > now) }
        val allDay = list.filter { it.allDay }
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val hello = if (tomorrow) "Tomorrow" else if (hour < 12) "Morning." else if (hour < 18) "Afternoon." else "Evening."
        val n = timed.size
        if (n == 0 && allDay.isEmpty()) return if (tomorrow) "Tomorrow is clear — nothing on your calendar." else "$hello Your calendar is clear for the rest of today."
        val b = StringBuilder()
        b.append(if (tomorrow) "Tomorrow you have " else "$hello You have ")
        b.append(when (n) { 0 -> "no timed events"; 1 -> "one thing"; 2 -> "two things"; 3 -> "three things"; else -> "$n things" })
        b.append(if (tomorrow) "." else if (hour < 12) " today." else " left today.")
        if (allDay.isNotEmpty()) b.append(" All day: ").append(if (full) allDay.joinToString(", ") { it.title } else "${allDay.size} event${if (allDay.size > 1) "s" else ""}").append('.')
        if (n > 0) {
            val first = timed.first()
            b.append(if (n == 1) " It's " else " Your first ").append(if (full && n == 1) "${first.title} at ${time(first.start)}" else if (n == 1) "at ${time(first.start)}" else "starts at ${time(first.start)}${if (full) " (${first.title})" else ""}").append('.')
            val win = free(timed, maxOf(first.end, if (tomorrow) 0 else now), timed.last().start, 45).maxByOrNull { it.second - it.first }
            if (win != null) b.append(" You have a free window between ${time(win.first)} and ${time(win.second)}")
            if (n > 1) b.append(if (win != null) ", and your" else " Your").append(" last event finishes at ${time(timed.last().end)}.") else if (win != null) b.append('.')
        }
        return b.toString().replace("  ", " ")
    }
}
