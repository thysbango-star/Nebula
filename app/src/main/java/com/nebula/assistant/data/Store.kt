package com.nebula.assistant.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

/**
 * Nebula's memory: conversations, tasks and files, kept in small JSON files and exposed as
 * live flows to the UI, the Notch and the voice overlay. Writes are debounced.
 */
object Store {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: File
    private var saveJob: Job? = null
    private val lock = Any()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val _tasks = MutableStateFlow<List<NebulaTask>>(emptyList())
    private val _files = MutableStateFlow<List<FileRecord>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    val tasks: StateFlow<List<NebulaTask>> = _tasks.asStateFlow()
    val files: StateFlow<List<FileRecord>> = _files.asStateFlow()

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "nebula").apply { mkdirs() }
        _conversations.value = load("conversations.json") { ModelJson.conversation(it) }.sortedByDescending { it.updatedAt }
        // Anything still "in progress" from a previous run was interrupted.
        _tasks.value = load("tasks.json") { ModelJson.task(it) }.map {
            if (it.status == TaskStatus.IN_PROGRESS) it.copy(status = TaskStatus.FAILED,
                summary = it.summary.ifBlank { "Interrupted before it finished (the app was closed)." }) else it
        }.sortedByDescending { it.updatedAt }
        _files.value = load("files.json") { ModelJson.file(it) }.filter { File(it.path).exists() }.sortedByDescending { it.updatedAt }
    }

    // ---------------------------------------------------------------- conversations

    fun conversation(id: String?) = _conversations.value.firstOrNull { it.id == id }

    fun newConversation(): Conversation {
        val c = Conversation()
        synchronized(lock) { _conversations.value = listOf(c) + _conversations.value }
        save()
        return c
    }

    fun addMessage(conversationId: String, m: ChatMessage) = updateConversation(conversationId) { c ->
        val title = if (c.messages.none { it.role == "user" } && m.role == "user") titleFrom(m.text) else c.title
        c.copy(messages = c.messages + m, title = title, updatedAt = System.currentTimeMillis())
    }

    fun replaceMessage(conversationId: String, messageId: String, transform: (ChatMessage) -> ChatMessage) =
        updateConversation(conversationId) { c -> c.copy(messages = c.messages.map { if (it.id == messageId) transform(it) else it }) }

    fun updateConversation(id: String, transform: (Conversation) -> Conversation) {
        synchronized(lock) {
            _conversations.value = _conversations.value.map { if (it.id == id) transform(it) else it }.sortedByDescending { it.updatedAt }
        }
        save()
    }

    fun deleteConversation(id: String) {
        synchronized(lock) { _conversations.value = _conversations.value.filterNot { it.id == id } }
        save()
    }

    private fun titleFrom(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ").removePrefix("hey nebula").trim(',', ' ')
        return (if (t.length > 42) t.take(40).trimEnd() + "…" else t).replaceFirstChar { it.uppercase() }.ifBlank { "New chat" }
    }

    // ---------------------------------------------------------------- tasks

    fun task(id: String?) = _tasks.value.firstOrNull { it.id == id }

    fun upsertTask(t: NebulaTask) {
        synchronized(lock) {
            val now = t.copy(updatedAt = System.currentTimeMillis())
            val list = _tasks.value
            _tasks.value = (if (list.any { it.id == t.id }) list.map { if (it.id == t.id) now else it } else listOf(now) + list)
                .sortedByDescending { it.updatedAt }
        }
        save()
    }

    fun updateTask(id: String, transform: (NebulaTask) -> NebulaTask): NebulaTask? {
        var result: NebulaTask? = null
        synchronized(lock) {
            _tasks.value = _tasks.value.map {
                if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()).also { n -> result = n } else it
            }.sortedByDescending { it.updatedAt }
        }
        save()
        return result
    }

    fun deleteTask(id: String) {
        synchronized(lock) { _tasks.value = _tasks.value.filterNot { it.id == id } }
        save()
    }

    // ---------------------------------------------------------------- files

    fun file(id: String?) = _files.value.firstOrNull { it.id == id }

    fun upsertFile(f: FileRecord) {
        synchronized(lock) {
            val list = _files.value
            _files.value = (if (list.any { it.id == f.id }) list.map { if (it.id == f.id) f else it } else listOf(f) + list)
                .sortedByDescending { it.updatedAt }
        }
        save()
    }

    fun deleteFile(id: String) {
        file(id)?.let { runCatching { File(it.path).delete(); it.specPath?.let { p -> File(p).delete() } } }
        synchronized(lock) { _files.value = _files.value.filterNot { it.id == id } }
        save()
    }

    // ---------------------------------------------------------------- persistence

    private fun save() {
        if (!::dir.isInitialized) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(250)
            write("conversations.json", JSONArray(_conversations.value.take(200).map { ModelJson.conversation(it) }))
            write("tasks.json", JSONArray(_tasks.value.take(300).map { ModelJson.task(it) }))
            write("files.json", JSONArray(_files.value.map { ModelJson.file(it) }))
        }
    }

    private fun write(name: String, data: JSONArray) {
        runCatching {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(data.toString())
            tmp.renameTo(File(dir, name))
        }
    }

    private fun <T> load(name: String, map: (org.json.JSONObject) -> T): List<T> = runCatching {
        val f = File(dir, name)
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> runCatching { map(o) }.getOrNull() } }
    }.getOrDefault(emptyList())
}
