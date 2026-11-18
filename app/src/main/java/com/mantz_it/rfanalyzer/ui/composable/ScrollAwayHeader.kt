package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * <h1>RF Analyzer - Scroll-Away Header</h1>
 *
 * Module:      ScrollAwayHeader.kt
 * Description: Scrolling containers: ScrollableColumnWithFadingEdge, a scrollable column whose
 *              edges fade out while there is more to scroll, and
 *              LazyColumnWithScrollAwayHeaderElement, a LazyColumn whose header slides away while
 *              the list scrolls and snaps fully in or out when it stops (with its saveable
 *              ScrollAwayHeaderState).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@Composable
fun ScrollableColumnWithFadingEdge(
    modifier: Modifier = Modifier,
    fadeHeight: Dp = 70.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val fadeHeightPx = with(density) { fadeHeight.toPx() }
    val canScrollUp by remember { derivedStateOf { scrollState.value > 0 } }
    val canScrollDown by remember { derivedStateOf { scrollState.value < scrollState.maxValue && scrollState.maxValue > 0 } }

    Box(
        modifier = modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()

                if (!canScrollUp && !canScrollDown) return@drawWithContent

                // DstIn mask: Transparent fades the content out, Black keeps it visible
                val topColor = if (canScrollUp) Color.Transparent else Color.Black
                val bottomColor = if (canScrollDown) Color.Transparent else Color.Black

                val topFadeEndPx = fadeHeightPx.coerceAtMost(size.height / 2)
                val bottomFadeStartPx = (size.height - fadeHeightPx).coerceAtLeast(size.height / 2)

                // gradient stops in the normalized 0f..1f range
                val colorStops = arrayOf(
                    0f to topColor,
                    (topFadeEndPx / size.height) to Color.Black,
                    (bottomFadeStartPx / size.height) to Color.Black,
                    1f to bottomColor
                )
                drawRect(brush = Brush.verticalGradient(colorStops = colorStops), blendMode = BlendMode.DstIn)
            }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.verticalScroll(scrollState),
            content = content
        )
    }
}

// LazyColumnWithScrollAwayHeaderElement: a LazyColumn whose header slides away while scrolling

@Stable
class ScrollAwayHeaderState(
    val listState: LazyListState,
    initialHeaderOffset: Float
) {
    var headerOffset by mutableFloatStateOf(initialHeaderOffset)
    var headerHeightPx by mutableFloatStateOf(0f)
}

@Composable
fun rememberScrollAwayHeaderState(
    initialHeaderOffset: Float = 0f
): ScrollAwayHeaderState {
    return rememberSaveable(
        saver = Saver(
            save = {
                listOf(
                    it.listState.firstVisibleItemIndex,
                    it.listState.firstVisibleItemScrollOffset,
                    it.headerOffset,
                    it.headerHeightPx
                )
            },
            restore = {
                val listState = LazyListState(
                    firstVisibleItemIndex = it[0] as Int,
                    firstVisibleItemScrollOffset = it[1] as Int
                )
                ScrollAwayHeaderState(
                    listState,
                    initialHeaderOffset = it[2] as Float
                ).apply {
                    headerHeightPx = it[3] as Float
                }
            }
        )
    ) {
        ScrollAwayHeaderState(
            listState = LazyListState(),
            initialHeaderOffset = initialHeaderOffset
        )
    }
}


@Composable
fun LazyColumnWithScrollAwayHeaderElement(
    state: ScrollAwayHeaderState,
    modifier: Modifier = Modifier,
    lazyColumnContentPaddingValues: PaddingValues = PaddingValues(),
    headerElement: @Composable () -> Unit,
    content: LazyListScope.() -> Unit
) {
    val listState = state.listState
    val animatableOffset = remember { Animatable(state.headerOffset) }

    // Keep animatable in sync during drag
    LaunchedEffect(state.headerOffset) {
        if (listState.isScrollInProgress) {
            animatableOffset.snapTo(state.headerOffset)
        }
    }

    // Snap to closest position after scrolling
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && state.headerHeightPx > 0f) {
            val halfHeight = -state.headerHeightPx / 2f
            val target = if (state.headerOffset < halfHeight) -state.headerHeightPx else 0f
            animatableOffset.animateTo(target)
            state.headerOffset = animatableOffset.value
        }
    }

    val scrollBehavior = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (state.headerHeightPx != 0f) {
                    val delta = available.y
                    state.headerOffset = (state.headerOffset + delta).coerceIn(-state.headerHeightPx, 0f)
                }
                return Offset.Zero
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior)
    ) {
        // List "under" top content
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = with(LocalDensity.current) { (state.headerHeightPx + animatableOffset.value.roundToInt()).coerceAtLeast(0f).toDp() }),
            contentPadding = lazyColumnContentPaddingValues,
            content = content
        )

        // Sliding top element
        Box(
            Modifier
                .onSizeChanged { state.headerHeightPx = it.height.toFloat() }
                .offset { IntOffset(0, animatableOffset.value.roundToInt()) }
        ) {
            headerElement()
        }
    }
}
