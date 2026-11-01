package com.nebula.assistant.wake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL_LISTENER = "nebula_listener"
    const val CHANNEL_WAKE = "nebula_wake"

    const val ID_LISTENER = 1
    const val ID_TAP_TO_TALK = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENER, "Wake word listener", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Nebula is listening for “Hey Nebula”."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, "Nebula is ready", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pops up when Nebula hears you but can't open over the current app."
                setShowBadge(false)
            }
        )
    }
}
