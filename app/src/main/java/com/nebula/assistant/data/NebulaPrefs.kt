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
        get() = sp.getString(KEY_GROQ, "").orEmpty()
        set(v) = sp.edit().putString(KEY_GROQ, cleanKey(v)).remove(KEY_NO_SEARCH).apply()

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
    var gamePackages: Set<String> get() = sp.getStringSet("game_pkgs", emptySet()) ?: emptySet(); set(v) = sp.edit().putStringSet("game_pkgs", v).apply()

    // Nebula Notch
    var notchEnabled: Boolean get() = bool("notch_enabled", true); set(v) = setBool("notch_enabled", v)
    var notchMusic: Boolean get() = bool("notch_music", true); set(v) = setBool("notch_music", v)
    var notchCalls: Boolean get() = bool("notch_calls", true); set(v) = setBool("notch_calls", v)
    var notchMessages: Boolean get() = bool("notch_messages", true); set(v) = setBool("notch_messages", v)
    var notchVolume: Boolean get() = bool("notch_volume", true); set(v) = setBool("notch_volume", v)
    var notchUnlock: Boolean get() = bool("notch_unlock", true); set(v) = setBool("notch_unlock", v)
    var notchTasks: Boolean get() = bool("notch_tasks", true); set(v) = setBool("notch_tasks", v)

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
