package com.nebula.assistant.brain

import android.content.Context
import android.content.Intent
import com.nebula.assistant.agent.TaskEngine
import com.nebula.assistant.data.Store

typealias Source = com.nebula.assistant.data.Source

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
    /** Nebula asked a question / needs approval: listen again for the answer. */
    val expectsAnswer: Boolean = false,
    val taskId: String? = null,
)

/** The thinking part of Nebula. */
interface NebulaBrain {
    suspend fun respond(input: String): BrainReply
}

/**
 * Voice requests go through the same task engine as the chat, so "Hey Nebula, text Mom I'm
 * running late" really sends it (after you approve by voice or tap) and shows up in Tasks.
 */
class AgentBrain(private val context: Context) : NebulaBrain {
    override suspend fun respond(input: String): BrainReply {
        val engine = TaskEngine.get(context)
        // Use the chat the user was in (mic button), else keep talking in a chat used in the last 10 minutes.
        val focused = focusConversationId?.let { Store.conversation(it) }
        val recent = Store.conversations.value.firstOrNull { System.currentTimeMillis() - it.updatedAt < 10 * 60_000 }
        val conv = focused ?: recent ?: Store.newConversation()
        focusConversationId = conv.id
        val r = engine.submit(conv.id, input).await()
        val last = Store.conversation(conv.id)?.messages?.lastOrNull { it.role == "assistant" }
        val spoken = if (r.needsInput) {
            val opts = Store.task(r.taskId)?.input?.options.orEmpty()
            r.text + if (opts.size in 2..4) " Say ${opts.joinToString(" or ")}." else ""
        } else r.text
        return BrainReply(spoken, sources = last?.sources.orEmpty(), expectsAnswer = r.needsInput, taskId = r.taskId)
    }

    companion object {
        /** Set by the chat screen's mic button so voice continues that conversation. */
        @Volatile var focusConversationId: String? = null
    }
}
