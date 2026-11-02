package com.nebula.assistant.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Task status. COMPLETED is only ever set by the engine when a verifying action succeeded —
 * never because the AI said so.
 */
enum class TaskStatus { IN_PROGRESS, REQUIRES_INPUT, COMPLETED, FAILED, CANCELLED }

enum class StepStatus { RUNNING, DONE, FAILED, WAITING, SKIPPED }

/** One real action Nebula took, with its verified outcome. */
data class TaskStep(
    val id: String = newId(),
    val label: String,
    val status: StepStatus,
    val detail: String = "",
    val verified: Boolean = false,
    val at: Long = System.currentTimeMillis(),
)

/** What Nebula needs from the user to continue (shown as a tappable card, like a mini form). */
data class InputRequest(
    /** choice | confirm | text */
    val kind: String,
    val question: String,
    val options: List<String> = emptyList(),
    val multiple: Boolean = false,
    /** Action to run straight away if the user approves a confirm request (e.g. send this SMS). */
    val pendingAction: JSONObject? = null,
    val toolCallId: String? = null,
)

data class NebulaTask(
    val id: String = newId(),
    val title: String,
    val conversationId: String,
    val status: TaskStatus = TaskStatus.IN_PROGRESS,
    val plan: List<String> = emptyList(),
    val steps: List<TaskStep> = emptyList(),
    val summary: String = "",
    val fileIds: List<String> = emptyList(),
    val input: InputRequest? = null,
    /** Icon/colour category: message | document | screen | web | phone | general */
    val kind: String = "general",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isActive get() = status == TaskStatus.IN_PROGRESS || status == TaskStatus.REQUIRES_INPUT
    val progress: Float
        get() = when {
            status == TaskStatus.COMPLETED -> 1f
            plan.isEmpty() -> if (steps.isEmpty()) 0.05f else (steps.size / (steps.size + 2f)).coerceAtMost(0.9f)
            else -> (steps.count { it.status == StepStatus.DONE }.toFloat() / plan.size).coerceIn(0.05f, 0.95f)
        }
}

data class Source(val title: String, val url: String)

data class ChatMessage(
    val id: String = newId(),
    /** user | assistant | error */
    val role: String,
    val text: String,
    val taskId: String? = null,
    val fileIds: List<String> = emptyList(),
    val sources: List<Source> = emptyList(),
    val at: Long = System.currentTimeMillis(),
)

data class Conversation(
    val id: String = newId(),
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class FileRecord(
    val id: String = newId(),
    val title: String,
    val fileName: String,
    val ext: String,
    val mime: String,
    /** Private copy inside the app (always there). */
    val path: String,
    /** Public copy in Downloads/Nebula (content:// uri), if saved. */
    val publicUri: String? = null,
    /** JSON spec used to regenerate the file when it's edited. */
    val specPath: String? = null,
    val size: Long = 0,
    val taskId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

fun newId(): String = UUID.randomUUID().toString().take(12)

// ---------------------------------------------------------------------- JSON mapping

internal object ModelJson {
    fun step(s: TaskStep) = JSONObject().put("id", s.id).put("label", s.label).put("status", s.status.name)
        .put("detail", s.detail).put("verified", s.verified).put("at", s.at)

    fun step(o: JSONObject) = TaskStep(o.optString("id"), o.optString("label"),
        runCatching { StepStatus.valueOf(o.optString("status")) }.getOrDefault(StepStatus.DONE),
        o.optString("detail"), o.optBoolean("verified"), o.optLong("at"))

    fun input(i: InputRequest) = JSONObject().put("kind", i.kind).put("question", i.question)
        .put("options", JSONArray(i.options)).put("multiple", i.multiple)
        .put("pendingAction", i.pendingAction ?: JSONObject.NULL).put("toolCallId", i.toolCallId ?: JSONObject.NULL)

    fun input(o: JSONObject) = InputRequest(o.optString("kind"), o.optString("question"), strings(o.optJSONArray("options")),
        o.optBoolean("multiple"), o.optJSONObject("pendingAction"), o.optString("toolCallId").takeIf { it.isNotBlank() && it != "null" })

    fun task(t: NebulaTask) = JSONObject().put("id", t.id).put("title", t.title).put("conversationId", t.conversationId)
        .put("status", t.status.name).put("plan", JSONArray(t.plan)).put("steps", JSONArray(t.steps.map { step(it) }))
        .put("summary", t.summary).put("fileIds", JSONArray(t.fileIds)).put("input", t.input?.let { input(it) } ?: JSONObject.NULL)
        .put("kind", t.kind).put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)

    fun task(o: JSONObject) = NebulaTask(o.optString("id"), o.optString("title"), o.optString("conversationId"),
        runCatching { TaskStatus.valueOf(o.optString("status")) }.getOrDefault(TaskStatus.FAILED),
        strings(o.optJSONArray("plan")), objs(o.optJSONArray("steps")).map { step(it) }, o.optString("summary"),
        strings(o.optJSONArray("fileIds")), o.optJSONObject("input")?.let { input(it) }, o.optString("kind", "general"),
        o.optLong("createdAt"), o.optLong("updatedAt"))

    fun message(m: ChatMessage) = JSONObject().put("id", m.id).put("role", m.role).put("text", m.text)
        .put("taskId", m.taskId ?: JSONObject.NULL).put("fileIds", JSONArray(m.fileIds))
        .put("sources", JSONArray(m.sources.map { JSONObject().put("title", it.title).put("url", it.url) })).put("at", m.at)

    fun message(o: JSONObject) = ChatMessage(o.optString("id"), o.optString("role"), o.optString("text"),
        o.optString("taskId").takeIf { it.isNotBlank() && it != "null" }, strings(o.optJSONArray("fileIds")),
        objs(o.optJSONArray("sources")).map { Source(it.optString("title"), it.optString("url")) }, o.optLong("at"))

    fun conversation(c: Conversation) = JSONObject().put("id", c.id).put("title", c.title)
        .put("messages", JSONArray(c.messages.map { message(it) })).put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)

    fun conversation(o: JSONObject) = Conversation(o.optString("id"), o.optString("title"),
        objs(o.optJSONArray("messages")).map { message(it) }, o.optLong("createdAt"), o.optLong("updatedAt"))

    fun file(f: FileRecord) = JSONObject().put("id", f.id).put("title", f.title).put("fileName", f.fileName).put("ext", f.ext)
        .put("mime", f.mime).put("path", f.path).put("publicUri", f.publicUri ?: JSONObject.NULL)
        .put("specPath", f.specPath ?: JSONObject.NULL).put("size", f.size).put("taskId", f.taskId ?: JSONObject.NULL)
        .put("createdAt", f.createdAt).put("updatedAt", f.updatedAt)

    fun file(o: JSONObject) = FileRecord(o.optString("id"), o.optString("title"), o.optString("fileName"), o.optString("ext"),
        o.optString("mime"), o.optString("path"), o.optString("publicUri").takeIf { it.isNotBlank() && it != "null" },
        o.optString("specPath").takeIf { it.isNotBlank() && it != "null" }, o.optLong("size"),
        o.optString("taskId").takeIf { it.isNotBlank() && it != "null" }, o.optLong("createdAt"), o.optLong("updatedAt"))

    fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    fun objs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
