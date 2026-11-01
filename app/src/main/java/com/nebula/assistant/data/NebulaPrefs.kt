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
