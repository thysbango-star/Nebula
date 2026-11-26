package com.nebula.assistant.mood

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nebula.assistant.R
import com.nebula.assistant.notch.NotchBus
import com.nebula.assistant.wake.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * Mood PLAY's sound on the TV, over Wi-Fi: Android's own playback capture (the same thing screen recorders use
 * for "device audio") takes what's playing, it's encoded to AAC and streamed to the TV page, which plays it
 * through the TV's speakers. Android asks first ("Start recording or casting with Nebula?") and shows a
 * notification while it's on.
 *
 * Honest limits, detected and said out loud rather than failing silently:
 *  - some apps block their sound from being captured (the TV would get silence) → we stop and say so;
 *  - "mute the phone" relies on the capture not following the phone's volume; where it does, we put the volume back.
 */
object MoodAudio {
    enum class State { OFF, STARTING, ON }
    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state
    private val _note = MutableStateFlow("")
    /** A plain-language problem, if there is one ("This app doesn't let its sound be sent…"). */
    val note: StateFlow<String> = _note

    private val sinks = CopyOnWriteArrayList<(ByteArray) -> Boolean>()
    fun addSink(f: (ByteArray) -> Boolean) { sinks += f }
    fun removeSink(f: (ByteArray) -> Boolean) { sinks -= f }
    internal fun emit(frame: ByteArray) { for (s in sinks) if (!s(frame)) sinks -= s }
    internal fun set(s: State, note: String = _note.value) { _state.value = s; _note.value = note }
    val listening: Int get() = sinks.size

    /** Ask Android for the sound (it shows its consent dialog), then start streaming. */
    fun start(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) { set(State.OFF, "Sound over Wi-Fi needs Android 10 or newer — use the Bluetooth option"); return }
        set(State.STARTING, "")
        ctx.startActivity(Intent(ctx, MoodAudioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MoodAudioService::class.java)); set(State.OFF) }

    /** One ADTS header for an AAC-LC frame (48 kHz, stereo), so the TV's browser can play the raw stream. */
    internal fun adts(len: Int): ByteArray {
        val p = len + 7; val profile = 2; val freq = 3; val chan = 2
        return byteArrayOf(0xFF.toByte(), 0xF1.toByte(), (((profile - 1) shl 6) or (freq shl 2) or (chan shr 2)).toByte(),
            (((chan and 3) shl 6) or (p shr 11)).toByte(), ((p and 0x7FF) shr 3).toByte(), (((p and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
    }
}

/** Invisible: just asks Android's permission for the sound, then hands it to the service. */
class MoodAudioActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), 7)
    }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this, Intent(this, MoodAudioService::class.java).putExtra("code", resultCode).putExtra("data", data))
        } else MoodAudio.set(MoodAudio.State.OFF, "Sound stays on the phone (you said no)")
        finish()
    }
}

class MoodAudioService : Service() {
    private var projection: MediaProjection? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var savedVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_stat_nebula).setContentTitle("Mood PLAY").setContentText("Sending the sound to your TV")
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
        ServiceCompat.startForeground(this, 41, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        if (running) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra("data")
        if (Build.VERSION.SDK_INT < 29 || data == null) { stopSelf(); return START_NOT_STICKY }
        projection = runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }.getOrNull()
        val p = projection ?: run { MoodAudio.set(MoodAudio.State.OFF, "Android didn't hand over the sound"); stopSelf(); return START_NOT_STICKY }
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, null)
        running = true
        thread = Thread({ capture(p) }, "mood-audio").apply { start() }
        return START_NOT_STICKY
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun capture(p: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) return
        val rate = 48000
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(minBuf, 4096) * 4).setAudioPlaybackCaptureConfig(cfg).build() }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { MoodAudio.set(MoodAudio.State.OFF, "This phone wouldn't share its sound"); stopSelf(); return }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start(); rec.startRecording()
        MoodAudio.set(MoodAudio.State.ON, "")
        val am = getSystemService(AudioManager::class.java)
        // The phone goes quiet while the TV plays it (put back when Mood PLAY's sound stops).
        if (com.nebula.assistant.data.NebulaPrefs(this).moodMutePhone) runCatching {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC); am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
        val pcm = ByteArray(4096); val info = MediaCodec.BufferInfo()
        var silentSince = 0L; var mutedChecked = false
        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                // is anything actually coming through while music plays?
                var sum = 0.0; var i = 0; while (i + 1 < n) { val v = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8); sum += v.toDouble() * v; i += 2 }
                val rms = sqrt(sum / (n / 2)) / 32768.0
                val playing = NotchBus.media.value?.playing == true
                if (playing && rms < 1e-5) { if (silentSince == 0L) silentSince = System.currentTimeMillis() } else silentSince = 0L
                if (silentSince > 0 && System.currentTimeMillis() - silentSince > 4000) {
                    if (savedVolume >= 0 && !mutedChecked) {
                        // On this phone the capture follows the volume: put the volume back and keep going.
                        mutedChecked = true; runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0) }; savedVolume = -1; silentSince = 0L
                        MoodAudio.set(MoodAudio.State.ON, "This phone can't stay silent while sending — turn its volume down yourself")
                        continue
                    }
                    MoodAudio.set(MoodAudio.State.OFF, "${NotchBus.media.value?.let { appName(it.pkg) } ?: "This app"} doesn't let its sound be sent. Try Bluetooth, or another app")
                    break
                }
                val ib = enc.dequeueInputBuffer(10_000)
                if (ib >= 0) { enc.getInputBuffer(ib)!!.apply { clear(); put(pcm, 0, n) }; enc.queueInputBuffer(ib, 0, n, System.nanoTime() / 1000, 0) }
                while (true) {
                    val ob = enc.dequeueOutputBuffer(info, 0); if (ob < 0) break
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buf = enc.getOutputBuffer(ob)!!; val frame = ByteArray(info.size + 7)
                        System.arraycopy(MoodAudio.adts(info.size), 0, frame, 0, 7); buf.position(info.offset); buf.get(frame, 7, info.size)
                        MoodAudio.emit(frame)
                    }
                    enc.releaseOutputBuffer(ob, false)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { rec.stop(); rec.release() }; runCatching { enc.stop(); enc.release() }
            if (savedVolume >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0) }
            savedVolume = -1
            if (MoodAudio.state.value == MoodAudio.State.ON) MoodAudio.set(MoodAudio.State.OFF)
            stopSelf()
        }
    }

    private fun appName(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("This app")

    override fun onDestroy() {
        running = false
        runCatching { thread?.join(1500) }
        runCatching { projection?.stop() }
        super.onDestroy()
    }
}
