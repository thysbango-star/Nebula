package com.nebula.assistant.brain

import android.content.Intent

/** A web page Nebula used to answer. */
data class Source(val title: String, val url: String)

/** What Nebula says back, plus anything it should do on the phone. */
data class BrainReply(
    val text: String,
    /** An activity to launch (timer, alarm, another app…). */
    val launch: Intent? = null,
    /** Close the panel after launching (e.g. when opening another app). */
    val closePanel: Boolean = false,
    /** Ask to unlock first if the phone is locked. */
    val needsUnlock: Boolean = false,
    /** Web pages the answer came from. */
    val sources: List<Source> = emptyList(),
)

/** The thinking part of Nebula. */
interface NebulaBrain {
    suspend fun respond(input: String): BrainReply
}

/**
 * Phone commands are handled instantly on the device; everything else goes to the AI.
 */
class HybridBrain(private val local: LocalBrain, private val cloud: GeminiBrain) : NebulaBrain {
    override suspend fun respond(input: String): BrainReply =
        local.handle(input) ?: cloud.respond(input)
}
