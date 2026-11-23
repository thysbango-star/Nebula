package com.nebula.assistant.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nebula.assistant.data.NebulaPrefs
import kotlinx.coroutines.launch

data class SettingsActions(
    val close: () -> Unit,
    val saveKey: (String) -> Unit,
    val testKey: suspend (String) -> String,
    val getKey: () -> Unit,
    val setWake: (Boolean) -> Unit,
    val wakeSensitivity: (Float) -> Unit,
    val requestPerm: (Perm) -> Unit,
    val restricted: () -> Unit,
    val previewSound: (Boolean) -> Unit,
    val setDefaultSound: (Boolean) -> Unit,
    val setTheme: (String) -> Unit,
    val replayOnboarding: () -> Unit,
)

@Composable
fun SettingsScreen(prefs: NebulaPrefs, perms: List<Perm>, a: SettingsActions, version: String) {
    val p = Pal
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var keyStatus by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(prefs.groqKey.isNotBlank()) }
    var testing by remember { mutableStateOf(false) }
    var wake by remember { mutableStateOf(prefs.wakeEnabled) }
    var sens by remember { mutableFloatStateOf(prefs.sensitivity) }
    var glow by remember { mutableFloatStateOf(prefs.vibranceIntensity) }
    var theme by remember { mutableStateOf(prefs.theme) }
    var tick by remember { mutableStateOf(0) } // re-read toggles after changes

    Column(Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Close, "Close", tint = p.dim, modifier = Modifier.size(42.dp).clip(CircleShape).background(p.surface).pressable(a.close).padding(10.dp))
        }

        Group("AI brain", if (hasKey) "Connected" else "Not set up", if (hasKey) p.ok else p.warn) {
            Text("Nebula thinks with Groq's free AI. Paste your key from console.groq.com (starts with gsk_).", style = MaterialTheme.typography.bodySmall, color = p.dim)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(14.dp)) {
                if (key.isEmpty()) Text(if (hasKey) "Replace key…" else "gsk_…", color = p.faint, style = MaterialTheme.typography.bodyMedium)
                BasicTextField(key, { key = it.trim() }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.text), cursorBrush = SolidColor(p.flame), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Btn("Save", primary = true) { if (key.length >= 20) { a.saveKey(key); key = ""; hasKey = true; keyStatus = "Saved. Tap Test to check it." } else keyStatus = "That doesn't look like a full key." }
                Btn(if (testing) "Testing…" else "Test") { if (!testing) scope.launch { testing = true; keyStatus = a.testKey(key); testing = false } }
                Spacer(Modifier.weight(1f))
                Text("Get a free key ›", style = MaterialTheme.typography.labelLarge, color = p.flame, modifier = Modifier.pressable(a.getKey))
            }
            keyStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 10.dp)) }
            BackupKeys(prefs)
        }

        Group("Hey Nebula") {
            Toggle("Listen for the wake word", "Fully offline. Nothing leaves your phone until Nebula wakes.", wake) { wake = it; a.setWake(it) }
            Text("Sensitivity · ${if (sens < 0.34f) "Relaxed" else if (sens > 0.66f) "Eager" else "Balanced"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            Slider(sens, { sens = it }, onValueChangeFinished = { a.wakeSensitivity(sens) }, colors = sliderColors())
            Toggle("Speak replies", "Answers out loud as well as on screen.", prefs.speakReplies) { prefs.speakReplies = it; tick++ }
            Keyed(tick) {
                Toggle("Conversation mode", "After Nebula answers it keeps listening a few seconds, so you can just ask a follow-up.", prefs.followUps) { prefs.followUps = it; tick++ }
                Toggle("Share my screen", "Nebula looks at your screen when you ask something, so it understands what you're looking at. Needs Screen control (Android 11+).", prefs.screenShare) { prefs.screenShare = it; tick++ }
            }
        }

        NameCoach(prefs)

        VoiceGroup(prefs)

        Group("Nebula Notch") {
            NotchSize(prefs)
            Keyed(tick) {
                Toggle("Notch", "The live island at the top of your screen.", prefs.notchEnabled) { prefs.notchEnabled = it; tick++ }
                Toggle("Music", "What's playing, with controls.", prefs.notchMusic) { prefs.notchMusic = it; tick++ }
                Toggle("Incoming calls", "Answer or decline from the island.", prefs.notchCalls) { prefs.notchCalls = it; tick++ }
                Toggle("Messages", "New message previews with reply.", prefs.notchMessages) { prefs.notchMessages = it; tick++ }
                Toggle("Volume", "Animated level when you press the volume keys.", prefs.notchVolume) { prefs.notchVolume = it; tick++ }
                Toggle("Earbuds & headphones", "Shows when AirPods, Buds or headphones connect.", prefs.notchDevices) { prefs.notchDevices = it; tick++ }
                Toggle("Unlock animation", "Scan ring → tick when your phone unlocks.", prefs.notchUnlock) { prefs.notchUnlock = it; tick++ }
                Toggle("Nebula tasks", "Live progress of what Nebula is doing.", prefs.notchTasks) { prefs.notchTasks = it; tick++ }
            }
            Text("Needs Screen control (and Notification access for music, messages and calls).", style = MaterialTheme.typography.bodySmall, color = p.faint, modifier = Modifier.padding(top = 6.dp))
        }

        R3cketGroup(prefs)
        SportsGroup(prefs)

        Group("Vibrance glow") {
            Text("The light that wraps the screen when Nebula wakes.", style = MaterialTheme.typography.bodySmall, color = p.dim)
            Slider(glow, { glow = it }, onValueChangeFinished = { prefs.vibranceIntensity = glow }, colors = sliderColors())
        }

        Group("Sounds") {
            Keyed(tick) {
                Toggle("Voice cues", "Chimes when Nebula wakes and stops listening.", prefs.voiceCues) { prefs.voiceCues = it; tick++ }
                Toggle("Task sounds", "When a task completes, fails or needs you.", prefs.taskSounds) { prefs.taskSounds = it; tick++ }
            }
            Text("Nebula's own ringtone and notification tone", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("▶ Ringtone") { a.previewSound(true) }
                Btn("Set as ringtone") { a.setDefaultSound(true) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("▶ Chime") { a.previewSound(false) }
                Btn("Set for notifications") { a.setDefaultSound(false) }
            }
        }

        Group("Messaging") {
            Keyed(tick) { Toggle("Ask before sending messages", "Calls and payments always ask.", prefs.confirmMessages) { prefs.confirmMessages = it; tick++ } }
        }

        Group("Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (v, l) -> Chip(l, theme == v) { theme = v; a.setTheme(v) } }
            }
        }

        Group("Permissions") {
            perms.forEach { perm -> PermRow(perm) { a.requestPerm(perm) } }
            Text("Can't switch on Screen control or Notification access? Android blocks them for apps installed from a file. Open App info → ⋮ → “Allow restricted settings”, then try again.",
                style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 8.dp))
            Text("Open App info ›", style = MaterialTheme.typography.labelLarge, color = p.flame, modifier = Modifier.padding(top = 8.dp).pressable(a.restricted))
        }

        Group("About") {
            Text("Nebula $version", style = MaterialTheme.typography.bodyMedium)
            Text("Show the welcome tour again ›", style = MaterialTheme.typography.labelLarge, color = p.flame, modifier = Modifier.padding(top = 8.dp).pressable(a.replayOnboarding))
        }
        Spacer(Modifier.height(30.dp))
    }
}

