package com.nebula.assistant.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nebula.assistant.data.WakeState
import com.nebula.assistant.data.WakeStatus
import com.nebula.assistant.ui.components.GlassSurface
import com.nebula.assistant.ui.components.NebulaBackground
import com.nebula.assistant.ui.components.NebulaOrb
import com.nebula.assistant.ui.components.OrbMood
import com.nebula.assistant.ui.theme.NebulaColors
import com.nebula.assistant.ui.vibrance.VibranceGlow
import com.nebula.assistant.ui.vibrance.rememberVibranceClock

data class HomeUiState(
    val wake: WakeState,
    val micLevel: Float,
    val wakeEnabled: Boolean,
    val sensitivity: Float,
    val speakReplies: Boolean,
    val permissions: PermissionStatus,
    val hasGeminiKey: Boolean,
    val geminiModel: String,
    val aiStatus: String?,
    val aiTesting: Boolean,
    val vibrance: Float,
)

data class HomeActions(
    val onWakeToggle: (Boolean) -> Unit,
    val onSensitivityChange: (Float) -> Unit,
    val onSensitivityDone: () -> Unit,
    val onSpeakToggle: (Boolean) -> Unit,
    val onTalk: () -> Unit,
    val onAllowMic: () -> Unit,
    val onAllowNotifications: () -> Unit,
    val onAllowOverlay: () -> Unit,
    val onAllowBattery: () -> Unit,
    val onSaveAi: (key: String, model: String) -> Unit,
    val onTestAi: (key: String, model: String) -> Unit,
    val onGetKey: () -> Unit,
    val onVibranceChange: (Float) -> Unit,
    val onVibranceDone: () -> Unit,
)

@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions) {
    val listening = state.wake.status == WakeStatus.Listening
    Box(Modifier.fillMaxSize()) {
        NebulaBackground(Modifier.fillMaxSize(), energy = if (listening) state.micLevel else 0f)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(14.dp))
            Header(state.wake, state.wakeEnabled)

            NebulaOrb(
                Modifier.size(250.dp),
                mood = when (state.wake.status) {
                    WakeStatus.Listening -> OrbMood.Listening
                    WakeStatus.Loading -> OrbMood.Thinking
                    WakeStatus.Error -> OrbMood.Error
                    else -> OrbMood.Idle
                },
                level = if (listening) state.micLevel else 0f,
            )
            Headline(state)

            Spacer(Modifier.height(26.dp))
            WakeCard(state, actions)
            Spacer(Modifier.height(14.dp))
            VibranceCard(state, actions)
            Spacer(Modifier.height(14.dp))
            AiCard(state, actions)
            Spacer(Modifier.height(14.dp))
            SetupCard(state.permissions, actions)
            Spacer(Modifier.height(14.dp))
            TryCard()
            Spacer(Modifier.navigationBarsPadding().height(120.dp)) // room for the floating button
        }

        TalkButton(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
            onClick = actions.onTalk,
        )
    }
}

// ---------------------------------------------------------------------- header

@Composable
private fun Header(wake: WakeState, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Nebula", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
        StatusChip(wake.status, enabled)
    }
}

@Composable
private fun StatusChip(status: WakeStatus, enabled: Boolean) {
    val (label, color) = when (status) {
        WakeStatus.Listening -> "Listening" to NebulaColors.Mint
        WakeStatus.Loading -> "Starting" to NebulaColors.Amber
        WakeStatus.Paused -> "Paused" to NebulaColors.Lilac
        WakeStatus.Error -> "Needs you" to NebulaColors.Magenta
        WakeStatus.Off -> (if (enabled) "Asleep" else "Off") to NebulaColors.TextFaint
    }
    val dotColor by animateColorAsState(color, label = "dot")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "glow",
    )
    GlassSurface(shape = CircleShape) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = if (status == WakeStatus.Listening) glow else 1f }
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = NebulaColors.TextDim)
        }
    }
}

