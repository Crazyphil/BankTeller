package it.kapfer.bankteller.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Minimum thumb length — §6.1.4 pins 36dp; a shorter thumb would make long
 * lists hard to grab.
 */
val LedgerScrollbarMinThumb: Dp = 36.dp

/**
 * Normalized scrollbar metrics (device pixels). [rangePx] is the maximum
 * scroll distance — 0 means the content fits its container and no thumb
 * renders. [viewportPx] doubles as the thumb track height.
 */
internal data class ScrollbarMetrics(
    val offsetPx: Int,
    val rangePx: Int,
    val viewportPx: Int,
)

internal fun scrollbarHasOverflow(metrics: ScrollbarMetrics): Boolean = metrics.rangePx > 0

internal fun scrollbarThumbLengthPx(metrics: ScrollbarMetrics, minThumbPx: Int): Int {
    if (metrics.rangePx <= 0 || metrics.viewportPx <= 0) return 0
    val contentPx = metrics.viewportPx.toLong() + metrics.rangePx
    val proportional = (metrics.viewportPx.toLong() * metrics.viewportPx / contentPx).toInt()
    return min(metrics.viewportPx, max(minThumbPx, proportional))
}

internal fun scrollbarThumbOffsetPx(metrics: ScrollbarMetrics, thumbPx: Int): Int {
    val travelPx = metrics.viewportPx - thumbPx
    if (metrics.rangePx <= 0 || travelPx <= 0) return 0
    val offset = (metrics.offsetPx.toLong() * travelPx / metrics.rangePx).toInt()
    return offset.coerceIn(0, travelPx)
}

internal fun scrollbarDragToScrollDeltaPx(dragPx: Int, metrics: ScrollbarMetrics, thumbPx: Int): Int {
    if (metrics.rangePx <= 0 || dragPx == 0) return 0
    val travelPx = metrics.viewportPx - thumbPx
    return if (travelPx > 0) {
        (dragPx.toLong() * metrics.rangePx / travelPx).toInt()
    } else {
        // Degenerate track (thumb fills it) — fall back to content ratio.
        (dragPx.toLong() * metrics.rangePx / max(1, metrics.viewportPx)).toInt()
    }
}

/** Touch fades after 400ms of momentum inactivity; pointer after 1.2s (§6.1.4). */
internal fun scrollbarFadeDelayMs(pointerIsTouch: Boolean): Long = if (pointerIsTouch) 400L else 1200L

/**
 * [LazyListState]-derived metrics: item heights are not known upfront, so the
 * visible window is used to estimate a uniform span-per-item (start-to-start,
 * spacing included) and extrapolate the content height. Offsets are relative
 * to the viewport start; the first item's offset is negative once it scrolls
 * out.
 */
internal fun lazyScrollbarMetrics(
    firstIndex: Int,
    firstOffset: Int,
    firstSize: Int,
    lastIndex: Int,
    lastStartOffset: Int,
    totalItems: Int,
    viewportPx: Int,
): ScrollbarMetrics {
    if (totalItems <= 0 || viewportPx <= 0 || firstIndex < 0 || firstSize <= 0) {
        return ScrollbarMetrics(0, 0, viewportPx)
    }
    val spanPerItem = if (lastIndex > firstIndex) {
        (lastStartOffset - firstOffset) / (lastIndex - firstIndex)
    } else {
        firstSize
    }
    if (spanPerItem <= 0) return ScrollbarMetrics(0, 0, viewportPx)
    val contentPx = spanPerItem.toLong() * totalItems
    val rangePx = (contentPx - viewportPx).coerceAtLeast(0L).toInt()
    val offsetPx = (firstIndex.toLong() * spanPerItem - firstOffset)
        .coerceIn(0L, rangePx.toLong())
        .toInt()
    return ScrollbarMetrics(offsetPx, rangePx, viewportPx)
}

/**
 * Hairline overlay scrollbar for internal scrollable containers (§6.1.4 —
 * Compose for Web ships no native scrollbar).
 *
 * Usage: WRAP the scrollable content — the wrapper Box parents the content
 * (a parent pointerInput observes zone-wide pointer events without stealing
 * hits from the scrollable child) and the thumb overlays its trailing edge:
 *
 * ```
 * LedgerScrollbar(listState = state, modifier = Modifier.heightIn(max = 400.dp)) {
 *     LazyColumn(state = state) { ... }
 * }
 * ```
 *
 * Thumb: trackless, 4dp wide at rest / 6dp on hover or drag, 2dp rounded
 * caps, at least [LedgerScrollbarMinThumb] long, `onSurfaceVariant` at 30%
 * alpha at rest / 60% when engaged, inset 2dp from the trailing edge. It
 * renders only when the content overflows the wrapper.
 *
 * Pointer devices: fades in during scrolling or cursor movement over the
 * zone, fades out after 1.2s of inactivity, and drags proportionally. Touch
 * devices: 30% alpha while the scroll offset changes (momentum), fades out
 * after 400ms, never interactive. Pointer type is detected per event via
 * [PointerType] (mouse events also flip detection back after a touch).
 */
@Composable
fun LedgerScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    minThumb: Dp = LedgerScrollbarMinThumb,
    content: @Composable BoxScope.() -> Unit,
) {
    LedgerScrollbarImpl(
        modifier = modifier,
        minThumb = minThumb,
        metricsFor = { viewportPx ->
            ScrollbarMetrics(scrollState.value, scrollState.maxValue, viewportPx)
        },
        onScrollDelta = { delta -> scrollState.dispatchRawDelta(delta) },
        content = content,
    )
}