// ---------------------------------------------------------------------- backup keys

@Composable
private fun BackupKeys(prefs: NebulaPrefs) {
    val p = Pal
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(prefs.groqExtraKeys.size) }
    var note by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Backup keys", style = MaterialTheme.typography.bodyLarge)
            Text(if (count == 0) "Add more Groq keys: when one runs out for the day, Nebula switches automatically."
                else "$count backup key${if (count > 1) "s" else ""} · using key ${prefs.activeKeyNumber} of ${prefs.allGroqKeys.size}",
                style = MaterialTheme.typography.bodySmall, color = p.dim)
        }
        Text(if (open) "Done" else if (count == 0) "Add" else "Edit", style = MaterialTheme.typography.labelLarge, color = p.flame,
            modifier = Modifier.padding(start = 10.dp).pressable({ open = !open; note = null }))
    }
    if (open) {
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(14.dp)) {
            if (text.isEmpty()) Text("Paste one key per line (gsk_…)", color = p.faint, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(text, { text = it }, visualTransformation = PasswordVisualTransformation(), minLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.text), cursorBrush = SolidColor(p.flame), modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Add keys", primary = true) {
                val fresh = text.split(Regex("[\\s,]+")).map { NebulaPrefs.cleanKey(it) }.filter { it.length >= 20 }
                if (fresh.isEmpty()) { note = "Paste at least one full key."; return@Btn }
                prefs.groqExtraKeys = prefs.groqExtraKeys + fresh; count = prefs.groqExtraKeys.size; text = ""
                note = "Added ${fresh.size}. Tip: make each key in a different Groq account — limits are per account."
            }
            if (count > 0) Btn("Remove all") { prefs.groqExtraKeys = emptyList(); count = 0; note = "Backup keys removed." }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 8.dp)) }
    }
}

