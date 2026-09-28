package com.cbkres.visavole.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val HAZE_SIZE = 48.dp
private const val HAZE_RAMP = 80f

/**
 * Soft edge fades that sit on top of a scrollable region so items melt into the
 * container edges instead of hard-clipping. Place it as a sibling of the scroll
 * content inside the same [Box] (see [HazeBox]). [startAlpha] drives the top (or
 * left, when [horizontal]) edge and [endAlpha] drives the bottom (or right) edge;
 * both are 0..1 where 1 is fully opaque.
 */
@Composable
private fun HazeEdges(
    startAlpha: Float,
    endAlpha: Float,
    color: Color,
    horizontal: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // P3-42: the brushes depend only on `color`/`horizontal`, not the per-frame alphas, so remember
    // them — otherwise a scroll frame rebuilds up to two gradient brushes per HazeBox.
    val startFade = remember(color, horizontal) {
        if (horizontal) Brush.horizontalGradient(0f to color, 1f to Color.Transparent)
        else Brush.verticalGradient(0f to color, 1f to Color.Transparent)
    }
    val endFade = remember(color, horizontal) {
        if (horizontal) Brush.horizontalGradient(0f to Color.Transparent, 1f to color)
        else Brush.verticalGradient(0f to Color.Transparent, 1f to color)
    }
    Box(modifier) {
        if (horizontal) {
            if (startAlpha > 0.01f) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(HAZE_SIZE)
                        .alpha(startAlpha)
                        .background(startFade),
                )
            }
            if (endAlpha > 0.01f) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(HAZE_SIZE)
                        .alpha(endAlpha)
                        .background(endFade),
                )
            }
        } else {
            if (startAlpha > 0.01f) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(HAZE_SIZE)
                        .alpha(startAlpha)
                        .background(startFade),
                )
            }
            if (endAlpha > 0.01f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(HAZE_SIZE)
                        .alpha(endAlpha)
                        .background(endFade),
                )
            }
        }
    }
}

/**
 * Wraps [content] in a [Box] and overlays [HazeEdges] on top, so any scrollable
 * region can get the soft edge fades with one call.
 */
@Composable
fun HazeBox(
    startAlpha: Float,
    endAlpha: Float,
    color: Color,
    horizontal: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        content()
        HazeEdges(startAlpha, endAlpha, color, horizontal, modifier = Modifier.matchParentSize())
    }
}

/**
 * [HazeBox] for a [LazyListState] that reads the alphas from the scroll state ITSELF: the
 * per-frame layout reads then invalidate only the haze edges, not the whole surrounding
 * screen — otherwise every visible card recomposes on every scroll frame.
 */
@Composable
fun HazeBox(
    scrollState: LazyListState,
    color: Color,
    horizontal: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val (start, end) = scrollState.hazeAlphas()
    Box(modifier) {
        content()
        HazeEdges(start, end, color, horizontal, modifier = Modifier.matchParentSize())
    }
}

/** [HazeBox] for a classic [ScrollState]; see the [LazyListState] overload. */
@Composable
fun HazeBox(
    scrollState: ScrollState,
    color: Color,
    horizontal: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val (start, end) = scrollState.hazeAlphas()
    Box(modifier) {
        content()
        HazeEdges(start, end, color, horizontal, modifier = Modifier.matchParentSize())
    }
}

/** Start/end haze opacity for a classic [ScrollState]. */
fun ScrollState.hazeAlphas(): Pair<Float, Float> {
    val start = (value / HAZE_RAMP).coerceIn(0f, 1f)
    val end = ((maxValue - value) / HAZE_RAMP).coerceIn(0f, 1f)
    return start to end
}

/** Start/end haze opacity for a [LazyListState]. */
fun LazyListState.hazeAlphas(): Pair<Float, Float> {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0f to 0f
    val first = visible.first()
    val start = if (first.index == 0) {
        (-first.offset / HAZE_RAMP).coerceIn(0f, 1f)
    } else {
        1f
    }
    val last = visible.last()
    val end = if (last.index == info.totalItemsCount - 1) {
        val distanceFromBottom = (info.viewportEndOffset - (last.offset + last.size)).toFloat()
        (-distanceFromBottom / HAZE_RAMP).coerceIn(0f, 1f)
    } else {
        1f
    }
    return start to end
}
