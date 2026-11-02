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
            _inGame.value = game
            _game.value = if (game) label(context, pkg) else ""
            if (!game) stopClicker()
        }
        lastPkg = pkg
    }
    private var lastPkg = ""

    fun isGame(context: Context, pkg: String, prefs: NebulaPrefs = NebulaPrefs(context)): Boolean {
        if (pkg in prefs.gamePackages) return true
        if (KNOWN.any { pkg.startsWith(it) }) return true
        return runCatching {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            ai.category == ApplicationInfo.CATEGORY_GAME || @Suppress("DEPRECATION") (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        }.getOrDefault(false)
    }

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