// ---------------------------------------------------------------------- voice

private val VoiceModels = listOf("canopylabs/orpheus-v1-english" to "Orpheus English", "canopylabs/orpheus-arabic-saudi" to "Orpheus Arabic")
private val Voices = listOf("troy" to "Troy", "austin" to "Austin", "daniel" to "Daniel", "autumn" to "Autumn", "diana" to "Diana", "hannah" to "Hannah")

@Composable
private fun VoiceGroup(prefs: NebulaPrefs) {
    val p = Pal
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf(prefs.voiceEngine) }
    var voice by remember { mutableStateOf(prefs.groqVoice) }
    var model by remember { mutableStateOf(prefs.groqVoiceModel) }
    var eleven by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    val speaker = remember { com.nebula.assistant.voice.Speaker(ctx) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { speaker.release() } }

    fun test() {
        if (playing) { speaker.stop(); playing = false; return }
        scope.launch {
            playing = true; status = null
            speaker.speak("Hey, I'm Nebula. This is how I'll sound when we talk.")
            status = speaker.lastProblem?.let { "Used the phone voice: $it" } ?: if (prefs.voiceEngine == "system") "That's the phone's own voice." else "That's the natural voice. Sounds good?"
            playing = false
        }
    }

    Group("Voice", when (engine) { "groq" -> "Natural"; "eleven" -> "ElevenLabs"; else -> "Phone" }, p.ok) {
        Text("How Nebula sounds when it talks back.", style = MaterialTheme.typography.bodySmall, color = p.dim)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("groq" to "Natural (free)", "eleven" to "ElevenLabs", "system" to "Phone voice").forEach { (v, l) ->
                Chip(l, engine == v) { engine = v; prefs.voiceEngine = v; status = null }
            }
        }
        when (engine) {
            "groq" -> {
                Text("Human-sounding speech from Groq's Orpheus voices, using your Groq key. First time only: open console.groq.com → Playground, choose the Orpheus model and accept its terms.",
                    style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 10.dp))
                Text("Voice", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Voices.forEach { (v, l) -> Chip(l, voice == v) { voice = v; prefs.groqVoice = v } }
                }
                Text("Model", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceModels.forEach { (v, l) -> Chip(l, model == v) { model = v; prefs.groqVoiceModel = v; status = null } }
                }
                Text(model, style = MaterialTheme.typography.labelMedium, color = p.faint, modifier = Modifier.padding(top = 6.dp))
            }
            "eleven" -> {
                Text("Paste a free ElevenLabs API key (elevenlabs.io → Profile → API keys). Kept only on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 10.dp))
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(14.dp)) {
                    if (eleven.isEmpty()) Text(if (prefs.elevenKey.isNotBlank()) "Key saved · paste to replace" else "ElevenLabs key", color = p.faint, style = MaterialTheme.typography.bodyMedium)
                    BasicTextField(eleven, { eleven = it.trim() }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.text), cursorBrush = SolidColor(p.flame), modifier = Modifier.fillMaxWidth())
                }
                if (eleven.isNotEmpty()) Row(Modifier.padding(top = 8.dp)) { Btn("Save key", primary = true) { prefs.elevenKey = eleven; eleven = ""; status = "Saved." } }
            }
            else -> Text("Uses the voice installed on your phone (Samsung or Google). Works offline.",
                style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 10.dp))
        }
        Row(Modifier.padding(top = 12.dp)) { Btn(if (playing) "■ Stop" else "▶ Hear Nebula", primary = true) { test() } }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 8.dp)) }
    }
}

