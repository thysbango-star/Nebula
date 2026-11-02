package com.nebula.assistant.notch

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Sees what's playing (media sessions), incoming messages and incoming calls, and hands them to
 * the Notch. Nothing is stored or sent anywhere.
 */
class NebulaNotificationListener : NotificationListenerService() {

    private var sessions: MediaSessionManager? = null
    private var active: MediaController? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { active = null; NotchBus.setMedia(null) }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        sessions = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, NebulaNotificationListener::class.java)
        runCatching {
            sessions?.addOnActiveSessionsChangedListener(sessionsListener, me)
            pick(sessions?.getActiveSessions(me).orEmpty())
        }
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onListenerDisconnected() {
        connected = false
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsListener) }
        active?.unregisterCallback(callback); active = null
        super.onListenerDisconnected()
    }

    // ---------------------------------------------------------------- media

    private fun pick(list: List<MediaController>) {
        val playing = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (playing?.sessionToken != active?.sessionToken) {
            active?.unregisterCallback(callback)
            active = playing
            playing?.registerCallback(callback)
        }
        publish()
    }

    private fun publish() {
        val c = active ?: return NotchBus.setMedia(null)
        val md = c.metadata
        val st = c.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) return NotchBus.setMedia(null)
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        NotchBus.setMedia(NotchBus.Media(
            title = title,
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            art = art?.let { scale(it) },
            playing = st?.state == PlaybackState.STATE_PLAYING,
            position = st?.position ?: 0, duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            updatedAt = st?.lastPositionUpdateTime ?: System.currentTimeMillis(), pkg = c.packageName, controller = c,
        ))
    }

    private fun scale(b: Bitmap): Bitmap = if (b.width <= 160) b else Bitmap.createScaledBitmap(b, 160, 160 * b.height / b.width, true)

    // ---------------------------------------------------------------- messages & calls

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val extras = n.extras
        when {
            n.category == Notification.CATEGORY_CALL || (n.fullScreenIntent != null && n.category == null && isDialer(sbn.packageName)) -> {
                val actions = n.actions.orEmpty()
                val answer = actions.firstOrNull { it.title?.toString()?.contains(Regex("answer|accept|antwoord", RegexOption.IGNORE_CASE)) == true }
                val decline = actions.firstOrNull { it.title?.toString()?.contains(Regex("decline|reject|hang|end|dismiss|weier", RegexOption.IGNORE_CASE)) == true }
                // Ringing calls offer "Answer"; once answered the dialer swaps it for "Hang up".
                val ringing = answer != null
                NotchBus.setCall(NotchBus.Call(
                    caller = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { "Unknown caller" },
                    subtitle = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                    ringing = ringing, icon = largeIcon(n), answer = answer?.actionIntent, decline = decline?.actionIntent, key = sbn.key,
                ))
            }
            isMessage(sbn) -> {
                if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
                val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
                val lastMsg = style?.messages?.lastOrNull()
                val sender = lastMsg?.person?.name?.toString() ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = lastMsg?.text?.toString() ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                if (sender.isBlank() && text.isBlank()) return
                val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
                NotchBus.setMessage(NotchBus.Message(sbn.key, appLabel(sbn.packageName), sbn.packageName, sender, text,
                    largeIcon(n) ?: appIcon(sbn.packageName), n.contentIntent, reply, System.currentTimeMillis()))
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotchBus.clearCall(sbn.key)
        NotchBus.clearMessage(sbn.key)
    }

    private fun isMessage(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_MESSAGE) return true
        if (n.extras.containsKey(Notification.EXTRA_MESSAGES)) return true
        return sbn.packageName in MESSAGING
    }

    private fun isDialer(pkg: String) = pkg.contains("dialer") || pkg.contains("incallui") || pkg == "com.samsung.android.incallui"

    private fun largeIcon(n: Notification): Bitmap? = runCatching {
        (n.getLargeIcon() as Icon?)?.loadDrawable(this)?.toBitmap(96, 96)
    }.getOrNull()

    private fun appIcon(pkg: String): Bitmap? = runCatching { packageManager.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        @Volatile var connected = false
            private set

        private val MESSAGING = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.facebook.orca", "com.instagram.android", "com.discord", "org.thoughtcrime.securesms",
            "com.snapchat.android", "com.microsoft.teams", "com.Slack")

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
        }
    }
}
