package com.nebula.assistant.notch

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nebula.assistant.beam.BeamDevice
import com.nebula.assistant.beam.BeamLink
import com.nebula.assistant.beam.BeamUi
import com.nebula.assistant.beam.Offer
import com.nebula.assistant.beam.OfferItem
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.NebulaTask
import com.nebula.assistant.data.StepStatus
import com.nebula.assistant.data.TaskStatus
import com.nebula.assistant.data.TaskStep
import com.nebula.assistant.game.R3cket
import com.nebula.assistant.sports.Logos
import com.nebula.assistant.sports.Sports
import com.nebula.assistant.voice.VoiceState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Records every notch animation, frame by frame, from the real composables (the same code the phone runs),
 * driven by a test clock at 30 fps. CI turns the frames into a video. Only runs with -Pfilm.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w420dp-h470dp-xhdpi")
class NotchFilm {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val fps = 30
    private var frame = 0
    private lateinit var out: File

    private class Host(override val context: android.content.Context) : NotchHost {
        override val notchPrefs = NebulaPrefs(context)
        override val layout = NotchController.Layout(1f, 1f, 0f, 0f)
        override var unlock by mutableStateOf<NotchController.UnlockPhase?>(null)
        override var volume by mutableStateOf<Pair<Float, Boolean>?>(null)
        override var phoneInCall by mutableStateOf(false)
        override var charge by mutableStateOf<NotchBus.Charge?>(null)
        override var ringer by mutableStateOf<NotchBus.Ringer?>(null)
        override fun cutoutHeightPx() = (36 * context.resources.displayMetrics.density).toInt()
        override fun reserve(w: Int, h: Int) {}
        override fun fit(w: Int, h: Int, afterMs: Long) {}
        override fun talk() {}
        override fun openApp(tab: String?, taskId: String?) {}
        override fun later(ms: Long, f: () -> Unit) {}
    }

    private var st by mutableStateOf(IslandState())
    private var caption by mutableStateOf("")
    private var section by mutableStateOf("")
    private var at = 1_000L
    private fun next() = ++at + System.currentTimeMillis()

