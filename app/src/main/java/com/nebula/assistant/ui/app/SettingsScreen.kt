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
        }

        Group("Hey Nebula") {
            Toggle("Listen for the wake word", "Fully offline. Nothing leaves your phone until Nebula wakes.", wake) { wake = it; a.setWake(it) }
            Text("Sensitivity · ${if (sens < 0.34f) "Relaxed" else if (sens > 0.66f) "Eager" else "Balanced"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            Slider(sens, { sens = it }, onValueChangeFinished = { a.wakeSensitivity(sens) }, colors = sliderColors())
            Toggle("Speak replies", "Answers out loud as well as on screen.", prefs.speakReplies) { prefs.speakReplies = it; tick++ }
        }

        VoiceGroup(prefs)

        Group("Nebula Notch") {
            NotchSize(prefs)
            Keyed(tick) {
                Toggle("Notch", "The live island at the top of your screen.", prefs.notchEnabled) { prefs.notchEnabled = it; tick++ }
                Toggle("Music", "What's playing, with controls.", prefs.notchMusic) { prefs.notchMusic = it; tick++ }
                Toggle("Incoming calls", "Answer or decline from the island.", prefs.notchCalls) { prefs.notchCalls = it; tick++ }
                Toggle("Messages", "New message previews with reply.", prefs.notchMessages) { prefs.notchMessages = it; tick++ }
                Toggle("Volume", "Animated level when you press the volume keys.", prefs.notchVolume) { prefs.notchVolume = it; tick++ }
                Toggle("Unlock animation", "Scan ring → tick when your phone unlocks.", prefs.notchUnlock) { prefs.notchUnlock = it; tick++ }
                Toggle("Nebula tasks", "Live progress of what Nebula is doing.", prefs.notchTasks) { prefs.notchTasks = it; tick++ }
            }
            Text("Needs Screen control (and Notification access for music, messages and calls).", style = MaterialTheme.typography.bodySmall, color = p.faint, modifier = Modifier.padding(top = 6.dp))
        }

        R3cketGroup(prefs)

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
    Column(Modifier.padding(top = 6.dp)) {
        apps.forEach { (pkg, label) ->
            val auto = com.nebula.assistant.game.R3cket.isGame(ctx, pkg, prefs) && pkg !in picked
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (auto) Text("auto", style = MaterialTheme.typography.labelMedium, color = p.ok, modifier = Modifier.padding(end = 8.dp))
                Switch(auto || pkg in picked, { on ->
                    picked = if (on) picked + pkg else picked - pkg; prefs.gamePackages = picked
                }, enabled = !auto, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = p.flame, checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = p.faint, uncheckedTrackColor = p.surface2, uncheckedBorderColor = p.line))
            }
        }
    }
}

@Composable
private fun Keyed(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

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
