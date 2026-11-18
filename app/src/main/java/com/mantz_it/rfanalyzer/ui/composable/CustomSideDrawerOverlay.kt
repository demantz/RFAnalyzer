package com.mantz_it.rfanalyzer.ui.composable

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * <h1>RF Analyzer - Control Drawer</h1>
 *
 * Module:      CustomSideDrawerOverlay.kt
 * Description: The control drawer overlay: slides in from the bottom (portrait) or from the left
 *              or right side (landscape), with the FAB menu next to it.
 *              Based on: https://medium.com/@kerry.bisset/implementing-a-customside-sheet-in-jetpack-compose-49e5d61b9847
 *
 * @author Kerry Bisset (original template)
 * @author Dennis Mantz (modifications)
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


enum class DrawerSide {
    LEFT, RIGHT, BOTTOM
}

data class FabAction(val icon: ImageVector, val label: String, val customIconComposable: (@Composable () -> Unit)? = null, val onClick: () -> Unit)

/**
 * The main screen [content] with the control drawer over it, which slides in from [drawerSide], and
 * the FAB menu of [fabActions].
 * Based on: https://medium.com/@kerry.bisset/implementing-a-customside-sheet-in-jetpack-compose-49e5d61b9847
 *
 * @param isDrawerOpen Whether the drawer is open.
 * @param onDismiss Called when the drawer should close (back, or dragged closed).
 * @param onOpen Called by the open button shown while the drawer is closed.
 * @param drawerHeader Composable shown above [drawerContent] (the tab row).
 * @param drawerContent Composable content that is displayed inside the drawer.
 * @param content Composable content of the main screen.
 * @param modifier Modifier to be applied to the drawer overlay container.
 * @param drawerSizeExpanded Height of the open bottom drawer.
 * @param drawerWidth Width of a side drawer, maximum width of the bottom drawer.
 * @param animationDuration Duration of the drawer open/close animation in milliseconds.
 * @param drawerSide Side of the screen where the drawer appears.
 * @param cornerRadius Corner radius of the drawer; the drag handle lies in this strip.
 * @param dragThresholdFraction Fraction of the drawer's size that must be dragged to close it.
 * @param enableSwipe Whether the drag handle opens/closes the drawer.
 */
