package com.nebula.assistant.sports

import com.nebula.assistant.data.NebulaPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Live scores for the teams you follow (Settings → Sports), from ESPN's public scoreboard feed.
 * While one of your teams is playing, the Notch shows the score around the camera; when they score,
 * the Notch plays the goal animation.
 */
object Sports {
    enum class Kind { SOCCER, FOOTBALL, BASKETBALL, HOCKEY, BASEBALL }
    data class League(val path: String, val name: String, val kind: Kind)

    val LEAGUES = listOf(
        League("soccer/eng.1", "Premier League", Kind.SOCCER),
        League("soccer/rsa.1", "SA Premiership", Kind.SOCCER),
        League("soccer/uefa.champions", "Champions League", Kind.SOCCER),
        League("soccer/esp.1", "LaLiga", Kind.SOCCER),
        League("soccer/ita.1", "Serie A", Kind.SOCCER),
        League("soccer/ger.1", "Bundesliga", Kind.SOCCER),
        League("soccer/fra.1", "Ligue 1", Kind.SOCCER),
        League("soccer/usa.1", "MLS", Kind.SOCCER),
        League("basketball/nba", "NBA", Kind.BASKETBALL),
        League("football/nfl", "NFL", Kind.FOOTBALL),
        League("hockey/nhl", "NHL", Kind.HOCKEY),
        League("baseball/mlb", "MLB", Kind.BASEBALL),
    )
    fun league(path: String) = LEAGUES.firstOrNull { it.path == path }

    /** A team you follow. Stored as "league|id|abbr|name|color". */
    data class Team(val league: String, val id: String, val abbr: String, val name: String, val color: Long) {
        fun encode() = listOf(league, id, abbr, name.replace("|", " "), color.toString()).joinToString("|")
        companion object {
            fun decode(s: String): Team? = s.split('|').takeIf { it.size >= 5 }?.let { Team(it[0], it[1], it[2], it[3], it[4].toLongOrNull() ?: 0xFF555555) }
        }
    }

    data class Side(val id: String, val abbr: String, val name: String, val score: Int, val color: Long, val record: String = "")
    data class Game(
        val id: String, val league: League, val home: Side, val away: Side,
        val state: String,          // "pre", "in" or "post"
        val clock: String,          // "67'", "HT", "3rd 14:50", "Final", or the kick-off time
        val lastPlay: String,
        val start: Long,
    ) {
        val live get() = state == "in"
    }
    /** Someone just scored. */
    data class Score(val game: Game, val home: Boolean, val before: Int, val label: String, val who: String, val at: Long = System.currentTimeMillis())

    private val _games = MutableStateFlow<List<Game>>(emptyList())
    /** Your teams' games worth showing: live, about to start, or just finished. Most interesting first. */
    val games: StateFlow<List<Game>> = _games
    private val _goal = MutableStateFlow<Score?>(null)
    val goal: StateFlow<Score?> = _goal
    fun clearGoal() { _goal.value = null }

    private val last = HashMap<String, Game>()
    private val finishedAt = HashMap<String, Long>()
    @Volatile private var demoUntil = 0L

    /** Runs for as long as the Notch is up. */
    suspend fun run(prefs: NebulaPrefs) {
        while (true) {
            val wait = runCatching { withContext(Dispatchers.Default) { poll(prefs) } }.getOrElse { 60_000L }
            delay(wait)
        }
    }

