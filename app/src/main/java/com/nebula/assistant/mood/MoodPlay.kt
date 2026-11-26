package com.nebula.assistant.mood

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.nebula.assistant.beam.BeamLink
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.notch.NotchBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** What the Mood PLAY panel in the notch shows. */
sealed class MoodUi {
    object Idle : MoodUi()
    data class Finding(val tvs: List<Tv>, val searching: Boolean, val wifi: Boolean, val url: String) : MoodUi()
    data class Opening(val tv: Tv, val asking: Boolean) : MoodUi()
    data class Manual(val url: String, val qr: Bitmap, val why: String) : MoodUi()
    data class Live(val tv: String, val url: String, val viewers: Int, val theme: String) : MoodUi()
}

/**
 * Mood PLAY: what's playing on the phone, on the TV — cover, title, artist, time — with the TV remote
 * controlling it. The phone serves the Mood PLAY screen over your Wi-Fi; Samsung and LG TVs get it opened
 * for them (after "Allow" on the TV the first time), any other TV or computer opens the short address.
 *
 * The music keeps playing wherever it was. "Sound on TV" hands the audio to the TV through Android's own
 * output picker (the TV as a Bluetooth speaker), if the TV can take Bluetooth audio.
 */
object MoodPlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context
    private lateinit var prefs: NebulaPrefs
    private val _ui = MutableStateFlow<MoodUi>(MoodUi.Idle)
    val ui: StateFlow<MoodUi> = _ui
    /** True while a TV is (or could be) showing Mood PLAY — the session outlives the panel. */
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live

    private var server: MoodServer? = null
    private var url = ""
    private var tvName = ""
    private var scanJob: Job? = null
    private var feedJob: Job? = null
    private var themeChanged = false
    private val tvs = LinkedHashMap<String, Tv>()

    fun init(ctx: Context) { if (!::app.isInitialized) { app = ctx.applicationContext; prefs = NebulaPrefs(app) } }

    /** The Mood PLAY button: start (or show) the session and look for TVs. */
    fun open() {
        if (!::app.isInitialized) return
        if (server != null && _live.value) { showLive(); return }
        if (!ensureServer()) return
        tvs.clear()
        find()
    }

    fun find() {
        val wifi = BeamLink.wifiIp(app) != null
        _ui.value = MoodUi.Finding(tvs.values.toList(), wifi, wifi, url)
        if (!wifi) return
        scanJob?.cancel()
        scanJob = scope.launch {
            runCatching {
                TvFinder(app).scan(5) { tv -> scope.launch { tvs[tv.id] = tv; (_ui.value as? MoodUi.Finding)?.let { _ui.value = it.copy(tvs = tvs.values.toList()) } } }
            }
            (_ui.value as? MoodUi.Finding)?.let { _ui.value = it.copy(searching = false) }
        }
    }

    private fun ensureServer(): Boolean {
        if (server != null) return true
        val ip = BeamLink.wifiIp(app) ?: run { _ui.value = MoodUi.Finding(emptyList(), false, false, ""); return false }
        val code = MoodServer.newCode()
        val s = MoodServer(app, code, ::stateJson, ::artBytes, ::command)
        val port = runCatching { s.start() }.getOrElse { _ui.value = MoodUi.Finding(emptyList(), false, false, ""); return false }
        server = s
        url = "http://$ip:$port/$code/"
        feedJob = scope.launch { // the TV follows the phone: every change in what's playing, straight away
            var last = ""
            NotchBus.media.collect { m ->
                val key = m?.let { "${it.title}|${it.artist}|${it.playing}|${it.position / 2000}|${it.duration}" } ?: ""
                if (key != last) { last = key; server?.push(stateJson()) }
            }
        }
        scope.launch { while (server != null) { delay(3000); val v = server?.viewers ?: 0; (_ui.value as? MoodUi.Live)?.let { if (it.viewers != v) _ui.value = it.copy(viewers = v) }; _live.value = server != null && (v > 0 || _ui.value is MoodUi.Live) } }
        return true
    }

    /** Pick a TV: open the screen on it (Samsung / LG), or show how to open it (everything else). */
    fun connect(tv: Tv) {
        scanJob?.cancel()
        tvName = tv.name
        if (tv.kind != "samsung" && tv.kind != "lg") { manual("Open this on ${tv.name}'s web browser"); return }
        _ui.value = MoodUi.Opening(tv, asking = false)
        scope.launch {
            val saved = runCatching { JSONObject(prefs.moodTvs).optString(tv.id).ifBlank { null } }.getOrNull()
            when (val r = TvLauncher.open(tv, url, saved) { scope.launch { (_ui.value as? MoodUi.Opening)?.let { _ui.value = it.copy(asking = true) } } }) {
                is TvLauncher.Result.Opened -> {
                    r.token?.let { t -> prefs.moodTvs = JSONObject(prefs.moodTvs).put(tv.id, t).toString() }
                    showLive()
                }
                TvLauncher.Result.Declined -> manual("${tv.name} said no — you can still open it yourself")
                is TvLauncher.Result.Failed -> manual("Couldn't open it on ${tv.name} (${r.why.take(60)}). Open it on the TV instead")
            }
        }
    }

    /** Any TV or computer: type the short address (or scan the QR) in its browser. */
    fun manual(why: String = "Open this in your TV's web browser") {
        if (!ensureServer()) return
        scanJob?.cancel()
        _ui.value = MoodUi.Manual(url, BeamLink.qr(url, 360), why)
        _live.value = true
    }

    /** "It's on the TV": the address was opened by hand. */
    fun manualDone() { if (tvName.isBlank()) tvName = "your TV"; showLive() }

    private fun showLive() { _live.value = true; _ui.value = MoodUi.Live(tvName.ifBlank { "your TV" }, url, server?.viewers ?: 0, prefs.moodTheme) }

    /** Close the panel; Mood PLAY keeps running on the TV until Stop. */
    fun hide() {
        scanJob?.cancel()
        if (!_live.value || (server?.viewers ?: 0) == 0 && _ui.value !is MoodUi.Live && _ui.value !is MoodUi.Manual) stop() else _ui.value = MoodUi.Idle
    }

    fun stop() {
        scanJob?.cancel(); feedJob?.cancel()
        server?.stop(); server = null; url = ""; tvName = ""
        _live.value = false; _ui.value = MoodUi.Idle
    }

    fun setTheme(t: String) {
        prefs.moodTheme = t; themeChanged = true
        server?.push(stateJson())
        (_ui.value as? MoodUi.Live)?.let { _ui.value = it.copy(theme = t) }
    }

    /** Hand the sound to the TV (or back): Android's own output picker, where the TV shows up as a Bluetooth speaker. */
    fun soundPicker(ctx: Context, pkg: String?) {
        val i = Intent("com.android.settings.panel.action.MEDIA_OUTPUT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg)
        runCatching { ctx.startActivity(i) }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    // ---------------------------------------------------------------- what the TV gets

    private fun compat(m: NotchBus.Media): MediaControllerCompat? = runCatching {
        m.controller?.sessionToken?.let { MediaControllerCompat(app, MediaSessionCompat.Token.fromToken(it)) }
    }.getOrNull()

    private fun deviceName(): String = runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    private var artKey = ""; private var artPng: ByteArray? = null
    private fun artVersion(m: NotchBus.Media): String? {
        val b = m.art ?: return null
        val k = "${m.title}|${m.artist}|${b.generationId}|${System.identityHashCode(b)}"
        if (k != artKey) {
            artKey = k
            artPng = runCatching { ByteArrayOutputStream().also { o -> Bitmap.createScaledBitmap(b, minOf(b.width, 600), minOf(b.height, 600), true).compress(Bitmap.CompressFormat.PNG, 100, o) }.toByteArray() }.getOrNull()
        }
        return Integer.toHexString(k.hashCode())
    }
    private fun artBytes(@Suppress("UNUSED_PARAMETER") q: String): ByteArray? = artPng

    private fun stateJson(): String {
        val m = NotchBus.media.value
        val j = JSONObject().put("now", System.currentTimeMillis()).put("phone", deviceName()).put("theme", prefs.moodTheme)
        if (themeChanged) { j.put("themeChanged", true); themeChanged = false }
        if (m != null && m.title.isNotBlank()) {
            val c = compat(m)
            val rep = c?.repeatMode ?: -1; val shf = c?.shuffleMode ?: -1
            val v = artVersion(m)
            j.put("media", JSONObject().put("title", m.title).put("artist", m.artist).put("playing", m.playing)
                .put("pos", m.position).put("dur", m.duration).put("at", m.updatedAt)
                .put("art", if (v != null) "${server?.code?.let { "/$it" } ?: ""}/art?v=$v" else "")
                .put("repeat", when (rep) { PlaybackStateCompat.REPEAT_MODE_NONE -> "off"; PlaybackStateCompat.REPEAT_MODE_ONE -> "one"; PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> "all"; else -> JSONObject.NULL })
                .put("shuffle", when (shf) { PlaybackStateCompat.SHUFFLE_MODE_NONE -> false; PlaybackStateCompat.SHUFFLE_MODE_ALL, PlaybackStateCompat.SHUFFLE_MODE_GROUP -> true; else -> JSONObject.NULL }))
        }
        return j.toString()
    }

    /** The TV remote's buttons, on the phone's real media session. */
    private fun command(c: String) = scope.launch {
        val m = NotchBus.media.value ?: return@launch
        val tc = m.controller?.transportControls
        when (c) {
            "toggle" -> if (m.playing) tc?.pause() else tc?.play()
            "next" -> tc?.skipToNext()
            "prev" -> tc?.skipToPrevious()
            "repeat" -> compat(m)?.let { cc -> cc.transportControls.setRepeatMode(when (cc.repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_NONE -> PlaybackStateCompat.REPEAT_MODE_ALL
                PlaybackStateCompat.REPEAT_MODE_ALL -> PlaybackStateCompat.REPEAT_MODE_ONE
                else -> PlaybackStateCompat.REPEAT_MODE_NONE }) }
            "shuffle" -> compat(m)?.let { cc -> cc.transportControls.setShuffleMode(if (cc.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_NONE) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE) }
        }
        delay(400); server?.push(stateJson())
    }

    val themes = listOf("auto" to "Auto", "waves" to "Waves", "shards" to "Shards", "arcs" to "Arcs", "glow" to "Glow", "vinyl" to "Vinyl", "dots" to "Dots")
}
