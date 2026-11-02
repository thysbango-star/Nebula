package com.nebula.assistant.agent

import android.content.Context
import com.nebula.assistant.audio.NebulaSound
import com.nebula.assistant.audio.Sounds
import com.nebula.assistant.brain.LocalBrain
import com.nebula.assistant.data.ChatMessage
import com.nebula.assistant.data.InputRequest
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.NebulaTask
import com.nebula.assistant.data.Source
import com.nebula.assistant.data.StepStatus
import com.nebula.assistant.data.Store
import com.nebula.assistant.data.TaskStatus
import com.nebula.assistant.data.TaskStep
import com.nebula.assistant.device.NebulaAccessibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Nebula's task completion engine.
 *
 * understand → plan → act with real tools → verify each action → recover → report.
 * A task is only COMPLETED when a tool verified the result. If the last action couldn't be
 * verified, Nebula asks the user to check instead of claiming success.
 */
class TaskEngine private constructor(private val context: Context) {

    private val prefs = NebulaPrefs(context)
    private val groq = GroqClient(prefs)
    private val tools = ToolExecutor(context, prefs, groq)
    private val local = LocalBrain(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pausedDir = File(context.filesDir, "nebula/paused").apply { mkdirs() }

    /** Live status line per conversation ("Thinking…", "Reading the screen…"). Empty = idle. */
    private val _activity = MutableStateFlow<Map<String, String>>(emptyMap())
    val activity: StateFlow<Map<String, String>> = _activity.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val waiters = mutableMapOf<String, CompletableDeferred<Result>>()

    data class Result(val text: String, val taskId: String?, val status: TaskStatus?, val needsInput: Boolean)

    private class Run(
        val conversationId: String,
        val messages: JSONArray,
        var taskId: String? = null,
        val sources: MutableList<Source> = mutableListOf(),
        val fileIds: MutableList<String> = mutableListOf(),
        var pendingCallId: String? = null,
        var deferredCallIds: List<String> = emptyList(),
        var turns: Int = 0,
    )

    // ================================================================ public API

    /** New request from the user. Returns when Nebula has answered or needs input. */
    fun submit(conversationId: String, text: String): CompletableDeferred<Result> {
        val done = CompletableDeferred<Result>()
        Store.addMessage(conversationId, ChatMessage(role = "user", text = text))
        // If a task in this chat is waiting for an answer, treat typed/spoken text as that answer.
        val waiting = Store.tasks.value.firstOrNull { it.conversationId == conversationId && it.status == TaskStatus.REQUIRES_INPUT }
        if (waiting != null && waiting.input != null) {
            val choice = matchAnswer(waiting.input, text)
            if (choice != null) return answer(waiting.id, choice, done, echo = false)
        }
        jobs[conversationId]?.cancel()
        jobs[conversationId] = scope.launch {
            val r = try { handle(conversationId, text) } catch (e: Exception) { fail(conversationId, null, e) }
            done.complete(r)
        }
        return done
    }

    /** User answered a question/approval card. */
    fun answer(taskId: String, choices: List<String>, done: CompletableDeferred<Result> = CompletableDeferred(), echo: Boolean = true): CompletableDeferred<Result> {
        val task = Store.task(taskId) ?: return done.also { it.complete(Result("That task is gone.", null, null, false)) }
        val input = task.input ?: return done.also { it.complete(Result("Nothing to answer.", taskId, task.status, false)) }
        if (echo) Store.addMessage(task.conversationId, ChatMessage(role = "user", text = choices.joinToString(", "), taskId = taskId))
        Store.updateTask(taskId) { it.copy(status = TaskStatus.IN_PROGRESS, input = null,
            steps = it.steps.map { s -> if (s.status == StepStatus.WAITING) s.copy(status = StepStatus.DONE, detail = "You chose: ${choices.joinToString()}", verified = true) else s }) }
        jobs[task.conversationId] = scope.launch {
            val r = try { resume(task, input, choices) } catch (e: Exception) { fail(task.conversationId, taskId, e) }
            done.complete(r)
        }
        return done
    }

    fun cancel(taskId: String) {
        val t = Store.task(taskId) ?: return
        jobs[t.conversationId]?.cancel()
        File(pausedDir, "$taskId.json").delete()
        Store.updateTask(taskId) { it.copy(status = TaskStatus.CANCELLED, input = null, summary = it.summary.ifBlank { "Cancelled." }) }
        setActivity(t.conversationId, null)
    }

    fun stop(conversationId: String) {
        jobs[conversationId]?.cancel()
        Store.tasks.value.filter { it.conversationId == conversationId && it.status == TaskStatus.IN_PROGRESS }.forEach {
            Store.updateTask(it.id) { t -> t.copy(status = TaskStatus.CANCELLED, summary = "Stopped by you.") }
        }
        setActivity(conversationId, null)
    }

    suspend fun testKey(key: String) = groq.test(key)

    // ================================================================ the loop

    private suspend fun handle(conversationId: String, text: String): Result {
        // Instant offline answers (time, date, battery, flashlight, timers, alarms).
        local.handle(text)?.takeIf { !it.closePanel }?.let { r ->
            if (r.launch != null) {
                val ok = runCatching { context.startActivity(r.launch); true }.getOrDefault(false)
                val t = NebulaTask(title = text.trim().replaceFirstChar { it.uppercase() }, conversationId = conversationId,
                    status = if (ok) TaskStatus.COMPLETED else TaskStatus.FAILED, kind = "phone",
                    steps = listOf(TaskStep(label = r.text.removeSuffix("."), status = if (ok) StepStatus.DONE else StepStatus.FAILED, verified = ok)),
                    summary = if (ok) r.text else "No clock app accepted that.")
                Store.upsertTask(t)
                Store.addMessage(conversationId, ChatMessage(role = "assistant", text = t.summary, taskId = t.id))
                Sounds.play(context, if (ok) NebulaSound.SUCCESS else NebulaSound.FAILURE)
                return Result(t.summary, t.id, t.status, false)
            }
            Store.addMessage(conversationId, ChatMessage(role = "assistant", text = r.text))
            return Result(r.text, null, null, false)
        }
        val run = Run(conversationId, buildMessages(conversationId))
        return loop(run)
    }

    private suspend fun loop(run: Run): Result {
        groq.onWait = { msg -> setActivity(run.conversationId, msg) }
        while (run.turns < MAX_TURNS) {
            run.turns++
            setActivity(run.conversationId, if (run.turns == 1) "Thinking…" else "Working on it…")
            val reply = groq.chat(run.messages, ToolDefs.all, effort = "medium")
            run.sources += reply.sources.filter { s -> run.sources.none { it.url == s.url } }
            val msg = reply.message
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return finalize(run, null, clean(msg.optString("content")))
            }
            run.messages.put(JSONObject().put("role", "assistant").put("content", msg.optString("content").ifBlank { "" })
                .put("tool_calls", calls))
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val id = call.optString("id")
                val fn = call.optJSONObject("function") ?: JSONObject()
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())

                if (name in ToolDefs.ACTION && run.taskId == null) createTask(run, name)
                setActivity(run.conversationId, label(name, args))
                val out = tools.run(name, args, id, run.taskId)
                record(run, out)

                if (out.finish != null) {
                    run.messages.put(tool(id, out.content))
                    for (j in i + 1 until calls.length()) run.messages.put(tool(calls.getJSONObject(j).optString("id"), "Not run: task finished."))
                    return finalize(run, out.finish, null)
                }
                if (out.pause != null) {
                    run.pendingCallId = id
                    run.deferredCallIds = (i + 1 until calls.length()).map { calls.getJSONObject(it).optString("id") }
                    return pause(run, out.pause)
                }
                run.messages.put(tool(id, out.content))
            }
        }
        return finalize(run, "failed" to "I stopped after $MAX_TURNS steps without finishing — the task was taking too long.", null)
    }

    private suspend fun resume(task: NebulaTask, input: InputRequest, choices: List<String>): Result {
        val run = loadPaused(task.id) ?: return Result("I lost track of that task. Please ask again.", task.id, TaskStatus.FAILED, false)
            .also { Store.updateTask(task.id) { t -> t.copy(status = TaskStatus.FAILED, summary = "Couldn't resume after the app restarted.") } }
        File(pausedDir, "${task.id}.json").delete()
        val callId = run.pendingCallId ?: input.toolCallId ?: ""
        val answer = choices.joinToString(", ")
        val approved = input.kind == "confirm" && choices.firstOrNull()?.let { it == input.options.firstOrNull() } == true

        // "Did it work?" check after an unverified action — the user's word becomes the verification.
        if (input.pendingAction?.optString("tool") == "_verify") {
            val worked = approved
            Store.updateTask(task.id) { t -> t.copy(status = if (worked) TaskStatus.COMPLETED else TaskStatus.FAILED,
                summary = if (worked) "${t.summary} (You confirmed it worked.)" else "You said it didn't work: ${t.summary}") }
            val txt = if (worked) "Great, marked as done." else "Got it — marked as failed. Tell me what to try differently."
            Store.addMessage(task.conversationId, ChatMessage(role = "assistant", text = txt, taskId = task.id))
            Sounds.play(context, if (worked) NebulaSound.SUCCESS else NebulaSound.FAILURE)
            return Result(txt, task.id, if (worked) TaskStatus.COMPLETED else TaskStatus.FAILED, false)
        }

        val content = when {
            input.pendingAction != null && approved -> {
                val pa = input.pendingAction
                setActivity(run.conversationId, label(pa.optString("tool"), pa.optJSONObject("args") ?: JSONObject()))
                val out = tools.run(pa.optString("tool"), pa.optJSONObject("args") ?: JSONObject(), callId, run.taskId)
                record(run, out)
                out.content
            }
            input.pendingAction != null -> {
                Store.updateTask(task.id) { t -> t.copy(steps = t.steps + TaskStep(label = "You declined", status = StepStatus.SKIPPED)) }
                "The user declined (chose “$answer”). Do not do it. Finish the task as cancelled unless they asked for something else."
            }
            else -> "The user answered: $answer"
        }
        run.messages.put(tool(callId, content))
        run.deferredCallIds.forEach { run.messages.put(tool(it, "Not run yet: call it again if it's still needed.")) }
        run.pendingCallId = null; run.deferredCallIds = emptyList()
        return loop(run)
    }

    // ================================================================ bookkeeping

    private fun createTask(run: Run, firstTool: String) {
        val userText = Store.conversation(run.conversationId)?.messages?.lastOrNull { it.role == "user" }?.text.orEmpty()
        val kind = when (firstTool) {
            "send_sms", "send_whatsapp", "call" -> "message"
            "create_document", "update_document" -> "document"
            "read_screen", "tap", "type_text", "scroll", "navigate", "open_app" -> "screen"
            "set_timer", "set_alarm", "flashlight" -> "phone"
            else -> "general"
        }
        val t = NebulaTask(title = userText.trim().replaceFirstChar { it.uppercase() }.take(80).ifBlank { "Task" },
            conversationId = run.conversationId, kind = kind)
        Store.upsertTask(t)
        run.taskId = t.id
    }

    private fun record(run: Run, out: ToolOutcome) {
        run.sources += out.sources.filter { s -> run.sources.none { it.url == s.url } }
        run.fileIds += out.fileIds.filter { it !in run.fileIds }
        val id = run.taskId ?: return
        Store.updateTask(id) { t ->
            var n = t
            if (out.plan != null) n = n.copy(plan = out.plan)
            if (out.step != null) n = n.copy(steps = n.steps + out.step)
            if (out.fileIds.isNotEmpty()) n = n.copy(fileIds = (n.fileIds + out.fileIds).distinct(),
                kind = if (n.kind == "general") "document" else n.kind)
            n
        }
    }

    private fun pause(run: Run, input: InputRequest): Result {
        savePaused(run)
        val id = run.taskId ?: run.also { createTask(it, "ask_user") }.taskId!!
        Store.updateTask(id) { it.copy(status = TaskStatus.REQUIRES_INPUT, input = input) }
        Store.addMessage(run.conversationId, ChatMessage(role = "assistant", text = input.question, taskId = id))
        setActivity(run.conversationId, null)
        Sounds.play(context, NebulaSound.NEEDS_INPUT)
        return Result(input.question, id, TaskStatus.REQUIRES_INPUT, true)
    }

    private fun finalize(run: Run, finish: Pair<String, String>?, text: String?): Result {
        setActivity(run.conversationId, null)
        val id = run.taskId
        val reply = (finish?.second?.takeIf { it.isNotBlank() } ?: text).orEmpty().ifBlank { "Done." }
        if (id == null) {
            Store.addMessage(run.conversationId, ChatMessage(role = "assistant", text = reply, sources = run.sources.toList(), fileIds = run.fileIds.toList()))
            return Result(reply, null, null, false)
        }
        val task = Store.task(id)!!
        val actions = task.steps.filter { it.status != StepStatus.WAITING && it.status != StepStatus.SKIPPED && it.label != "Read the screen" }
        val claimed = finish?.first?.lowercase() ?: "completed"
        val last = actions.lastOrNull()
        val anyVerified = actions.any { it.status == StepStatus.DONE && it.verified }
        val declined = task.steps.any { it.status == StepStatus.SKIPPED }

        var status: TaskStatus
        var summary = reply
        var input: InputRequest? = null
        when {
            claimed.startsWith("cancel") || (declined && claimed != "completed") -> status = TaskStatus.CANCELLED
            claimed.startsWith("fail") -> status = TaskStatus.FAILED
            last?.status == StepStatus.FAILED -> { status = TaskStatus.FAILED; if (!reply.contains("couldn", true) && !reply.contains("fail", true)) summary = "${last.label} failed: ${last.detail}" }
            last != null && last.status == StepStatus.DONE && last.verified -> status = TaskStatus.COMPLETED
            anyVerified || actions.isNotEmpty() -> {
                // Something was done but the final step couldn't be confirmed: ask, don't claim.
                status = TaskStatus.REQUIRES_INPUT
                input = InputRequest("confirm", "I couldn't confirm the last step (“${last?.label ?: "the action"}”). Did it work?",
                    listOf("Yes, it worked", "No"), pendingAction = JSONObject().put("tool", "_verify"))
                savePaused(run)
            }
            task.steps.any { it.label == "Read the screen" && it.verified } -> status = TaskStatus.COMPLETED // pure "read my screen"
            else -> { status = TaskStatus.FAILED; summary = "Nothing could be done or confirmed. $reply" }
        }
        Store.updateTask(id) { it.copy(status = status, summary = summary, input = input, fileIds = (it.fileIds + run.fileIds).distinct()) }
        Store.addMessage(run.conversationId, ChatMessage(role = "assistant", text = input?.question?.let { "$summary\n\n$it" } ?: summary,
            taskId = id, sources = run.sources.toList(), fileIds = run.fileIds.toList()))
        Sounds.play(context, when (status) {
            TaskStatus.COMPLETED -> NebulaSound.SUCCESS
            TaskStatus.REQUIRES_INPUT -> NebulaSound.NEEDS_INPUT
            TaskStatus.FAILED -> NebulaSound.FAILURE
            else -> NebulaSound.SOFT
        })
        return Result(summary, id, status, status == TaskStatus.REQUIRES_INPUT)
    }

    private fun fail(conversationId: String, taskId: String?, e: Exception): Result {
        if (e is kotlinx.coroutines.CancellationException) throw e
        setActivity(conversationId, null)
        val spoken = (e as? GroqClient.Spoken)?.spoken ?: "Something went wrong: ${e.message}"
        val tid = taskId ?: Store.tasks.value.firstOrNull { it.conversationId == conversationId && it.status == TaskStatus.IN_PROGRESS }?.id
        tid?.let { Store.updateTask(it) { t -> t.copy(status = TaskStatus.FAILED, summary = spoken, input = null) } }
        Store.addMessage(conversationId, ChatMessage(role = "error", text = spoken, taskId = tid))
        Sounds.play(context, NebulaSound.FAILURE)
        return Result(spoken, tid, if (tid != null) TaskStatus.FAILED else null, false)
    }

    private fun setActivity(conversationId: String, text: String?) {
        _activity.value = _activity.value.toMutableMap().apply { if (text == null) remove(conversationId) else put(conversationId, text) }
    }

    private fun tool(id: String, content: String) = JSONObject().put("role", "tool").put("tool_call_id", id).put("content", content)

    private fun label(name: String, a: JSONObject) = when (name) {
        "web_search" -> "Searching the web…"
        "read_screen" -> "Reading the screen…"
        "tap" -> "Tapping ${a.optString("target").take(30)}…"
        "type_text" -> "Typing…"
        "scroll" -> "Scrolling…"
        "open_app" -> "Opening ${a.optString("name")}…"
        "find_contact" -> "Looking up ${a.optString("name")}…"
        "send_sms" -> "Sending SMS…"
        "send_whatsapp" -> "Sending on WhatsApp…"
        "call" -> "Calling…"
        "create_document" -> "Creating your file…"
        "update_document" -> "Updating your file…"
        "get_document" -> "Opening your file…"
        "plan" -> "Planning…"
        else -> "Working on it…"
    }

    /** Maps a typed/spoken reply ("yes", "send it", "the second one") onto the card's options. */
    private fun matchAnswer(input: InputRequest, text: String): List<String>? {
        val t = text.lowercase().trim().trimEnd('.', '!')
        if (input.kind == "text" || input.options.isEmpty()) return listOf(text.trim())
        input.options.firstOrNull { it.equals(t, true) }?.let { return listOf(it) }
        if (input.kind == "confirm") {
            if (Regex("^(yes|yeah|yep|sure|ok|okay|do it|go ahead|send( it)?|call|confirm|tap it|it worked|correct)\\b").containsMatchIn(t)) return listOf(input.options.first())
            if (Regex("^(no|nope|cancel|stop|don't|do not|never mind)\\b").containsMatchIn(t)) return listOf(input.options.last())
        }
        val ordinals = listOf("first", "second", "third", "fourth", "fifth", "sixth")
        ordinals.forEachIndexed { i, o -> if (t.contains(o) && i < input.options.size) return listOf(input.options[i]) }
        input.options.firstOrNull { t.contains(it.lowercase()) || it.lowercase().contains(t) }?.let { return listOf(it) }
        return if (input.kind == "choice") listOf(text.trim()) else null
    }

    private fun buildMessages(conversationId: String): JSONArray {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        val history = Store.conversation(conversationId)?.messages.orEmpty().filter { it.role == "user" || it.role == "assistant" }.takeLast(14)
        history.forEach { m -> msgs.put(JSONObject().put("role", m.role).put("content", m.text.take(1500) +
            if (m.fileIds.isNotEmpty()) "\n[files: ${m.fileIds.joinToString { id -> Store.file(id)?.let { "${it.fileName} id=${it.id}" } ?: id }}]" else "")) }
        return msgs
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val files = Store.files.value.take(12).joinToString("\n") { "- ${it.fileName} (id=${it.id}, ${it.ext})" }.ifBlank { "none yet" }
        val screen = if (NebulaAccessibility.instance != null) "ON" else "OFF (screen tools will fail; tell the user to enable it)"
        return """
You are Nebula, a voice-first assistant that DOES things on the user's Samsung Android phone using tools.
Now: $now (${TimeZone.getDefault().id}). Screen control: $screen.${if (com.nebula.assistant.game.R3cket.inGame.value) "\nR3cket game mode: the user is playing ${com.nebula.assistant.game.R3cket.gameName.value}. Keep replies to one short sentence, don't open other apps unless asked, and use autoclicker for repeated tapping." else ""}
Recent files Nebula made:
$files

How to work:
- Do the task with tools instead of explaining how. Plain questions: answer directly; use web_search for anything current.
- Multi-step tasks: call plan first, then act step by step.
- Screen tasks: read_screen → tap/type_text using [numbers] → read_screen again to check it worked. Use open_app to switch apps and navigate(back) to return. Recover from mistakes (go back, scroll, try another element).
- Messaging: use find_contact unless a number is given. If several numbers/people match, ask_user to pick. Use send_sms by default, send_whatsapp if they mention WhatsApp. Approval is handled for you — never ask "should I send?" yourself.
- Files: write substantial, well-structured, professional content and call create_document ONCE with the full spec. To edit, call get_document then update_document with the complete edited spec. "Turn this conversation into a PDF" = summarise the chat into a pdf.
- Missing essential details (who, which, when)? ask_user with short tappable options. Don't ask about things you can reasonably decide.
- Tool results say "verified". Never claim something happened unless a tool verified it. If unverified, check (read_screen) or say so.
- Always end a task with finish_task(outcome, summary). Summaries are read aloud: 1-2 short plain sentences, no markdown, no URLs.
""".trim()
    }

    private fun clean(s: String) = s.replace(Regex("【[^】]*】"), "").replace(Regex("\\*\\*|__|`|#+ "), "").trim()

    // ================================================================ paused runs survive restarts

    private fun savePaused(run: Run) {
        val id = run.taskId ?: return
        runCatching {
            File(pausedDir, "$id.json").writeText(JSONObject().put("conversationId", run.conversationId).put("messages", run.messages)
                .put("taskId", id).put("pending", run.pendingCallId ?: JSONObject.NULL).put("deferred", JSONArray(run.deferredCallIds))
                .put("files", JSONArray(run.fileIds)).put("turns", run.turns)
                .put("sources", JSONArray(run.sources.map { JSONObject().put("title", it.title).put("url", it.url) })).toString())
        }
    }

    private fun loadPaused(taskId: String): Run? = runCatching {
        val o = JSONObject(File(pausedDir, "$taskId.json").readText())
        Run(o.getString("conversationId"), o.getJSONArray("messages"), taskId,
            (0 until (o.optJSONArray("sources")?.length() ?: 0)).map { o.getJSONArray("sources").getJSONObject(it).let { s -> Source(s.optString("title"), s.optString("url")) } }.toMutableList(),
            (0 until (o.optJSONArray("files")?.length() ?: 0)).map { o.getJSONArray("files").getString(it) }.toMutableList(),
            o.optString("pending").takeIf { it.isNotBlank() && it != "null" },
            (0 until (o.optJSONArray("deferred")?.length() ?: 0)).map { o.getJSONArray("deferred").getString(it) },
            o.optInt("turns"))
    }.getOrNull()

    companion object {
        private const val MAX_TURNS = 24
        @Volatile private var instance: TaskEngine? = null
        fun get(context: Context): TaskEngine = instance ?: synchronized(this) {
            instance ?: TaskEngine(context.applicationContext).also { instance = it }
        }
    }
}