@Composable
private fun Headline(state: HomeUiState) {
    val (title, subtitle) = when (state.wake.status) {
        WakeStatus.Listening -> "Say “Hey Nebula”" to "I'm listening quietly on this phone. Nothing leaves it until I wake up."
        WakeStatus.Loading -> "Waking up…" to "Loading the wake word model."
        WakeStatus.Paused -> "Nebula is open" to "Listening resumes when the panel closes."
        WakeStatus.Error -> "Something needs attention" to (state.wake.message ?: "Turn the wake word off and on again.")
        WakeStatus.Off ->
            if (state.wakeEnabled) "Tap to wake me" to "Listening was stopped. It restarts when you open Nebula."
            else "Wake word is off" to "Turn on “Hey Nebula” below, or tap Talk."
    }
    AnimatedContent(
        targetState = title to subtitle,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "headline",
    ) { (t, s) ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                s,
                style = MaterialTheme.typography.bodyMedium,
                color = NebulaColors.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------- cards

@Composable
private fun WakeCard(state: HomeUiState, actions: HomeActions) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hey Nebula", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Listen for the wake word, even with the screen off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NebulaColors.TextDim,
                    )
                }
                Spacer(Modifier.width(12.dp))
                NebulaSwitch(state.wakeEnabled, actions.onWakeToggle)
            }

            Hairline()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sensitivity", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    when {
                        state.sensitivity < 0.34f -> "Relaxed"
                        state.sensitivity > 0.66f -> "Eager"
                        else -> "Balanced"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = NebulaColors.Lilac,
                )
            }
            Slider(
                value = state.sensitivity,
                onValueChange = actions.onSensitivityChange,
                onValueChangeFinished = actions.onSensitivityDone,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = NebulaColors.Violet,
                    inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                ),
            )
            Text(
                "Waking up by accident? Slide toward Relaxed. Missing you? Slide toward Eager.",
                style = MaterialTheme.typography.bodySmall,
                color = NebulaColors.TextFaint,
            )

            Hairline()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speak replies", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Nebula answers out loud as well as on screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NebulaColors.TextDim,
                    )
                }
                Spacer(Modifier.width(12.dp))
                NebulaSwitch(state.speakReplies, actions.onSpeakToggle)
            }
        }
    }
}

@Composable
private fun VibranceCard(state: HomeUiState, actions: HomeActions) {
    val clock = rememberVibranceClock(Phase.Listening)
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            // Live mini preview: a little phone with the glow and island.
            Box(
                Modifier
                    .size(width = 78.dp, height = 148.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0B0A14))
            ) {
                VibranceGlow(
                    modifier = Modifier.fillMaxSize(),
                    phase = Phase.Listening,
                    level = 0.15f,
                    visible = true,
                    cornerRadiusPx = with(LocalDensity.current) { 18.dp.toPx() },
                    intensity = state.vibrance,
                    clock = clock,
                )
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .size(width = 30.dp, height = 9.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("Vibrance", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The glowing edge and island that appear when Nebula wakes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NebulaColors.TextDim,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Glow", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            state.vibrance < 0.34f -> "Subtle"
                            state.vibrance > 0.66f -> "Vivid"
                            else -> "Soft"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = NebulaColors.Lilac,
                    )
                }
                Slider(
                    value = state.vibrance,
                    onValueChange = actions.onVibranceChange,
                    onValueChangeFinished = actions.onVibranceDone,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFFF4F9A),
                        inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                    ),
                )
                Text(
                    "Tap Talk to Nebula to see it full screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NebulaColors.TextFaint,
                )
            }
        }
    }
}

@Composable
private fun AiCard(state: HomeUiState, actions: HomeActions) {
    var key by remember { mutableStateOf("") }
    var model by remember(state.geminiModel) { mutableStateOf(state.geminiModel) }
    var showKey by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = NebulaColors.Lilac, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI brain", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (state.hasGeminiKey) "Connected" else "Not set up",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.hasGeminiKey) NebulaColors.Mint else NebulaColors.Amber,
                )
            }
            Text(
                "Google Gemini with web search answers everything that isn't a phone command. " +
                    "The free key covers personal use.",
                style = MaterialTheme.typography.bodySmall,
                color = NebulaColors.TextDim,
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (state.hasGeminiKey) "Replace Gemini key" else "Paste your Gemini key") },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    Icon(
                        if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showKey) "Hide key" else "Show key",
                        tint = NebulaColors.TextDim,
                        modifier = Modifier.clickable { showKey = !showKey },
                    )
                },
                shape = RoundedCornerShape(16.dp),
                colors = glassFieldColors(),
            )

            if (advanced) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Model") },
                    shape = RoundedCornerShape(16.dp),
                    colors = glassFieldColors(),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("Save", enabled = key.isNotBlank() || model != state.geminiModel) {
                    actions.onSaveAi(key, model)
                    key = ""
                }
                Spacer(Modifier.width(8.dp))
                PillButton(if (state.aiTesting) "Testing…" else "Test", filled = false, enabled = !state.aiTesting) {
                    actions.onTestAi(key, model)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (advanced) "Less" else "Advanced",
                    style = MaterialTheme.typography.labelMedium,
                    color = NebulaColors.TextFaint,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { advanced = !advanced }
                        .padding(8.dp),
                )
            }

            state.aiStatus?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = NebulaColors.TextDim)
            }

            if (!state.hasGeminiKey) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Get a free key at aistudio.google.com  ›",
                    style = MaterialTheme.typography.bodySmall,
                    color = NebulaColors.Cyan,
                    modifier = Modifier.clickable(onClick = actions.onGetKey),
                )
            }
        }
    }
}