// ---------------------------------------------------------------------- notch size

@Composable
private fun NotchSize(prefs: NebulaPrefs) {
    val p = Pal
    var scale by remember { mutableFloatStateOf(prefs.notchScale) }
    var height by remember { mutableFloatStateOf(prefs.notchHeight) }
    var y by remember { mutableFloatStateOf(prefs.notchY) }
    var x by remember { mutableFloatStateOf(prefs.notchX) }
    val scope = rememberCoroutineScope()
    var hide by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun live(set: () -> Unit) { set(); hide?.cancel(); com.nebula.assistant.notch.NotchBus.preview.value = true }
    fun done() { hide?.cancel(); hide = scope.launch { kotlinx.coroutines.delay(1800); com.nebula.assistant.notch.NotchBus.preview.value = false } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.nebula.assistant.notch.NotchBus.preview.value = false } }

    Text("Size & position — the island moves as you drag.", style = MaterialTheme.typography.bodySmall, color = p.dim)
    SliderRow("Size", "${(scale * 100).toInt()}%", scale, 0.7f..1.5f, { scale = it; live { prefs.notchScale = it } }, ::done)
    SliderRow("Height", "${(height * 100).toInt()}%", height, 0.7f..1.6f, { height = it; live { prefs.notchHeight = it } }, ::done)
    SliderRow("Down from top", "${y.toInt()}", y, -20f..60f, { y = it; live { prefs.notchY = it } }, ::done)
    SliderRow("Left / right", "${x.toInt()}", x, -120f..120f, { x = it; live { prefs.notchX = it } }, ::done)
    Row(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Btn("Reset") { scale = 1f; height = 1f; y = 0f; x = 0f; live { prefs.notchScale = 1f; prefs.notchHeight = 1f; prefs.notchY = 0f; prefs.notchX = 0f }; done() }
    }
}

@Composable
private fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = Pal.dim)
    }
    Slider(v, onChange, valueRange = range, onValueChangeFinished = onDone, colors = sliderColors())
}

// ---------------------------------------------------------------------- R3cket

