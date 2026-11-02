package com.nebula.assistant.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Welcome → permissions (each explained) → AI key → done. */
@Composable
fun Onboarding(perms: List<Perm>, onRequest: (Perm) -> Unit, onRestricted: () -> Unit, saveKey: (String) -> Unit,
               testKey: suspend (String) -> String, getKey: () -> Unit, onDone: () -> Unit) {
    val p = Pal
    var step by remember { mutableIntStateOf(0) }
    AnimatedContent(step, transitionSpec = {
        (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 300f)) { it / 3 } + fadeIn(tween(300))) togetherWith
            (slideOutHorizontally(tween(250)) { -it / 4 } + fadeOut(tween(200)))
    }, label = "onboarding", modifier = Modifier.fillMaxSize().background(p.bg)) { s ->
        when (s) {
            0 -> Box(Modifier.fillMaxSize()) {
                EmberGlow(Modifier.fillMaxWidth().height(560.dp).padding(top = 40.dp))
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(1f))
                    NebulaMark(84.dp)
                    Spacer(Modifier.height(22.dp))
                    Text("Smarter help.\nDone for you.", style = MaterialTheme.typography.displayMedium, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text("Nebula sends messages, makes documents, works your screen and tells you exactly what happened.",
                        style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                    Spacer(Modifier.weight(1.1f))
                    Big("Get started") { step = 1 }
                }
            }
            1 -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
                Text("Let Nebula help", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(6.dp))
                Text("Each permission switches on one thing. Allow what you want — you can change it later in Settings.",
                    style = MaterialTheme.typography.bodyMedium, color = p.dim)
                Spacer(Modifier.height(14.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    perms.forEach { perm ->
                        NCard(Modifier.fillMaxWidth().padding(vertical = 5.dp), color = p.raised, radius = 22.dp) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { PermRow(perm) { onRequest(perm) } }
                        }
                    }
                    NCard(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = p.surface, radius = 22.dp) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Screen control or Notification access greyed out?", style = MaterialTheme.typography.titleSmall)
                            Text("Because Nebula was installed from a file, Android asks once: App info → ⋮ (top right) → Allow restricted settings. Then come back and tap Allow.",
                                style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 4.dp))
                            Text("Open App info ›", style = MaterialTheme.typography.labelLarge, color = p.flame, modifier = Modifier.padding(top = 8.dp).pressable(onRestricted))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Back", style = MaterialTheme.typography.labelLarge, color = p.dim, modifier = Modifier.pressable({ step = 0 }).padding(12.dp))
                    Spacer(Modifier.weight(1f))
                    Big("Continue", Modifier.width(200.dp)) { step = 2 }
                }
            }
            2 -> KeyStep(saveKey, testKey, getKey, back = { step = 1 }, next = { step = 3 })
            else -> Box(Modifier.fillMaxSize()) {
                EmberGlow(Modifier.fillMaxWidth().height(520.dp).padding(top = 60.dp))
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(1f))
                    NebulaMark(72.dp, spinning = true)
                    Spacer(Modifier.height(20.dp))
                    Text("You're set", style = MaterialTheme.typography.displayMedium, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Text("Try “Hey Nebula, text Mom I'll be five minutes late” or “Make a budget spreadsheet”.",
                        style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                    Spacer(Modifier.weight(1.1f))
                    Big("Start") { onDone() }
                }
            }
        }
    }
}

@Composable
private fun KeyStep(saveKey: (String) -> Unit, testKey: suspend (String) -> String, getKey: () -> Unit, back: () -> Unit, next: () -> Unit) {
    val p = Pal
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        Text("Switch on the brain", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(6.dp))
        Text("Nebula uses Groq's free AI. Make a key at console.groq.com → API Keys → Create (starts with gsk_), then paste it here. It stays on this phone.",
            style = MaterialTheme.typography.bodyMedium, color = p.dim)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.raised).border(1.dp, p.line, RoundedCornerShape(18.dp)).padding(16.dp)) {
            if (key.isEmpty()) Text("gsk_…", color = p.faint, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(key, { key = it.trim() }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text), cursorBrush = SolidColor(p.flame), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn(if (busy) "Checking…" else "Save & test", primary = true) {
                if (key.length < 20) { status = "Paste the whole key."; return@Btn }
                saveKey(key); scope.launch { busy = true; status = testKey(key); busy = false }
            }
            Text("Get a free key ›", style = MaterialTheme.typography.labelLarge, color = p.flame, modifier = Modifier.pressable(getKey).padding(8.dp))
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.dim, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Back", style = MaterialTheme.typography.labelLarge, color = p.dim, modifier = Modifier.pressable(back).padding(12.dp))
            Spacer(Modifier.weight(1f))
            Text("Skip", style = MaterialTheme.typography.labelLarge, color = p.dim, modifier = Modifier.pressable(next).padding(12.dp))
            Big("Continue", Modifier.width(160.dp)) { next() }
        }
    }
}

@Composable
private fun Big(label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val p = Pal
    Box(modifier.clip(CircleShape).background(p.text).pressable(onClick).padding(vertical = 17.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = p.bg)
    }
}