    @Test
    fun film() {
        assumeTrue(System.getProperty("nebula.film") == "1")
        out = File(System.getProperty("nebula.filmDir") ?: "build/notch-film").apply { deleteRecursively(); mkdirs() }
        val ctx = rule.activity
        val host = Host(ctx)
        val logos = runBlocking { fetchLogos(ctx) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A3550), Color(0xFF6E5A8C), Color(0xFFD7A6A0))))) {
                // a hint of a phone: status bar text either side of the camera
                Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 14.dp)) {
                    Text("9:41", color = Color.White, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
                    Spacer(Modifier.weight(1f))
                    Text("5G  87%", color = Color.White, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
                }
                Box(Modifier.fillMaxWidth().height(410.dp)) {
                    Island(st, host, onGoalDone = { st = st.copy(goal = null) }, onPanel = { st = st.copy(panel = it) })
                }
                // the camera, sitting in the island
                Canvas(Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(32.dp)) {
                    drawCircle(Color(0xFF0C0C10), 5.5.dp.toPx(), Offset(size.width / 2, size.height / 2))
                    drawCircle(Color(0xFF26304A), 2.2.dp.toPx(), Offset(size.width / 2 - 1.dp.toPx(), size.height / 2 - 1.dp.toPx()))
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0B0B0F)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text(section.uppercase(), color = Color(0xFFF2A31B), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.6.sp))
                    Text(caption, color = Color.White, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp))
                }
            }
        }
        try { script(ctx, host, logos) } finally { finish() }
    }

    // ------------------------------------------------------------------ the reel

    private fun script(ctx: android.content.Context, host: Host, L: Map<String, String>) {
        val now = System.currentTimeMillis()
        hold(0.8, "The Nebula Notch", "Resting — just the camera")

        // ---- system
        section = "System"
        host.volume = 0.35f to false; hold(0.5, caption = "Volume"); host.volume = 0.7f to false; hold(1.2); host.volume = null; hold(0.8)
        host.charge = NotchBus.Charge(64, true, fast = true); hold(3.0, caption = "Charging"); host.charge = null; hold(0.8)
        host.ringer = NotchBus.Ringer(android.media.AudioManager.RINGER_MODE_SILENT); hold(2.0, caption = "Ring / silent switch"); host.ringer = null; hold(0.8)
        st = st.copy(device = NotchBus.Device("Galaxy Buds3 Pro", true, "buds", battery = 82)); hold(2.6, caption = "Earbuds connected"); st = st.copy(device = null); hold(0.8)
        host.unlock = NotchController.UnlockPhase.SCANNING; hold(1.2, caption = "Face unlock"); host.unlock = NotchController.UnlockPhase.SUCCESS; hold(1.4); host.unlock = null; hold(0.8)

        // ---- music
        section = "Music player"
        val a1 = cover(0xFFFF7A59.toInt(), 0xFF5B2A86.toInt()); val a2 = cover(0xFF1FB6A6.toInt(), 0xFF1E2A78.toInt())
        st = st.copy(media = NotchBus.Media("Midnight Drive", "Nova Lane", a1, true, 62_000, 214_000, System.currentTimeMillis(), "com.spotify.music", null))
        hold(2.4, caption = "Compact — cover, live bars, progress hairline")
        tapIsland(); hold(3.2, caption = "Open player — tap or drag to seek")
        st = st.copy(media = st.media!!.copy(title = "Glass Harbour", artist = "The Quiet Hours", art = a2, position = 0, updatedAt = System.currentTimeMillis()))
        hold(2.6, caption = "Song changes: cover blooms, title slides, colours follow")
        st = st.copy(media = st.media!!.copy(playing = false)); hold(1.4, caption = "Pause morph")
        st = st.copy(media = null); hold(1.2)

        // ---- Hey Nebula
        section = "Hey Nebula"
        val t0 = System.currentTimeMillis()
        for (i in 0 until 50) { st = st.copy(voice = VoiceState.Session(VoiceState.Phase.LISTENING, if (i > 18) "What's the score in the Arsenal game" else "", level = (kotlin.math.sin(i * 0.7) * 0.5 + 0.5).toFloat(), startedAt = t0)); hold(1.0 / 15, caption = "Listening — the Nebula rim runs while you talk") }
        st = st.copy(voice = st.voice.copy(phase = VoiceState.Phase.THINKING, level = 0f)); hold(1.6, caption = "Thinking")
        st = st.copy(voice = st.voice.copy(phase = VoiceState.Phase.SPEAKING, reply = "Arsenal lead Chelsea 2–1 in the 67th minute. Saka just scored.")); hold(2.6, caption = "Answer")
        st = st.copy(voice = VoiceState.Session()); hold(1.0)

        // ---- calls, messages, tasks, live activities
        section = "Calls, messages, tasks"
        st = st.copy(call = NotchBus.Call("Mom", "Mobile", true, null, null, null, "c1")); host.phoneInCall = true; hold(2.6, caption = "Incoming call")
        st = st.copy(call = null); host.phoneInCall = false; hold(0.8)
        st = st.copy(message = NotchBus.Message("m1", "WhatsApp", "com.whatsapp", "Thando", "Are we still on for 7? I booked the table by the window 🙌", null, null, null, next())); hold(3.0, caption = "Message")
        st = st.copy(message = null); hold(0.8)
        val task = NebulaTask(title = "Book a table", conversationId = "c", status = TaskStatus.IN_PROGRESS, plan = listOf("a", "b", "c", "d"),
            steps = listOf(TaskStep(label = "a", status = StepStatus.DONE)))
        st = st.copy(tasks = listOf(task)); hold(1.4, caption = "A task Nebula is doing")
        st = st.copy(tasks = listOf(task.copy(steps = task.steps + TaskStep(label = "b", status = StepStatus.DONE) + TaskStep(label = "c", status = StepStatus.DONE)))); hold(1.2)
        st = st.copy(tasks = listOf(task.copy(status = TaskStatus.COMPLETED, updatedAt = System.currentTimeMillis()))); hold(2.0, caption = "Done")
        st = st.copy(tasks = emptyList()); hold(0.8)
        st = st.copy(lives = listOf(NotchBus.Live("l1", "com.google.android.deskclock", "Clock", "timer", "Pasta", "Timer", null, 0, null, System.currentTimeMillis() + 7 * 60_000, true, "", null)))
        hold(1.8, caption = "Live Activity (a timer)"); tapIsland(); hold(2.6, caption = "Opened"); st = st.copy(lives = emptyList()); hold(1.0)

        // ---- football
        section = "Premier League"
        val epl = Sports.League("demo", "Premier League", Sports.Kind.SOCCER, "Football", "England")
        val stats = listOf(Sports.Stat("Possession", "54%", "46%", 54f, 46f), Sports.Stat("Shots", "11", "7", 11f, 7f), Sports.Stat("On target", "5", "2", 5f, 2f))
        fun ars(n: Int) = Sports.Side("1", "ARS", "Arsenal", n, 0xFFE20520, logo = L["arsenal"].orEmpty())
        fun che(n: Int) = Sports.Side("2", "CHE", "Chelsea", n, 0xFF034694, logo = L["chelsea"].orEmpty())
        val g0 = Sports.Game("g", epl, ars(0), che(0), "in", "1'", "", 0, leagueLogo = L["league"].orEmpty())
        st = st.copy(games = listOf(g0), goal = Sports.Score(g0, true, "0", "KICK-OFF", "", at = next(), kind = "intro")); hold(5.4, caption = "Competition intro at kick-off")
        hold(3.6, caption = "Compact score — teams by name, then the score")
        val g1 = g0.copy(home = ars(1), clock = "23'", lastPlay = "M. Ødegaard 23'", stats = stats)
        st = st.copy(games = listOf(g1), goal = Sports.Score(g1, true, "0", "GOAL", "M. Ødegaard 23'", at = next(), motif = "cannons")); hold(7.2, caption = "Goal — Arsenal's own moment (cannons)")
        val g2 = g1.copy(away = che(1), clock = "41'", lastPlay = "C. Palmer 41'")
        st = st.copy(games = listOf(g2), goal = Sports.Score(g2, false, "0", "GOAL", "C. Palmer 41'", at = next(), motif = "wordwall")); hold(6.8, caption = "Goal — the word wall look")
        val g3 = g2.copy(home = ars(2), clock = "67'", lastPlay = "B. Saka 67'")
        st = st.copy(games = listOf(g3), goal = Sports.Score(g3, true, "1", "GOAL", "B. Saka 67'", "COYG", at = next(), motif = "logopeek")); hold(8.2, caption = "Goal — supporters' word, logo peek")
        tapIsland(); hold(8.0, caption = "Open board — stats brought to you by ESPN, on rotation")
        st = st.copy(goal = Sports.Score(g3, true, "2", "VAR", "Possible offside", at = next(), kind = "check", extra = "VAR")); hold(5.4, caption = "VAR check")
        st = st.copy(goal = Sports.Score(g3, true, "2", "VAR", "GOAL STANDS", at = next(), kind = "decision", extra = "VAR", favour = 1, good = true)); hold(6.0, caption = "VAR decision — in favour of Arsenal")
        st = st.copy(goal = Sports.Score(g3, false, "1", "SUBSTITUTION", "", at = next(), kind = "sub", minute = "72'", subs = listOf("N. Jackson" to "C. Nkunku", "E. Fernández" to "R. Lavia"))); hold(6.4, caption = "Substitution board")
        st = st.copy(goal = Sports.Score(g3, false, "1", "RED CARD", "M. Caicedo", at = next(), kind = "red", minute = "78'")); hold(6.0, caption = "Red card")
        val g4 = g3.copy(clock = "90'", added = "4")
        st = st.copy(games = listOf(g4), goal = Sports.Score(g4, true, "2", "+4", "minutes of added time", at = next(), kind = "added", extra = "4")); hold(5.4, caption = "Added time — then a +4 tab on the clock")
        hold(2.0, caption = "Compact with the added-time tab")
        val gf = g4.copy(state = "post", clock = "Full time", added = "")
        st = st.copy(games = listOf(gf), goal = Sports.Score(gf, true, "2", "FULL TIME", "", at = next(), kind = "fulltime")); hold(6.4, caption = "Full time")
        st = st.copy(games = emptyList()); hold(1.0)
        val bha = Sports.Side("3", "BHA", "Brighton", 1, 0xFF0057B8, logo = L["brighton"].orEmpty())
        val new = Sports.Side("4", "NEW", "Newcastle", 1, 0xFF3A3A3A, logo = L["newcastle"].orEmpty())
        val gb = Sports.Game("g2", epl, bha, new, "in", "71'", "K. Mitoma 71'", 0, leagueLogo = L["league"].orEmpty())
        st = st.copy(games = listOf(gb), goal = Sports.Score(gb, true, "0", "GOAL", "K. Mitoma 71'", at = next(), motif = "gulls")); hold(7.2, caption = "Brighton — seagulls")
        st = st.copy(goal = Sports.Score(gb.copy(away = new.copy(score = 2, text = "2")), false, "1", "GOAL", "A. Isak 80'", at = next(), motif = "magpies")); hold(7.2, caption = "Newcastle — magpies")
        st = st.copy(goal = Sports.Score(gb, true, "1", "GOAL", "J. Pedro 84'", at = next(), motif = "burst")); hold(6.6, caption = "The burst look")
        st = st.copy(goal = Sports.Score(gb, true, "1", "GOAL", "D. Welbeck 88'", at = next(), motif = "logos")); hold(6.6, caption = "The logo-tiles look")
        st = st.copy(games = emptyList(), goal = null); hold(1.0)

        // ---- rugby
        section = "Rugby"
        val tests = Sports.League("demo", "Nations Championship", Sports.Kind.RUGBY, "Rugby", "International")
        fun rsa(n: Int) = Sports.Side("1", "RSA", "South Africa", n, 0xFF00704A, logo = L["southafrica"].orEmpty())
        fun eng(n: Int) = Sports.Side("2", "ENG", "England", n, 0xFFC8102E, logo = L["england"].orEmpty())
        val r0 = Sports.Game("r", tests, rsa(0), eng(0), "pre", "17:00", "", now + 3_600_000, leagueLogo = L["rugbyleague"].orEmpty())
        val names = listOf("Ox Nché", "Malcolm Marx", "Frans Malherbe", "Eben Etzebeth", "RG Snyman", "Siya Kolisi")
        val players = names.mapIndexed { i, n -> Sports.Player("${i + 1}", n, "", true) } + listOf(Sports.Player("16", "Bongi Mbonambi", "", false), Sports.Player("17", "Faf de Klerk", "", false))
        st = st.copy(games = listOf(r0), goal = Sports.Score(r0, true, "0", "STARTING XV", "South Africa", at = next(), kind = "lineup", players = players))
        hold(lineupSec(players), caption = "Lineup — No.6 SIYA KOLISI — then the bench")
        val r1 = r0.copy(state = "in", clock = "47'", home = rsa(15), away = eng(14), lastPlay = "C. Kolbe 47'")
        st = st.copy(games = listOf(r1), goal = Sports.Score(r1, true, "10", "TRY", "C. Kolbe 47'", "BOKKE", at = next(), motif = "wordwall")); hold(8.0, caption = "Try — the TRY board")
        val r2 = r1.copy(home = rsa(17), clock = "48'", note = "")
        st = st.copy(games = listOf(r2), goal = Sports.Score(r2, true, "15", "CONVERTED", "H. Pollard 48'", at = next(), motif = "cinematic")); hold(7.0, caption = "Conversion — cinematic")
        st = st.copy(goal = Sports.Score(r2, false, "14", "TMO", "Possible knock-on", at = next(), kind = "check", extra = "TMO")); hold(5.4, caption = "TMO check")
        st = st.copy(goal = Sports.Score(r2, true, "17", "TMO", "NO TRY", at = next(), kind = "decision", extra = "TMO", favour = 1, good = false)); hold(6.0, caption = "TMO decision — in favour of South Africa")
        val until = System.currentTimeMillis() + 10 * 60_000
        st = st.copy(goal = Sports.Score(r2, false, "14", "YELLOW CARD", "M. Itoje", at = next(), kind = "yellow", minute = "52'", until = until)); hold(5.8, caption = "Yellow card — sin bin clock")
        st = st.copy(games = listOf(r2.copy(cards = listOf(Sports.Card(false, "M. Itoje", "yellow", "52'", until)))), goal = null); hold(2.4, caption = "Compact — the sin-bin countdown")
        st = st.copy(games = emptyList()); hold(1.0)

        // ---- R3cket
        section = "R3cket"
        val gs = R3cket.Session("Asphalt Legends", "com.gameloft.asphalt", System.currentTimeMillis(), boosting = true)
        st = st.copy(gaming = true, game = gs, ping = 38); hold(1.6, caption = "Game starts — the boost runs inside the slim HUD")
        st = st.copy(game = gs.copy(boosting = false, freedMb = 412, closedApps = 9)); hold(2.4, caption = "What it did, then the game clock")
        st = st.copy(panel = true); hold(2.6, caption = "Tap: the tools, in one low strip")
        st = st.copy(panel = false); hold(1.2)
        st = st.copy(gaming = false, game = null, ping = null); hold(1.0)

        // ---- Beam
        section = "Nebula Beam"
        st = st.copy(beam = BeamUi.Scanning(emptyList(), true, true)); hold(2.6, caption = "Double-tap: Beam looks for Nebula devices")
        st = st.copy(beam = BeamUi.Scanning(listOf(BeamDevice("d1", "Simon's PC", "windows", "", 0), BeamDevice("d2", "Galaxy Tab", "android", "", 0)), true, true)); hold(2.4, caption = "Devices pop in")
        st = st.copy(beam = BeamUi.Waiting("Simon's PC", "482913")); hold(2.0, caption = "Waiting for them to accept")
        for (i in 0..40) { st = st.copy(beam = BeamUi.Sending("Simon's PC", i / 40f, 3, "482913")); hold(1.0 / 12, caption = "Flying — the plane rides the real progress") }
        st = st.copy(beam = BeamUi.Sent("Simon's PC", 3, "nebula")); hold(2.4, caption = "Sent")
        st = st.copy(beam = BeamUi.Idle); hold(0.8)
        val url = "http://192.168.1.24:41733/b/4f0c9a1e7b2d4c8e"
        st = st.copy(beam = BeamUi.Link(url, BeamLink.qr(url), System.currentTimeMillis() + 600_000, 2, 0, listOf("a.jpg", "b.mp4"))); hold(2.6, caption = "Beam Link — the QR turns over, a scan line reads it")
        st = st.copy(beam = (st.beam as BeamUi.Link).copy(downloads = 1)); hold(1.6, caption = "Each download bumps the counter")
        val join = BeamLink.qr("WIFI:T:WPA;S:Nebula-4F2A;P:k7m2q9x4;;")
        st = st.copy(beam = BeamUi.Link(url, BeamLink.qr(url), System.currentTimeMillis() + 600_000, 2, 0, listOf("a.jpg"), join, "Nebula-4F2A", "k7m2q9x4", 0)); hold(2.6, caption = "Not on the same Wi-Fi: step 1, join the phone's hotspot")
        st = st.copy(beam = (st.beam as BeamUi.Link).copy(step = 1)); hold(2.2, caption = "Step 2, open the link")
        st = st.copy(beam = BeamUi.Failed("This Wi-Fi doesn't let devices reach each other (common on guest and public networks)", false, true, true)); hold(2.4, caption = "An honest failure, with a way out")
        st = st.copy(beam = BeamUi.Incoming(Offer("o1", "Galaxy Tab", "android", listOf(OfferItem("IMG_2041.jpg", 3_400_000, "image/jpeg"), OfferItem("IMG_2042.jpg", 3_100_000, "image/jpeg")), "731204", null)))
        hold(2.8, caption = "Someone Beams you — check the code, accept or decline")
        for (i in 0..24) { st = st.copy(beam = BeamUi.Receiving("Galaxy Tab", i / 24f, 2)); hold(1.0 / 12, caption = "Arriving") }
        st = st.copy(beam = BeamUi.Received("Galaxy Tab", 2, listOf(Uri.parse("content://demo/1")), null)); hold(2.2, caption = "Received")
        st = st.copy(beam = BeamUi.Idle); hold(1.2, caption = "")
    }

    private fun lineupSec(p: List<Sports.Player>): Double {
        val s = p.count { it.starter }; val b = p.size - s
        return (1500 + s * 850 + (if (b > 0) 700 + b * 500 else 0) + 900) / 1000.0
    }

    // ------------------------------------------------------------------ helpers

    private fun hold(sec: Double, sectionName: String? = null, caption: String? = null) {
        sectionName?.let { section = it }
        caption?.let { this.caption = it }
        repeat((sec * fps).toInt().coerceAtLeast(1)) {
            rule.mainClock.advanceTimeBy(1000L / fps, ignoreFrameDuration = true)
            capture()
        }
    }

    private fun tapIsland() {
        val d = rule.activity.resources.displayMetrics.density
        rule.onRoot().performTouchInput { click(Offset(width / 2f, (5 + 16) * d)) }
    }

    private var ffmpeg: Process? = null
    private var raw: java.io.OutputStream? = null

    /** Draws the stage and streams the frame straight into ffmpeg (or saves a PNG if ffmpeg isn't there). */
    private fun capture() {
        val v = (rule.activity.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0)
        val w = v.width and 1.inv(); val h = v.height and 1.inv()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        val tool = System.getProperty("nebula.ffmpeg").orEmpty()
        if (raw == null && tool.isNotEmpty() && File(tool).canExecute()) {
            ffmpeg = ProcessBuilder(tool, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${w}x$h", "-r", "$fps", "-i", "-",
                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", File(out, "notch-animations.mp4").absolutePath)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
            raw = java.io.BufferedOutputStream(ffmpeg!!.outputStream, 1 shl 22)
        }
        if (frame == 0 || frame % 150 == 0) File(out, "still-%05d.png".format(frame)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val r = raw
        if (r != null) {
            val buf = java.nio.ByteBuffer.allocate(w * h * 4); bmp.copyPixelsToBuffer(buf); r.write(buf.array())
        } else File(out, "%05d.png".format(frame)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        frame++
        bmp.recycle()
    }

    private fun finish() {
        raw?.close(); ffmpeg?.waitFor(); raw = null
        println("Film: $frame frames")
    }

    /** An invented album cover: a gradient with soft shapes (no real artwork). */
    private fun cover(c1: Int, c2: Int): Bitmap {
        val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 300f, 300f, c1, c2, android.graphics.Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, 300f, 300f, p)
        p.shader = null; p.color = 0x55FFFFFF
        cv.drawCircle(210f, 90f, 60f, p); p.color = 0x33000000; cv.drawCircle(90f, 220f, 90f, p)
        return b
    }

    /** Real ESPN logos for the teams and competitions in the reel. */
    private suspend fun fetchLogos(ctx: android.content.Context): Map<String, String> {
        val outMap = HashMap<String, String>()
        // The JDK's own HTTP client (Android's URLConnection doesn't work inside Robolectric).
        val http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(10)).build()
        fun bytes(u: String): ByteArray? = runCatching {
            val r = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI(u)).header("User-Agent", "Mozilla/5.0").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray())
            if (r.statusCode() in 200..299) r.body() else { println("Film: HTTP ${r.statusCode()} for $u"); null }
        }.onFailure { println("Film: fetch failed $u: $it") }.getOrNull()
        fun get(u: String) = bytes(u)?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
        val base = "https://site.api.espn.com/apis/site/v2/sports/"
        for ((path, keys) in listOf("soccer/eng.1" to listOf("arsenal", "chelsea", "brighton", "newcastle"), "rugby/289234" to listOf("southafrica", "england"))) {
            val arr = get(base + path + "/teams")?.optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams") ?: continue
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i)?.optJSONObject("team") ?: continue
                val n = t.optString("displayName").lowercase().replace(Regex("[^a-z]"), "")
                val k = keys.firstOrNull { n.startsWith(it) } ?: continue
                val logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
                if (logo.isNotBlank()) outMap.putIfAbsent(k, logo)
            }
            get(base + path + "/scoreboard")?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf { it.isNotBlank() }
                ?.let { outMap[if (path.startsWith("rugby")) "rugbyleague" else "league"] = it }
        }
        if (outMap["southafrica"] == null) runCatching { Sports.nationLogos() }.getOrNull()?.let { m -> m["southafrica"]?.let { outMap["southafrica"] = it }; m["england"]?.let { outMap["england"] = it } }
        // Seed Nebula's own logo cache on disk, then load them the normal way.
        val dir = File(ctx.cacheDir, "logos").apply { mkdirs() }
        for (u in outMap.values) {
            val f = File(dir, java.security.MessageDigest.getInstance("SHA-1").digest(u.toByteArray()).joinToString("") { "%02x".format(it) } + ".png")
            bytes(u)?.let { f.writeBytes(it) }
            println("Film: logo $u -> ${Logos.get(ctx, u) != null}")
        }
        println("Film logos: $outMap")
        return outMap
    }
}
