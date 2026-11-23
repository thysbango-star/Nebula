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
        link?.stop(); link = null
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

    private fun startLink(items: List<BeamItem>) {
        link?.stop()
        if (!discovery.wifiOn()) { _ui.value = BeamUi.Failed("Wi-Fi unavailable — Beam Link needs both devices on the same Wi-Fi", canLink = false, canRetry = true); return }
        val l = BeamLink(app, items, discovery.deviceName(), onDownload = { n -> (_ui.value as? BeamUi.Link)?.let { _ui.value = it.copy(downloads = n) } })
        val url = runCatching { l.start() }.getOrNull()
        if (url == null) { _ui.value = BeamUi.Failed("Couldn't start Beam Link on this network", canLink = false, canRetry = true); return }
        link = l
        _ui.value = BeamUi.Link(url, BeamLink.qr(url), l.expiresAt, items.size, 0, items.map { it.name })
        record("Beam Link", items, "link")
        scope.launch { delay(l.expiresAt - System.currentTimeMillis()); if (link === l) { l.stop(); link = null; if (_ui.value is BeamUi.Link) _ui.value = BeamUi.Failed("Link expired", canLink = true, canRetry = false) } }
    }

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
