package com.nebula.assistant.data

import kotlinx.coroutines.flow.MutableStateFlow

enum class WakeStatus { Off, Loading, Listening, Paused, Error }

data class WakeState(
    val status: WakeStatus = WakeStatus.Off,
    val message: String? = null,
)

/** App-wide live state shared between the background listener and the UI. */
object NebulaState {
    /** What the "Hey Nebula" listener is doing right now. */
    val wake = MutableStateFlow(WakeState())

    /** Ambient mic loudness 0..1 while listening for the wake word (drives the home orb). */
    val micLevel = MutableStateFlow(0f)

    /** True while the assistant panel is on screen. */
    val panelOpen = MutableStateFlow(false)
}
