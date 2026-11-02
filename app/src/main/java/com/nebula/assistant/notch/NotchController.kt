package com.nebula.assistant.notch

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
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
import com.nebula.assistant.ui.MainActivity

/**
 * Hosts the Nebula Notch: an overlay window centred on the camera hole, drawn by the
 * accessibility service (so it needs no extra background service). Watches volume and
 * unlock events itself; music, messages and calls arrive via [NotchBus].
 */
class NotchController(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val prefs = NebulaPrefs(service)
    private val main = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private val owner = OverlayOwner()

    /** Transient states driven from here. */
    var volume by mutableStateOf<Pair<Float, Boolean>?>(null)   // level 0..1, muted
        private set
    var unlock by mutableStateOf<UnlockPhase?>(null)
        private set

    enum class UnlockPhase { SCANNING, SUCCESS }

    private val hideVolume = Runnable { volume = null }
    private val endUnlock = Runnable { unlock = null }
    private var lastVol = -1

    private val volumeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshVolume(fromKey = false)
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
        val cv = ComposeView(service).apply {
            setViewTreeLifecycleOwner(owner); setViewTreeSavedStateRegistryOwner(owner); setViewTreeViewModelStoreOwner(owner)
            setContent { NotchUi(this@NotchController) }
        }
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = cutoutOffsetX(); y = 0
            title = "Nebula Notch"
        }
        runCatching { wm.addView(cv, lp); view = cv }
        service.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        ContextCompat.registerReceiver(service, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED)
        lastVol = audio.getStreamVolume(stream())
    }

    fun stop() {
        runCatching { service.contentResolver.unregisterContentObserver(volumeObserver) }
        runCatching { service.unregisterReceiver(screenReceiver) }
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        owner.stop()
    }

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

    val notchPrefs get() = prefs

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
