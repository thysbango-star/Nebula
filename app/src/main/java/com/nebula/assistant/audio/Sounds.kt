package com.nebula.assistant.audio

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import com.nebula.assistant.R
import com.nebula.assistant.data.NebulaPrefs

/** Nebula's original sound identity. */
enum class NebulaSound(val res: Int, val cue: Boolean) {
    WAKE(R.raw.nebula_wake, true),
    LISTEN_END(R.raw.nebula_listen_end, true),
    SUCCESS(R.raw.nebula_success, false),
    NEEDS_INPUT(R.raw.nebula_needs_input, false),
    FAILURE(R.raw.nebula_failure, false),
    SOFT(R.raw.nebula_soft, false),
    NOTIFICATION(R.raw.nebula_notification, false),
}

object Sounds {
    private var pool: SoundPool? = null
    private val ids = mutableMapOf<NebulaSound, Int>()
    private val loaded = mutableSetOf<Int>()

    private fun pool(context: Context): SoundPool = pool ?: SoundPool.Builder().setMaxStreams(3)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build().also { p ->
            p.setOnLoadCompleteListener { sp, id, status -> if (status == 0) { loaded += id; pendingPlay.remove(id)?.let { v -> sp.play(id, v, v, 1, 0, 1f) } } }
            pool = p
            NebulaSound.entries.forEach { s -> ids[s] = p.load(context.applicationContext, s.res, 1) }
        }

    private val pendingPlay = mutableMapOf<Int, Float>()

    /** Plays [s] if the user has sounds on (voice cues and task sounds can be switched off separately). */
    fun play(context: Context, s: NebulaSound) {
        val prefs = NebulaPrefs(context)
        if (s.cue && !prefs.voiceCues) return
        if (!s.cue && !prefs.taskSounds) return
        val p = pool(context)
        val id = ids[s] ?: return
        val vol = 0.85f
        if (id in loaded) p.play(id, vol, vol, 1, 0, 1f) else pendingPlay[id] = vol
    }

    fun warmUp(context: Context) { pool(context) }

    // ---------------------------------------------------------------- ringtone & notification tone

    /** Copies Nebula's ringtone/notification tone into the phone's sound lists. Returns the new uri. */
    fun install(context: Context, ringtone: Boolean): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val res = if (ringtone) R.raw.nebula_ringtone else R.raw.nebula_notification
        val name = if (ringtone) "Nebula" else "Nebula Chime"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.ogg")
            put(MediaStore.Audio.Media.TITLE, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, if (ringtone) "Ringtones/Nebula" else "Notifications/Nebula")
            put(MediaStore.Audio.Media.IS_RINGTONE, ringtone)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, !ringtone)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> context.resources.openRawResource(res).use { it.copyTo(out) } } ?: return null
        values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** Sets it as the default (needs "Modify system settings"); returns false if that isn't granted. */
    fun setDefault(context: Context, ringtone: Boolean): Boolean {
        if (!Settings.System.canWrite(context)) return false
        val uri = install(context, ringtone) ?: return false
        RingtoneManager.setActualDefaultRingtoneUri(context, if (ringtone) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION, uri)
        return true
    }

    fun preview(context: Context, ringtone: Boolean): android.media.MediaPlayer? =
        android.media.MediaPlayer.create(context, if (ringtone) R.raw.nebula_ringtone else R.raw.nebula_notification)?.apply { start() }
}
