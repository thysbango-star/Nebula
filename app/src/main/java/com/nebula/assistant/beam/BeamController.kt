package com.nebula.assistant.beam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.nebula.assistant.data.NebulaPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nebula Beam: double-tap the Notch → nearby Nebula devices (real ones, found on your Wi-Fi) → choose what to send.
 *
 * Routes, best first, and only the ones that really work:
 *  - Nebula → Nebula: discovered with mDNS, receiver says yes or no, encrypted transfer with real progress.
 *  - Quick Share & apps: Android's own share sheet. Quick Share — including Quick Share → AirDrop on phones that
 *    support it — runs there; Nebula only hands the files over and never claims to control it.
 *  - Beam Link: a QR code to a one-time page served by this phone on the same Wi-Fi (no app, no cloud).
 */
object BeamController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow<BeamUi>(BeamUi.Idle)
    val ui: StateFlow<BeamUi> = _ui
    private lateinit var app: Context
    private lateinit var discovery: BeamDiscovery
    private var receiver: BeamTransport.Receiver? = null
    private var link: BeamLink? = null
    private var scanJob: Job? = null
    private var sendJob: Job? = null
    private var pendingOffer: CompletableDeferred<Boolean>? = null
    private var lastTarget: BeamTarget? = null
    private var lastItems: List<BeamItem> = emptyList()
    private var open = false
    private val prefs get() = NebulaPrefs(app)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        discovery = BeamDiscovery(app)
        if (prefs.beamVisibleUntil > System.currentTimeMillis()) makeVisible(((prefs.beamVisibleUntil - System.currentTimeMillis()) / 60_000).toInt().coerceAtLeast(1))
    }

    val visible get() = ::app.isInitialized && prefs.beamVisibleUntil > System.currentTimeMillis()

    /** Double-tap / tile / button: open Beam and start looking. Also findable by others while it's open. */
    fun open() {
        if (!::app.isInitialized) return
        if (_ui.value !is BeamUi.Idle && _ui.value !is BeamUi.Scanning) return
        open = true
        buzz(20)
        startReceiver()
        discovery.startScan()
        scanJob?.cancel()
        scanJob = scope.launch {
            val until = System.currentTimeMillis() + prefs.beamScanSeconds * 1000L
            discovery.devices.collect { list ->
                if (_ui.value is BeamUi.Idle || _ui.value is BeamUi.Scanning)
                    _ui.value = BeamUi.Scanning(list, true, discovery.wifiOn(), if (System.currentTimeMillis() > until && list.isEmpty()) "No Nebula devices found yet" else "")
            }
        }
        _ui.value = BeamUi.Scanning(emptyList(), true, discovery.wifiOn())
        // Stop scanning after the scan window to save battery (devices already found stay).
        scope.launch { delay(prefs.beamScanSeconds * 1000L); if (open) { discovery.stopScan(); (_ui.value as? BeamUi.Scanning)?.let { if (it.devices.isEmpty()) _ui.value = it.copy(note = "No Nebula devices found — try Quick Share or Beam Link") } } }
    }

    fun rescan() { discovery.stopScan(); scanJob?.cancel(); _ui.value = BeamUi.Idle; open() }

    fun close() {
        open = false
        scanJob?.cancel(); sendJob?.cancel()
        discovery.stopScan()
        stopLink()
        pendingOffer?.complete(false); pendingOffer = null
        if (!visible) stopReceiver()
        _ui.value = BeamUi.Idle
    }

    /** A target was tapped: pick what to send (system pickers need an Activity). */
    fun pick(target: BeamTarget) {
        lastTarget = target
        val t = when (target) { is BeamTarget.Nebula -> "nebula:${target.device.id}"; BeamTarget.System -> "system"; BeamTarget.Link -> "link" }
        app.startActivity(Intent(app, BeamActivity::class.java).putExtra("target", t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun target(key: String): BeamTarget? = when {
        key == "system" -> BeamTarget.System
        key == "link" -> BeamTarget.Link
        key.startsWith("nebula:") -> discovery.devices.value.firstOrNull { it.id == key.removePrefix("nebula:") }?.let { BeamTarget.Nebula(it) }
        else -> null
    }

    /** The files/text are chosen: send them by the chosen route. */
    fun send(target: BeamTarget, items: List<BeamItem>) {
        if (items.isEmpty()) return
        lastTarget = target; lastItems = items
        discovery.stopScan(); scanJob?.cancel()
        when (target) {
            BeamTarget.System -> { _ui.value = BeamUi.Sent("Android sharing", items.size, "system"); record("Quick Share & apps", items, "handed off"); later(4000) { if (_ui.value is BeamUi.Sent) close() } }
            BeamTarget.Link -> startLink(items)
            is BeamTarget.Nebula -> sendNebula(target.device, items)
        }
    }

    private fun sendNebula(d: BeamDevice, items: List<BeamItem>) {
        sendJob?.cancel()
        _ui.value = BeamUi.Connecting(d.name)
        sendJob = scope.launch {
            var code = ""
            val r = BeamTransport.send(app, d, discovery.deviceName(), if (Build.MANUFACTURER.equals("samsung", true)) "samsung" else "android", items,
                onWaiting = { c -> code = c.removePrefix("…"); _ui.value = if (c.isEmpty() || c.startsWith("…")) BeamUi.Waiting(d.name, code) else BeamUi.Sending(d.name, 0f, items.size, code) },
                onProgress = { p -> _ui.value = BeamUi.Sending(d.name, p, items.size, code) })
            if (r.ok) {
                buzz(40); _ui.value = BeamUi.Sent(d.name, items.size, "nebula"); record(d.name, items, "sent")
                delay(4500); if (_ui.value is BeamUi.Sent) close()
            } else {
                _ui.value = BeamUi.Failed(r.reason.ifBlank { "Connection lost" }, canLink = prefs.beamLinkFallback, canRetry = r.reason != "Transfer declined")
                record(d.name, items, r.reason.ifBlank { "failed" })
            }
        }
    }

    private var hotspot: android.net.wifi.WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * Beam Link. What used to go wrong: the QR could carry an address the other phone couldn't reach (a guessed
     * interface), and the files were read straight from the picker's temporary permission, which Android can take
     * back once the picker has closed. Now: the files are copied into Nebula first, the server listens on every
     * interface with the real Wi-Fi address in the QR, and the phone asks its own server for the page through that
     * address before showing the code — if this network blocks it, you're offered the phone's own hotspot instead.
     */
    private fun startLink(items: List<BeamItem>, hotspotMode: Boolean = false) {
        link?.stop(); link = null
        if (!hotspotMode && !discovery.wifiOn()) { _ui.value = BeamUi.Failed("You're not on Wi-Fi — use the phone's own hotspot instead", canLink = false, canRetry = false, canHotspot = true); return }
        _ui.value = BeamUi.Preparing(if (hotspotMode) "Starting your hotspot…" else "Getting the link ready…")
        sendJob?.cancel()
        sendJob = scope.launch {
            val local = withContext(Dispatchers.IO) { copyForLink(items) }
            val host = if (hotspotMode) BeamLink.hotspotIp(app) else null
            if (hotspotMode && host == null) { _ui.value = BeamUi.Failed("The hotspot started but has no address yet — try again", canLink = false, canRetry = true, canHotspot = true); return@launch }
            val l = BeamLink(app, local, discovery.deviceName(), onDownload = { n -> scope.launch { (_ui.value as? BeamUi.Link)?.let { _ui.value = it.copy(downloads = n) } } })
            val url = withContext(Dispatchers.IO) { runCatching { l.start(host) }.getOrNull() }
            if (url == null) { _ui.value = BeamUi.Failed("Couldn't start Beam Link on this network", canLink = false, canRetry = true, canHotspot = !hotspotMode); return@launch }
            val reachable = withContext(Dispatchers.IO) { l.selfCheck() }
            if (!reachable && !hotspotMode) {
                l.stop()
                _ui.value = BeamUi.Failed("This Wi-Fi doesn't let devices reach each other (common on guest and public networks)", canLink = false, canRetry = true, canHotspot = true)
                return@launch
            }
            link = l
            val r = hotspot
            val (ssid, pass) = if (hotspotMode && r != null) hotspotCredentials(r) else "" to ""
            val joinQr = if (hotspotMode && ssid.isNotEmpty()) BeamLink.qr("WIFI:T:WPA;S:${wifiEsc(ssid)};P:${wifiEsc(pass)};;") else null
            _ui.value = BeamUi.Link(url, BeamLink.qr(url), l.expiresAt, items.size, 0, items.map { it.name }, joinQr, ssid, pass, if (joinQr != null) 0 else 1)
            record(if (hotspotMode) "Beam Link (hotspot)" else "Beam Link", items, "link")
            delay(l.expiresAt - System.currentTimeMillis())
            if (link === l) { stopLink(); if (_ui.value is BeamUi.Link) _ui.value = BeamUi.Failed("Link expired", canLink = true, canRetry = false) }
        }
    }

    /** Copies picked files into Nebula's cache so the link can always read them (the picker's permission can lapse). */
    private fun copyForLink(items: List<BeamItem>): List<BeamItem> {
        val dir = java.io.File(app.cacheDir, "beamlink").apply { deleteRecursively(); mkdirs() }
        return items.mapIndexed { i, it ->
            val u = it.uri ?: return@mapIndexed it
            runCatching {
                val f = java.io.File(dir, "$i-" + it.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80))
                app.contentResolver.openInputStream(u)?.use { inp -> f.outputStream().use { out -> inp.copyTo(out, 256 * 1024) } } ?: return@mapIndexed it
                it.copy(uri = Uri.fromFile(f), size = f.length())
            }.getOrDefault(it)
        }
    }

    private fun stopLink() {
        link?.stop(); link = null
        runCatching { hotspot?.close() }; hotspot = null
        runCatching { java.io.File(app.cacheDir, "beamlink").deleteRecursively() }
    }

    /** Hotspot mode: Android makes a temporary Wi-Fi just for this; the other device joins it from the first QR. */
    @android.annotation.SuppressLint("MissingPermission")
    fun useHotspot() {
        if (lastItems.isEmpty()) return
        val needs = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.NEARBY_WIFI_DEVICES else android.Manifest.permission.ACCESS_FINE_LOCATION
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, needs) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            app.startActivity(Intent(app, BeamActivity::class.java).putExtra("action", "hotspot").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        link?.stop(); link = null
        runCatching { hotspot?.close() }; hotspot = null
        _ui.value = BeamUi.Preparing("Starting your hotspot…")
        val wm = app.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        runCatching {
            wm.startLocalOnlyHotspot(object : android.net.wifi.WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: android.net.wifi.WifiManager.LocalOnlyHotspotReservation) {
                    hotspot = reservation
                    scope.launch { delay(600); startLink(lastItems, hotspotMode = true) }
                }
                override fun onFailed(reason: Int) {
                    _ui.value = BeamUi.Failed(if (reason == ERROR_TETHERING_DISALLOWED) "Hotspots are turned off on this phone" else "Couldn't start a hotspot (turn off your normal hotspot and try again)", canLink = false, canRetry = false)
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { _ui.value = BeamUi.Failed("Couldn't start a hotspot: ${it.message ?: "not allowed"}", canLink = false, canRetry = false) }
    }

    private fun hotspotCredentials(r: android.net.wifi.WifiManager.LocalOnlyHotspotReservation): Pair<String, String> = runCatching {
        if (Build.VERSION.SDK_INT >= 30) { val c = r.softApConfiguration; (if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else @Suppress("DEPRECATION") c.ssid).orEmpty() to c.passphrase.orEmpty() }
        else { @Suppress("DEPRECATION") val c = r.wifiConfiguration; c?.SSID.orEmpty().trim('"') to c?.preSharedKey.orEmpty().trim('"') }
    }.getOrDefault("" to "")

    private fun wifiEsc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")

    /** Hotspot mode: they've joined — show the link's QR. */
    fun linkStep(step: Int) { (_ui.value as? BeamUi.Link)?.let { _ui.value = it.copy(step = step) } }

    fun retry() { val t = lastTarget ?: return; if (lastItems.isNotEmpty()) send(t, lastItems) }
    fun useLink() { if (lastItems.isNotEmpty()) send(BeamTarget.Link, lastItems) }

    // ------------------------------------------------------------------ receiving

    /** Be findable by Nebula devices nearby for [minutes] (then it switches itself off). */
    fun makeVisible(minutes: Int) {
        prefs.beamVisibleUntil = System.currentTimeMillis() + minutes * 60_000L
        startReceiver()
        scope.launch { delay(minutes * 60_000L); if (!visible) { if (!open) stopReceiver() } }
    }
    fun hide() { prefs.beamVisibleUntil = 0; if (!open) stopReceiver() }

    private fun startReceiver() {
        if (receiver != null) return
        val r = BeamTransport.Receiver(app,
            onOffer = { offer ->
                val d = CompletableDeferred<Boolean>(); pendingOffer = d
                scope.launch { buzz(60); _ui.value = BeamUi.Incoming(offer) }
                d.await()
            },
            onProgress = { from, p, n -> scope.launch { _ui.value = BeamUi.Receiving(from, p, n) } },
            onDone = { from, saved, text -> scope.launch {
                buzz(40); _ui.value = BeamUi.Received(from, saved.size, saved, text)
                record(from, saved.map { BeamItem(it, it.lastPathSegment ?: "file", 0, "") }, "received")
            } },
            onError = { e -> scope.launch { _ui.value = BeamUi.Failed(e, canLink = false, canRetry = false) } })
        val port = runCatching { r.start() }.getOrNull() ?: return
        receiver = r
        discovery.advertise(port)
    }
    private fun stopReceiver() { discovery.hide(); receiver?.stop(); receiver = null }

    fun accept() { pendingOffer?.complete(true); pendingOffer = null }
    fun decline() { pendingOffer?.complete(false); pendingOffer = null; close() }

    fun openReceived(u: Uri) {
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, app.contentResolver.getType(u)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ------------------------------------------------------------------ history (names and counts only — never the files)

    private fun record(to: String, items: List<BeamItem>, result: String) {
        val arr = runCatching { JSONArray(prefs.beamHistory) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("at", System.currentTimeMillis()).put("to", to).put("count", items.size).put("result", result))
        val keep = JSONArray(); for (i in maxOf(0, arr.length() - 30) until arr.length()) keep.put(arr.get(i))
        prefs.beamHistory = keep.toString()
    }

    private fun later(ms: Long, f: () -> Unit) { scope.launch { delay(ms); f() } }
    private fun buzz(ms: Long) {
        if (!prefs.beamHaptics) return
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createOneShot(ms, 150))
        }
    }
}
