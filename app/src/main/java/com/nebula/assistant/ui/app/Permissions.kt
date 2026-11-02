package com.nebula.assistant.ui.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nebula.assistant.device.NebulaAccessibility
import com.nebula.assistant.notch.NebulaNotificationListener

/** Everything Nebula can ask for, with a plain-English reason. */
data class Perm(
    val id: String,
    val title: String,
    val why: String,
    val granted: Boolean,
    val runtime: Array<String> = emptyArray(),
    val settings: Intent? = null,
    val essential: Boolean = false,
    /** Sideloaded apps need "Allow restricted settings" first on Android 13+. */
    val restricted: Boolean = false,
)

object Permissions {
    private fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun all(c: Context): List<Perm> {
        val pkg = Uri.parse("package:${c.packageName}")
        val list = mutableListOf(
            Perm("mic", "Microphone", "Hear “Hey Nebula” and what you ask.", has(c, Manifest.permission.RECORD_AUDIO),
                arrayOf(Manifest.permission.RECORD_AUDIO), essential = true),
            Perm("screen", "Screen control", "Read what's on screen and tap, type and scroll for you. Also shows the Nebula Notch. Nothing leaves your phone except what you ask the AI.",
                NebulaAccessibility.isEnabled(c), settings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), essential = true, restricted = true),
            Perm("messaging", "Contacts & SMS", "Find the right person and send texts — always after you approve.",
                has(c, Manifest.permission.READ_CONTACTS) && has(c, Manifest.permission.SEND_SMS),
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)),
            Perm("calls", "Phone & calls", "Make calls you approve, and show incoming calls in the Notch with answer/decline.",
                has(c, Manifest.permission.CALL_PHONE) && has(c, Manifest.permission.READ_PHONE_STATE),
                buildList {
                    add(Manifest.permission.CALL_PHONE); add(Manifest.permission.READ_PHONE_STATE)
                    add(Manifest.permission.ANSWER_PHONE_CALLS)
                }.toTypedArray()),
            Perm("notifications_access", "Notification access", "Lets the Notch show what's playing, new messages (with reply) and calls.",
                NebulaNotificationListener.isEnabled(c), settings = notificationAccessIntent(c), restricted = true),
            Perm("overlay", "Appear on top", "Lets Nebula pop up over other apps when you say “Hey Nebula”.",
                Settings.canDrawOverlays(c), settings = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)),
            Perm("battery", "Unrestricted battery", "Stops One UI putting Nebula to sleep, so the wake word and Notch keep working.",
                c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true,
                settings = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)),
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(1, Perm("notifications", "Notifications", "Show when Nebula is listening and when tasks finish.",
            has(c, Manifest.permission.POST_NOTIFICATIONS), arrayOf(Manifest.permission.POST_NOTIFICATIONS)))
        return list
    }

    fun appDetails(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun notificationAccessIntent(c: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(c, NebulaNotificationListener::class.java).flattenToString())
        else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
}
