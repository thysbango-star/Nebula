package com.nebula.assistant.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nebula.assistant.data.FileRecord
import com.nebula.assistant.data.NebulaTask
import com.nebula.assistant.data.StepStatus
import com.nebula.assistant.data.TaskStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class Assign(val text: String, val prompt: String, val icon: ImageVector)

private val ASSIGN = listOf(
    Assign("Message someone for me", "Send a message to ", Icons.Rounded.Forum),
    Assign("Build a spreadsheet with totals & a chart", "Make an Excel spreadsheet tracking ", Icons.Rounded.TableChart),
    Assign("Write a polished Word document", "Create a Word document about ", Icons.Rounded.Description),
    Assign("Do something on my screen", "On my screen, ", Icons.Rounded.TouchApp),
    Assign("Research and summarise as a PDF", "Research and make a PDF summary of ", Icons.Rounded.TravelExplore),
    Assign("Plan my day", "Plan my day today: ", Icons.Rounded.Event),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(tasks: List<NebulaTask>, onAssign: (String) -> Unit, onOpen: (String) -> Unit, busy: Boolean, onMic: () -> Unit) {
    val p = Pal
    var filter by remember { mutableStateOf("All") }
    var draft by remember { mutableStateOf("") }
    val shown = when (filter) {
        "Active" -> tasks.filter { it.status == TaskStatus.IN_PROGRESS }
        "Needs you" -> tasks.filter { it.status == TaskStatus.REQUIRES_INPUT }
        "Done" -> tasks.filter { it.status == TaskStatus.COMPLETED }
        else -> tasks
    }
    val running = tasks.count { it.status == TaskStatus.IN_PROGRESS }
    val needs = tasks.count { it.status == TaskStatus.REQUIRES_INPUT }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 4.dp)) {
                    Text(greeting(), style = MaterialTheme.typography.displayMedium.copy(brush = Brush.linearGradient(listOf(p.ember, p.flame, p.ruby))))
                    Spacer(Modifier.height(4.dp))
                    Text(when {
                        needs > 0 -> "$needs task${if (needs > 1) "s" else ""} need${if (needs == 1) "s" else ""} you · $running running"
                        running > 0 -> "$running task${if (running > 1) "s" else ""} running — I'll bring ${if (running > 1) "them" else "it"} back as soon as ${if (running > 1) "they're" else "it's"} done"
                        else -> "Assign me something and I'll get it done."
                    }, style = MaterialTheme.typography.bodyMedium, color = p.dim)
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ASSIGN.size) { i ->
                        val a = ASSIGN[i]
                        val featured = i == 0
                        Box(Modifier.width(158.dp).height(132.dp).clip(RoundedCornerShape(24.dp))
                            .background(if (featured) p.gradient else Brush.linearGradient(listOf(p.raised, p.raised)))
                            .pressable({ draft = a.prompt })) {
                            Text(a.text, style = MaterialTheme.typography.titleSmall, color = if (featured) Color.White else p.text,
                                modifier = Modifier.padding(16.dp))
                            Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(34.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (featured) Color.White.copy(alpha = 0.22f) else kindColor(i, p)), contentAlignment = Alignment.Center) {
                                Icon(a.icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All", "Active", "Needs you", "Done")) { f -> Chip(f, filter == f) { filter = f } }
                }
            }
            if (shown.isEmpty()) item {
                Text(if (tasks.isEmpty()) "No tasks yet. Pick a card above or type below." else "Nothing here right now.",
                    style = MaterialTheme.typography.bodyMedium, color = p.faint, modifier = Modifier.padding(horizontal = 22.dp, vertical = 30.dp))
            }
            items(shown, key = { it.id }) { t ->
                Box(Modifier.padding(horizontal = 16.dp).animateItem(fadeInSpec = tween(250), placementSpec = spring(dampingRatio = 0.8f, stiffness = 400f))) {
                    TaskRow(t) { onOpen(t.id) }
                }
            }
        }
        AskBar(draft, { draft = it }, { if (draft.isNotBlank()) { onAssign(draft.trim()); draft = "" } }, onMic, busy = false, onStop = {},
            placeholder = "Assign a task…", modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp).navigationBarsPadding())
    }
}

private fun kindColor(i: Int, p: NebulaPalette) = listOf(p.flame, p.teal, p.violet, p.orange, p.blue, p.pink)[i % 6]

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Hello, night owl"
}

