package com.nebula.assistant.notch

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.game.ClickerOverlay
import com.nebula.assistant.game.R3cket
import com.nebula.assistant.ui.MainActivity
import com.nebula.assistant.voice.GlowOverlay
import com.nebula.assistant.voice.VoiceState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Hosts the Nebula Notch (one island for everything — including "Hey Nebula"), the full-screen
 * voice glow, and R3cket's tap-point markers. All are accessibility overlays, so no extra
 * permission or background service is needed.
 *
 * Smoothness: the island window is never resized frame-by-frame. It jumps to a roomy size the
 * moment a bigger view is coming, lets the island animate inside it, then shrinks to fit once
 * the animation has settled.
 */
class NotchController(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val prefs = NebulaPrefs(service)
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var view: ComposeView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var glowView: ComposeView? = null
    private var clickView: ComposeView? = null
    private var clickTouchable: Boolean? = null
    private val owner = OverlayOwner()

    /** Transient states driven from here. */
    var volume by mutableStateOf<Pair<Float, Boolean>?>(null)   // level 0..1, muted
        private set
    var unlock by mutableStateOf<UnlockPhase?>(null)
        private set

    data class Layout(val scale: Float, val height: Float, val x: Float, val y: Float)
    /** Live size/position from Settings — sliders move the island instantly. */
    var layout by mutableStateOf(readLayout())
        private set
    private fun readLayout() = Layout(prefs.notchScale, prefs.notchHeight, prefs.notchX, prefs.notchY)

    enum class UnlockPhase { SCANNING, SUCCESS }

    private val hideVolume = Runnable { volume = null }
    private val endUnlock = Runnable { unlock = null }
    private var lastVol = -1

    private val volumeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshVolume(fromKey = false)
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k != null && k.startsWith("notch_")) main.post {
            layout = readLayout()
            lp?.let { p -> p.x = cutoutOffsetX() + dp(layout.x); p.y = dp(layout.y); update() }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!prefs.notchEnabled || !prefs.notchUnlock) return
            val km = service.getSystemService(KeyguardManager::class.java)
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> if (km.isKeyguardLocked) {
                    unlock = UnlockPhase.SCANNING
                    main.removeCallbacks(endUnlock); main.postDelayed(endUnlock, 8000) // give up quietly
                }
                // The real unlock (face, fingerprint or PIN) has happened — show the tick.
                Intent.ACTION_USER_PRESENT -> {
                    unlock = UnlockPhase.SUCCESS
                    main.removeCallbacks(endUnlock); main.postDelayed(endUnlock, 1300)
                }
                Intent.ACTION_SCREEN_OFF -> { main.removeCallbacks(endUnlock); unlock = null }
            }
        }
    }

    fun start() {
        if (view != null) return
        owner.start()
        // Layers, bottom to top: glow, R3cket points, island (so the island is never covered).
        val gp = fullScreen(false).apply { title = "Nebula Glow" }
        glowView = compose { GlowOverlay() }.also { v -> v.visibility = View.GONE; glowLp = gp; runCatching { wm.addView(v, gp) } }
        val kp = fullScreen(false).apply { title = "R3cket points" }
        clickView = compose { ClickerOverlay(service) }.also { v -> v.visibility = View.GONE; clickLp = kp; runCatching { wm.addView(v, kp) } }
        val cv = compose { NotchUi(this@NotchController) }
        val p = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = cutoutOffsetX() + dp(layout.x); y = dp(layout.y)
            title = "Nebula Notch"
        }
        runCatching { wm.addView(cv, p); view = cv; lp = p }
        service.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        ContextCompat.registerReceiver(service, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED)
        service.getSharedPreferences("nebula", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
        lastVol = audio.getStreamVolume(stream())

        // Full-screen glow while Nebula is listening (not in games).
        scope.launch {
            VoiceState.session.collect { s -> if (s.active && !s.gaming) showGlow() else if (!s.active) hideGlowSoon() }
        }
        // R3cket: tap-point markers, and keep the screen awake while gaming.
        scope.launch {
            combine(R3cket.clicker, R3cket.inGame) { c, g -> c to g }.collect { (c, g) ->
                when {
                    c.editing -> showMarkers(touchable = true)
                    c.running && g -> showMarkers(touchable = false)
                    else -> hideMarkers()
                }
                setKeepAwake(g && prefs.r3cketAwake)
            }
        }
    }

    fun stop() {
        scope.cancel()
        runCatching { service.contentResolver.unregisterContentObserver(volumeObserver) }
        runCatching { service.unregisterReceiver(screenReceiver) }
        runCatching { service.getSharedPreferences("nebula", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener) }
        listOfNotNull(view, glowView, clickView).forEach { runCatching { wm.removeView(it) } }
        view = null; glowView = null; clickView = null; lp = null
        owner.stop()
    }

    // ------------------------------------------------------------------ island window sizing

    private var shrinkTo: Pair<Int, Int>? = null
    private val shrink = Runnable { shrinkTo?.let { (w, h) -> setSize(w, h) } }

    /** Make room right away for a view of up to [w]×[h] px (never shrinks). */
    fun reserve(w: Int, h: Int) {
        val p = lp ?: return
        main.removeCallbacks(shrink)
        val nw = maxOf(p.width, w); val nh = maxOf(p.height, h)
        if (nw != p.width || nh != p.height) setSize(nw, nh)
    }

    /** Once the island has settled at [w]×[h] px, trim the window to it so it never blocks touches. */
    fun fit(w: Int, h: Int, afterMs: Long = 420) {
        shrinkTo = w.coerceAtLeast(1) to h.coerceAtLeast(1)
        main.removeCallbacks(shrink); main.postDelayed(shrink, afterMs)
    }

    private fun setSize(w: Int, h: Int) {
        val p = lp ?: return
        if (p.width == w && p.height == h) return
        p.width = w; p.height = h; update()
    }

    private fun update() { val v = view ?: return; lp?.let { runCatching { wm.updateViewLayout(v, it) } } }

    private var keepAwake = false
    private fun setKeepAwake(on: Boolean) {
        val p = lp ?: return
        if (on == keepAwake) return
        keepAwake = on
        p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        update()
    }

    // ------------------------------------------------------------------ glow + markers

    private var glowLp: WindowManager.LayoutParams? = null
    private var clickLp: WindowManager.LayoutParams? = null
    private val removeGlow = Runnable { glowView?.visibility = View.GONE }

    private fun showGlow() {
        main.removeCallbacks(removeGlow)
        glowView?.visibility = View.VISIBLE
    }
    private fun hideGlowSoon() { main.removeCallbacks(removeGlow); main.postDelayed(removeGlow, 900) }

    private fun showMarkers(touchable: Boolean) {
        val v = clickView ?: return
        v.visibility = View.VISIBLE
        setTouchable(touchable)
    }
    private fun hideMarkers() { clickView?.visibility = View.GONE; setTouchable(false) }

    private fun setTouchable(on: Boolean) {
        val v = clickView ?: return; val p = clickLp ?: return
        if (clickTouchable == on) return
        clickTouchable = on
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun fullScreen(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun compose(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(service).apply {
        setViewTreeLifecycleOwner(owner); setViewTreeSavedStateRegistryOwner(owner); setViewTreeViewModelStoreOwner(owner)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        setContent(content)
    }

    // ------------------------------------------------------------------ volume

    fun onVolumeKey() = main.postDelayed({ refreshVolume(fromKey = true) }, 60)

    private fun stream() = if (audio.isMusicActive) AudioManager.STREAM_MUSIC else AudioManager.STREAM_RING

    private fun refreshVolume(fromKey: Boolean) {
        if (!prefs.notchEnabled || !prefs.notchVolume) return
        val s = stream()
        val v = audio.getStreamVolume(s)
        val max = audio.getStreamMaxVolume(s).coerceAtLeast(1)
        if (!fromKey && v == lastVol) return
        lastVol = v
        volume = v.toFloat() / max to (v == 0)
        main.removeCallbacks(hideVolume); main.postDelayed(hideVolume, 1700)
    }

    // ------------------------------------------------------------------ geometry & actions

    private fun dp(v: Float) = (v * service.resources.displayMetrics.density).toInt()

    /** Horizontal offset so the island sits exactly over the camera hole. */
    private fun cutoutOffsetX(): Int {
        if (Build.VERSION.SDK_INT < 30) return 0
        val m = wm.currentWindowMetrics
        val cut = m.windowInsets.displayCutout?.boundingRects?.minByOrNull { it.top } ?: return 0
        return cut.centerX() - m.bounds.width() / 2
    }

    fun cutoutHeightPx(): Int {
        if (Build.VERSION.SDK_INT < 30) return 0
        val m = wm.currentWindowMetrics
        return m.windowInsets.displayCutout?.boundingRects?.minByOrNull { it.top }?.let { it.bottom } ?: 0
    }

    fun openApp(tab: String? = null, taskId: String? = null) {
        service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_TAB, tab).putExtra(MainActivity.EXTRA_TASK, taskId))
    }

    /** Tap-to-talk from the island (goes through an invisible activity so the mic may start). */
    fun talk() {
        service.startActivity(Intent(service, com.nebula.assistant.ui.TalkActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    val notchPrefs get() = prefs
    val context: Context get() = service

    /** Minimal lifecycle so Compose can run inside a service window. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val ssrc = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = ssrc.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
        fun start() {
            ssrc.performRestore(Bundle())
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun stop() { registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY); store.clear() }
    }
}
