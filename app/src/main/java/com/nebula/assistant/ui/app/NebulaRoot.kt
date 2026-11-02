package com.nebula.assistant.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nebula.assistant.agent.TaskEngine
import com.nebula.assistant.data.FileRecord
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Things only the Activity can do (open files, ask for permissions…). */
class Host(
    val openFile: (FileRecord) -> Unit,
    val shareFile: (FileRecord) -> Unit,
    val openUrl: (String) -> Unit,
    val voice: (String) -> Unit,
    val requestPerm: (Perm) -> Unit,
    val restricted: () -> Unit,
    val setWake: (Boolean) -> Unit,
    val wakeSensitivity: (Float) -> Unit,
    val previewSound: (Boolean) -> Unit,
    val setDefaultSound: (Boolean) -> Unit,
    val setTheme: (String) -> Unit,
    val getKey: () -> Unit,
    val version: String,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NebulaRoot(engine: TaskEngine, prefs: NebulaPrefs, perms: List<Perm>, host: Host, startTab: Int, startTask: String?) {
    val p = Pal
    val conversations by Store.conversations.collectAsState()
    val tasks by Store.tasks.collectAsState()
    val files by Store.files.collectAsState()
    val activity by engine.activity.collectAsState()

    var onboarded by rememberSaveable { mutableStateOf(prefs.onboarded) }
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var overlay by rememberSaveable { mutableStateOf(if (startTask != null) "task" else "") }
    var taskId by rememberSaveable { mutableStateOf(startTask) }
    var convId by rememberSaveable { mutableStateOf(conversations.firstOrNull { System.currentTimeMillis() - it.updatedAt < 30 * 60_000 }?.id) }
    var hasKey by rememberSaveable { mutableStateOf(prefs.groqKey.isNotBlank()) }
    LaunchedEffect(startTab, startTask) { tab = startTab; if (startTask != null) { taskId = startTask; overlay = "task" } }

    val fileMap = files.associateBy { it.id }
    val taskMap = tasks.associateBy { it.id }
    fun ensureConv(): String = convId ?: Store.newConversation().id.also { convId = it }
    val chatActions = ChatActions(
        send = { engine.submit(ensureConv(), it) },
        mic = { host.voice(ensureConv()) },
        stop = { convId?.let { engine.stop(it) } },
        answer = { id, choice -> engine.answer(id, choice) },
        openTask = { taskId = it; overlay = "task" },
        openFile = host.openFile,
        openUrl = host.openUrl,
    )

    if (!onboarded) {
        Onboarding(perms, host.requestPerm, host.restricted, { prefs.groqKey = it; hasKey = true }, { engine.testKey(it) }, host.getKey) {
            prefs.onboarded = true; onboarded = true
        }
        return
    }

    BackHandler(enabled = overlay.isNotEmpty() || tab != 0) { if (overlay.isNotEmpty()) overlay = "" else tab = 0 }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(tab, { tab = it }, onHistory = { overlay = "history" }, onSettings = { overlay = "settings" },
                onNew = if (tab == 0) ({ convId = null }) else null,
                badge = tasks.count { it.status == com.nebula.assistant.data.TaskStatus.REQUIRES_INPUT })
            AnimatedContent(tab, transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 320f)) { it / 4 * dir } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(220)) { -it / 5 * dir } + fadeOut(tween(160)))
            }, label = "tabs", modifier = Modifier.weight(1f)) { t ->
                when (t) {
                    0 -> {
                        val conv = conversations.firstOrNull { it.id == convId }
                        ChatScreen(conv?.messages.orEmpty(), taskMap, fileMap, convId?.let { activity[it] }, hasKey || prefs.groqKey.isNotBlank(), chatActions) { overlay = "settings" }
                    }
                    1 -> TasksScreen(tasks, onAssign = { text -> val c = Store.newConversation(); engine.submit(c.id, text) },
                        onOpen = { taskId = it; overlay = "task" }, busy = false, onMic = { host.voice(Store.newConversation().id) })
                    else -> FilesScreen(files, host.openFile, host.shareFile, { Store.deleteFile(it.id) }) { tab = 0; chatActions.send(it) }
                }
            }
        }

        // Overlays slide up from the bottom with a spring.
        AnimatedVisibility(overlay == "task" && taskId != null && taskMap[taskId] != null,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 340f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(240)) { it } + fadeOut()) {
            taskMap[taskId]?.let { t ->
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    TaskDetail(t, fileMap, onClose = { overlay = "" }, onAnswer = { engine.answer(t.id, it) },
                        onCancel = { engine.cancel(t.id) }, onRetry = { overlay = ""; val c = Store.newConversation(); engine.submit(c.id, t.title) },
                        onDelete = { overlay = ""; Store.deleteTask(t.id) }, onOpenFile = host.openFile,
                        onOpenChat = { convId = t.conversationId; tab = 0; overlay = "" })
                }
            }
        }
        AnimatedVisibility(overlay == "settings", enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 340f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(240)) { it } + fadeOut()) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                SettingsScreen(prefs, perms, SettingsActions(
                    close = { overlay = ""; hasKey = prefs.groqKey.isNotBlank() },
                    saveKey = { prefs.groqKey = it; hasKey = true }, testKey = { engine.testKey(it) }, getKey = host.getKey,
                    setWake = host.setWake, wakeSensitivity = host.wakeSensitivity, requestPerm = host.requestPerm, restricted = host.restricted,
                    previewSound = host.previewSound, setDefaultSound = host.setDefaultSound, setTheme = host.setTheme,
                    replayOnboarding = { prefs.onboarded = false; onboarded = false; overlay = "" },
                ), host.version)
            }
        }
        AnimatedVisibility(overlay == "history", enter = slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 340f)) { -it } + fadeIn(),
            exit = slideOutHorizontally(tween(220)) { -it } + fadeOut()) {
            History(conversations.filter { it.messages.isNotEmpty() }, convId, onPick = { convId = it; tab = 0; overlay = "" },
                onDelete = { Store.deleteConversation(it); if (convId == it) convId = null }, onNew = { convId = null; tab = 0; overlay = "" }, onClose = { overlay = "" })
        }
    }
}

