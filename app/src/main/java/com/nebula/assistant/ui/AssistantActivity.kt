package com.nebula.assistant.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.nebula.assistant.brain.BrainReply
import com.nebula.assistant.brain.GroqBrain
import com.nebula.assistant.brain.HybridBrain
import com.nebula.assistant.brain.LocalBrain
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.NebulaState
import com.nebula.assistant.ui.theme.NebulaTheme
import com.nebula.assistant.ui.vibrance.VibranceOverlay
import com.nebula.assistant.wake.Notifications
import com.nebula.assistant.wake.WakeWordService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hosts Vibrance over whatever app you're in (and over the lock screen): the screen edges
 * glow and the island grows from the camera. Hands the mic back to the wake word listener
 * when it closes.
 */
class AssistantActivity : ComponentActivity() {

    private lateinit var prefs: NebulaPrefs
    private lateinit var voice: VoiceController
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        immerse()

        NebulaState.panelOpen.value = true
        NotificationManagerCompat.from(this).cancel(Notifications.ID_TAP_TO_TALK)
        prefs = NebulaPrefs(this)

        voice = VoiceController(this, HybridBrain(LocalBrain(this), GroqBrain(prefs)), prefs)
        voice.onAction = ::perform

        val fromWake = intent.getBooleanExtra(EXTRA_FROM_WAKE, false)
        lifecycleScope.launch {
            if (!hasMic()) {
                voice.fail("Nebula needs microphone access. Open the Nebula app to allow it.")
                return@launch
            }
            if (!fromWake && prefs.wakeEnabled) {
                // Opened by hand: ask the wake listener to let go of the mic first.
                WakeWordService.send(this@AssistantActivity, WakeWordService.ACTION_PAUSE)
                delay(350)
            }
            voice.start()
        }

        setContent {
            NebulaTheme {
                VibranceOverlay(
                    voice = voice,
                    intensity = prefs.vibranceIntensity,
                    onClosed = ::close,
                    onOpenApp = ::openNebulaHome,
                    onOpenSource = ::openSource,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Went to the background (home button, another app opened): close and free the mic.
        if (!isChangingConfigurations) close()
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    private fun close() {
        if (closed) return
        closed = true
        voice.release()
        NebulaState.panelOpen.value = false
        if (prefs.wakeEnabled) WakeWordService.send(this, WakeWordService.ACTION_RESUME)
        if (!isFinishing) finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun openNebulaHome() {
        startActivity(android.content.Intent(this, MainActivity::class.java))
        close()
    }

    /** Opens a source web page in the browser. */
    private fun openSource(url: String) {
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        perform(BrainReply(text = voice.reply, launch = view, closePanel = true, needsUnlock = true))
    }

    /** Runs whatever the brain asked for: set a timer, open an app… */
    private fun perform(reply: BrainReply) {
        val target = reply.launch ?: return
        val go = {
            try {
                startActivity(target)
            } catch (e: ActivityNotFoundException) {
                voice.reply = "I couldn't find an app on this phone that can do that."
            } catch (e: SecurityException) {
                voice.reply = "Android blocked that action."
            }
            if (reply.closePanel) close()
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (reply.needsUnlock && km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { go() }
            })
        } else {
            go()
        }
    }

    /**
     * Full-screen and drawn right up to the camera hole, so the island can grow out of it.
     * The status bar hides while Vibrance is up (swipe down to peek), and the app behind
     * stays visible with only a whisper of dimming.
     */
    private fun immerse() {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        window.setDimAmount(0.14f)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_FROM_WAKE = "from_wake"
    }
}
