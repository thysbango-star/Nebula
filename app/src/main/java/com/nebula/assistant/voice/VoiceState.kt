package com.nebula.assistant.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The live voice conversation, shown inside the Nebula Notch (and the glow). */
object VoiceState {
    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, DONE, ERROR }

    data class Session(
        val phase: Phase = Phase.IDLE,
        val transcript: String = "",
        val reply: String = "",
        val level: Float = 0f,
        val sources: List<com.nebula.assistant.data.Source> = emptyList(),
        val taskId: String? = null,
        /** True while a game is in front: compact bar, no full-screen effects. */
        val gaming: Boolean = false,
        val startedAt: Long = 0L,
    ) {
        val active get() = phase != Phase.IDLE
    }

    private val _s = MutableStateFlow(Session())
    val session: StateFlow<Session> = _s.asStateFlow()

    fun update(f: (Session) -> Session) { _s.value = f(_s.value) }
    fun level(l: Float) { if (_s.value.active) _s.value = _s.value.copy(level = l) }
    fun reset() { _s.value = Session() }

    /** Requests from the Notch UI (close / ask again) to whoever runs the session. */
    @Volatile var onStop: (() -> Unit)? = null
    @Volatile var onAgain: (() -> Unit)? = null
}