@Composable
private fun TopBar(tab: Int, onTab: (Int) -> Unit, onHistory: () -> Unit, onSettings: () -> Unit, onNew: (() -> Unit)?, badge: Int) {
    val p = Pal
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.Rounded.Menu, "History", onHistory)
        Spacer(Modifier.weight(1f))
        // Segmented control with a sliding indicator.
        val labels = listOf("Chat", "Tasks", "Files")
        BoxWithConstraints(Modifier.width(228.dp).height(42.dp).clip(CircleShape).background(p.surface).border(1.dp, p.line, CircleShape).padding(4.dp)) {
            val w = maxWidth / 3
            val x by animateDpAsState(w * tab, spring(dampingRatio = 0.72f, stiffness = 420f), label = "seg")
            Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(CircleShape).background(p.text))
            Row(Modifier.fillMaxSize()) {
                labels.forEachIndexed { i, l ->
                    Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).pressable({ onTab(i) }), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(l, style = MaterialTheme.typography.labelLarge, color = if (tab == i) p.bg else p.dim)
                            if (i == 1 && badge > 0) { Spacer(Modifier.width(4.dp)); Box(Modifier.size(7.dp).clip(CircleShape).background(p.warn)) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        AnimatedContent(onNew != null, label = "new") { show ->
            if (show) RoundIcon(Icons.Rounded.Add, "New chat") { onNew?.invoke() } else RoundIcon(Icons.Rounded.Settings, "Settings", onSettings)
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val p = Pal
    Icon(icon, desc, tint = p.text, modifier = Modifier.size(42.dp).clip(CircleShape).background(p.surface).border(1.dp, p.line, CircleShape).pressable(onClick).padding(10.dp))
}

@Composable
private fun History(list: List<com.nebula.assistant.data.Conversation>, current: String?, onPick: (String) -> Unit, onDelete: (String) -> Unit, onNew: () -> Unit, onClose: () -> Unit) {
    val p = Pal
    Box(Modifier.fillMaxSize().background(p.bg.copy(alpha = 0.6f)).pressable(onClose)) {
        Column(Modifier.fillMaxHeight().width(320.dp).background(p.bg).statusBarsPadding().pressable({})) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                RoundIcon(Icons.Rounded.Close, "Close", onClose)
            }
            Box(Modifier.padding(horizontal = 16.dp)) { Chip("New chat", icon = Icons.Rounded.Add, onClick = onNew) }
            Spacer(Modifier.height(10.dp))
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (list.isEmpty()) item { Text("No chats yet.", color = p.faint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)) }
                items(list, key = { it.id }) { c ->
                    NCard(Modifier.fillMaxWidth(), color = if (c.id == current) p.surface2 else p.surface, radius = 18.dp, onClick = { onPick(c.id) }) {
                        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(c.updatedAt)) + " · ${c.messages.size} messages",
                                    style = MaterialTheme.typography.labelMedium, color = p.faint)
                            }
                            Icon(Icons.Rounded.DeleteOutline, "Delete", tint = p.faint, modifier = Modifier.size(36.dp).clip(CircleShape).pressable({ onDelete(c.id) }).padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}
