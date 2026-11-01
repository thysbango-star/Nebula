package com.nebula.assistant.ui.vibrance

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nebula.assistant.brain.Source
import com.nebula.assistant.ui.Phase
import com.nebula.assistant.ui.VoiceController
import com.nebula.assistant.ui.components.NebulaOrb
import com.nebula.assistant.ui.components.OrbMood
import com.nebula.assistant.ui.theme.NebulaColors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class IslandStage { Hidden, Compact, Expanded }

/**
 * The Vibrance island. It grows out of the camera hole as a small black pill (orb on the
 * left, live voice bars on the right), then pours open into a card as soon as you start
 * talking, showing your words, Nebula's answer, sources and controls.
 * Swipe it up, tap outside or press Back to dismiss.
 */
@Composable
fun VibranceIsland(
    voice: VoiceController,
    shape: ScreenShape,
    visible: Boolean,
    maxWidth: Dp,
    clock: State<Float>,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val density = LocalDensity.current
    val hole = shape.cutout
    val holeW = with(density) { (hole?.width() ?: 0).toDp() }
    val holeH = with(density) { (hole?.height() ?: 0).toDp() }

    val pillH = max(holeH.value + 14f, 36f).dp
    val compactW = max(holeW.value + 132f, 168f).dp
    val expandedW = minOf(maxWidth - 20.dp, 440.dp)
    val seedW = max(holeW.value, pillH.value).dp

    val expanded = voice.transcript.isNotBlank() || voice.reply.isNotBlank() || voice.phase != Phase.Listening
    val stage = when {
        !visible -> IslandStage.Hidden
        expanded -> IslandStage.Expanded
        else -> IslandStage.Compact
    }

    // Liquid springs: a little overshoot as it stretches, then settles.
    val liquid = spring<Dp>(dampingRatio = 0.62f, stiffness = 360f)
    val width by animateDpAsState(
        when (stage) {
            IslandStage.Hidden -> seedW
            IslandStage.Compact -> compactW
            IslandStage.Expanded -> expandedW
        }, liquid, label = "islandWidth"
    )
    val corner by animateDpAsState(
        if (stage == IslandStage.Expanded) 34.dp else pillH / 2, liquid, label = "islandCorner"
    )
    val presence by animateFloatAsState(
        if (stage == IslandStage.Hidden) 0f else 1f, tween(if (visible) 260 else 380), label = "presence"
    )

    // Centre on the camera hole (or the top centre if there isn't one).
    val centerXPx = hole?.exactCenterX() ?: with(density) { (maxWidth / 2).toPx() }
    val topPx = with(density) {
        if (hole != null) max(hole.top - (pillH.toPx() - hole.height()) / 2f, 4.dp.toPx())
        else 10.dp.toPx()
    }

    val colors = VibrancePalette.forPhase(voice.phase)
    val islandShape = RoundedCornerShape(corner)

    Box(
        Modifier
            .offset {
                IntOffset((centerXPx - width.toPx() / 2f).roundToInt(), topPx.roundToInt())
            }
            .width(width)
            .graphicsLayer { alpha = presence }
            // Glowing rim in the same flowing colours as the screen edge.
            .drawWithContent {
                drawContent()
                val brush = flowingSweep(colors, clock.value * 0.08f, Offset(size.width / 2, size.height / 2), alpha = 0.75f)
                drawRoundRect(
                    brush,
                    cornerRadius = CornerRadius(corner.toPx()),
                    style = Stroke(1.3.dp.toPx()),
                )
            }
            .clip(islandShape)
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { /* keep taps on the island */ } }
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -48.dp.toPx()) onDismiss() },
                ) { change, dy ->
                    change.consume()
                    dragged += dy
                }
            }
    ) {
        AnimatedContent(
            targetState = stage == IslandStage.Expanded,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 90)) + scaleIn(initialScale = 0.96f))
                    .togetherWith(fadeOut(tween(110)))
                    .using(SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.7f, stiffness = 380f) })
            },
            label = "islandContent",
        ) { isExpanded ->
            if (isExpanded) {
                ExpandedIsland(voice, pillH, clock, onDismiss, onOpenApp, onOpenSource)
            } else {
                CompactIsland(voice, pillH, clock)
            }
        }
    }
}