@Composable
fun CustomSideDrawerOverlay(
    isDrawerOpen: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    drawerHeader: @Composable () -> Unit,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
    fabActions: List<FabAction>,
    modifier: Modifier = Modifier,
    /** Small info hovering at the start, level with the FAB's bottom, moving with the drawer like it. */
    floatingInfo: (@Composable () -> Unit)? = null,
    drawerSizeExpanded: Dp = 350.dp,
    drawerWidth: Dp = 400.dp,
    animationDuration: Int = 300,
    drawerSide: DrawerSide = DrawerSide.BOTTOM,
    cornerRadius: Dp = 10.dp,
    dragThresholdFraction: Float = 0.7f,
    enableSwipe: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val drawerHandleColor = MaterialTheme.colorScheme.secondary

    val density = LocalDensity.current

    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp

    // Size of the drawer in pixels
    val drawerSizeFullScreenPx = with(density) { screenHeightDp.toPx() }
    val drawerSizePx = with(density) { drawerSizeExpanded.toPx() }
    val drawerWidthPx = with(density) { drawerWidth.toPx() }

    // The drag handler (pointerInput(Unit)) would otherwise keep the drawerSide it was created with.
    val currentDrawerSide by rememberUpdatedState(drawerSide)

    // State of the FAB menu:
    var fabExpanded by remember { mutableStateOf(false) }

    // Offset for the drawer animation
    val offset = remember {
        Animatable(
            if (isDrawerOpen) 0f
            else {
                if (drawerSide == DrawerSide.BOTTOM) drawerSizePx
                else drawerWidthPx * (if (drawerSide == DrawerSide.LEFT) -1 else 1)
            }
        )
    }

    // Launch animation when the drawer state changes
    LaunchedEffect(isDrawerOpen) {
        val targetOffset =
            if (isDrawerOpen) {
                if (drawerSide == DrawerSide.BOTTOM)
                    drawerSizeFullScreenPx - drawerSizePx
                else
                    0f
            } else {
                when(drawerSide) {
                    DrawerSide.LEFT   -> drawerWidthPx * -1
                    DrawerSide.RIGHT  -> drawerWidthPx
                    DrawerSide.BOTTOM -> drawerSizeFullScreenPx
                }
            }
        offset.animateTo(
            targetValue = targetOffset,
            animationSpec = tween(durationMillis = animationDuration)
        )
    }

    if (isDrawerOpen) {
        BackHandler {
            onDismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomEnd) {
        // Main screen content (add padding if the drawer is open and hides the left/right side)
        Box(modifier = when {
            (isDrawerOpen && drawerSide == DrawerSide.LEFT)  -> Modifier.padding(start = drawerWidth)
            (isDrawerOpen && drawerSide == DrawerSide.RIGHT) -> Modifier.padding(end = drawerWidth)
            else -> Modifier
        }
        ) {
            content()
        }

        // Drawer open button (only when closed)
        if (!isDrawerOpen) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment =
                    when (drawerSide) {
                        DrawerSide.BOTTOM -> Alignment.BottomCenter
                        DrawerSide.LEFT   -> Alignment.CenterStart
                        DrawerSide.RIGHT  -> Alignment.CenterEnd
                    }
            ) {
                IconButton(
                    onClick = onOpen,
                    modifier = Modifier
                        .padding(
                            bottom = if (drawerSide == DrawerSide.BOTTOM) 24.dp else 0.dp,
                            start = if (drawerSide == DrawerSide.LEFT) 16.dp else 0.dp,
                            end   = if (drawerSide == DrawerSide.RIGHT) 16.dp else 0.dp
                        )
                        .size(56.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                        )
                        .border(1.dp, Color.White, shape = CircleShape)
                ) {
                    Icon(
                        imageVector =
                            when (drawerSide) {
                                DrawerSide.BOTTOM -> Icons.Default.KeyboardArrowUp
                                DrawerSide.LEFT   -> Icons.Default.KeyboardArrowRight
                                DrawerSide.RIGHT  -> Icons.Default.KeyboardArrowLeft
                            },
                        contentDescription = "Open Drawer",
                    )
                }
            }
        }

        // Drawer content
        Box(
            modifier = Modifier.then(
                if (drawerSide == DrawerSide.BOTTOM) {
                    Modifier
                        .padding(horizontal = 10.dp)
                        .widthIn(max = drawerWidth)
                        .height(screenHeightDp - with(density){offset.value.toDp()})  // Bottom drawer drag affects size of drawer
                } else {
                    Modifier.fillMaxHeight().width(drawerWidth)
                }
            )
                .offset {
                    IntOffset(
                        x = if (drawerSide == DrawerSide.BOTTOM) 0 else offset.value.roundToInt(),      // Side drawer drag affects horizontal offset
                        y = 0
                    )
                }
                .align(
                    when (drawerSide) {
                        DrawerSide.LEFT -> Alignment.CenterStart
                        DrawerSide.RIGHT -> Alignment.CenterEnd
                        DrawerSide.BOTTOM -> Alignment.BottomCenter
                    }
                )
                .background(
                    color = if(drawerSide == DrawerSide.BOTTOM)
                                MaterialTheme.colorScheme.surfaceVariant   // the handle strip matches the content (portrait)
                            else
                                MaterialTheme.colorScheme.surface,         // darker handle strip (landscape)
                    shape = if (cornerRadius > 0.dp) {
                        if (drawerSide == DrawerSide.LEFT) {
                            RoundedCornerShape(topEnd = cornerRadius, bottomEnd = cornerRadius)
                        } else if (drawerSide == DrawerSide.RIGHT) {
                            RoundedCornerShape(topStart = cornerRadius, bottomStart = cornerRadius)
                        } else {
                            RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
                        }
                    } else {
                        RectangleShape
                    }
                )
                .drawBehind {
                    // The drag handle: a line in the corner-radius strip
                    val strokeWidth = 2.dp.toPx()
                    if (drawerSide == DrawerSide.BOTTOM) {
                        drawLine(
                            color = drawerHandleColor,
                            start = Offset(size.width / 4, cornerRadius.toPx() / 2),
                            end = Offset(size.width / 4 * 3, cornerRadius.toPx() / 2),
                            strokeWidth = strokeWidth
                        )
                    } else {
                        val xOffset = if(drawerSide == DrawerSide.LEFT)
                            size.width - cornerRadius.toPx()/2
                            else cornerRadius.toPx()/2
                        drawLine(
                            color = drawerHandleColor,
                            start = Offset(xOffset, size.height / 4),
                            end = Offset(xOffset, size.height / 4 * 3),
                            strokeWidth = strokeWidth
                        )
                    }
                }
        ) {
            // Content inside the drawer, padded to leave the drag handle strip free.
            // pointerHitTestSink() comes before the padding, so it covers the whole drawer including
            // the handle strip: a tap on blank drawer space must not fall through to the
            // AnalyzerSurface and retune. The drag-handle Box below is a later sibling and so is
            // still hit-tested first.
            Box(modifier = Modifier.pointerHitTestSink().then(when(drawerSide) {
                DrawerSide.LEFT   -> Modifier.padding(end   = cornerRadius)
                DrawerSide.RIGHT  -> Modifier.padding(start = cornerRadius)
                DrawerSide.BOTTOM -> Modifier.padding(top   = cornerRadius)
            }).background(MaterialTheme.colorScheme.surfaceVariant)  // background of the content
            ) {
                Column {
                    drawerHeader()
                    drawerContent()
                }
            }

            // The drag handle's own hit area, around the handle line rather than the whole drawer
            // (that would steal gestures from drawerContent()). It reaches dragHandleOffset into the
            // FFT/waterfall plot so it is easier to grab.
            val dragHandleOffset = 15.dp
            Box(
                modifier = Modifier
                    .align(
                        when (drawerSide) {
                            DrawerSide.BOTTOM -> Alignment.TopCenter
                            DrawerSide.LEFT   -> Alignment.CenterEnd
                            DrawerSide.RIGHT  -> Alignment.CenterStart
                        }
                    )
                    .offset(
                        x = if (drawerSide == DrawerSide.BOTTOM) 0.dp else dragHandleOffset.times(-1),
                        y = if (drawerSide == DrawerSide.BOTTOM) dragHandleOffset.times(-1) else 0.dp,
                    )
                    .then(
                        if (drawerSide == DrawerSide.BOTTOM) Modifier.fillMaxWidth(0.95f).height(cornerRadius + dragHandleOffset + 3.dp) // +3.dp to overlap minimal into the tab header
                        else Modifier.fillMaxHeight().width(cornerRadius + dragHandleOffset + 5.dp) // +5.dp to overlap a bit into the tab space (which is padding anyway)
                    )
                    .pointerInput(Unit) {
                        if (enableSwipe) {
                            detectDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        val shouldClose = when (currentDrawerSide) {
                                            DrawerSide.LEFT -> offset.value < -drawerWidthPx * dragThresholdFraction
                                            DrawerSide.RIGHT -> offset.value > drawerWidthPx * dragThresholdFraction
                                            DrawerSide.BOTTOM -> drawerSizeFullScreenPx - offset.value < drawerSizePx * (1-dragThresholdFraction)
                                        }

                                        val finalTarget = if (shouldClose) {
                                            when (currentDrawerSide) {
                                                DrawerSide.BOTTOM -> drawerSizeFullScreenPx
                                                DrawerSide.LEFT -> drawerWidthPx * -1
                                                DrawerSide.RIGHT -> drawerWidthPx
                                            }
                                        } else if (currentDrawerSide==DrawerSide.BOTTOM) {
                                            offset.value
                                        } else {
                                            0f
                                        }

                                        offset.animateTo(
                                            targetValue = finalTarget,
                                            animationSpec = tween(durationMillis = animationDuration)
                                        )

                                        if (shouldClose) {
                                            onDismiss()
                                        }
                                    }
                                }
                            ) { change, dragAmount ->
                                change.consume()

                                scope.launch {
                                    val newOffset =
                                        offset.value + if (currentDrawerSide == DrawerSide.BOTTOM) dragAmount.y else dragAmount.x

                                    val clampedOffset = when (currentDrawerSide) {
                                        DrawerSide.LEFT -> newOffset.coerceIn(-drawerWidthPx, 0f)
                                        DrawerSide.RIGHT -> newOffset.coerceIn(0f, drawerWidthPx)
                                        DrawerSide.BOTTOM -> newOffset.coerceIn(0f, drawerSizeFullScreenPx)
                                    }

                                    offset.snapTo(clampedOffset)
                                }
                            }
                        }
                    }
            )
        }

        if (floatingInfo != null) {
            // Above the bottom drawer's start edge, or at the bottom of the content next to a side
            // drawer. Its own 8 dp touch margin puts what it shows level with the FAB's bottom.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = if (drawerSide == DrawerSide.BOTTOM) 10.dp else 16.dp, bottom = 8.dp)
                    .offset {
                        IntOffset(
                            x = if (drawerSide == DrawerSide.LEFT) (offset.value + drawerWidthPx).roundToInt() else 0,
                            y = if (drawerSide == DrawerSide.BOTTOM) offset.value.roundToInt() - drawerSizeFullScreenPx.toInt() else 0,
                        )
                    }
            ) {
                floatingInfo()
            }
        }

        // FAB
        if (fabExpanded) { // Close FAB menu when tapping outside
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) {
                        detectTapGestures {
                            fabExpanded = false
                        }
                    }
            )
        }
        val fabOffset = IntOffset( // offsets according to the drawer position
            x = if (drawerSide == DrawerSide.BOTTOM) 0 else if (drawerSide == DrawerSide.LEFT) 0 else offset.value.toInt() - drawerWidthPx.toInt(),
            y = if (drawerSide != DrawerSide.BOTTOM) 0 else offset.value.roundToInt() - drawerSizeFullScreenPx.toInt()
        )
        // The menu opens upwards from the FAB (whose top is 72 dp above the bottom drawer's top edge).
        // When the bottom drawer is pulled up so far that the menu doesn't fit above, it opens
        // downwards from the drawer's top edge instead, over the drawer, in mirrored order.
        val fabMenuHeightPx = with(density) { (fabActions.size * 40 + fabActions.size * 16).dp.toPx() }
        val fabMenuOpensDown = drawerSide == DrawerSide.BOTTOM &&
            offset.value - with(density) { 72.dp.toPx() } < fabMenuHeightPx
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.End,
            modifier = if (fabMenuOpensDown) {
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp)
                    .offset { IntOffset(0, offset.value.roundToInt()) }
            } else {
                Modifier
                    .padding(bottom = 72.dp, end = 8.dp)
                    .offset { fabOffset }
            }
        ) {
            for (action in if (fabMenuOpensDown) fabActions.asReversed() else fabActions) {
                AnimatedVisibility(visible = fabExpanded, enter = fadeIn() + slideInHorizontally(), exit = fadeOut() + slideOutHorizontally()) {
                    if (action.customIconComposable != null)
                        FabActionComposableCustom(custom = action.customIconComposable, label = action.label, onClick = { action.onClick(); fabExpanded = false })
                    else
                        FabActionComposable(icon = action.icon, label = action.label, onClick = { action.onClick(); fabExpanded = false })
                }
            }
        }
        FloatingActionButton(
            onClick = { fabExpanded = !fabExpanded },
            containerColor = if (!fabExpanded) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
            modifier = Modifier
                .padding(16.dp)
                .offset { fabOffset }
                .then(
                    if (!fabExpanded) Modifier.border(1.dp, Color.White, shape = MaterialTheme.shapes.medium) else Modifier
                )
        ) {
            if (fabExpanded) {
                Surface(shape = CircleShape, shadowElevation = 4.dp, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.Default.Close,
                        contentDescription = "Close Menu",
                        modifier = Modifier
                            .size(40.dp)
                            .padding(8.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            } else
                Icon(Icons.Default.MenuBook, contentDescription = "Open Menu")
        }
    }
}


@Composable
private fun FabActionComposable(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onClick() }
                .padding(end = 12.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier
                    .size(40.dp)
                    .padding(8.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FabActionComposableCustom(custom: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onClick() }
                .padding(end = 12.dp)
        ) {
            custom()
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
