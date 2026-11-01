package com.nebula.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nebula.assistant.brain.Source
import com.nebula.assistant.ui.components.GlassSurface
import com.nebula.assistant.ui.components.NebulaOrb
import com.nebula.assistant.ui.components.OrbMood
import com.nebula.assistant.ui.theme.NebulaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The glass sheet that rises from the bottom when you say "Hey Nebula".
 * Springs up with a little liquid overshoot; tap outside, swipe down or go Back to dismiss.
 */
@Composable
fun AssistantPanel(
    voice: VoiceController,
    onClosed: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val dismiss: () -> Unit = {
        if (shown) {
            shown = false
            scope.launch { delay(240); onClosed() }
        }
    }

    LaunchedEffect(Unit) { shown = true }
    BackHandler { dismiss() }

    // Hands-free: tidy away on its own after answering.
    LaunchedEffect(voice.phase) {
        when (voice.phase) {
            // Leave enough time to read longer answers.
            Phase.Done -> { delay((7_000L + voice.reply.length * 40L).coerceAtMost(25_000L)); dismiss() }
            Phase.Error -> { delay(5_000); dismiss() }
            else -> Unit
        }
    }

    val rise by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 340f),
        label = "rise",
    )
    val dragSettle by animateFloatAsState(dragY, spring(dampingRatio = 0.7f), label = "drag")

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { dismiss() } }
    ) {
        GlassSurface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    translationY = (1f - rise) * size.height * 1.15f + dragSettle
                    // Liquid feel: slightly narrow while rising, overshoots a touch, settles.
                    scaleX = 0.88f + 0.12f * rise
                    scaleY = 0.94f + 0.06f * rise
                    alpha = rise.coerceIn(0f, 1f)
                }
                .pointerInput(Unit) { detectTapGestures { /* keep taps inside the panel */ } }
                .pointerInput(Unit) {
                    val threshold = with(density) { 110.dp.toPx() }
                    detectVerticalDragGestures(
                        onDragEnd = { if (dragY > threshold) dismiss() else dragY = 0f },
                        onDragCancel = { dragY = 0f },
                    ) { change, dy ->
                        change.consume()
                        dragY = (dragY + dy).coerceAtLeast(0f)
                    }
                },
            shape = RoundedCornerShape(36.dp),
            tint = NebulaColors.Ink.copy(alpha = 0.62f),
        ) {
            PanelContent(voice, onClose = dismiss, onOpenApp = onOpenApp, onOpenSource = onOpenSource)
        }
    }
}

@Composable
private fun PanelContent(
    voice: VoiceController,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val mood = when (voice.phase) {
        Phase.Listening -> OrbMood.Listening
        Phase.Thinking -> OrbMood.Thinking
        Phase.Speaking -> OrbMood.Speaking
        Phase.Done -> OrbMood.Idle
        Phase.Error -> OrbMood.Error
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(top = 10.dp, bottom = 20.dp)
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 380f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f))
        )
        Spacer(Modifier.height(10.dp))

        NebulaOrb(Modifier.size(124.dp), mood = mood, level = voice.level)

        Spacer(Modifier.height(6.dp))
        AnimatedContent(
            targetState = when (voice.phase) {
                Phase.Listening -> "LISTENING"
                Phase.Thinking -> "THINKING"
                Phase.Error -> "HMM"
                else -> "NEBULA"
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "status",
        ) { label ->
            Text(label, style = MaterialTheme.typography.labelLarge, color = NebulaColors.TextFaint)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = voice.transcript.ifBlank {
                if (voice.phase == Phase.Listening) "Ask me anything…" else ""
            },
            style = MaterialTheme.typography.titleLarge,
            color = if (voice.transcript.isBlank()) NebulaColors.TextFaint else NebulaColors.Text,
            textAlign = TextAlign.Center,
        )

        AnimatedVisibility(
            visible = voice.reply.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut(),
        ) {
            Text(
                text = voice.reply,
                style = MaterialTheme.typography.bodyLarge,
                color = NebulaColors.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        AnimatedVisibility(
            visible = voice.sources.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut(),
        ) {
            SourceChips(voice.sources, onOpenSource)
        }

        Spacer(Modifier.height(22.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundGlassButton(Icons.Rounded.Close, "Close", onClose)
            MicButton(
                listening = voice.phase == Phase.Listening,
                onClick = { if (voice.phase == Phase.Listening) voice.stopListening() else voice.listen() },
            )
            RoundGlassButton(Icons.Rounded.Tune, "Nebula settings", onOpenApp)
        }
    }
}

@Composable
private fun RoundGlassButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier
            .size(52.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
    ) {
        Icon(icon, description, Modifier.align(Alignment.Center), tint = NebulaColors.TextDim)
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(70.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(NebulaColors.Violet, NebulaColors.Magenta.copy(alpha = 0.85f))
                )
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
            contentDescription = if (listening) "Done talking" else "Ask again",
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceChips(sources: List<Source>, onOpen: (String) -> Unit) {
    FlowRow(
        Modifier.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sources.forEach { source ->
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { onOpen(source.url) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Public, null,
                    tint = NebulaColors.Cyan, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    source.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = NebulaColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
        }
    }
}