// ---------------------------------------------------------------------- compact pill

@Composable
private fun CompactIsland(voice: VoiceController, height: Dp, clock: State<Float>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NebulaOrb(Modifier.size(height - 8.dp), mood = OrbMood.Listening, level = voice.level)
        Spacer(Modifier.weight(1f)) // the camera sits here
        VoiceBars(level = voice.level, clock = clock, modifier = Modifier.size(width = 34.dp, height = height - 16.dp))
    }
}

/** Five little bars that dance with your voice, tinted with the Vibrance colours. */
@Composable
private fun VoiceBars(level: Float, clock: State<Float>, modifier: Modifier) {
    val lvl by animateFloatAsState(level, spring(dampingRatio = 0.5f, stiffness = 600f), label = "bars")
    Canvas(modifier) {
        val n = 5
        val gap = size.width / (n * 2f - 1f)
        val t = clock.value
        val brush = Brush.horizontalGradient(VibrancePalette.Flow.take(5))
        for (i in 0 until n) {
            val wobble = 0.55f + 0.45f * sin(t * 9f + i * 1.3f)
            val h = size.height * (0.18f + 0.82f * (0.15f + 0.85f * lvl) * wobble).coerceIn(0.18f, 1f)
            val x = i * gap * 2f + gap / 2f
            drawLine(
                brush,
                start = Offset(x, (size.height - h) / 2f),
                end = Offset(x, (size.height + h) / 2f),
                strokeWidth = gap,
                cap = StrokeCap.Round,
            )
        }
    }
}

// ---------------------------------------------------------------------- expanded card

@Composable
private fun ExpandedIsland(
    voice: VoiceController,
    pillH: Dp,
    clock: State<Float>,
    onDismiss: () -> Unit,
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
            .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
    ) {
        // Header row sits level with the camera; nothing important goes in the middle.
        Row(Modifier.fillMaxWidth().height(pillH + 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NebulaOrb(Modifier.size(30.dp), mood = mood, level = voice.level)
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = when (voice.phase) {
                    Phase.Listening -> "Listening"
                    Phase.Thinking -> "Thinking"
                    Phase.Error -> "Hmm"
                    else -> "Nebula"
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "status",
            ) { label ->
                Text(label, style = MaterialTheme.typography.labelMedium, color = NebulaColors.TextDim)
            }
            Spacer(Modifier.weight(1f))
            if (voice.phase == Phase.Listening) {
                VoiceBars(voice.level, clock, Modifier.size(width = 30.dp, height = 16.dp))
                Spacer(Modifier.width(12.dp))
            }
            Icon(
                Icons.Rounded.Close, "Close",
                tint = NebulaColors.TextDim,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable(onClick = onDismiss)
                    .padding(6.dp),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = voice.transcript.ifBlank { "Ask me anything…" },
            style = MaterialTheme.typography.titleMedium,
            color = if (voice.transcript.isBlank()) NebulaColors.TextFaint else Color.White,
        )

        AnimatedVisibility(
            visible = voice.reply.isNotBlank(),
            enter = fadeIn(tween(300)) + expandVertically(spring(dampingRatio = 0.8f, stiffness = 300f)),
            exit = fadeOut(),
        ) {
            Text(
                text = voice.reply,
                style = MaterialTheme.typography.bodyLarge,
                color = NebulaColors.Text.copy(alpha = 0.82f),
                modifier = Modifier
                    .padding(top = 10.dp)
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }

        AnimatedVisibility(visible = voice.sources.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            SourceChips(voice.sources, onOpenSource)
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFB45CFF), Color(0xFFFF4F9A))))
                    .clickable { if (voice.phase == Phase.Listening) voice.stopListening() else voice.listen() },
                contentAlignment = Alignment.Center,
            ) {
                val listening = voice.phase == Phase.Listening
                Icon(
                    if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    if (listening) "Done talking" else "Ask again",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Icon(
                Icons.Rounded.Tune, "Nebula settings",
                tint = NebulaColors.TextFaint,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenApp)
                    .padding(7.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceChips(sources: List<Source>, onOpen: (String) -> Unit) {
    FlowRow(
        Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sources.forEach { source ->
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable { onOpen(source.url) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Public, null, tint = Color(0xFF3FC8FF), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    source.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = NebulaColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
        }
    }
}
