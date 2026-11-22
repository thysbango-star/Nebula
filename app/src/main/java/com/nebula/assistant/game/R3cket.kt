package com.nebula.assistant.game

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.graphics.PointF
import android.os.BatteryManager
import com.nebula.assistant.data.NebulaPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * R3cket — Nebula's game mode. Detects games, keeps "Hey Nebula" to a small bar, silences
 * pop-ups, keeps the screen awake, and runs an auto-clicker through the accessibility service.
 */
object R3cket {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _inGame = MutableStateFlow(false)
    private val _game = MutableStateFlow("")
    val inGame: StateFlow<Boolean> = _inGame.asStateFlow()
    val gameName: StateFlow<String> = _game.asStateFlow()

    // ---------------------------------------------------------------- detection

    private val KNOWN = listOf("com.supercell.", "com.tencent.ig", "com.pubg.", "com.activision.", "com.miHoYo.", "com.mihoyo.",
        "com.HoYoverse.", "com.roblox.", "com.mojang.", "com.king.", "com.garena.", "com.epicgames.", "com.ea.gp.", "com.gameloft.",
        "com.innersloth.", "com.dts.freefire", "com.riotgames.", "com.netease.", "com.nianticlabs.", "com.kiloo.", "com.outfit7.")

    fun onForeground(context: Context, pkg: String) {
        val prefs = NebulaPrefs(context)
        val game = prefs.r3cketEnabled && pkg.isNotBlank() && isGame(context, pkg, prefs)
        if (game != _inGame.value || (game && pkg != lastPkg)) {
            val was = _inGame.value
            _inGame.value = game
            _game.value = if (game) label(context, pkg) else ""
            if (game) enterGame(context.applicationContext, pkg, _game.value, prefs)
            else { stopClicker(); if (was) exitGame(context.applicationContext) }
        }
        lastPkg = pkg
    }
    private var lastPkg = ""
    private val engineCache = HashMap<String, Boolean>()

