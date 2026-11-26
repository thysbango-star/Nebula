package com.nebula.assistant.data

import android.content.Context

/** Small settings store. */
class NebulaPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("nebula", Context.MODE_PRIVATE)

    var wakeEnabled: Boolean
        get() = sp.getBoolean(KEY_WAKE, false)
        set(v) = sp.edit().putBoolean(KEY_WAKE, v).apply()

    /** 0 = relaxed (fewer false wakes), 1 = eager (wakes more easily). */
    var sensitivity: Float
        get() = sp.getFloat(KEY_SENS, 0.5f)
        set(v) = sp.edit().putFloat(KEY_SENS, v.coerceIn(0f, 1f)).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean(KEY_SPEAK, true)
        set(v) = sp.edit().putBoolean(KEY_SPEAK, v).apply()

    /** Vibrance glow strength, 0 = subtle, 1 = vivid. */
    var vibranceIntensity: Float
        get() = sp.getFloat(KEY_VIBRANCE, 0.75f)
        set(v) = sp.edit().putFloat(KEY_VIBRANCE, v.coerceIn(0f, 1f)).apply()

    /** The user's own Groq API key. Stored only in this app's private storage (backups disabled). */
    var groqKey: String
        get() = allGroqKeys.let { all -> if (all.isEmpty()) "" else all[sp.getInt("groq_key_active", 0).mod(all.size)] }
        set(v) = sp.edit().putString(KEY_GROQ, cleanKey(v)).putInt("groq_key_active", 0).remove(KEY_NO_SEARCH).apply()

    /** Backup Groq keys: when one runs out of its daily allowance (or stops working) Nebula moves to the next. */
    var groqExtraKeys: List<String>
        get() = sp.getString("groq_keys_extra", "").orEmpty().split('\n').map { cleanKey(it) }.filter { it.length >= 20 }
        set(v) = sp.edit().putString("groq_keys_extra", v.map { cleanKey(it) }.filter { it.length >= 20 }.distinct().joinToString("\n")).apply()

    val allGroqKeys: List<String>
        get() = (listOf(sp.getString(KEY_GROQ, "").orEmpty()) + groqExtraKeys).filter { it.isNotBlank() }.distinct()

    /** Switches to the next key. Returns false if there's no other key. */
    fun nextGroqKey(): Boolean {
        val n = allGroqKeys.size
        if (n < 2) return false
        sp.edit().putInt("groq_key_active", (sp.getInt("groq_key_active", 0) + 1).mod(n)).commit()
        return true
    }
    val activeKeyNumber get() = allGroqKeys.let { if (it.isEmpty()) 0 else sp.getInt("groq_key_active", 0).mod(it.size) + 1 }

    var groqModel: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(v) = sp.edit().putString(KEY_MODEL, v.trim()).apply()

    /** Model that turned out not to allow web search on this key (answers go without it). */
    var groqNoSearchModel: String
        get() = sp.getString(KEY_NO_SEARCH, "").orEmpty()
        set(v) = sp.edit().putString(KEY_NO_SEARCH, v).apply()

    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** Ask before sending SMS/WhatsApp messages (calls and purchases always ask). */
    var confirmMessages: Boolean get() = bool("confirm_messages", true); set(v) = setBool("confirm_messages", v)

    /** Short chimes when Nebula wakes / stops listening. */
    var voiceCues: Boolean get() = bool("voice_cues", true); set(v) = setBool("voice_cues", v)

    /** Sounds when a task completes, fails or needs you. */
    var taskSounds: Boolean get() = bool("task_sounds", true); set(v) = setBool("task_sounds", v)

    /** Theme: "system", "light" or "dark". */
    var theme: String get() = sp.getString("theme", "system") ?: "system"; set(v) = sp.edit().putString("theme", v).apply()

    var onboarded: Boolean get() = bool("onboarded", false); set(v) = setBool("onboarded", v)

    // Voice: "groq" (Orpheus, natural), "eleven" (ElevenLabs), "system" (phone voice)
    var voiceEngine: String get() = sp.getString("voice_engine", "groq") ?: "groq"; set(v) = sp.edit().putString("voice_engine", v).apply()
    var groqVoice: String get() = sp.getString("groq_voice", "troy") ?: "troy"; set(v) = sp.edit().putString("groq_voice", v).apply()
    var groqVoiceModel: String get() = sp.getString("groq_voice_model", "canopylabs/orpheus-v1-english") ?: "canopylabs/orpheus-v1-english"
        set(v) = sp.edit().putString("groq_voice_model", v).apply()
    var elevenKey: String get() = sp.getString("eleven_key", "") ?: ""; set(v) = sp.edit().putString("eleven_key", v.trim()).apply()
    var elevenVoice: String get() = sp.getString("eleven_voice", "21m00Tcm4TdlvDq8ikWAM") ?: "21m00Tcm4TdlvDq8ikWAM"; set(v) = sp.edit().putString("eleven_voice", v).apply()

    // Notch size & position (live-adjustable)
    var notchScale: Float get() = sp.getFloat("notch_scale", 1f); set(v) = sp.edit().putFloat("notch_scale", v.coerceIn(0.7f, 1.5f)).apply()
    var notchHeight: Float get() = sp.getFloat("notch_height", 1f); set(v) = sp.edit().putFloat("notch_height", v.coerceIn(0.7f, 1.6f)).apply()
    var notchY: Float get() = sp.getFloat("notch_y", 0f); set(v) = sp.edit().putFloat("notch_y", v.coerceIn(-20f, 60f)).apply()
    var notchX: Float get() = sp.getFloat("notch_x", 0f); set(v) = sp.edit().putFloat("notch_x", v.coerceIn(-120f, 120f)).apply()

    // R3cket game mode
    var r3cketEnabled: Boolean get() = bool("r3cket", true); set(v) = setBool("r3cket", v)
    var r3cketQuiet: Boolean get() = bool("r3cket_quiet", true); set(v) = setBool("r3cket_quiet", v)
    var r3cketAwake: Boolean get() = bool("r3cket_awake", true); set(v) = setBool("r3cket_awake", v)
    var clickInterval: Int get() = sp.getInt("click_interval", 150); set(v) = sp.edit().putInt("click_interval", v.coerceIn(30, 5000)).apply()
    /** After Nebula answers, keep listening a few seconds for a follow-up. */
    var followUps: Boolean get() = bool("follow_ups", true); set(v) = setBool("follow_ups", v)
    // Name coach
    var userName: String get() = sp.getString("user_name", "") ?: ""; set(v) = sp.edit().putString("user_name", v.trim()).apply()
    var nameSayAs: String get() = sp.getString("name_say_as", "") ?: ""; set(v) = sp.edit().putString("name_say_as", v.trim()).apply()
    var nameHeardAs: Set<String> get() = sp.getStringSet("name_heard_as", emptySet()) ?: emptySet(); set(v) = sp.edit().putStringSet("name_heard_as", v).apply()
    var screenShare: Boolean get() = bool("screen_share", false); set(v) = setBool("screen_share", v)
    var r3cketBoost: Boolean get() = bool("r3cket_boost", true); set(v) = setBool("r3cket_boost", v)
    var r3cketDnd: Boolean get() = bool("r3cket_dnd", true); set(v) = setBool("r3cket_dnd", v)
    var r3cketCrosshair: Boolean get() = bool("r3cket_crosshair", false); set(v) = setBool("r3cket_crosshair", v)
    var r3cketHud: Boolean get() = bool("r3cket_hud", true); set(v) = setBool("r3cket_hud", v)
    var notGames: Set<String> get() = sp.getStringSet("not_games", emptySet()) ?: emptySet(); set(v) = sp.edit().putStringSet("not_games", v).apply()
    // Sports notch
    var sportsOn: Boolean get() = bool("sports_on", true); set(v) = setBool("sports_on", v)
    var sportsGoals: Boolean get() = bool("sports_goals", true); set(v) = setBool("sports_goals", v)
    var sportsEvents: Boolean get() = bool("sports_events", true); set(v) = setBool("sports_events", v)
    var sportsTeams: Set<String> get() = sp.getStringSet("sports_teams", emptySet()) ?: emptySet(); set(v) = sp.edit().putStringSet("sports_teams", v).apply()
    var gamePackages: Set<String> get() = sp.getStringSet("game_pkgs", emptySet()) ?: emptySet(); set(v) = sp.edit().putStringSet("game_pkgs", v).apply()

    // Nebula Notch
    var notchEnabled: Boolean get() = bool("notch_enabled", true); set(v) = setBool("notch_enabled", v)
    var notchMusic: Boolean get() = bool("notch_music", true); set(v) = setBool("notch_music", v)
    var notchCalls: Boolean get() = bool("notch_calls", true); set(v) = setBool("notch_calls", v)
    var notchMessages: Boolean get() = bool("notch_messages", true); set(v) = setBool("notch_messages", v)
    var notchLive: Boolean get() = bool("notch_live", true); set(v) = setBool("notch_live", v)
    // Rovian Intelligence — Voice Interrupt
    var interruptOn: Boolean get() = bool("interrupt_on", true); set(v) = setBool("interrupt_on", v)
    /** 0 = relaxed (needs clear, loud speech), 1 = eager. */
    var interruptSens: Float get() = sp.getFloat("interrupt_sens", 0.5f); set(v) = sp.edit().putFloat("interrupt_sens", v.coerceIn(0f, 1f)).apply()
    var interruptHaptics: Boolean get() = bool("interrupt_haptics", true); set(v) = setBool("interrupt_haptics", v)
    /** Nebula starts its new answer with a quick natural acknowledgement ("Got you — …"). */
    var interruptAck: Boolean get() = bool("interrupt_ack", true); set(v) = setBool("interrupt_ack", v)
    /** Only listen for interruptions when headphones are connected (no speaker echo at all). */
    var interruptHeadphonesOnly: Boolean get() = bool("interrupt_hp_only", false); set(v) = setBool("interrupt_hp_only", v)
    // Rovian Intelligence — Calendar
    var calOn: Boolean get() = bool("cal_on", false); set(v) = setBool("cal_on", v)
    /** Calendars Nebula may read (ids). Empty = all visible calendars. */
    var calIds: Set<String> get() = sp.getStringSet("cal_ids", emptySet()) ?: emptySet(); set(v) = sp.edit().putStringSet("cal_ids", v).apply()
    /** "full" = titles and places, "times" = only busy/free times. */
    var calDetail: String get() = sp.getString("cal_detail", "full") ?: "full"; set(v) = sp.edit().putString("cal_detail", v).apply()
    var calNotify: Boolean get() = bool("cal_notify", true); set(v) = setBool("cal_notify", v)
    /** Minutes before an event (0 = at the start time). */
    var calLead: Int get() = sp.getInt("cal_lead", 10); set(v) = sp.edit().putInt("cal_lead", v.coerceIn(0, 24 * 60)).apply()
    /** Skip events that already have their own calendar reminder (no double alerts). */
    var calUseOwn: Boolean get() = bool("cal_use_own", true); set(v) = setBool("cal_use_own", v)
    var calFreeAlerts: Boolean get() = bool("cal_free_alerts", false); set(v) = setBool("cal_free_alerts", v)
    var briefMorning: Boolean get() = bool("brief_morning", true); set(v) = setBool("brief_morning", v)
    var briefTime: Int get() = sp.getInt("brief_time", 7 * 60 + 30); set(v) = sp.edit().putInt("brief_time", v.coerceIn(0, 24 * 60 - 1)).apply()
    var briefAfternoon: Boolean get() = bool("brief_afternoon", false); set(v) = setBool("brief_afternoon", v)
    var briefTomorrow: Boolean get() = bool("brief_tomorrow", false); set(v) = setBool("brief_tomorrow", v)
    var notifyReminders: Boolean get() = bool("notify_reminders", true); set(v) = setBool("notify_reminders", v)
    /** Alarms Nebula has scheduled (key → time) and alerts already shown, so nothing is ever sent twice. */
    var intelScheduled: String get() = sp.getString("intel_scheduled", "{}") ?: "{}"; set(v) = sp.edit().putString("intel_scheduled", v).apply()
    var intelSent: String get() = sp.getString("intel_sent", "{}") ?: "{}"; set(v) = sp.edit().putString("intel_sent", v).apply()
    // Rovian Intelligence — Widgets
    /** How often the Sports and Daily widgets refresh on their own, in minutes (events also refresh them). */
    var widgetRefresh: Int get() = sp.getInt("widget_refresh", 30); set(v) = sp.edit().putInt("widget_refresh", v.coerceIn(15, 180)).apply()
    // Nebula Beam
    var beamDoubleTap: Boolean get() = bool("beam_double_tap", true); set(v) = setBool("beam_double_tap", v)
    /** Mood PLAY: the TV screen's theme ("auto" picks one from each cover) and TVs that said yes (id → token / key). */
    var moodTheme: String get() = sp.getString("mood_theme", "auto") ?: "auto"; set(v) = sp.edit().putString("mood_theme", v).apply()
    var moodTvs: String get() = sp.getString("mood_tvs", "{}") ?: "{}"; set(v) = sp.edit().putString("mood_tvs", v).apply()
    var beamHaptics: Boolean get() = bool("beam_haptics", true); set(v) = setBool("beam_haptics", v)
    var beamLinkFallback: Boolean get() = bool("beam_link_fallback", true); set(v) = setBool("beam_link_fallback", v)
    var beamMaxMb: Int get() = sp.getInt("beam_max_mb", 2048); set(v) = sp.edit().putInt("beam_max_mb", v.coerceIn(10, 10240)).apply()
    var beamScanSeconds: Int get() = sp.getInt("beam_scan_s", 30); set(v) = sp.edit().putInt("beam_scan_s", v.coerceIn(10, 120)).apply()
    var beamVisibleUntil: Long get() = sp.getLong("beam_visible_until", 0); set(v) = sp.edit().putLong("beam_visible_until", v).apply()
    var beamHistory: String get() = sp.getString("beam_history", "[]") ?: "[]"; set(v) = sp.edit().putString("beam_history", v).apply()
    var notchVolume: Boolean get() = bool("notch_volume", true); set(v) = setBool("notch_volume", v)
    var notchUnlock: Boolean get() = bool("notch_unlock", true); set(v) = setBool("notch_unlock", v)
    var notchTasks: Boolean get() = bool("notch_tasks", true); set(v) = setBool("notch_tasks", v)
    var notchDevices: Boolean get() = bool("notch_devices", true); set(v) = setBool("notch_devices", v)
    var notchCharging: Boolean get() = bool("notch_charging", true); set(v) = setBool("notch_charging", v)
    var notchRinger: Boolean get() = bool("notch_ringer", true); set(v) = setBool("notch_ringer", v)
    /** R3cket: while you play, the notch drops its heavy effects and Nebula slows its own background work. */
    var r3cketLite: Boolean get() = bool("r3cket_lite", true); set(v) = setBool("r3cket_lite", v)

    /**
     * Detection threshold handed to the wake word engine. Lower = triggers more easily.
     * Tuned by testing "Hey Nebula" in three voices against 33 sound-alike phrases:
     * Relaxed 0.30, Balanced 0.19 (caught 9/9, 0 false wakes), Eager 0.08.
     */
    val wakeThreshold: Float
        get() = 0.30f - 0.22f * sensitivity

    companion object {
        /** Removes spaces, line breaks, quotes and invisible characters that sneak in when copying a key. */
        fun cleanKey(raw: String): String =
            raw.filter { !it.isWhitespace() && it.code in 33..126 && it != '"' && it != '\'' && it != '`' }

        /** Groq's strongest free model; supports browser search. */
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"
        private const val KEY_NO_SEARCH = "groq_no_search_model"

        private const val KEY_VIBRANCE = "vibrance_intensity"
        private const val KEY_GROQ = "groq_key"
        private const val KEY_MODEL = "groq_model"
        private const val KEY_WAKE = "wake_enabled"
        private const val KEY_SENS = "wake_sensitivity"
        private const val KEY_SPEAK = "speak_replies"
    }
}
