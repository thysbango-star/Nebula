package com.nebula.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.nebula.assistant.brain.BrainReply
import com.nebula.assistant.brain.NebulaBrain
import com.nebula.assistant.brain.Source
import com.nebula.assistant.data.NebulaPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class Phase { Listening, Thinking, Speaking, Done, Error }

/**
 * One conversation turn: listen → transcribe → think → speak.
 * Exposes Compose state so the panel can animate along with it.
 */
class VoiceController(
    private val activity: ComponentActivity,
    private val brain: NebulaBrain,
    private val prefs: NebulaPrefs,
) : RecognitionListener, TextToSpeech.OnInitListener {

    var phase by mutableStateOf(Phase.Listening)
        private set
    var transcript by mutableStateOf("")
        private set
    var reply by mutableStateOf("")
    var sources by mutableStateOf<List<Source>>(emptyList())
        private set
    var level by mutableFloatStateOf(0f)
        private set

    /** Called when a reply wants to open something (timer, app…). */
    var onAction: ((BrainReply) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var waitingToSpeak: String? = null
    private var released = false
    /** Nebula asked something: start listening again as soon as it finishes speaking. */
    private var expectingAnswer = false

    fun start() {
        tts = TextToSpeech(activity, this)
        listen()
    }

    /** Start (or restart) listening for a question. */
    fun listen() {
        if (released) return
        tts?.stop()
        waitingToSpeak = null
        transcript = ""
        reply = ""
        sources = emptyList()
        level = 0f
        phase = Phase.Listening

        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            fail("Speech recognition isn't available. Install or update the Google app, then try again.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity).also { it.setRecognitionListener(this) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        recognizer?.startListening(intent)
    }

    /** "I'm done talking" — finish early and process what was heard. */
    fun stopListening() {
        recognizer?.stopListening()
    }

    fun fail(message: String) {
        level = 0f
        reply = message
        phase = Phase.Error
    }

    fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ------------------------------------------------------------------ thinking + speaking

    private fun think(text: String) {
        phase = Phase.Thinking
        activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching { brain.respond(text) }
                    .getOrElse { BrainReply("Something went wrong: ${it.message}") }
            }
            if (released) return@launch
            reply = r.text
            sources = r.sources
            if (r.launch != null) onAction?.invoke(r)
            if (r.closePanel) return@launch // panel is going away; skip speaking
            expectingAnswer = r.expectsAnswer
            speak(r.text)
        }
    }

    private fun speak(text: String) {
        when {
            !prefs.speakReplies || ttsFailed -> if (expectingAnswer) { expectingAnswer = false; listen() } else phase = Phase.Done
            ttsReady -> {
                phase = Phase.Speaking
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nebula-${System.nanoTime()}")
            }
            else -> { // engine still warming up
                phase = Phase.Speaking
                waitingToSpeak = text
            }
        }
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            ttsFailed = true
            if (waitingToSpeak != null) main.post { phase = Phase.Done }
            return
        }
        val lang = engine.setLanguage(Locale.getDefault())
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        engine.setSpeechRate(1.04f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post {
                    if (phase == Phase.Speaking) {
                        if (expectingAnswer) { expectingAnswer = false; listen() } else phase = Phase.Done
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { if (phase == Phase.Speaking) phase = Phase.Done }
            }
        })
        ttsReady = true
        waitingToSpeak?.let { text ->
            waitingToSpeak = null
            main.post { if (phase == Phase.Speaking) speak(text) }
        }
    }

    // ------------------------------------------------------------------ speech recognizer callbacks

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onRmsChanged(rmsdB: Float) {
        // Recognizers report roughly -2..10 dB.
        level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onEndOfSpeech() {
        level = 0f
        com.nebula.assistant.audio.Sounds.play(activity, com.nebula.assistant.audio.NebulaSound.LISTEN_END)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { transcript = it }
    }

    override fun onResults(results: Bundle?) {
        if (phase != Phase.Listening) return
        level = 0f
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
            .ifBlank { transcript }
        if (text.isBlank()) {
            fail("I didn't catch that.")
            return
        }
        transcript = text.replaceFirstChar { it.uppercase() }
        think(text)
    }

    override fun onError(error: Int) {
        if (phase != Phase.Listening) return // late errors after results are normal; ignore them
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> fail("I didn't catch that.")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Nebula needs microphone access.")
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> fail("Voice input needs a connection right now.")
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> fail("The microphone is busy. Tap to try again.")
            else -> fail("Voice input hiccup (code $error). Tap to try again.")
        }
    }
}