@Composable
fun TaskRow(t: NebulaTask, onClick: () -> Unit) {
    val p = Pal
    NCard(Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.85f)), color = p.raised, radius = 22.dp, onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            KindIcon(t.kind)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (t.status == TaskStatus.COMPLETED) TextDecoration.LineThrough else null,
                    color = if (t.status == TaskStatus.COMPLETED) p.dim else p.text)
                Spacer(Modifier.height(2.dp))
                val sub = when (t.status) {
                    TaskStatus.IN_PROGRESS -> t.steps.lastOrNull()?.label ?: "Starting…"
                    TaskStatus.REQUIRES_INPUT -> t.input?.question?.lineSequence()?.firstOrNull() ?: "Needs your input"
                    else -> t.summary.ifBlank { statusLabel(t.status) }
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.status == TaskStatus.IN_PROGRESS) {
                    val prog by animateFloatAsState(t.progress, tween(600), label = "prog")
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(p.surface2)) {
                        Box(Modifier.fillMaxWidth(prog).height(4.dp).clip(CircleShape).background(p.gradient))
                    }
                }
            }
            StatusMark(t.status, t.progress)
        }
    }
}

/** Full detail of one task: plan, verified log, input, files, result. */
@Composable
fun TaskDetail(
    t: NebulaTask, files: Map<String, FileRecord>, onClose: () -> Unit, onAnswer: (List<String>) -> Unit,
    onCancel: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit, onOpenFile: (FileRecord) -> Unit, onOpenChat: () -> Unit,
) {
    val p = Pal
    Column(Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            KindIcon(t.kind, 48.dp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.Close, "Close", tint = p.dim, modifier = Modifier.size(42.dp).clip(CircleShape).background(p.surface).pressable(onClose).padding(10.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(t.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusMark(t.status, t.progress, 22.dp)
            Spacer(Modifier.size(8.dp))
            AnimatedContent(statusLabel(t.status), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "st") {
                Text(it, style = MaterialTheme.typography.labelLarge, color = when (t.status) {
                    TaskStatus.COMPLETED -> p.ok; TaskStatus.FAILED -> p.bad; TaskStatus.REQUIRES_INPUT -> p.warn; else -> p.dim })
            }
            Spacer(Modifier.weight(1f))
            Text(SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(t.createdAt)), style = MaterialTheme.typography.labelMedium, color = p.faint)
        }
        if (t.status == TaskStatus.REQUIRES_INPUT && t.input != null) InputCard(t.input, onAnswer)

        if (t.summary.isNotBlank() && !t.isActive) {
            Spacer(Modifier.height(16.dp))
            NCard(Modifier.fillMaxWidth(), color = p.raised) {
                Column(Modifier.padding(16.dp)) {
                    Text("RESULT", style = MaterialTheme.typography.labelSmall, color = p.faint)
                    Spacer(Modifier.height(6.dp))
                    Text(t.summary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (t.plan.isNotEmpty()) {
            Section("PLAN")
            val done = t.steps.count { it.status == StepStatus.DONE }
            t.plan.forEachIndexed { i, s ->
                val st = when { i < done -> StepStatus.DONE; i == done && t.isActive -> StepStatus.RUNNING; else -> StepStatus.SKIPPED }
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(when (st) { StepStatus.DONE -> p.text; StepStatus.RUNNING -> p.flame; else -> p.surface2 }),
                        contentAlignment = Alignment.Center) { Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (st == StepStatus.SKIPPED) p.dim else p.bg) }
                    Spacer(Modifier.size(12.dp))
                    Text(s, style = MaterialTheme.typography.bodyMedium, color = if (st == StepStatus.SKIPPED) p.dim else p.text)
                }
            }
        }
        if (t.steps.isNotEmpty()) {
            Section("WHAT NEBULA DID")
            t.steps.forEach { s ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    StepRow(s.label, s.status, s.verified)
                    if (s.detail.isNotBlank()) Text(s.detail, style = MaterialTheme.typography.bodySmall, color = p.faint, modifier = Modifier.padding(start = 18.dp))
                }
            }
        }
        val fs = t.fileIds.mapNotNull { files[it] }
        if (fs.isNotEmpty()) {
            Section("FILES")
            fs.forEach { f -> FileChip(f) { onOpenFile(f) }; Spacer(Modifier.height(8.dp)) }
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (t.isActive) Action("Cancel task", p.bad, onCancel)
            if (t.status == TaskStatus.FAILED || t.status == TaskStatus.CANCELLED) Action("Try again", p.flame, onRetry)
            Action("Open chat", p.text, onOpenChat)
            if (!t.isActive) Action("Delete", p.dim, onDelete)
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, style = MaterialTheme.typography.labelSmall, color = Pal.faint)
    Spacer(Modifier.height(8.dp))
}

@Composable private fun Action(label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(Pal.surface).pressable(onClick).padding(horizontal = 16.dp, vertical = 11.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
