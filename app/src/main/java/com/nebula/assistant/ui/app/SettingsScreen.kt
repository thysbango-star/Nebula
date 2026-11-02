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

        Group("Nebula Notch") {
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
