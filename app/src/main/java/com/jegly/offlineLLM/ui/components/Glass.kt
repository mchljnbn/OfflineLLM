package com.jegly.offlineLLM.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A frosted-glass style surface: soft translucent fill and a thin light border
 * to catch the "edge highlight" look you get in iOS/visionOS style
 * glassmorphism cards.
 *
 * Works on minSdk 33 (this project's floor) since RenderEffect-backed blur has
 * been available since API 31 — no extra Android-version handling needed, and
 * it runs fine on Android 14 (34), 15 (35) and 16 (36).
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = MaterialTheme.colorScheme.surface,
    tintAlpha: Float = 0.55f,
    borderAlpha: Float = 0.35f,
    blurRadius: Dp = 3.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        (tintAlpha + 0.1f).coerceIn(0f, 1f).let { tint.copy(alpha = it) },
                        (tintAlpha - 0.15f).coerceIn(0f, 1f).let { tint.copy(alpha = it) },
                    )
                )
            )
            .blur(radius = blurRadius) // gentle self-blur to soften the fill edges
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = borderAlpha),
                shape = shape,
            )
    ) {
        content()
    }
}

/**
 * Wrap a screen's content in this to get the soft gradient backdrop that glass
 * cards need in order to actually read as "glass" — a flat single-color
 * background makes GlassSurface look like nothing at all.
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        cs.primary.copy(alpha = 0.18f),
                        cs.tertiary.copy(alpha = 0.10f),
                        cs.background,
                    )
                )
            )
    ) {
        content()
    }
}
