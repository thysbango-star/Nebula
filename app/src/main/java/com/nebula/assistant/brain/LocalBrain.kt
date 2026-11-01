package com.nebula.assistant.brain

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.AlarmClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Phone commands Nebula handles instantly, with no internet and no AI. */
class LocalBrain(context: Context) {

    private val ctx = context.applicationContext

    /** Handles phone commands; returns null for anything else so the AI can answer. */
    fun handle(input: String): BrainReply? {
        val q = input.lowercase(Locale.ROOT).trim().trimEnd('?', '.', '!')
        return when {
            q.isBlank() -> BrainReply("I didn't catch that.")
            "timer" in q && (Regex("\\b(set|start)\\b").containsMatchIn(q) || q.startsWith("timer")) -> timer(q)
            ("alarm" in q && "set" in q) || q.startsWith("wake me") -> alarm(q)
            q.startsWith("open ") || q.startsWith("launch ") -> openApp(q)
            ("flashlight" in q || "torch" in q || "flash light" in q) &&
                Regex("\\b(on|off|turn|switch)\\b").containsMatchIn(q) ->
                torch(on = !Regex("\\boff\\b").containsMatchIn(q))
            "battery" in q && Regex("\\b(my|phone|level|how|percent|charge)\\b").containsMatchIn(q) -> battery()
            Regex("^(what('s| is) the time|what time is it|tell me the time)( now| right now)?$").matches(q) -> time()
            Regex("^(what('s| is) (the date|today('s date)?)|what day is it( today)?)$").matches(q) -> date()
            else -> null
        }
    }

    // ------------------------------------------------------------------ commands

    private fun time(): BrainReply {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        return BrainReply("It's ${fmt.format(Date())}.")
    }

    private fun date(): BrainReply {
        val fmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
        return BrainReply("It's ${fmt.format(Date())}.")
    }

    private fun battery(): BrainReply {
        val bm = ctx.getSystemService(BatteryManager::class.java)
            ?: return BrainReply("I couldn't read the battery.")
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return BrainReply("You're at $pct%" + if (charging) ", and charging." else ".")
    }

    private fun torch(on: Boolean): BrainReply = try {
        val cm = ctx.getSystemService(CameraManager::class.java)!!
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return BrainReply("This phone doesn't seem to have a flashlight I can use.")
        cm.setTorchMode(id, on)
        BrainReply(if (on) "Flashlight on." else "Flashlight off.")
    } catch (e: Exception) {
        BrainReply("I couldn't reach the flashlight. The camera might be in use.")
    }

    private fun timer(q: String): BrainReply {
        val seconds = parseDuration(q)
        if (seconds <= 0) return BrainReply("How long? Try “set a timer for 5 minutes”.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Nebula timer")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return BrainReply("Timer set for ${describe(seconds)}.", launch = intent)
    }

    private fun alarm(q: String): BrainReply {
        // Matches "7", "7:30", "7 30", with optional am/pm ("a.m.", "pm", …).
        val m = Regex("\\b(\\d{1,2})(?:[:. ](\\d{2}))?\\s*(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)?").find(q)
            ?: return BrainReply("What time? Try “wake me up at 6:30 AM”.")
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3].replace(Regex("[^ap]"), "")
        if (ampm == "p" && hour < 12) hour += 12
        if (ampm == "a" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return BrainReply("That doesn't sound like a valid time.")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Nebula alarm")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        }
        val label = SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
        return BrainReply("Alarm set for $label.", launch = intent)
    }

    private fun openApp(q: String): BrainReply {
        val name = q.substringAfter(' ').removePrefix("the ").removeSuffix(" app").trim()
        if (name.isBlank()) return BrainReply("Which app should I open?")

        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != ctx.packageName }

        val match = apps.firstOrNull { it.first.equals(name, ignoreCase = true) }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(name) }
            ?: apps.firstOrNull { name in it.first.lowercase() }
            ?: return BrainReply("I couldn't find an app called “$name”.")

        val intent = pm.getLaunchIntentForPackage(match.second)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return BrainReply("I found ${match.first}, but it can't be opened.")
        return BrainReply("Opening ${match.first}.", launch = intent, closePanel = true, needsUnlock = true)
    }

    // ------------------------------------------------------------------ parsing helpers

    private fun parseDuration(input: String): Int {
        val q = input
            .replace("half an hour", "30 minutes")
            .replace("an hour and a half", "90 minutes")
            .replace("a minute and a half", "90 seconds")
        val unit = Regex("(\\d+|[a-z]+(?:[- ][a-z]+)?)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b")
        var total = 0
        for (m in unit.findAll(q)) {
            val n = m.groupValues[1].toIntOrNull() ?: wordsToNumber(m.groupValues[1]) ?: continue
            total += when {
                m.groupValues[2].startsWith("h") -> n * 3600
                m.groupValues[2].startsWith("m") -> n * 60
                else -> n
            }
        }
        return total
    }

    private fun wordsToNumber(text: String): Int? {
        val small = mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
            "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
            "fifty" to 50, "sixty" to 60, "ninety" to 90,
        )
        // Use the last word or two, e.g. "for twenty five" → 25, "for a" → 1.
        val words = text.split(' ', '-').filter { it.isNotBlank() }
        if (words.size >= 2) {
            val tens = small[words[words.size - 2]]
            val ones = small[words.last()]
            if (tens != null && ones != null && tens >= 20 && ones < 10) return tens + ones
        }
        return small[words.lastOrNull()]
    }

    private fun describe(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            if (h > 0) "$h hour${if (h > 1) "s" else ""}" else null,
            if (m > 0) "$m minute${if (m > 1) "s" else ""}" else null,
            if (s > 0) "$s second${if (s > 1) "s" else ""}" else null,
        ).joinToString(" and ")
    }
}