@Composable
private fun R3cketGroup(prefs: NebulaPrefs) {
    val p = Pal
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var interval by remember { mutableFloatStateOf(prefs.clickInterval.toFloat()) }
    var showApps by remember { mutableStateOf(false) }
    Group("R3cket", "Game mode", p.flame) {
        Text("Spots when you're in a game. “Hey Nebula” becomes a slim bar at the top so play never stops, and you can say “auto-click”, “set tap points”, “faster” or “stop clicking”.",
            style = MaterialTheme.typography.bodySmall, color = p.dim)
        Keyed(tick) {
            Toggle("R3cket", "Detect games automatically.", prefs.r3cketEnabled) { prefs.r3cketEnabled = it; tick++ }
            Toggle("Quiet island", "Hide message pop-ups and music while you play.", prefs.r3cketQuiet) { prefs.r3cketQuiet = it; tick++ }
            Toggle("Keep screen awake", "Stops the screen dimming mid-game.", prefs.r3cketAwake) { prefs.r3cketAwake = it; tick++ }
            Toggle("Boost on launch", "Closes apps idling in the background to free memory for the game, and shows how much it freed.", prefs.r3cketBoost) { prefs.r3cketBoost = it; tick++ }
            Toggle("Do not disturb while playing", "Silences calls and notifications during a game, then puts things back.", prefs.r3cketDnd) {
                prefs.r3cketDnd = it; tick++
                if (it && !com.nebula.assistant.game.R3cket.dndAllowed(ctx)) runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            Toggle("Game HUD", "Game time, ping and phone temperature around the camera. Tap it for the R3cket panel.", prefs.r3cketHud) { prefs.r3cketHud = it; tick++ }
            Toggle("Crosshair", "A centre aim dot for shooters (can also be switched from the panel).", prefs.r3cketCrosshair) { prefs.r3cketCrosshair = it; tick++ }
        }
        val cps = 1000f / interval
        SliderRow("Auto-click speed", if (cps >= 1) "${"%.1f".format(cps)} taps/s" else "every ${"%.1f".format(interval / 1000f)} s",
            kotlin.math.ln(interval), kotlin.math.ln(30f)..kotlin.math.ln(5000f),
            { interval = kotlin.math.exp(it) }, { com.nebula.assistant.game.R3cket.setInterval(ctx, interval.toInt()) })
        Text("Some online games ban auto-clickers in their rules (and may suspend accounts). Use it in games where that's allowed.",
            style = MaterialTheme.typography.bodySmall, color = p.faint, modifier = Modifier.padding(top = 4.dp))
        Text(if (showApps) "Hide game list ›" else "Choose which apps count as games ›", style = MaterialTheme.typography.labelLarge, color = p.flame,
            modifier = Modifier.padding(top = 10.dp).pressable(onClick = { showApps = !showApps }))
        if (showApps) GameList(prefs)
    }
}

@Composable
private fun GameList(prefs: NebulaPrefs) {
    val p = Pal
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val apps = remember {
        val pm = ctx.packageManager
        pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.filter { it.first != ctx.packageName }.sortedBy { it.second.lowercase() }
    }
    var picked by remember { mutableStateOf(prefs.gamePackages) }
    var blocked by remember { mutableStateOf(prefs.notGames) }
    // Looking inside apps for a game engine takes a moment: do it off the main thread.
    val detected by androidx.compose.runtime.produceState(emptySet<String>(), apps) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val clean = NebulaPrefs(ctx).also { } // fresh read, ignoring the user's own lists below
            apps.map { it.first }.filter { p -> p !in clean.gamePackages && p !in clean.notGames && com.nebula.assistant.game.R3cket.isGame(ctx, p, clean) }.toSet()
        }
    }
    Column(Modifier.padding(top = 6.dp)) {
        if (detected.isEmpty()) Text("Scanning your apps for games…", style = MaterialTheme.typography.bodySmall, color = p.faint)
        apps.forEach { (pkg, label) ->
            val auto = pkg in detected && pkg !in picked
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (auto) Text("auto", style = MaterialTheme.typography.labelMedium, color = p.ok, modifier = Modifier.padding(end = 8.dp))
                Switch((auto || pkg in picked) && pkg !in blocked, { on ->
                    picked = if (on && !auto) picked + pkg else picked - pkg; prefs.gamePackages = picked
                    blocked = if (on) blocked - pkg else if (auto) blocked + pkg else blocked; prefs.notGames = blocked
                }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = p.flame, checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = p.faint, uncheckedTrackColor = p.surface2, uncheckedBorderColor = p.line))
            }
        }
    }
}

