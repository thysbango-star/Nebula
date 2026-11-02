package com.nebula.assistant.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nebula.assistant.data.ChatMessage
import com.nebula.assistant.data.FileRecord
import com.nebula.assistant.data.InputRequest
import com.nebula.assistant.data.NebulaTask
import com.nebula.assistant.data.StepStatus
import com.nebula.assistant.data.TaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatActions(
    val send: (String) -> Unit,
    val mic: () -> Unit,
    val stop: () -> Unit,
    val answer: (String, List<String>) -> Unit,
    val openTask: (String) -> Unit,
    val openFile: (FileRecord) -> Unit,
    val openUrl: (String) -> Unit,
)

@Composable
fun ChatScreen(
    messages: List<ChatMessage>, tasks: Map<String, NebulaTask>, files: Map<String, FileRecord>,
    activity: String?, hasKey: Boolean, actions: ChatActions, onSetup: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val p = Pal
    val send = { if (draft.isNotBlank()) { actions.send(draft.trim()); draft = "" } }
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(messages.isEmpty(), transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) }, label = "chat") { empty ->
                if (empty) EmptyHome(hasKey, onSetup) { actions.send(it) }
                else Thread(messages, tasks, files, activity, actions)
            }
        }
        AskBar(draft, { draft = it }, send, actions.mic, busy = activity != null, onStop = actions.stop,
            modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp).navigationBarsPadding())
    }
}

// ---------------------------------------------------------------------- empty state: glow + suggestions

@Composable
private fun EmptyHome(hasKey: Boolean, onSetup: () -> Unit, onPick: (String) -> Unit) {
    val p = Pal
    Box(Modifier.fillMaxSize()) {
        EmberGlow(Modifier.fillMaxWidth().height(520.dp).align(Alignment.TopCenter).padding(top = 10.dp))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(0.9f))
            NebulaMark(64.dp)
            Spacer(Modifier.height(18.dp))
            Row {
                Text("Nebula. ", style = MaterialTheme.typography.displaySmall, color = p.gold)
                Text("Your way", style = MaterialTheme.typography.displaySmall, color = if (p.dark) Color.White else Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text("Ask once — it gets done.\nMessages, documents, your screen.", color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.weight(1f))
            if (!hasKey) {
                NCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = p.raised, onClick = onSetup) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(p.warn))
                        Spacer(Modifier.size(10.dp))
                        Text("Add your free Groq key to switch on Nebula's brain", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("Set up", style = MaterialTheme.typography.labelLarge, color = p.flame)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(SUGGESTIONS) { s ->
                    NCard(Modifier.widthIn(max = 200.dp), color = p.surface, radius = 22.dp, onClick = { onPick(s) }) {
                        Text(s, style = MaterialTheme.typography.bodyMedium, color = p.text, modifier = Modifier.padding(16.dp), minLines = 2)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private val SUGGESTIONS = listOf(
    "Text Mom that I'll be five minutes late",
    "Make a monthly budget spreadsheet with charts",
    "What's on my screen right now?",
    "Create a PowerPoint about artificial intelligence",
    "Write a Word document explaining photosynthesis",
    "What's the weather this weekend?",
)

// ---------------------------------------------------------------------- the thread

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thread(messages: List<ChatMessage>, tasks: Map<String, NebulaTask>, files: Map<String, FileRecord>, activity: String?, a: ChatActions) {
    val state = rememberLazyListState()
    LaunchedEffect(messages.size, activity) { state.animateScrollToItem((messages.size + if (activity != null) 1 else 0).coerceAtLeast(1) - 1) }
    // Show each task card only once (on its latest message).
    val lastForTask = messages.filter { it.taskId != null }.associateBy { it.taskId }.values.map { it.id }.toSet()
    LazyColumn(state = state, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(messages, key = { it.id }) { m ->
            Box(Modifier.animateItem(fadeInSpec = tween(300), placementSpec = spring(dampingRatio = 0.8f, stiffness = 380f))) {
                Bubble(m, if (m.id in lastForTask) tasks[m.taskId] else null, files, a)
            }
        }
        if (activity != null) item(key = "activity") {
            Row(Modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                NebulaMark(26.dp, spinning = true)
                Spacer(Modifier.size(10.dp))
                Text(activity, style = MaterialTheme.typography.bodyMedium, color = Pal.dim)
                Spacer(Modifier.size(8.dp))
                ThinkingDots(Pal.flame)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bubble(m: ChatMessage, task: NebulaTask?, files: Map<String, FileRecord>, a: ChatActions) {
    val p = Pal
    val mine = m.role == "user"
    val time = remember(m.at) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.at)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        val showText = !(task?.input != null && task.status == TaskStatus.REQUIRES_INPUT && m.text == task.input.question)
        if (showText) {
            val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (mine) 22.dp else 6.dp, bottomEnd = if (mine) 6.dp else 22.dp)
            Box(Modifier.widthIn(max = 310.dp).clip(shape)
                .background(when (m.role) { "user" -> p.text; "error" -> p.bad.copy(alpha = 0.12f); else -> p.raised })
                .border(1.dp, if (mine) Color.Transparent else p.line, shape).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge, color = when (m.role) { "user" -> p.bg; "error" -> p.bad; else -> p.text })
            }
        }
        if (task != null) {
            Spacer(Modifier.height(8.dp))
            TaskCard(task, a)
        }
        if (m.fileIds.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            m.fileIds.mapNotNull { files[it] }.forEach { f -> FileChip(f) { a.openFile(f) }; Spacer(Modifier.height(6.dp)) }
        }
        if (m.sources.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 8.dp).widthIn(max = 320.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                m.sources.forEach { s ->
                    Row(Modifier.clip(CircleShape).background(p.surface).border(1.dp, p.line, CircleShape).pressable({ a.openUrl(s.url) })
                        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Language, null, tint = p.blue, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.size(5.dp))
                        Text(s.title, style = MaterialTheme.typography.labelMedium, color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp))
                    }
                }
            }
        }
        Text(time, style = MaterialTheme.typography.labelSmall, color = p.faint, modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
    }
}

/** Live task card inside the chat: status, steps, and any question/approval to answer. */
@Composable
fun TaskCard(t: NebulaTask, a: ChatActions) {
    val p = Pal
    NCard(Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.8f, stiffness = 380f)), color = p.surface, onClick = { a.openTask(t.id) }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindIcon(t.kind, 38.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(statusLabel(t.status), style = MaterialTheme.typography.labelMedium, color = when (t.status) {
                        TaskStatus.COMPLETED -> p.ok; TaskStatus.FAILED -> p.bad; TaskStatus.REQUIRES_INPUT -> p.warn; else -> p.dim })
                }
                StatusMark(t.status, t.progress)
            }
            val shownSteps = t.steps.filter { it.status != StepStatus.WAITING }.takeLast(4)
            if (shownSteps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                shownSteps.forEach { s -> StepRow(s.label, s.status, s.verified) }
            }
            AnimatedVisibility(t.input != null && t.status == TaskStatus.REQUIRES_INPUT, enter = fadeIn() + expandVertically(spring(dampingRatio = 0.8f))) {
                t.input?.let { InputCard(it) { choice -> a.answer(t.id, choice) } }
            }
        }
    }
}

