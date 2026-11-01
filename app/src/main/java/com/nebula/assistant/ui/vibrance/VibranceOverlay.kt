package com.nebula.assistant.ui.vibrance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.nebula.assistant.ui.Phase
import com.nebula.assistant.ui.VoiceController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Vibrance: the full-screen layer shown when Nebula wakes.
 * The app you were using stays visible underneath; the edges glow and the island talks.
 */
@Composable
fun VibranceOverlay(
    voice: VoiceController,
    intensity: Float,
    onClosed: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        if (visible) {
            visible = false
            scope.launch { delay(520); onClosed() } // let the glow retract and the island sink
        }
    }

    LaunchedEffect(Unit) { visible = true }
    BackHandler { dismiss() }

    // Hands-free: tidy away after answering, leaving time to read longer answers.
    LaunchedEffect(voice.phase) {
        when (voice.phase) {
            Phase.Done -> { delay((7_000L + voice.reply.length * 40L).coerceAtMost(25_000L)); dismiss() }
            Phase.Error -> { delay(5_000); dismiss() }
            else -> Unit
        }
    }

    val shape = rememberScreenShape()
    val clock = rememberVibranceClock(voice.phase)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { dismiss() } }
    ) {
        VibranceGlow(
            modifier = Modifier.fillMaxSize(),
            phase = voice.phase,
            level = voice.level,
            visible = visible,
            cornerRadiusPx = shape.cornerRadiusPx,
            intensity = intensity,
            clock = clock,
        )
        VibranceIsland(
            voice = voice,
            shape = shape,
            visible = visible,
            maxWidth = maxWidth,
            clock = clock,
            onDismiss = dismiss,
            onOpenApp = onOpenApp,
            onOpenSource = onOpenSource,
        )
    }
}