/**
 * [LazyListLayoutInfo]-to-[ScrollbarMetrics] adapter — shared by the
 * [LazyListState]-driven scrollbar and the wizard scaffold's divider
 * predicates, so both shapes reduce to the same (offset, range) pair.
 */
internal fun lazyListScrollbarMetrics(info: LazyListLayoutInfo): ScrollbarMetrics {
    val first = info.visibleItemsInfo.firstOrNull()
    val lazyViewportPx = info.viewportEndOffset - info.viewportStartOffset
    if (first == null || lazyViewportPx <= 0) {
        return ScrollbarMetrics(0, 0, lazyViewportPx)
    }
    val last = info.visibleItemsInfo.last()
    return lazyScrollbarMetrics(
        firstIndex = first.index,
        firstOffset = first.offset - info.viewportStartOffset,
        firstSize = first.size,
        lastIndex = last.index,
        lastStartOffset = last.offset - info.viewportStartOffset,
        totalItems = info.totalItemsCount,
        viewportPx = lazyViewportPx,
    )
}

/**
 * [LazyListState]-driven variant — see [LedgerScrollbar] for the contract.
 */
@Composable
fun LedgerScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    minThumb: Dp = LedgerScrollbarMinThumb,
    content: @Composable BoxScope.() -> Unit,
) {
    LedgerScrollbarImpl(
        modifier = modifier,
        minThumb = minThumb,
        metricsFor = { viewportPx ->
            val metrics = lazyListScrollbarMetrics(listState.layoutInfo)
            if (metrics.rangePx > 0) metrics else ScrollbarMetrics(0, 0, viewportPx)
        },
        onScrollDelta = { delta -> listState.dispatchRawDelta(delta) },
        content = content,
    )
}

@Composable
private fun LedgerScrollbarImpl(
    modifier: Modifier,
    minThumb: Dp,
    metricsFor: (viewportPx: Int) -> ScrollbarMetrics,
    onScrollDelta: (Float) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var viewportPx by remember { mutableStateOf(0) }
    // metricsFor captures long-lived states (ScrollState/LazyListState and the
    // viewportPx delegate), so a single remembered derivedStateOf stays valid.
    val metricsState: State<ScrollbarMetrics> = remember { derivedStateOf { metricsFor(viewportPx) } }

    var pointerIsTouch by remember { mutableStateOf(false) }
    var activityTick by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val thumbInteraction = remember { MutableInteractionSource() }
    val hovered by thumbInteraction.collectIsHoveredAsState()

    // Scroll activity (wheel, drag, momentum) keeps the thumb alive.
    LaunchedEffect(metricsState) {
        snapshotFlow { metricsState.value }.collect { metrics ->
            if (metrics.rangePx > 0 && metrics.offsetPx > 0) activityTick++
        }
    }
    LaunchedEffect(activityTick, hovered, dragging, pointerIsTouch) {
        if (hovered || dragging) {
            visible = true
        } else if (activityTick > 0) {
            visible = true
            delay(scrollbarFadeDelayMs(pointerIsTouch))
            if (!hovered && !dragging) visible = false
        }
    }

    val engaged = hovered || dragging
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "ledger-scrollbar-fade",
    )
    val thumbWidth by animateDpAsState(
        targetValue = if (engaged) 6.dp else 4.dp,
        animationSpec = tween(durationMillis = 100),
        label = "ledger-scrollbar-width",
    )

    Box(
        modifier = modifier
            .onSizeChanged { size -> viewportPx = size.height }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.changes.firstOrNull()?.type) {
                            PointerType.Mouse -> {
                                pointerIsTouch = false
                                activityTick++
                            }
                            PointerType.Touch, PointerType.Stylus, PointerType.Eraser ->
                                pointerIsTouch = true
                            else -> Unit
                        }
                    }
                }
            },
    ) {
        content()

        // Discrete derived reads: composition only reruns when overflow or
        // thumb geometry actually changes, not on every scroll frame (the
        // thumb position itself is a deferred offset{} read).
        val overflow by remember { derivedStateOf { scrollbarHasOverflow(metricsState.value) } }
        val density = LocalDensity.current
        val minThumbPx = with(density) { minThumb.roundToPx() }
        val thumbPx by remember(minThumbPx) {
            derivedStateOf { scrollbarThumbLengthPx(metricsState.value, minThumbPx) }
        }
        if (overflow && (visible || fade > 0.01f)) {
            val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (engaged) 0.6f else 0.3f,
            )
            val thumbModifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 2.dp)
                .offset { IntOffset(x = 0, y = scrollbarThumbOffsetPx(metricsState.value, thumbPx)) }
                .size(width = thumbWidth, height = with(density) { thumbPx.toDp() })
                .graphicsLayer { alpha = fade }
                .clip(RoundedCornerShape(2.dp))
                .background(thumbColor)
                .hoverable(thumbInteraction)
            if (pointerIsTouch) {
                // Touch thumbs are display-only (§6.1.4).
                Box(modifier = thumbModifier)
            } else {
                Box(
                    modifier = thumbModifier.pointerInput(thumbPx) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val scrollDelta = scrollbarDragToScrollDeltaPx(
                                    dragPx = dragAmount.roundToInt(),
                                    metrics = metricsState.value,
                                    thumbPx = thumbPx,
                                )
                                if (scrollDelta != 0) onScrollDelta(scrollDelta.toFloat())
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        )
                    },
                )
            }
        }
    }
}