@Composable
fun StepRow(label: String, status: StepStatus, verified: Boolean) {
    val p = Pal
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(when (status) {
            StepStatus.DONE -> if (verified) p.ok else p.warn; StepStatus.FAILED -> p.bad; StepStatus.RUNNING -> p.flame
            StepStatus.WAITING -> p.warn; StepStatus.SKIPPED -> p.faint }))
        Spacer(Modifier.size(10.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (status == StepStatus.DONE) Text(if (verified) "verified" else "unconfirmed", style = MaterialTheme.typography.labelSmall,
            color = if (verified) p.ok else p.warn)
    }
}

/** Tappable question / approval (the "checklist" pattern). */
@Composable
fun InputCard(input: InputRequest, onAnswer: (List<String>) -> Unit) {
    val p = Pal
    val picked = remember(input) { mutableStateListOf<String>() }
    var text by remember(input) { mutableStateOf("") }
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.raised).border(1.dp, p.line, RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text(input.question, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        when (input.kind) {
            "confirm" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                input.options.forEachIndexed { i, o ->
                    Box(Modifier.weight(1f).clip(CircleShape).background(if (i == 0) p.text else p.surface2).pressable({ onAnswer(listOf(o)) })
                        .padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                        Text(o, style = MaterialTheme.typography.labelLarge, color = if (i == 0) p.bg else p.text)
                    }
                }
            }
            "choice" -> {
                input.options.forEach { o ->
                    val on = o in picked
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable({
                        if (input.multiple) { if (on) picked.remove(o) else picked.add(o) } else onAnswer(listOf(o))
                    }).padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (input.multiple) (if (on) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank) else Icons.Rounded.RadioButtonUnchecked,
                            null, tint = if (on) p.flame else p.faint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(o, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (input.multiple) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (picked.isEmpty()) "Not selected" else "Selected ${picked.size}", style = MaterialTheme.typography.labelMedium, color = p.faint)
                    Spacer(Modifier.weight(1f))
                    Text("Skip", style = MaterialTheme.typography.labelLarge, color = p.dim, modifier = Modifier.clip(CircleShape).pressable({ onAnswer(listOf("Skip")) }).padding(10.dp))
                    Box(Modifier.clip(CircleShape).background(if (picked.isEmpty()) p.surface2 else p.text)
                        .pressable({ if (picked.isNotEmpty()) onAnswer(picked.toList()) }).padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text("Continue", style = MaterialTheme.typography.labelLarge, color = if (picked.isEmpty()) p.faint else p.bg)
                    }
                }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(p.surface).padding(12.dp)) {
                    if (text.isEmpty()) Text("Type your answer…", color = p.faint, style = MaterialTheme.typography.bodyMedium)
                    BasicTextField(text, { text = it }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.text), cursorBrush = SolidColor(p.flame))
                }
                Spacer(Modifier.size(8.dp))
                Box(Modifier.clip(CircleShape).background(p.text).pressable({ if (text.isNotBlank()) onAnswer(listOf(text.trim())) })
                    .padding(horizontal = 16.dp, vertical = 12.dp)) { Text("Send", color = p.bg, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
fun FileChip(f: FileRecord, onClick: () -> Unit) {
    val p = Pal
    NCard(Modifier.widthIn(max = 320.dp), color = p.raised, radius = 18.dp, onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileBadge(f.ext)
            Column(Modifier.padding(horizontal = 12.dp).weight(1f, fill = false)) {
                Text(f.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${com.nebula.assistant.docs.DocumentStore.kb(f.size.toInt())} · tap to open", style = MaterialTheme.typography.labelMedium, color = p.dim)
            }
        }
    }
}

@Composable
fun FileBadge(ext: String, size: androidx.compose.ui.unit.Dp = 42.dp) {
    val color = when (ext) {
        "docx" -> Color(0xFF2F6FE4); "xlsx" -> Color(0xFF1E9E5A); "pptx" -> Color(0xFFE5642A); "pdf" -> Color(0xFFD93A3A)
        "csv" -> Color(0xFF16A394); "html" -> Color(0xFF8A5CF6); else -> Color(0xFF7A746E)
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color), contentAlignment = Alignment.Center) {
        Text(ext.uppercase().take(4), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
