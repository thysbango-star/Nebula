package com.nebula.assistant.device

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

/** Real device actions. Each returns what actually happened — never an assumed success. */
class PhoneActions(private val context: Context) {

    data class Outcome(val ok: Boolean, val verified: Boolean, val message: String, val needs: String? = null)
    data class Contact(val name: String, val number: String, val label: String)

    private fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- contacts

    fun findContacts(query: String): Pair<List<Contact>, String?> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList<Contact>() to "contacts"
        val q = query.trim()
        val out = mutableListOf<Contact>()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL)
        val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        val selection = words.joinToString(" AND ") { "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" }
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection,
            selection.ifBlank { null }, words.map { "%$it%" }.toTypedArray().ifEmpty { null }, null)?.use { c ->
            while (c.moveToNext() && out.size < 30) {
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                out += Contact(c.getString(0).orEmpty(), c.getString(1).orEmpty(), label)
            }
        }
        // De-duplicate numbers; best name matches first.
        val norm = { n: String -> n.filter { it.isDigit() }.takeLast(9) }
        val unique = out.distinctBy { it.name.lowercase() + norm(it.number) }
        val ql = q.lowercase()
        return unique.sortedWith(compareByDescending<Contact> { it.name.equals(q, true) }
            .thenByDescending { it.name.lowercase().startsWith(ql) }
            .thenByDescending { it.label.contains("mobile", true) }
            .thenBy { it.name.length }) to null
    }

    // ---------------------------------------------------------------- SMS (verified by the radio's "sent" result)

    suspend fun sendSms(number: String, message: String): Outcome {
        if (!has(Manifest.permission.SEND_SMS)) return Outcome(false, false, "Nebula needs permission to send SMS.", needs = "sms")
        val dest = number.filter { it.isDigit() || it == '+' }
        if (dest.length < 3) return Outcome(false, false, "“$number” isn't a valid phone number.")
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(message)
        val action = "com.nebula.assistant.SMS_SENT.${System.nanoTime()}"
        val results = mutableListOf<Int>()
        val outcome = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        results += resultCode
                        if (results.size >= parts.size && cont.isActive) {
                            runCatching { context.unregisterReceiver(this) }
                            cont.resume(results.all { it == Activity.RESULT_OK })
                        }
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val sent = ArrayList(parts.indices.map {
                    PendingIntent.getBroadcast(context, it, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                })
                try {
                    if (parts.size > 1) sms.sendMultipartTextMessage(dest, null, parts, sent, null)
                    else sms.sendTextMessage(dest, null, message, sent[0], null)
                } catch (e: Exception) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
        return when (outcome) {
            true -> Outcome(true, true, "SMS sent to $dest (confirmed by the phone network).")
            false -> Outcome(false, false, "The SMS to $dest failed to send (no signal, airtime or SMS service).")
            null -> Outcome(false, false, "No confirmation from the network after 30 seconds, so I can't say the SMS was sent.")
        }
    }

    // ---------------------------------------------------------------- WhatsApp (verified on screen)

    suspend fun sendWhatsApp(number: String, message: String): Outcome {
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(it) }
            ?: return Outcome(false, false, "WhatsApp isn't installed.")
        val ax = NebulaAccessibility.instance
            ?: return Outcome(false, false, "To press Send in WhatsApp, Nebula needs Screen control (Accessibility) switched on.", needs = "accessibility")
        val e164 = toE164(number) ?: return Outcome(false, false, "I couldn't work out the international format for $number.")
        val url = "https://api.whatsapp.com/send?phone=${e164.removePrefix("+")}&text=${URLEncoder.encode(message, "UTF-8")}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            return Outcome(false, false, "WhatsApp wouldn't open that chat.")
        }
        if (!ax.waitForPackage(pkg, 8000)) return Outcome(false, false, "WhatsApp didn't open in time.")
        val send = ax.waitFor("send", 8000)
            ?: return Outcome(false, false, "The chat opened with your message typed, but I couldn't find the Send button. It's ready for you to send.")
        val box = ax.snapshot().elements.firstOrNull { it.editable }
        send.node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        delay(900)
        // Verified when the message box has been cleared (WhatsApp empties it after sending).
        box?.node?.refresh()
        val remaining = box?.node?.text?.toString().orEmpty()
        val cleared = box != null && (remaining.isBlank() || !remaining.contains(message.take(12)))
        return if (cleared) Outcome(true, true, "WhatsApp message sent to $e164 (confirmed on screen).")
        else Outcome(true, false, "I pressed Send in WhatsApp but couldn't confirm the message left the text box.")
    }

    private fun toE164(number: String): String? {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.startsWith("+")) return digits
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = (tm?.networkCountryIso?.ifBlank { null } ?: tm?.simCountryIso?.ifBlank { null } ?: Locale.getDefault().country).uppercase()
        return PhoneNumberUtils.formatNumberToE164(digits, iso) ?: if (digits.startsWith("00")) "+" + digits.drop(2) else null
    }

    // ---------------------------------------------------------------- calls

    suspend fun call(number: String): Outcome {
        val dest = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (!has(Manifest.permission.CALL_PHONE)) {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dest")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Outcome(true, false, "Opened the dialer with $dest — tap call to connect (Nebula doesn't have call permission).")
        }
        context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$dest")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val tm = context.getSystemService(TelephonyManager::class.java)
        val end = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < end) {
            @Suppress("DEPRECATION")
            val offhook = runCatching { tm.callState == TelephonyManager.CALL_STATE_OFFHOOK }.getOrDefault(false)
            if (offhook) return Outcome(true, true, "Calling $dest.")
            delay(300)
        }
        return Outcome(true, false, "Started a call to $dest, but couldn't confirm it connected.")
    }

    // ---------------------------------------------------------------- apps

    fun findApp(name: String): Pair<String, String>? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != context.packageName }
        apps.forEach { NebulaAccessibility.appLabelCache[it.second] = it.first }
        val q = name.lowercase().trim().removePrefix("the ").removeSuffix(" app")
        return apps.firstOrNull { it.first.equals(q, true) }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.first.lowercase().contains(q) }
            ?: apps.firstOrNull { it.second.lowercase().contains(q.replace(" ", "")) }
    }

    suspend fun openApp(name: String): Outcome {
        val (label, pkg) = findApp(name) ?: return Outcome(false, false, "I couldn't find an app called “$name”.")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return Outcome(false, false, "$label can't be opened directly.")
        context.startActivity(intent)
        val ax = NebulaAccessibility.instance ?: return Outcome(true, false, "Opened $label.")
        return if (ax.waitForPackage(pkg, 5000)) Outcome(true, true, "$label is open.")
        else Outcome(true, false, "Asked Android to open $label, but it didn't come to the front.")
    }

    fun openUrl(url: String): Outcome {
        val u = if (url.startsWith("http")) url else "https://$url"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Outcome(true, false, "Opened $u.")
        } catch (e: Exception) { Outcome(false, false, "No app could open $u.") }
    }

    private fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    // ---------------------------------------------------------------- clock, torch, battery

    fun setTimer(seconds: Int, label: String): Outcome = launchClock(Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Nebula timer" })
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Timer set for ${seconds / 60} min ${seconds % 60} s.")

    fun setAlarm(hour: Int, minute: Int, label: String): Outcome = launchClock(Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
        .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Nebula alarm" }).putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        "Alarm set for %02d:%02d.".format(hour, minute))

    private fun launchClock(i: Intent, msg: String): Outcome = try {
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // The clock app accepted the request; Android gives no stronger confirmation for third-party alarms.
        Outcome(true, true, msg)
    } catch (e: Exception) { Outcome(false, false, "There's no clock app that accepts timers/alarms.") }

    fun torch(on: Boolean): Outcome = try {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        cm.setTorchMode(id, on)
        Outcome(true, true, if (on) "Flashlight on." else "Flashlight off.")
    } catch (e: Exception) { Outcome(false, false, "I couldn't reach the flashlight (the camera may be in use).") }

    fun battery(): Outcome {
        val bm = context.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return Outcome(true, true, "Battery at $pct%${if (bm.isCharging) ", charging" else ""}.")
    }
}
