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
 * Live scores for the teams you follow (Settings → Sports: sport → country → league → team), from ESPN's
 * public feeds. While one of your teams plays, the Notch shows the score around the camera; when something
 * big happens (a goal, a try, a six, a wicket…) the Notch plays the score moment.
 *
 * How points work (so the moment says the right thing):
 *  - Rugby union (World Rugby Law 8): try 5, penalty try 7, conversion 2, penalty goal 3, drop goal 3.
 *    After a try the scoring team may kick a conversion (place or drop kick, in line with where the try was
 *    grounded, within 60 s of playing time — sevens and 10s: drop kick within 30 s). A penalty try is worth 7
 *    straight away, with no conversion kick. So: +5 = TRY (then "conversion to come"), +2 soon after = CONVERTED,
 *    no +2 within the window (or the next score isn't the kick) = conversion missed, +7 at once = converted try.
 *  - Cricket: runs (a boundary is 4, over the rope on the full is 6) and wickets; a score reads runs/wickets.
 */
object Sports {
    enum class Kind { SOCCER, RUGBY, CRICKET, FOOTBALL, BASKETBALL, HOCKEY, BASEBALL }

    /** A competition you can follow. [feeds] are ESPN league paths; a group (e.g. all rugby Tests) has several. */
    data class League(val path: String, val name: String, val kind: Kind, val sport: String, val country: String, val feeds: List<String> = listOf(path))

    private const val RUGBY_TESTS = "rugby/tests"
    val LEAGUES = listOf(
        League("soccer/eng.1", "Premier League", Kind.SOCCER, "Football", "England"),
        League("soccer/rsa.1", "Betway Premiership", Kind.SOCCER, "Football", "South Africa"),
        League("soccer/esp.1", "LaLiga", Kind.SOCCER, "Football", "Spain"),
        League("soccer/ita.1", "Serie A", Kind.SOCCER, "Football", "Italy"),
        League("soccer/ger.1", "Bundesliga", Kind.SOCCER, "Football", "Germany"),
        League("soccer/fra.1", "Ligue 1", Kind.SOCCER, "Football", "France"),
        League("soccer/usa.1", "MLS", Kind.SOCCER, "Football", "USA"),
        League("soccer/uefa.champions", "Champions League", Kind.SOCCER, "Football", "Europe"),
        League("soccer/fifa.world", "World Cup", Kind.SOCCER, "Football", "International"),

        League(RUGBY_TESTS, "Test rugby", Kind.RUGBY, "Rugby", "International",
            listOf("rugby/289234", "rugby/17567", "rugby/244293", "rugby/180659", "rugby/164205")),
        League("rugby/270557", "United Rugby Championship", Kind.RUGBY, "Rugby", "South Africa"),
        League("rugby/267979", "Premiership Rugby", Kind.RUGBY, "Rugby", "England"),
        League("rugby/270559", "Top 14", Kind.RUGBY, "Rugby", "France"),
        League("rugby/242041", "Super Rugby Pacific", Kind.RUGBY, "Rugby", "New Zealand & Australia"),

        League("cricket/intl", "International cricket", Kind.CRICKET, "Cricket", "International", listOf(CRICKET_FEED)),
        League("cricket/sa20", "SA20", Kind.CRICKET, "Cricket", "South Africa", listOf(CRICKET_FEED)),
        League("cricket/ipl", "IPL", Kind.CRICKET, "Cricket", "India", listOf(CRICKET_FEED)),
        League("cricket/bbl", "Big Bash", Kind.CRICKET, "Cricket", "Australia", listOf(CRICKET_FEED)),

        League("basketball/nba", "NBA", Kind.BASKETBALL, "US sports", "USA"),
        League("football/nfl", "NFL", Kind.FOOTBALL, "US sports", "USA"),
        League("hockey/nhl", "NHL", Kind.HOCKEY, "US sports", "USA"),
        League("baseball/mlb", "MLB", Kind.BASEBALL, "US sports", "USA"),
    )
    val SPORTS get() = LEAGUES.map { it.sport }.distinct()
    fun league(path: String) = LEAGUES.firstOrNull { it.path == path }

    /** A team you follow. Stored as "league|id|abbr|name|color". An empty id means "match by name". */
    data class Team(val league: String, val id: String, val abbr: String, val name: String, val color: Long, val logo: String = "") {
        fun encode() = listOf(league, id, abbr, name.replace("|", " "), color.toString(), logo.replace("|", "")).joinToString("|")
        companion object {
            fun decode(s: String): Team? = s.split('|').takeIf { it.size >= 5 }?.let {
                Team(it[0], it[1], it[2], it[3], it[4].toLongOrNull() ?: 0xFF555555, it.getOrNull(5).orEmpty())
            }
        }
    }

    /** One side of a game. [text] is what the board shows ("2", "24", "161/5"); [sub] is extra (cricket overs). */
    data class Side(val id: String, val abbr: String, val name: String, val score: Int, val color: Long,
                    val text: String = score.toString(), val wickets: Int = 0, val sub: String = "", val logo: String = "")
    data class Game(
        val id: String, val league: League, val home: Side, val away: Side,
        val state: String,          // "pre", "in" or "post"
        val clock: String,          // "67'", "HT", "52'", "18.2 ov", "Full time", or the start time
        val lastPlay: String,
        val start: Long,
        val note: String = "",      // e.g. rugby: "Conversion to come" / "Conversion missed"
    ) {
        val live get() = state == "in"
    }
    /** A big moment. [before] is the scorer's old board text, so the number can roll up. [chant] plays first, if any. */
    data class Score(val game: Game, val home: Boolean, val before: String, val label: String, val who: String,
                     val chant: String = "", val at: Long = System.currentTimeMillis())

    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games: StateFlow<List<Game>> = _games
    private val _goal = MutableStateFlow<Score?>(null)
    val goal: StateFlow<Score?> = _goal
    fun clearGoal() { _goal.value = null }

    private val last = HashMap<String, Game>()
    /** Rugby: tries waiting for their conversion kick, by "gameId|home"/"gameId|away" → when we saw the try. */
    private val awaitingKick = HashMap<String, Long>()
    private val missedAt = HashMap<String, Long>()
    private const val KICK_WINDOW = 150_000L // 60 s of playing time + walking back + the feed catching up
    private val finishedAt = HashMap<String, Long>()
    @Volatile private var demoUntil = 0L

    suspend fun run(prefs: NebulaPrefs) {
        while (true) {
            val wait = runCatching { withContext(Dispatchers.Default) { poll(prefs) } }.getOrElse { 60_000L }
            delay(wait)
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
    private fun matches(t: Team, s: Side) = (t.id.isNotEmpty() && t.id == s.id) || norm(t.name) == norm(s.name) ||
        (t.id.isEmpty() && t.abbr.isNotEmpty() && t.abbr.equals(s.abbr, true) && t.league.startsWith("cricket/").not())

    private suspend fun poll(prefs: NebulaPrefs): Long {
        if (System.currentTimeMillis() < demoUntil) return 2_000
        val teams = prefs.sportsTeams.mapNotNull(Team::decode)
        if (!prefs.sportsOn || teams.isEmpty()) { _games.value = emptyList(); return 60_000 }
        val now = System.currentTimeMillis()
        val leagues = teams.mapNotNull { league(it.league) }.distinct()
        val cache = HashMap<String, List<Game>>()
        val found = leagues.flatMap { lg ->
            lg.feeds.flatMap { feed -> cache.getOrPut(feed) { runCatching { fetchFeed(feed, lg) }.getOrDefault(emptyList()) } }
                .filter { g -> teams.any { t -> t.league == lg.path && (matches(t, g.home) || matches(t, g.away)) } }
        }.distinctBy { it.id }

        // Games whose feed has no logo: use the national logos we know.
        val natAll = nations.orEmpty()
        fun fill(sd: Side) = if (sd.logo.isNotEmpty()) sd else (natAll[norm(sd.name)] ?: CRICKET_TEAMS.firstOrNull { norm(it.name) == norm(sd.name) && it.logo.isNotEmpty() }?.logo)
            ?.let { sd.copy(logo = it) } ?: sd
        val filled = found.map { g -> if (g.home.logo.isNotEmpty() && g.away.logo.isNotEmpty()) g else g.copy(home = fill(g.home), away = fill(g.away)) }
        // Teams picked from our own lists (national sides, SA20…) learn their ESPN logo the first time they play.
        val sides = found.flatMap { listOf(it.home, it.away) }
        val nat = if (teams.any { it.logo.isEmpty() && it.league == RUGBY_TESTS }) runCatching { nationLogos() }.getOrDefault(emptyMap()) else emptyMap()
        val learned = teams.map { t ->
            if (t.logo.isNotEmpty()) t
            else sides.firstOrNull { matches(t, it) && it.logo.isNotEmpty() }?.let { t.copy(logo = it.logo) }
                ?: nat[norm(t.name)]?.let { t.copy(logo = it) }
                ?: CRICKET_TEAMS.firstOrNull { it.league == t.league && it.name == t.name && it.logo.isNotEmpty() }?.let { t.copy(logo = it.logo) }
                ?: t
        }
        if (learned != teams) prefs.sportsTeams = learned.map { it.encode() }.toSet()

        for (g in filled) {
            val prev = last[g.id]
            if (prev != null) moment(prev, g, now)?.let { _goal.value = it }
            if (prev?.state == "in" && g.state == "post") finishedAt[g.id] = now
            last[g.id] = g
        }
        // Rugby conversions: a try with no kick by the end of the window means it was missed (or not taken).
        for ((k, at) in awaitingKick.toMap()) if (now - at > KICK_WINDOW) { awaitingKick.remove(k); missedAt[k] = now }
        val annotated = filled.map { g ->
            if (g.league.kind != Sports.Kind.RUGBY) return@map g
            val side = listOf(true, false).firstOrNull { awaitingKick.containsKey(key(g, it)) || now - (missedAt[key(g, it)] ?: 0L) < 60_000L }
                ?: return@map g
            val name = if (side) g.home.name else g.away.name
            g.copy(note = if (awaitingKick.containsKey(key(g, side))) "$name · conversion to come" else "$name · conversion missed")
        }
        _games.value = annotated.filter { g ->
            g.live || (g.state == "post" && now - (finishedAt[g.id] ?: 0L) < 15 * 60_000L) ||
                (g.state == "pre" && g.start - now in 0..15 * 60_000L)
        }.sortedBy { if (it.live) 0 else if (it.state == "pre") 1 else 2 }

        return when {
            found.any { it.live } -> if (found.any { it.live && it.league.kind == Kind.CRICKET }) 15_000 else 20_000
            found.any { it.state == "pre" && it.start - now < 30 * 60_000L } -> 60_000
            else -> 10 * 60_000
        }
    }

    // ------------------------------------------------------------------ moments

    /** What just happened between two looks at the same game, if it deserves the big moment. */
    private fun key(g: Game, home: Boolean) = g.id + if (home) "|home" else "|away"

    private fun moment(prev: Game, g: Game, now: Long = System.currentTimeMillis()): Score? {
        if (g.league.kind == Kind.CRICKET) return cricketMoment(prev, g)
        if (!g.live) return null
        if (g.league.kind == Kind.RUGBY) return rugbyMoment(prev, g, now)
        for (home in listOf(true, false)) {
            val now = if (home) g.home else g.away
            val was = if (home) prev.home else prev.away
            if (now.score <= was.score) continue
            val delta = now.score - was.score
            val play = g.lastPlay
            val label = when (g.league.kind) {
                Kind.SOCCER, Kind.HOCKEY -> "GOAL"
                Kind.RUGBY -> when {
                    play.contains("penalty try", true) -> "PENALTY TRY"
                    delta >= 5 -> "TRY"                 // 5, or 7 once converted before we looked again
                    delta == 2 -> "CONVERSION"
                    delta == 3 && play.contains("drop", true) -> "DROP GOAL"
                    delta == 3 -> "PENALTY"
                    else -> "POINTS"
                }
                Kind.FOOTBALL -> when { delta >= 6 -> "TOUCHDOWN"; delta == 3 -> "FIELD GOAL"; delta == 2 -> "SAFETY"; else -> "SCORE" }
                Kind.BASEBALL -> if (play.contains("homer", true) || play.contains("home run", true)) "HOME RUN" else "RUN"
                else -> "" // basketball: baskets come every few seconds — the score just rolls
            }
            val who = if (g.league.kind == Kind.RUGBY && label == "TRY" && delta >= 7) listOf(play, "Converted").filter { it.isNotBlank() }.joinToString(" · ") else play
            return Score(g, home, was.text, label, who, chant(g.league.kind, now, label))
        }
        return null
    }

    private fun rugbyMoment(prev: Game, g: Game, now: Long): Score? {
        for (home in listOf(true, false)) {
            val s = if (home) g.home else g.away; val w = if (home) prev.home else prev.away
            if (s.score <= w.score) continue
            val delta = s.score - w.score
            val play = g.lastPlay
            val k = key(g, home)
            val waiting = awaitingKick.remove(k)
            missedAt.remove(k)
            // Any other score while a kick was pending means that conversion didn't happen.
            listOf(true, false).forEach { h -> if (h != home && awaitingKick.remove(key(g, h)) != null) missedAt[key(g, h)] = now }
            val (label, who) = when {
                play.contains("penalty try", true) -> "PENALTY TRY" to listOf(play, "7 points · no kick needed").filter { it.isNotBlank() }.joinToString(" · ")
                delta == 2 && (waiting != null || play.contains("conver", true)) -> "CONVERTED" to listOf(play, "+2").filter { it.isNotBlank() }.joinToString(" · ")
                delta == 2 -> "CONVERSION" to play
                delta == 7 -> "TRY" to listOf(play, "Converted").filter { it.isNotBlank() }.joinToString(" · ")
                delta == 5 || delta == 6 -> { awaitingKick[k] = now; "TRY" to play }
                delta == 3 && play.contains("drop", true) -> "DROP GOAL" to play
                delta == 3 -> "PENALTY" to play
                delta == 8 -> "TRY + PENALTY" to play
                else -> "POINTS" to play
            }
            if (waiting != null && delta != 2) missedAt[k] = now // they scored again instead of converting
            return Score(g, home, w.text, label, who, if (label == "CONVERTED" || label == "CONVERSION") "" else chant(Kind.RUGBY, s, label))
        }
        return null
    }

    private fun cricketMoment(prev: Game, g: Game): Score? {
        for (home in listOf(true, false)) {
            val now = if (home) g.home else g.away; val was = if (home) prev.home else prev.away
            val other = if (home) g.away else g.home
            // A wicket falls: it's the bowling side's moment.
            if (g.live && now.wickets > was.wickets && now.score >= was.score) {
                return Score(g, !home, (if (home) prev.away else prev.home).text, "WICKET", g.lastPlay, chant(Kind.CRICKET, other, "WICKET"))
            }
            if (g.live && now.wickets == was.wickets) {
                val label = when (now.score - was.score) { 6 -> "SIX"; 4 -> "FOUR"; else -> null }
                if (label != null) return Score(g, home, was.text, label, g.lastPlay, chant(Kind.CRICKET, now, label))
            }
        }
        if (prev.live && g.state == "post") {
            val won = listOf(true, false).firstOrNull { h -> val s = if (h) g.home else g.away; g.lastPlay.contains(s.name, true) && g.lastPlay.contains("won", true) }
            if (won != null) return Score(g, won, (if (won) g.home else g.away).text, "WIN", g.lastPlay)
        }
        return null
    }

    /** Supporters' words that flash up before the big word, like the club graphics in Premier League broadcasts. */
    private fun chant(kind: Kind, s: Side, label: String): String {
        val n = s.name.lowercase()
        return when {
            kind == Kind.RUGBY && n.contains("south africa") && label.contains("TRY") -> "BOKKE"
            kind == Kind.CRICKET && n == "south africa" -> "PROTEAS"
            kind == Kind.SOCCER && n.contains("tottenham") -> "COYS"
            kind == Kind.SOCCER && n.contains("newcastle") -> "HOWAY"
            kind == Kind.SOCCER && n.contains("kaizer chiefs") -> "AMAKHOSI"
            kind == Kind.SOCCER && n.contains("sundowns") -> "MASANDAWANA"
            kind == Kind.SOCCER && n.contains("orlando pirates") -> "BUCCANEERS"
            else -> ""
        }
    }

    // ------------------------------------------------------------------ feeds

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/"
    private const val CRICKET_FEED = "cricket/scorepanel"

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000; setRequestProperty("Accept", "application/json") }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    private suspend fun fetchFeed(feed: String, lg: League): List<Game> {
        if (feed == CRICKET_FEED) {
            val j = get("https://site.web.api.espn.com/apis/site/v2/sports/cricket/scorepanel")
            val out = ArrayList<Game>()
            j.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) runCatching { parseCricket(lg, a.getJSONObject(i)) }.getOrNull()?.let(out::add) }
            j.optJSONArray("scores")?.let { s ->
                for (k in 0 until s.length()) s.optJSONObject(k)?.optJSONArray("events")?.let { a ->
                    for (i in 0 until a.length()) runCatching { parseCricket(lg, a.getJSONObject(i)) }.getOrNull()?.let(out::add)
                }
            }
            return out
        }
        val events = get(BASE + feed + "/scoreboard").optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i -> runCatching { parse(lg, events.getJSONObject(i)) }.getOrNull() }
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun startOf(e: JSONObject) = runCatching { iso.parse(e.optString("date").take(16))!!.time }.getOrDefault(0L)

    private fun parse(lg: League, e: JSONObject): Game? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val cs = comp.optJSONArray("competitors") ?: return null
        var home: Side? = null; var away: Side? = null
        var homeAlt = 0L; var awayAlt = 0L
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i); val t = c.optJSONObject("team") ?: continue
            val color = hex(t.optString("color")); val alt = hex(t.optString("alternateColor"))
            val s = Side(t.optString("id"), t.optString("abbreviation").ifBlank { t.optString("shortDisplayName").take(3).uppercase() },
                t.optString("shortDisplayName").ifBlank { t.optString("displayName") }, c.optString("score").toDoubleOrNull()?.toInt() ?: 0,
                readable(color, alt, t.optString("abbreviation")), logo = logoOf(t))
            if (c.optString("homeAway") == "home" || (home == null && i == 0 && c.optString("homeAway").isEmpty())) { home = s; homeAlt = alt } else { away = s; awayAlt = alt }
        }
        var h = home ?: return null; var a = away ?: return null
        if (close(h.color, a.color) && awayAlt != 0L && !close(h.color, awayAlt)) a = a.copy(color = readable(awayAlt, 0, a.abbr))
        else if (close(h.color, a.color) && homeAlt != 0L) h = h.copy(color = readable(homeAlt, 0, h.abbr))

        val st = (comp.optJSONObject("status") ?: e.optJSONObject("status"))
        val type = st?.optJSONObject("type")
        val state = type?.optString("state").orEmpty().ifBlank { "pre" }
        val start = startOf(e)
        val clock = when (state) {
            "pre" -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start))
            "post" -> type?.optString("shortDetail").orEmpty().let { if ((lg.kind == Kind.SOCCER || lg.kind == Kind.RUGBY) && (it.startsWith("FT") || it.isBlank())) "Full time" else it.ifBlank { "Final" } }
            else -> type?.optString("shortDetail").orEmpty().ifBlank { st?.optString("displayClock").orEmpty() }
        }
        return Game(e.optString("id"), lg, h, a, state, clock, lastPlay(comp, h, a), start)
    }

    /** "T. Mokoena 67'" for football; the feed's own last-play line for other sports. */
    private fun lastPlay(comp: JSONObject, h: Side, a: Side): String {
        comp.optJSONObject("situation")?.optJSONObject("lastPlay")?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
        val d = comp.optJSONArray("details") ?: return ""
        for (i in d.length() - 1 downTo 0) {
            val x = d.optJSONObject(i) ?: continue
            if (!x.optBoolean("scoringPlay")) continue
            val who = x.optJSONArray("athletesInvolved")?.optJSONObject(0)?.optString("displayName").orEmpty()
            val min = x.optJSONObject("clock")?.optString("displayValue").orEmpty()
            val kind = x.optJSONObject("type")?.optString("text").orEmpty()
            val team = x.optJSONObject("team")?.optString("id").let { id -> if (id == h.id) h.name else if (id == a.id) a.name else "" }
            val extra = when { kind.contains("Own", true) -> " (own goal)"; kind.contains("Penalty", true) -> " (pen)"; kind.contains("Drop", true) -> " (drop goal)"; else -> "" }
            return listOf(who.ifBlank { team }, min).filter { it.isNotBlank() }.joinToString(" ") + extra
        }
        return ""
    }

    /** The team's official logo from ESPN — the dark-background version when there is one (our cards are dark). */
    private fun logoOf(t: JSONObject): String {
        val arr = t.optJSONArray("logos")
        if (arr != null) {
            var first = ""
            for (i in 0 until arr.length()) {
                val l = arr.optJSONObject(i) ?: continue
                val href = l.optString("href"); if (href.isBlank()) continue
                if (first.isEmpty()) first = href
                if (l.optJSONArray("rel")?.toString()?.contains("dark") == true) return href
            }
            if (first.isNotEmpty()) return first
        }
        return t.optString("logo")
    }

    // ---- cricket: "161/5 (18/20 ov, target 156)", "245 & 120/3 (35 ov)", or "" before batting
    data class Innings(val runs: Int, val wickets: Int, val text: String, val overs: String)
    fun innings(score: String): Innings {
        val lastInn = score.split('&').last().trim()
        val main = lastInn.substringBefore('(').trim()
        val overs = Regex("\\(([^)]*?ov)").find(lastInn)?.groupValues?.get(1)?.substringBefore(',')?.trim().orEmpty()
        val m = Regex("(\\d+)(?:/(\\d+))?").find(main)
        val runs = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val wk = m?.groupValues?.get(2)?.toIntOrNull() ?: if (m != null && main.isNotEmpty() && !main.contains('/')) 10 else 0
        val text = if (score.contains('&')) score.substringBefore('(').trim().replace(Regex("\\s+"), " ") else main.ifBlank { "–" }
        return Innings(runs, wk, text.ifBlank { "–" }, overs)
    }

    private fun parseCricket(lg: League, e: JSONObject): Game? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val cs = comp.optJSONArray("competitors") ?: return null
        if (cs.length() < 2) return null
        val sides = (0 until 2).map { i ->
            val c = cs.getJSONObject(i); val t = c.optJSONObject("team") ?: JSONObject()
            val inn = innings(c.optString("score"))
            val known = CRICKET_TEAMS.firstOrNull { k -> norm(k.name) == norm(t.optString("displayName")) }
            Side(t.optString("id"), t.optString("abbreviation").ifBlank { known?.abbr ?: t.optString("displayName").take(3).uppercase() },
                t.optString("displayName").ifBlank { t.optString("name") }, inn.runs,
                known?.color ?: readable(hex(t.optString("color")), hex(t.optString("alternateColor")), t.optString("abbreviation")),
                inn.text, inn.wickets, inn.overs, logoOf(t))
        }
        val h = sides[0]; var a = sides[1]
        if (close(h.color, a.color)) a = a.copy(color = readable(0, 0, a.abbr + "x"))
        val st = comp.optJSONObject("status") ?: e.optJSONObject("status")
        val type = st?.optJSONObject("type")
        val state = type?.optString("state").orEmpty().ifBlank { "pre" }
        val start = startOf(e)
        val batting = listOf(h, a).lastOrNull { it.sub.isNotBlank() }
        val clock = when (state) {
            "pre" -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start))
            "post" -> "Result"
            else -> batting?.sub?.let { it.substringBefore('/') + if (it.endsWith("ov")) "" else " ov" } ?: type?.optString("shortDetail").orEmpty()
        }
        val summary = st?.optString("summary").orEmpty().ifBlank { comp.optString("note").ifBlank { type?.optString("detail").orEmpty() } }
        val label = comp.optJSONObject("class")?.optString("generalClassCard").orEmpty()
        return Game(e.optString("id"), lg.copy(name = listOf(label, comp.optString("description")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { lg.name }),
            h, a, state, clock.ifBlank { "Live" }, summary, start)
    }

    /** Teams you can pick in a league. Cricket and the national rugby sides come from lists; the rest from ESPN. */
    suspend fun teams(lg: League): List<Team> {
        if (lg.kind == Kind.CRICKET) return CRICKET_TEAMS.filter { it.league == lg.path }
        if (lg.path == RUGBY_TESTS) { val logos = nationLogos(); return RUGBY_NATIONS.map { Team(lg.path, "", it.abbr, it.name, it.color, logos[norm(it.name)].orEmpty()) } }
        val arr = get(BASE + lg.path + "/teams").optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")
            ?.optJSONObject(0)?.optJSONArray("teams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i)?.optJSONObject("team") ?: return@mapNotNull null
            Team(lg.path, t.optString("id"), t.optString("abbreviation"), t.optString("displayName"),
                readable(hex(t.optString("color")), hex(t.optString("alternateColor")), t.optString("abbreviation")), logoOf(t))
        }.sortedBy { it.name }
    }

    private fun t(league: String, abbr: String, name: String, color: Long, logo: String = "") = Team(league, "", abbr, name, color, logo)

    /** National rugby logos, read from ESPN's own Test-rugby team lists (no guessing at ids). Fetched once. */
    @Volatile private var nations: Map<String, String>? = null
    suspend fun nationLogos(): Map<String, String> {
        nations?.let { return it }
        val out = HashMap<String, String>()
        for (feed in listOf("rugby/289234", "rugby/17567", "rugby/244293", "rugby/180659", "rugby/164205")) runCatching {
            val arr = get(BASE + feed + "/teams").optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams")
            if (arr != null) for (i in 0 until arr.length()) {
                val tm = arr.optJSONObject(i)?.optJSONObject("team") ?: continue
                val logo = logoOf(tm); if (logo.isBlank()) continue
                for (n in listOf(tm.optString("displayName"), tm.optString("shortDisplayName"), tm.optString("name"))) if (n.isNotBlank()) out.putIfAbsent(norm(n), logo)
            }
        }
        if (out.isNotEmpty()) nations = out
        return out
    }
    /** ESPN serves cricket logos by ESPNcricinfo team id. */
    private fun cricLogo(id: Int) = "https://a.espncdn.com/i/teamlogos/cricket/500/$id.png"
    private val RUGBY_NATIONS = listOf(
        t("", "RSA", "South Africa", 0xFF00704A), t("", "NZ", "New Zealand", 0xFF3A3A3A), t("", "AUS", "Australia", 0xFFF2B51C),
        t("", "ARG", "Argentina", 0xFF5DA9E9), t("", "ENG", "England", 0xFFC8102E), t("", "IRE", "Ireland", 0xFF169B62),
        t("", "FRA", "France", 0xFF1E3A8A), t("", "SCO", "Scotland", 0xFF1F3B73), t("", "WAL", "Wales", 0xFFD30731),
        t("", "ITA", "Italy", 0xFF0057B8), t("", "FIJ", "Fiji", 0xFF2F80C8), t("", "JPN", "Japan", 0xFFD7263D),
        t("", "SAM", "Samoa", 0xFF1D3C8F), t("", "GEO", "Georgia", 0xFFB3132A), t("", "TGA", "Tonga", 0xFFC8102E),
        t("", "USA", "United States", 0xFF27457A), t("", "CAN", "Canada", 0xFFD52B1E),
    )
    private val CRICKET_TEAMS = listOf(
        t("cricket/intl", "SA", "South Africa", 0xFF00843D, cricLogo(3)), t("cricket/intl", "AUS", "Australia", 0xFFF2B51C, cricLogo(2)),
        t("cricket/intl", "ENG", "England", 0xFF1C3D7A, cricLogo(1)), t("cricket/intl", "IND", "India", 0xFF1F5BB5, cricLogo(6)),
        t("cricket/intl", "NZ", "New Zealand", 0xFF3A3A3A, cricLogo(5)), t("cricket/intl", "PAK", "Pakistan", 0xFF11703A, cricLogo(7)),
        t("cricket/intl", "SL", "Sri Lanka", 0xFF1B3F94, cricLogo(8)), t("cricket/intl", "WI", "West Indies", 0xFF7B1E3A, cricLogo(4)),
        t("cricket/intl", "BAN", "Bangladesh", 0xFF006A4E, cricLogo(25)), t("cricket/intl", "AFG", "Afghanistan", 0xFF2563B8, cricLogo(40)),
        t("cricket/intl", "ZIM", "Zimbabwe", 0xFFCE1126, cricLogo(9)), t("cricket/intl", "IRE", "Ireland", 0xFF169B62, cricLogo(29)),
        t("cricket/sa20", "DSG", "Durban's Super Giants", 0xFF00A3C4), t("cricket/sa20", "JSK", "Joburg Super Kings", 0xFFF5B800),
        t("cricket/sa20", "MICT", "MI Cape Town", 0xFF1F5FAD), t("cricket/sa20", "PR", "Paarl Royals", 0xFFE5157F),
        t("cricket/sa20", "PC", "Pretoria Capitals", 0xFF2E5BA8), t("cricket/sa20", "SEC", "Sunrisers Eastern Cape", 0xFFF26522),
        t("cricket/ipl", "CSK", "Chennai Super Kings", 0xFFF5C400), t("cricket/ipl", "MI", "Mumbai Indians", 0xFF1F5FAD),
        t("cricket/ipl", "RCB", "Royal Challengers Bengaluru", 0xFFD1141B), t("cricket/ipl", "KKR", "Kolkata Knight Riders", 0xFF5B2A86),
        t("cricket/ipl", "SRH", "Sunrisers Hyderabad", 0xFFF26522), t("cricket/ipl", "RR", "Rajasthan Royals", 0xFFE5157F),
        t("cricket/ipl", "DC", "Delhi Capitals", 0xFF2E5BA8), t("cricket/ipl", "PBKS", "Punjab Kings", 0xFFD71920),
        t("cricket/ipl", "GT", "Gujarat Titans", 0xFF2A4470), t("cricket/ipl", "LSG", "Lucknow Super Giants", 0xFF18A3C4),
        t("cricket/bbl", "STR", "Adelaide Strikers", 0xFF2E8FD8), t("cricket/bbl", "HEA", "Brisbane Heat", 0xFF0FA3B1),
        t("cricket/bbl", "HUR", "Hobart Hurricanes", 0xFF6B3FA0), t("cricket/bbl", "REN", "Melbourne Renegades", 0xFFD71920),
        t("cricket/bbl", "STA", "Melbourne Stars", 0xFF1BA34A), t("cricket/bbl", "SCO", "Perth Scorchers", 0xFFF26522),
        t("cricket/bbl", "SIX", "Sydney Sixers", 0xFFE5157F), t("cricket/bbl", "THU", "Sydney Thunder", 0xFF8BC53F),
    )

    // ------------------------------------------------------------------ colours

    private fun hex(s: String?): Long = s?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0L
    private fun lum(c: Long): Float { val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f; return 0.2126f * r + 0.7152f * g + 0.0722f * b }
    private fun close(a: Long, b: Long): Boolean {
        val dr = (a shr 16 and 255) - (b shr 16 and 255); val dg = (a shr 8 and 255) - (b shr 8 and 255); val db = (a and 255) - (b and 255)
        return dr * dr + dg * dg + db * db < 70 * 70
    }
    private fun readable(main: Long, alt: Long, key: String): Long {
        for (c in listOf(main, alt)) if (c != 0L && lum(c) in 0.06f..0.85f) return c
        val pal = longArrayOf(0xFF2F6BFF, 0xFFE0383E, 0xFF13A35B, 0xFFF2A31B, 0xFF8A4DFF, 0xFF00A6B4, 0xFFE8531E)
        return pal[(key.hashCode() and 0x7fffffff) % pal.size]
    }

    // ------------------------------------------------------------------ demo

    private val demoScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    /** Settings → Sports → Preview: a pretend game of the chosen sport, then its big moment. */
    fun startDemo(kind: Kind = Kind.SOCCER) { demoScope.launch { demo(kind) } }

    suspend fun demo(kind: Kind = Kind.SOCCER) {
        val (g, g2, s) = when (kind) {
            Kind.RUGBY -> {
                val lg = League("demo", "Nations Championship", Kind.RUGBY, "Rugby", "International")
                val g = Game("demo", lg, Side("1", "RSA", "South Africa", 10, 0xFF00704A), Side("2", "ENG", "England", 14, 0xFFC8102E), "in", "44'", "", 0)
                val g2 = g.copy(home = g.home.copy(score = 15, text = "15"), clock = "47'", lastPlay = "C. Kolbe 47'")
                Triple(g, g2, Score(g2, true, "10", "TRY", "C. Kolbe 47'", "BOKKE"))
            }
            Kind.CRICKET -> {
                val lg = League("demo", "T20I · 2nd T20I", Kind.CRICKET, "Cricket", "International")
                val g = Game("demo", lg, Side("1", "SA", "South Africa", 161, 0xFF00843D, "161/5", 5, "17.4 ov"), Side("2", "AUS", "Australia", 182, 0xFFF2B51C, "182/7", 7, "20 ov"),
                    "in", "17.4 ov", "South Africa need 22 runs from 14 balls", 0)
                val g2 = g.copy(home = g.home.copy(score = 167, text = "167/5", sub = "17.5 ov"), clock = "17.5 ov", lastPlay = "South Africa need 16 runs from 13 balls")
                Triple(g, g2, Score(g2, true, "161/5", "SIX", "South Africa need 16 runs from 13 balls", "PROTEAS"))
            }
            else -> {
                val lg = League("demo", "Premier League", Kind.SOCCER, "Football", "England")
                val g = Game("demo", lg, Side("1", "JHB", "Lions", 1, 0xFFF2B01E), Side("2", "CPT", "Comets", 0, 0xFF2F6BFF), "in", "64'", "T. Mokoena 38'", 0)
                val g2 = g.copy(away = g.away.copy(score = 1, text = "1"), clock = "67'", lastPlay = "S. Dlamini 67'")
                Triple(g, g2, Score(g2, false, "0", "GOAL", g2.lastPlay))
            }
        }
        demoUntil = System.currentTimeMillis() + 22_000
        _games.value = listOf(g)
        delay(2600)
        _games.value = listOf(g2)
        _goal.value = s
        delay(19_000)
        _games.value = emptyList(); demoUntil = 0
    }
}
