package com.hhst.youtubelite.ui.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.theme.YtRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Top loading bar driven by WebView progress (`0f..1f`).
 * Animates while [visible], then finishes and fades out.
 */
@Composable
fun LoadingBar(
    progress: Float,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayed = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    var shimmerPhase by remember { mutableFloatStateOf(0f) }
    var isShown by remember { mutableStateOf(false) }

    LaunchedEffect(visible, progress) {
        if (visible) {
            isShown = true
            if (alpha.value < 1f) {
                alpha.snapTo(1f)
            }
            val target = progress.coerceIn(MIN_VISIBLE_PROGRESS, MAX_ACTIVE_PROGRESS)
            displayed.animateTo(
                targetValue = maxOf(displayed.value, target),
                animationSpec = tween(
                    durationMillis = PROGRESS_ANIM_MS,
                    easing = LinearEasing,
                ),
            )
        } else if (isShown) {
            displayed.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = COMPLETE_ANIM_MS,
                    easing = LinearEasing,
                ),
            )
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = FADE_OUT_MS),
            )
            displayed.snapTo(0f)
            isShown = false
        }
    }

    LaunchedEffect(isShown) {
        while (isActive && isShown) {
            shimmerPhase = (shimmerPhase + SHIMMER_STEP) % 1f
            delay(FRAME_MS)
        }
    }

    if (!isShown && alpha.value <= 0f) {
        return
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .padding(horizontal = HORIZONTAL_PADDING),
    ) {
        val corner = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
            color = TRACK_COLOR.copy(alpha = TRACK_ALPHA * alpha.value),
            cornerRadius = corner,
        )

        val fillWidth = size.width * displayed.value.coerceIn(0f, 1f)
        if (fillWidth <= 0f) {
            return@Canvas
        }

        val fillBrush = Brush.horizontalGradient(
            colors = listOf(GRADIENT_START, YtRed, GRADIENT_END),
            startX = 0f,
            endX = size.width,
        )
        drawRoundRect(
            brush = fillBrush,
            size = Size(fillWidth, size.height),
            cornerRadius = corner,
            alpha = alpha.value,
        )

        val shimmerWidth = size.width * SHIMMER_WIDTH_FRACTION
        val shimmerStart = (size.width + shimmerWidth) * shimmerPhase - shimmerWidth
        val shimmerBrush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = SHIMMER_ALPHA * alpha.value),
                Color.Transparent,
            ),
            startX = shimmerStart,
            endX = shimmerStart + shimmerWidth,
        )
        drawRoundRect(
            brush = shimmerBrush,
            topLeft = Offset.Zero,
            size = Size(fillWidth, size.height),
            cornerRadius = corner,
        )
    }
}

private val BAR_HEIGHT = 2.dp
private val HORIZONTAL_PADDING = 16.dp
private val TRACK_COLOR = Color(0xCCCCCCCC)
private val GRADIENT_START = Color(0xFF736EFE)
private val GRADIENT_END = Color(0xFF5EFCE8)

private const val MIN_VISIBLE_PROGRESS = 0.06f
private const val MAX_ACTIVE_PROGRESS = 0.994f
private const val TRACK_ALPHA = 0.35f
private const val SHIMMER_ALPHA = 0.35f
private const val SHIMMER_WIDTH_FRACTION = 0.22f
private const val SHIMMER_STEP = 0.04f
private const val PROGRESS_ANIM_MS = 180
private const val COMPLETE_ANIM_MS = 160
private const val FADE_OUT_MS = 220
private const val FRAME_MS = 16L
