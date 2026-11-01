package com.nebula.assistant.wake

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nebula.assistant.R
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.NebulaState
import com.nebula.assistant.data.WakeState
import com.nebula.assistant.data.WakeStatus
import com.nebula.assistant.ui.AssistantActivity
import com.nebula.assistant.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Always-on "Hey Nebula" listener.
 *
 * Runs as a microphone foreground service (Android requires the ongoing notification).
 * When the wake word is heard it releases the mic, buzzes, and opens the assistant panel.
 * The panel sends ACTION_RESUME when it closes, and listening picks up again.
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: NebulaPrefs

    private var listenJob: Job? = null
    private var watchdog: Job? = null
    private var engine: WakeWordEngine? = null
    private var engineThreshold = Float.NaN
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = NebulaPrefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                if (!goForeground()) return START_NOT_STICKY
                if (listenJob?.isActive != true) restartListening(delayMs = 0)
            }
            ACTION_RESUME -> {
                if (!prefs.wakeEnabled) {
                    shutdown()
                } else {
                    if (!goForeground()) return START_NOT_STICKY
                    // Small gap so the speech recognizer has fully let go of the mic.
                    restartListening(delayMs = 400)
                }
            }
            ACTION_PAUSE -> {
                if (!inForeground) {
                    stopSelf()
                } else {
                    stopListening()
                    setState(WakeStatus.Paused, "Paused while Nebula is open")
                }
            }
            ACTION_RELOAD -> {
                // Sensitivity changed: rebuild the engine with the new threshold.
                if (inForeground && NebulaState.wake.value.status == WakeStatus.Listening) {
                    restartListening(delayMs = 300) // let the old mic session close first
                }
            }
            ACTION_STOP -> {
                prefs.wakeEnabled = false
                shutdown()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listenJob?.cancel()
        watchdog?.cancel()
        scope.cancel()
        engine?.close()
        engine = null
        NebulaState.micLevel.value = 0f
        if (NebulaState.wake.value.status != WakeStatus.Error) {
            NebulaState.wake.value = WakeState(WakeStatus.Off)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- listening

    private fun restartListening(delayMs: Long) {
        listenJob?.cancel()
        watchdog?.cancel()
        if (!hasMic()) {
            setState(WakeStatus.Error, "Nebula needs microphone access")
            shutdown(keepError = true)
            return
        }
        setState(WakeStatus.Loading, "Starting up…")
        listenJob = scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                val eng = withContext(Dispatchers.IO) { obtainEngine() }
                setState(WakeStatus.Listening)
                val heard = withContext(Dispatchers.IO) { listenLoop(eng) }
                if (heard) onWake()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Wake word listener failed", e)
                setState(WakeStatus.Error, e.message ?: "Listener stopped unexpectedly")
            }
        }
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        NebulaState.micLevel.value = 0f
    }

    @Synchronized
    private fun obtainEngine(): WakeWordEngine {
        val threshold = prefs.wakeThreshold
        engine?.let { if (engineThreshold == threshold) return it }
        engine?.close()
        return WakeWordEngine(applicationContext, threshold).also {
            engine = it
            engineThreshold = threshold
        }
    }

    /** Streams the mic into the engine until the wake word is heard (true) or the job is cancelled (false). */
    @SuppressLint("MissingPermission") // checked in restartListening()
    private suspend fun listenLoop(eng: WakeWordEngine): Boolean {
        val sr = WakeWordEngine.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sr,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, sr / 5 * 2), // ≥200 ms of headroom
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("The microphone is busy")
        }

        val chunk = ShortArray(sr / 10) // 100 ms
        val samples = FloatArray(chunk.size)
        eng.resetStream()
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n < 0) throw IllegalStateException("Microphone read error ($n)")
                if (n == 0) continue

                var sumSq = 0.0
                for (i in 0 until n) {
                    val s = chunk[i] / 32768f
                    samples[i] = s
                    sumSq += s * s
                }
                NebulaState.micLevel.value = levelFromRms(sqrt(sumSq / n))

                val input = if (n == samples.size) samples else samples.copyOf(n)
                if (eng.accept(input) != null) return true
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            NebulaState.micLevel.value = 0f
        }
        return false
    }

    /** Maps loudness to 0..1 (≈ -60 dBFS silence → 0, -10 dBFS loud speech → 1). */
    private fun levelFromRms(rms: Double): Float {
        val db = 20 * log10(rms + 1e-9)
        return ((db + 60) / 50).toFloat().coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- wake

    private fun onWake() {
        Log.i(TAG, "Hey Nebula detected")
        setState(WakeStatus.Paused, "Heard you")
        buzz()
        openPanel()

        // If the panel never appeared (blocked by the system), go back to listening.
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(10_000)
            if (!NebulaState.panelOpen.value && prefs.wakeEnabled &&
                NebulaState.wake.value.status == WakeStatus.Paused
            ) {
                cancelTapToTalk()
                restartListening(delayMs = 0)
            }
        }
    }

    private fun openPanel() {
        val panel = Intent(this, AssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AssistantActivity.EXTRA_FROM_WAKE, true)

        runCatching { startActivity(panel) }

        // Without "Appear on top", Android blocks pop-ups from the background, so offer a tap instead.
        if (!Settings.canDrawOverlays(this)) postTapToTalk(panel)
    }

    @SuppressLint("MissingPermission")
    private fun postTapToTalk(panel: Intent) {
        if (!canNotify()) return
        val pi = PendingIntent.getActivity(
            this, 2, panel, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_stat_nebula)
            .setContentTitle("I'm listening")
            .setContentText("Tap to talk to Nebula")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(10_000)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(this).notify(Notifications.ID_TAP_TO_TALK, n)
    }

    private fun cancelTapToTalk() {
        NotificationManagerCompat.from(this).cancel(Notifications.ID_TAP_TO_TALK)
    }

    private fun buzz() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        // Two soft taps: "I'm here".
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 22, 70, 30), intArrayOf(0, 140, 0, 255), -1)
            )
        }
    }

    // ---------------------------------------------------------------- foreground + notification

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, Notifications.ID_LISTENER, buildNotification(), type)
        inForeground = true
        true
    } catch (e: Exception) {
        // Android 14+ won't let a mic service start while the app is in the background.
        Log.w(TAG, "Couldn't start listener in foreground", e)
        setState(WakeStatus.Error, "Open Nebula to turn listening back on")
        stopSelf()
        false
    }

    private fun shutdown(keepError: Boolean = false) {
        stopListening()
        watchdog?.cancel()
        engine?.close()
        engine = null
        if (!keepError) setState(WakeStatus.Off)
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        stopSelf()
    }

    private fun setState(status: WakeStatus, message: String? = null) {
        NebulaState.wake.value = WakeState(status, message)
        if (inForeground) updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        if (!canNotify()) return
        NotificationManagerCompat.from(this).notify(Notifications.ID_LISTENER, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = NebulaState.wake.value
        val text = when (state.status) {
            WakeStatus.Listening -> "Listening for “Hey Nebula”"
            WakeStatus.Loading -> "Starting up…"
            WakeStatus.Paused -> state.message ?: "Paused"
            WakeStatus.Error -> state.message ?: "Something went wrong"
            WakeStatus.Off -> "Off"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_stat_nebula)
            .setContentTitle("Nebula")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .build()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "NebulaWake"

        const val ACTION_START = "com.nebula.assistant.wake.START"
        const val ACTION_STOP = "com.nebula.assistant.wake.STOP"
        const val ACTION_PAUSE = "com.nebula.assistant.wake.PAUSE"
        const val ACTION_RESUME = "com.nebula.assistant.wake.RESUME"
        const val ACTION_RELOAD = "com.nebula.assistant.wake.RELOAD"

        /** Start listening. Call while Nebula is on screen (Android 14+ requires it for mic services). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            )
        }

        fun send(context: Context, action: String) {
            runCatching {
                context.startService(Intent(context, WakeWordService::class.java).setAction(action))
            }
        }
    }
}