// ---------------------------------------------------------------------- Sports

@Composable
private fun SportsGroup(prefs: NebulaPrefs) {
    val p = Pal
    var tick by remember { mutableStateOf(0) }
    var following by remember { mutableStateOf(prefs.sportsTeams.mapNotNull(com.nebula.assistant.sports.Sports.Team::decode)) }
    var sport by remember { mutableStateOf<String?>(null) }
    var country by remember { mutableStateOf<String?>(null) }
    var league by remember { mutableStateOf<com.nebula.assistant.sports.Sports.League?>(null) }
    var query by remember { mutableStateOf("") }
    fun save(list: List<com.nebula.assistant.sports.Sports.Team>) { following = list; prefs.sportsTeams = list.map { it.encode() }.toSet() }
    Group("Sports", if (following.isEmpty()) null else "${following.size} team${if (following.size == 1) "" else "s"}", p.ok) {
        Text("Football, rugby and cricket (plus US sports). Follow your teams and their live score sits around the camera while they play; tap it for the full card. Goals, tries, sixes and wickets get their own moment.",
            style = MaterialTheme.typography.bodySmall, color = p.dim)
        Keyed(tick) {
            Toggle("Live scores in the notch", "Only while one of your teams is playing.", prefs.sportsOn) { prefs.sportsOn = it; tick++ }
            Toggle("Goal moments", "The goal animation, a buzz and a cheer when your team scores.", prefs.sportsGoals) { prefs.sportsGoals = it; tick++ }
        }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Btn("Preview a goal", primary = true) { com.nebula.assistant.sports.Sports.startDemo(com.nebula.assistant.sports.Sports.Kind.SOCCER) }
            Spacer(Modifier.size(8.dp))
            Btn("A try") { com.nebula.assistant.sports.Sports.startDemo(com.nebula.assistant.sports.Sports.Kind.RUGBY) }
            Spacer(Modifier.size(8.dp))
            Btn("A six") { com.nebula.assistant.sports.Sports.startDemo(com.nebula.assistant.sports.Sports.Kind.CRICKET) }
        }
        Text("Watch the top of your screen.", style = MaterialTheme.typography.bodySmall, color = p.faint, modifier = Modifier.padding(top = 4.dp))
        if (following.isNotEmpty()) {
            Text("Following", style = MaterialTheme.typography.labelLarge, color = p.dim, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            following.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeamDot(t)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(t.name, style = MaterialTheme.typography.bodyLarge)
                        val lg = com.nebula.assistant.sports.Sports.league(t.league)
                        Text(listOfNotNull(lg?.sport, lg?.name).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = p.faint)
                    }
                    Icon(Icons.Rounded.Close, "Unfollow", tint = p.faint, modifier = Modifier.size(22.dp).pressable(onClick = { save(following - t) }))
                }
            }
        }
        // Pick: sport → country → league → team.
        Text("Add a team", style = MaterialTheme.typography.labelLarge, color = p.dim, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        val all = com.nebula.assistant.sports.Sports.LEAGUES
        ChipRow(com.nebula.assistant.sports.Sports.SPORTS, sport) { sport = if (sport == it) null else it; country = null; league = null; query = "" }
        sport?.let { sp ->
            val countries = all.filter { it.sport == sp }.map { it.country }.distinct()
            Text("Country", style = MaterialTheme.typography.labelMedium, color = p.faint, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            ChipRow(countries, country) { c ->
                country = if (country == c) null else c; query = ""
                league = all.filter { it.sport == sp && it.country == c }.singleOrNull()
            }
            country?.let { c ->
                val leagues = all.filter { it.sport == sp && it.country == c }
                if (leagues.size > 1) {
                    Text("League", style = MaterialTheme.typography.labelMedium, color = p.faint, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    ChipRow(leagues.map { it.name }, league?.name) { n -> league = leagues.firstOrNull { it.name == n }; query = "" }
                } else Text(leagues.firstOrNull()?.name ?: "", style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 8.dp))
            }
        }
        league?.let { lg ->
            val teams by androidx.compose.runtime.produceState<List<com.nebula.assistant.sports.Sports.Team>?>(null, lg) {
                value = runCatching { com.nebula.assistant.sports.Sports.teams(lg) }.getOrDefault(emptyList())
            }
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface2).padding(horizontal = 14.dp, vertical = 11.dp)) {
                if (query.isEmpty()) Text("Search ${lg.name}", style = MaterialTheme.typography.bodyMedium, color = p.faint)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.text),
                    cursorBrush = SolidColor(p.flame), modifier = Modifier.fillMaxWidth())
            }
            when {
                teams == null -> Text("Loading teams…", style = MaterialTheme.typography.bodySmall, color = p.faint, modifier = Modifier.padding(top = 8.dp))
                teams!!.isEmpty() -> Text("Couldn't load teams. Check your connection.", style = MaterialTheme.typography.bodySmall, color = p.warn, modifier = Modifier.padding(top = 8.dp))
                else -> Column(Modifier.padding(top = 6.dp)) {
                    teams!!.filter { query.isBlank() || it.name.contains(query, true) || it.abbr.contains(query, true) }.take(40).forEach { t ->
                        val on = following.any { it.league == t.league && it.id == t.id }
                        Row(Modifier.fillMaxWidth().pressable(onClick = { save(if (on) following.filterNot { it.league == t.league && it.id == t.id } else following + t) })
                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TeamDot(t)
                            Text(t.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                            Text(if (on) "★" else "☆", style = MaterialTheme.typography.titleLarge, color = if (on) p.flame else p.faint)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(items: List<String>, selected: String?, onPick: (String) -> Unit) {
    val p = Pal
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        items.forEach { item ->
            val on = item == selected
            Box(Modifier.padding(end = 6.dp).clip(CircleShape).background(if (on) p.text else p.surface2)
                .pressable(onClick = { onPick(item) }).padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(item, style = MaterialTheme.typography.labelLarge, color = if (on) p.bg else p.text)
            }
        }
    }
}

@Composable
private fun TeamDot(t: com.nebula.assistant.sports.Sports.Team) {
    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(t.color)), contentAlignment = Alignment.Center) {
        Text(t.abbr.take(3), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
private fun Keyed(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

// ---------------------------------------------------------------------- name coach

/** Say your name five times; Nebula learns how it's spelled, how it sounds, and how to say it back. */
@Composable
private fun NameCoach(prefs: NebulaPrefs) {
    val p = Pal
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(prefs.userName) }
    var step by remember { mutableStateOf(-1) }           // -1 idle, 0..4 recording takes, 5 choosing
    var heard by remember { mutableStateOf(listOf<String>()) }
    var options by remember { mutableStateOf(listOf<String>()) }
    var chosen by remember { mutableStateOf(prefs.nameSayAs) }
    var status by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }
    val speaker = remember { com.nebula.assistant.voice.Speaker(ctx) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { speaker.release() } }
    fun say(spelling: String) = scope.launch { speaker.speak("Hi $spelling, nice to meet you.") }

    suspend fun take() {
        listening = true; status = null
        val wav = com.nebula.assistant.voice.Names.recordTake()
        listening = false
        if (wav == null) { status = "I didn't hear anything. Try again."; return }
        val t = runCatching { com.nebula.assistant.voice.Hearing.transcribe(prefs, wav, coaching = true) }.getOrElse { status = it.message; return }
            .trim().trim('.', '!', '?', ',').removePrefix("My name is ").removePrefix("my name is ").trim()
        heard = heard + t.ifBlank { "?" }
        step++
        if (step == 5) {
            status = "Finding how to say it…"
            options = runCatching { com.nebula.assistant.voice.Names.suggest(prefs, name, heard) }.getOrElse { listOf(name) }
            chosen = options.first(); status = "Tap each one to hear it, then pick the one that sounds like you."
        }
    }

    Group("Name coach", if (prefs.nameSayAs.isNotBlank()) "Learned" else null, p.ok) {
        Text("Teach Nebula your name: say it 5 times so Nebula hears it right and says it the way you do.", style = MaterialTheme.typography.bodySmall, color = p.dim)
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(14.dp)) {
            if (name.isEmpty()) Text("Your name, as you write it", color = p.faint, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(name, { name = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.text),
                cursorBrush = SolidColor(p.flame), modifier = Modifier.fillMaxWidth())
        }
        when {
            step in 0..4 -> {
                Text("Say your name — take ${step + 1} of 5", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(5) { i -> Box(Modifier.size(10.dp).clip(CircleShape).background(if (i < step) p.ok else if (i == step) p.flame else p.surface2)) }
                }
                if (heard.isNotEmpty()) Text("Heard: ${heard.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (listening) "Listening…" else "● Record", primary = true) { if (!listening) scope.launch { take() } }
                    Btn("Cancel") { step = -1; heard = emptyList() }
                }
            }
            step == 5 -> {
                Text("How should Nebula say it?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { o -> Chip("▶ $o", chosen == o) { chosen = o; say(o) } }
                }
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(14.dp)) {
                    BasicTextField(chosen, { chosen = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.text),
                        cursorBrush = SolidColor(p.flame), modifier = Modifier.fillMaxWidth())
                }
                Text("Or type your own spelling above and tap Hear.", style = MaterialTheme.typography.bodySmall, color = p.faint, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Save", primary = true) {
                        prefs.userName = name; prefs.nameSayAs = chosen
                        prefs.nameHeardAs = heard.filter { it != "?" }.map { it.lowercase() }.toSet()
                        step = -1; status = "Got it. Nebula will call you ${name}."
                        say(chosen)
                    }
                    Btn("Hear") { say(chosen) }
                    Btn("Start over") { step = 0; heard = emptyList() }
                }
            }
            else -> Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (prefs.nameSayAs.isBlank()) "Teach my name" else "Coach again", primary = true) {
                    if (name.isBlank()) status = "Type your name first." else {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            status = "Nebula needs microphone permission (Permissions above)."
                        else { prefs.userName = name; step = 0; heard = emptyList() }
                    }
                }
                if (prefs.nameSayAs.isNotBlank()) Btn("Hear it") { say(prefs.nameSayAs) }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun Group(title: String, badge: String? = null, badgeColor: Color = Color.Unspecified, content: @Composable ColumnScope.() -> Unit) {
    val p = Pal
    Spacer(Modifier.height(12.dp))
    NCard(Modifier.fillMaxWidth(), color = p.raised) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (badge != null) Text(badge, style = MaterialTheme.typography.labelMedium, color = badgeColor)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val p = Pal
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = p.dim)
        }
        Spacer(Modifier.size(10.dp))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = p.flame, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = p.faint, uncheckedTrackColor = p.surface2, uncheckedBorderColor = p.line))
    }
}

@Composable
fun PermRow(perm: Perm, onAllow: () -> Unit) {
    val p = Pal
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(perm.title + if (perm.essential) "" else "", style = MaterialTheme.typography.bodyLarge)
            Text(perm.why, style = MaterialTheme.typography.bodySmall, color = p.dim)
        }
        Spacer(Modifier.size(10.dp))
        if (perm.granted) Icon(Icons.Rounded.CheckCircle, "Allowed", tint = p.ok, modifier = Modifier.size(28.dp))
        else Btn("Allow", primary = true, onClick = onAllow)
    }
}

@Composable
fun Btn(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val p = Pal
    Box(Modifier.clip(CircleShape).background(if (primary) p.text else p.surface2).pressable(onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (primary) p.bg else p.text)
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Pal.flame, inactiveTrackColor = Pal.surface2)
