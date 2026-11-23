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
            // Android stamps positions on the uptime clock; convert to wall-clock time so the card can count on from it.
            updatedAt = st?.lastPositionUpdateTime?.takeIf { it > 0 }?.let { System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - it) } ?: System.currentTimeMillis(), pkg = c.packageName, controller = c,
        ))
    }

    private fun scale(b: Bitmap): Bitmap = if (b.width <= 160) b else Bitmap.createScaledBitmap(b, 160, 160 * b.height / b.width, true)

    // ---------------------------------------------------------------- messages & calls

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val extras = n.extras
        when {
            isLiveCall(sbn) -> {
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
            isLive(sbn) -> live(sbn)
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
        NotchBus.clearLive(sbn.key)
    }

    // ------------------------------------------------------------------ Live Activities

    private val RIDES = listOf("com.ubercab", "ee.mtakso.client", "com.taxify", "com.didiglobal", "com.indriver")
    private val FOOD = listOf("com.ubercab.eats", "com.mrd.food", "co.za.mrdfood", "com.takealot.mrd", "com.checkers.sixty60", "za.co.checkers", "com.dominos", "com.kfc", "com.bolt.food")
    private val NAV = listOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps")
    private val CLOCK = listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock")

    /** An ongoing notification worth a Live Activity: rides, food, directions, timers, progress (downloads, uploads). */
    private fun isLive(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification; val pkg = sbn.packageName
        if (!sbn.isOngoing || isDialer(pkg)) return false
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false // music has its own view
        val known = (RIDES + FOOD + NAV + CLOCK).any { pkg.startsWith(it) }
        val hasProgress = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 || n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val chrono = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val cat = n.category.orEmpty()
        return known || chrono || (hasProgress && cat != Notification.CATEGORY_SERVICE && cat != Notification.CATEGORY_SYSTEM) ||
            cat == Notification.CATEGORY_NAVIGATION || cat == "stopwatch" || cat == Notification.CATEGORY_PROGRESS
    }

    private fun live(sbn: StatusBarNotification) {
        val n = sbn.notification; val e = n.extras; val pkg = sbn.packageName
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val max = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = if (max > 0) (e.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f) else null
        val chronoOn = e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countDown = chronoOn && android.os.Build.VERSION.SDK_INT >= 24 && e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val kind = when {
            RIDES.any { pkg.startsWith(it) } && FOOD.none { pkg.startsWith(it) } -> "ride"
            FOOD.any { pkg.startsWith(it) } -> "food"
            NAV.any { pkg.startsWith(it) } || n.category == Notification.CATEGORY_NAVIGATION -> "nav"
            CLOCK.any { pkg.startsWith(it) } || chronoOn -> if (countDown) "timer" else "stopwatch"
            else -> "progress"
        }
        val eta = Regex("(\\d{1,3})\\s*(min|mins|minutes|m)\\b", RegexOption.IGNORE_CASE).find("$title $text")?.let { "${it.groupValues[1]} min" }
            ?: Regex("\\b(\\d{1,2}:\\d{2})\\b").find("$title $text")?.value.orEmpty()
        NotchBus.putLive(NotchBus.Live(sbn.key, pkg, appLabel(pkg), kind, title, text, appIcon(pkg), n.color, progress,
            if (chronoOn) n.`when` else 0L, countDown, eta, n.contentIntent))
    }

    /**
     * Only a call that is actually ringing (has an Answer button) or actually in progress counts.
     * Missed-call notes, call logs, "call back" reminders and call-app status cards are ignored —
     * these were showing a fake call every time the phone was unlocked.
     */
    private fun isLiveCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val looksLikeCall = n.category == Notification.CATEGORY_CALL || (n.fullScreenIntent != null && isDialer(sbn.packageName))
        if (!looksLikeCall) return false
        val words = listOfNotNull(n.extras.getCharSequence(Notification.EXTRA_TITLE), n.extras.getCharSequence(Notification.EXTRA_TEXT),
            n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)).joinToString(" ").lowercase()
        if (Regex("missed|gemis|voicemail|call back|callback|call log|recent call").containsMatchIn(words)) return false
        if (android.os.Build.VERSION.SDK_INT >= 31 && n.category == "missed_call") return false
        val actions = n.actions.orEmpty().mapNotNull { it.title?.toString()?.lowercase() }
        val canAnswer = actions.any { Regex("answer|accept|antwoord").containsMatchIn(it) }
        if (canAnswer) return true
        // Ongoing call: must be ongoing AND either timed (call timer) or confirmed by the phone's call state.
        if (!sbn.isOngoing) return false
        val timed = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) || n.`when` > 0 && n.extras.getBoolean("android.showChronometer")
        val inCall = runCatching {
            @Suppress("MissingPermission") getSystemService(android.telecom.TelecomManager::class.java).isInCall
        }.getOrDefault(false)
        val hangUp = actions.any { Regex("\\b(hang up|end call|end|beëindig)\\b").containsMatchIn(it) }
        return (inCall && isDialer(sbn.packageName)) || (timed && hangUp)
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