    fun isGame(context: Context, pkg: String, prefs: NebulaPrefs = NebulaPrefs(context)): Boolean {
        if (pkg in prefs.gamePackages) return true
        if (pkg in prefs.notGames) return false
        if (KNOWN.any { pkg.startsWith(it) }) return true
        return runCatching {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@runCatching false
            ai.category == ApplicationInfo.CATEGORY_GAME || @Suppress("DEPRECATION") (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                engineCache.getOrPut(pkg) { usesGameEngine(ai) }
        }.getOrDefault(false)
    }

    /** Most games don't label themselves, but they all ship a game engine: look for it inside the app. */
    private val ENGINES = listOf("libunity.so", "libil2cpp.so", "libUE4.so", "libUnreal.so", "libcocos2dcpp.so", "libcocos2djs.so",
        "libcocos.so", "libgodot_android.so", "libgame.so", "libmain.so", "libgdx.so", "libyoyo.so", "libsolarengine.so", "libfmod.so")
    private fun usesGameEngine(ai: ApplicationInfo): Boolean = runCatching {
        ai.nativeLibraryDir?.let { java.io.File(it).list()?.any { f -> f in ENGINES } }?.let { if (it) return@runCatching true }
        val apks = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
        apks.any { path -> java.util.zip.ZipFile(path).use { z -> z.entries().asSequence().any { e -> e.name.startsWith("lib/") && e.name.substringAfterLast('/') in ENGINES } } }
    }.getOrDefault(false)

    // ---------------------------------------------------------------- the boost

    /** What R3cket did for this game session (shown in the Notch). */
    data class Session(val game: String, val pkg: String, val startedAt: Long = System.currentTimeMillis(),
                       val freedMb: Int = 0, val closedApps: Int = 0, val dnd: Boolean = false, val crosshair: Boolean = false,
                       val boosting: Boolean = true)
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val _ping = MutableStateFlow<Int?>(null)
    /** Network latency in ms (TCP connect to a nearby server), refreshed every few seconds in game. */
    val ping: StateFlow<Int?> = _ping.asStateFlow()
    /** Opens R3cket's panel in the Notch. */
    val panel = MutableStateFlow(false)
    private var pingJob: Job? = null
    private var prevFilter: Int? = null

    private fun enterGame(c: Context, pkg: String, name: String, prefs: NebulaPrefs) {
        _session.value = Session(name, pkg)
        panel.value = false
        scope.launch {
            // 1. Free memory: ask Android to close apps sitting in the background.
            var freed = 0; var closed = 0
            if (prefs.r3cketBoost) {
                val am = c.getSystemService(android.app.ActivityManager::class.java)
                val before = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val pm = c.packageManager
                    val launcher = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
                    val keep = setOf(pkg, c.packageName, launcher, "com.spotify.music", "com.google.android.apps.youtube.music") +
                        (com.nebula.assistant.notch.NotchBus.media.value?.pkg?.let { setOf(it) } ?: emptySet())
                    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                        .map { it.activityInfo.packageName }.distinct().filter { it !in keep }
                        .forEach { p -> runCatching { am.killBackgroundProcesses(p); closed++ } }
                }
                delay(900)
                val after = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
                freed = ((after - before) / 1_048_576L).toInt().coerceAtLeast(0)
            }
            // 2. Do not disturb (only with permission), restored when you leave the game.
            var dnd = false
            if (prefs.r3cketDnd) dnd = setDnd(c, true)
            _session.value = _session.value?.copy(freedMb = freed, closedApps = closed, dnd = dnd, boosting = false, crosshair = prefs.r3cketCrosshair)
            com.nebula.assistant.audio.Sounds.play(c, com.nebula.assistant.audio.NebulaSound.SOFT)
        }
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) { while (isActive) { _ping.value = measurePing(); delay(4000) } }
    }

    private fun exitGame(c: Context) {
        pingJob?.cancel(); pingJob = null; _ping.value = null
        if (_session.value?.dnd == true) setDnd(c, false)
        _session.value = null; panel.value = false
    }

    private fun measurePing(): Int? = runCatching {
        val t = System.nanoTime()
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("1.1.1.1", 443), 1500) }
        ((System.nanoTime() - t) / 1_000_000L).toInt()
    }.getOrNull()

    /** Turns Do Not Disturb on/off. Needs "Do not disturb access" for Nebula. */
    fun setDnd(c: Context, on: Boolean): Boolean {
        val nm = c.getSystemService(android.app.NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return false
        return runCatching {
            if (on) { prevFilter = nm.currentInterruptionFilter; nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
            else nm.setInterruptionFilter(prevFilter ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
            true
        }.getOrDefault(false)
    }
    fun dndAllowed(c: Context) = c.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun toggleDnd(c: Context) {
        val s = _session.value ?: return
        if (!dndAllowed(c)) {
            runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val next = !s.dnd
        if (setDnd(c, next)) _session.value = s.copy(dnd = next)
    }
    fun toggleCrosshair(c: Context) {
        val s = _session.value ?: return
        val on = !s.crosshair
        NebulaPrefs(c).r3cketCrosshair = on
        _session.value = s.copy(crosshair = on)
    }
    /** Screenshot without leaving the game. */
    fun screenshot(): Boolean = service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) == true

    private fun label(c: Context, pkg: String) = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Battery level/temperature for the R3cket bar. */
    fun battery(context: Context): Pair<Int, Float> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        return level to temp
    }

    // ---------------------------------------------------------------- auto-clicker

    data class Clicker(val points: List<PointF> = emptyList(), val running: Boolean = false, val editing: Boolean = false,
                       val intervalMs: Int = 150, val clicks: Long = 0)

    private val _clicker = MutableStateFlow(Clicker())
    val clicker: StateFlow<Clicker> = _clicker.asStateFlow()
    private var clickJob: Job? = null
    var service: AccessibilityService? = null

    fun setEditing(on: Boolean) { _clicker.value = _clicker.value.copy(editing = on, running = if (on) false else _clicker.value.running); if (on) clickJob?.cancel() }
    fun addPoint(p: PointF) { _clicker.value = _clicker.value.copy(points = (_clicker.value.points + p).takeLast(8)) }
    fun movePoint(i: Int, p: PointF) { _clicker.value = _clicker.value.copy(points = _clicker.value.points.mapIndexed { k, o -> if (k == i) p else o }) }
    fun clearPoints() { stopClicker(); _clicker.value = _clicker.value.copy(points = emptyList()) }
    fun setInterval(context: Context, ms: Int) {
        val v = ms.coerceIn(30, 5000); NebulaPrefs(context).clickInterval = v
        _clicker.value = _clicker.value.copy(intervalMs = v)
    }

    /** Starts tapping the saved points in order, forever, until stopped. Returns a message for Nebula to say. */
    fun startClicker(context: Context, centerIfEmpty: Boolean = true): String {
        val svc = service ?: return "Auto-click needs Screen control switched on."
        var c = _clicker.value.copy(intervalMs = NebulaPrefs(context).clickInterval)
        if (c.points.isEmpty()) {
            if (!centerIfEmpty) return "Add a tap point first."
            val dm = context.resources.displayMetrics
            c = c.copy(points = listOf(PointF(dm.widthPixels / 2f, dm.heightPixels / 2f)))
        }
        _clicker.value = c.copy(running = true, editing = false, clicks = 0)
        clickJob?.cancel()
        clickJob = scope.launch {
            var i = 0
            while (isActive && _clicker.value.running) {
                val pts = _clicker.value.points
                if (pts.isEmpty()) break
                val p = pts[i % pts.size]
                if (tap(svc, p)) _clicker.value = _clicker.value.copy(clicks = _clicker.value.clicks + 1)
                i++
                delay(_clicker.value.intervalMs.toLong())
            }
        }
        return "Auto-clicking ${c.points.size} point${if (c.points.size > 1) "s" else ""} every ${c.intervalMs} milliseconds. Say stop clicking to stop."
    }

    fun stopClicker() {
        clickJob?.cancel(); clickJob = null
        _clicker.value = _clicker.value.copy(running = false)
    }

    private suspend fun tap(svc: AccessibilityService, p: PointF): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(p.x, p.y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 35)).build()
        val ok = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }
}