@Composable
private fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NebulaColors.Violet,
    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
    focusedLabelColor = NebulaColors.Lilac,
    unfocusedLabelColor = NebulaColors.TextFaint,
    cursorColor = NebulaColors.Lilac,
    focusedContainerColor = Color.White.copy(alpha = 0.04f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
)

@Composable
private fun PillButton(label: String, filled: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.06f)
                    filled -> NebulaColors.Violet
                    else -> Color.White.copy(alpha = 0.10f)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) Color.White else NebulaColors.TextFaint,
        )
    }
}

@Composable
private fun SetupCard(p: PermissionStatus, actions: HomeActions) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            Text(
                if (p.allSet) "All set. Nebula can work hands-free." else "Nebula needs these to work hands-free.",
                style = MaterialTheme.typography.bodySmall,
                color = NebulaColors.TextDim,
            )
            Spacer(Modifier.height(10.dp))

            PermissionRow(Icons.Rounded.Mic, "Microphone", "To hear “Hey Nebula” and your questions", p.microphone, actions.onAllowMic)
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionRow(Icons.Rounded.Notifications, "Notifications", "Shows when Nebula is listening (Android requires it)", p.notifications, actions.onAllowNotifications)
            }
            PermissionRow(Icons.Rounded.Layers, "Appear on top", "Lets Nebula pop up over other apps", p.overlay, actions.onAllowOverlay)
            PermissionRow(Icons.Rounded.BatteryChargingFull, "Unrestricted battery", "Stops One UI putting Nebula to sleep", p.battery, actions.onAllowBattery)

            Spacer(Modifier.height(8.dp))
            Text(
                "Samsung tip: also go to Settings › Battery › Background usage limits › Never sleeping apps, and add Nebula.",
                style = MaterialTheme.typography.bodySmall,
                color = NebulaColors.TextFaint,
            )
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, detail: String, granted: Boolean, onAllow: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = NebulaColors.Lilac, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = NebulaColors.TextDim)
        }
        Spacer(Modifier.width(10.dp))
        AnimatedContent(granted, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "perm") { ok ->
            if (ok) {
                Icon(Icons.Rounded.CheckCircle, "Allowed", tint = NebulaColors.Mint, modifier = Modifier.size(26.dp))
            } else {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(NebulaColors.Violet)
                        .clickable(onClick = onAllow)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text("Allow", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TryCard() {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Try saying", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "What time is it?",
                    "Set a timer for 5 minutes",
                    "Turn on the flashlight",
                    "Wake me up at 6:30 AM",
                    "How's my battery?",
                    "Open Spotify",
                ).forEach { phrase ->
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.07f))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(phrase, style = MaterialTheme.typography.bodySmall, color = NebulaColors.TextDim)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- bits

@Composable
private fun Hairline() {
    Box(
        Modifier
            .padding(vertical = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(NebulaColors.Hairline)
    )
}

@Composable
private fun NebulaSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = NebulaColors.Violet,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White.copy(alpha = 0.75f),
            uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
            uncheckedBorderColor = Color.White.copy(alpha = 0.22f),
        ),
    )
}

@Composable
private fun TalkButton(modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "press",
    )
    GlassSurface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                // Violet glow under the glass
                drawCircle(
                    Brush.radialGradient(
                        listOf(NebulaColors.Violet.copy(alpha = 0.45f), Color.Transparent),
                        radius = size.width * 0.7f,
                    ),
                    radius = size.width * 0.7f,
                )
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = RoundedCornerShape(40.dp),
        tint = NebulaColors.Ink.copy(alpha = 0.55f),
    ) {
        Row(
            Modifier.padding(horizontal = 26.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Mic, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text("Talk to Nebula", style = MaterialTheme.typography.titleMedium)
        }
    }
}
