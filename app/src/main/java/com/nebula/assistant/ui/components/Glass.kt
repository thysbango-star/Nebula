package com.nebula.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.nebula.assistant.ui.theme.LocalGrain

/**
 * Matte "liquid glass": a soft translucent fill, a light catching the top edge,
 * film grain for the frosted finish, and a hairline rim that's brighter at the top-left.
 *
 * @param tint base colour under the frost; darker tints read better over busy apps.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = Color.White.copy(alpha = 0.06f),
    content: @Composable BoxScope.() -> Unit,
) {
    val grain = LocalGrain.current
    Box(
        modifier = modifier
            .clip(shape)
            .background(tint)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.10f),
                    0.35f to Color.White.copy(alpha = 0.02f),
                    1f to Color.Transparent,
                )
            )
            .drawBehind { drawRect(grain, alpha = 0.7f) }
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.32f),
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.14f),
                        )
                    )
                ),
                shape,
            ),
        content = content,
    )
}