    private suspend fun poll(prefs: NebulaPrefs): Long {
        if (System.currentTimeMillis() < demoUntil) return 2_000
        val teams = prefs.sportsTeams.mapNotNull(Team::decode)
        if (!prefs.sportsOn || teams.isEmpty()) { _games.value = emptyList(); return 60_000 }
        val now = System.currentTimeMillis()
        val found = teams.map { it.league }.distinct().flatMap { path ->
            val lg = league(path) ?: return@flatMap emptyList()
            runCatching { scoreboard(lg) }.getOrDefault(emptyList())
        }.filter { g -> teams.any { t -> t.league == g.league.path && (t.id == g.home.id || t.id == g.away.id) } }

        for (g in found) {
            val prev = last[g.id]
            if (prev != null && g.live) {
                if (g.home.score > prev.home.score) _goal.value = score(g, true, prev.home.score)
                else if (g.away.score > prev.away.score) _goal.value = score(g, false, prev.away.score)
            }
            if (prev?.state == "in" && g.state == "post") finishedAt[g.id] = now
            last[g.id] = g
        }
        _games.value = found.filter { g ->
            g.live || (g.state == "post" && now - (finishedAt[g.id] ?: 0L) < 15 * 60_000L) ||
                (g.state == "pre" && g.start - now in 0..15 * 60_000L)
        }.sortedBy { if (it.live) 0 else if (it.state == "pre") 1 else 2 }

        return when {
            found.any { it.live } -> 20_000
            found.any { it.state == "pre" && it.start - now < 30 * 60_000L } -> 60_000
            else -> 10 * 60_000
        }
    }

    private fun score(g: Game, home: Boolean, before: Int): Score {
        val side = if (home) g.home else g.away
        val delta = side.score - before
        val label = when (g.league.kind) {
            Kind.SOCCER, Kind.HOCKEY -> "GOAL"
            Kind.FOOTBALL -> when { delta >= 6 -> "TOUCHDOWN"; delta == 3 -> "FIELD GOAL"; delta == 2 -> "SAFETY"; else -> "SCORE" }
            Kind.BASEBALL -> if (g.lastPlay.contains("homer", true) || g.lastPlay.contains("home run", true)) "HOME RUN" else "RUN"
            Kind.BASKETBALL -> "" // baskets come every few seconds: just roll the score, no big moment
        }
        return Score(g, home, before, label, g.lastPlay)
    }

    // ------------------------------------------------------------------ feed

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/"

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000; setRequestProperty("Accept", "application/json") }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    suspend fun scoreboard(lg: League): List<Game> {
        val events = get(BASE + lg.path + "/scoreboard").optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i -> runCatching { parse(lg, events.getJSONObject(i)) }.getOrNull() }
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun parse(lg: League, e: JSONObject): Game? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val cs = comp.optJSONArray("competitors") ?: return null
        var home: Side? = null; var away: Side? = null
        var homeAlt = 0L; var awayAlt = 0L
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i); val t = c.optJSONObject("team") ?: continue
            val color = hex(t.optString("color")); val alt = hex(t.optString("alternateColor"))
            val rec = c.optJSONArray("records")?.optJSONObject(0)?.optString("summary").orEmpty()
            val s = Side(t.optString("id"), t.optString("abbreviation").ifBlank { t.optString("shortDisplayName").take(3).uppercase() },
                t.optString("shortDisplayName").ifBlank { t.optString("displayName") }, c.optString("score").toIntOrNull() ?: 0,
                readable(color, alt, t.optString("abbreviation")), rec)
            if (c.optString("homeAway") == "home") { home = s; homeAlt = alt } else { away = s; awayAlt = alt }
        }
        var h = home ?: return null; var a = away ?: return null
        // Two similar colours (e.g. two reds) would blur together on the card: switch the away team to its alternate.
        if (close(h.color, a.color) && awayAlt != 0L && !close(h.color, awayAlt)) a = a.copy(color = readable(awayAlt, 0, a.abbr))
        else if (close(h.color, a.color) && homeAlt != 0L) h = h.copy(color = readable(homeAlt, 0, h.abbr))

        val st = (comp.optJSONObject("status") ?: e.optJSONObject("status"))
        val type = st?.optJSONObject("type")
        val state = type?.optString("state").orEmpty().ifBlank { "pre" }
        val start = runCatching { iso.parse(e.optString("date").take(16))!!.time }.getOrDefault(0L)
        val clock = when (state) {
            "pre" -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start))
            "post" -> type?.optString("shortDetail").orEmpty().let { if (lg.kind == Kind.SOCCER && it.startsWith("FT")) "Full time" else it.ifBlank { "Final" } }
            else -> type?.optString("shortDetail").orEmpty().ifBlank { st?.optString("displayClock").orEmpty() }
        }
        return Game(e.optString("id"), lg, h, a, state, clock, lastPlay(lg, comp, h, a), start)
    }

    /** "T. Mokoena 67'" for football (soccer); the feed's own last-play line for other sports. */
    private fun lastPlay(lg: League, comp: JSONObject, h: Side, a: Side): String {
        comp.optJSONObject("situation")?.optJSONObject("lastPlay")?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
        val d = comp.optJSONArray("details") ?: return ""
        for (i in d.length() - 1 downTo 0) {
            val x = d.optJSONObject(i) ?: continue
            if (!x.optBoolean("scoringPlay")) continue
            val who = x.optJSONArray("athletesInvolved")?.optJSONObject(0)?.optString("displayName").orEmpty()
            val min = x.optJSONObject("clock")?.optString("displayValue").orEmpty()
            val kind = x.optJSONObject("type")?.optString("text").orEmpty()
            val team = x.optJSONObject("team")?.optString("id").let { id -> if (id == h.id) h.name else if (id == a.id) a.name else "" }
            val extra = when { kind.contains("Own", true) -> " (own goal)"; kind.contains("Penalty", true) -> " (pen)"; else -> "" }
            return listOf(who.ifBlank { team }, min).filter { it.isNotBlank() }.joinToString(" ") + extra
        }
        return ""
    }

    /** Teams in a league, for the picker in Settings. */
    suspend fun teams(lg: League): List<Team> {
        val arr = get(BASE + lg.path + "/teams").optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")
            ?.optJSONObject(0)?.optJSONArray("teams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i)?.optJSONObject("team") ?: return@mapNotNull null
            Team(lg.path, t.optString("id"), t.optString("abbreviation"), t.optString("displayName"),
                readable(hex(t.optString("color")), hex(t.optString("alternateColor")), t.optString("abbreviation")))
        }.sortedBy { it.name }
    }

    // ------------------------------------------------------------------ colours

    private fun hex(s: String?): Long = s?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0L
    private fun lum(c: Long): Float { val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f; return 0.2126f * r + 0.7152f * g + 0.0722f * b }
    private fun close(a: Long, b: Long): Boolean {
        val dr = (a shr 16 and 255) - (b shr 16 and 255); val dg = (a shr 8 and 255) - (b shr 8 and 255); val db = (a and 255) - (b and 255)
        return dr * dr + dg * dg + db * db < 70 * 70
    }
    /** The team colour, unless it's too dark or too pale to show on black — then its alternate, then a stable fallback. */
    private fun readable(main: Long, alt: Long, key: String): Long {
        for (c in listOf(main, alt)) if (c != 0L && lum(c) in 0.06f..0.85f) return c
        val pal = longArrayOf(0xFF2F6BFF, 0xFFE0383E, 0xFF13A35B, 0xFFF2A31B, 0xFF8A4DFF, 0xFF00A6B4, 0xFFE8531E)
        return pal[(key.hashCode() and 0x7fffffff) % pal.size]
    }

    // ------------------------------------------------------------------ demo

    private val demoScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    /** Runs the preview on its own, so it keeps going after you leave Settings. */
    fun startDemo() { demoScope.launch { demo() } }

    /** Settings → Sports → "Preview": a pretend match, then a goal, so you can see it without waiting for kick-off. */
    suspend fun demo() {
        val lg = League("demo", "Premier League", Kind.SOCCER)
        val g = Game("demo", lg, Side("1", "JHB", "Lions", 1, 0xFFF2B01E), Side("2", "CPT", "Comets", 0, 0xFF2F6BFF),
            "in", "64'", "T. Mokoena 38'", 0)
        demoUntil = System.currentTimeMillis() + 22_000
        _games.value = listOf(g)
        delay(2600)
        val g2 = g.copy(away = g.away.copy(score = 1), clock = "67'", lastPlay = "S. Dlamini 67'")
        _games.value = listOf(g2)
        _goal.value = Score(g2, home = false, before = 0, label = "GOAL", who = g2.lastPlay)
        delay(19_000)
        _games.value = emptyList(); demoUntil = 0
    }
}
