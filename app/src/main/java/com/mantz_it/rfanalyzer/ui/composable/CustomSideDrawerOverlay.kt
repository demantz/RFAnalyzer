package com.mantz_it.rfanalyzer.ui.composable

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
 * Module:      CustomSideDrawer.kt
 * Description: A composable component hat provides a side drawer overlay.
 * This drawer slides in and out from either the left or right side of the
 * screen and can be used to display additional options or information.
 * based on: https://medium.com/@kerry.bisset/implementing-a-customside-sheet-in-jetpack-compose-49e5d61b9847
 *
 * @author Kerry Bisset (original template)
 * @author Dennis Mantz (modifications)
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */


enum class DrawerSide {
    LEFT, RIGHT, BOTTOM
}

/**
 * A custom composable that provides a side drawer overlay.
 * This drawer slides in and out from either the left or right side of the screen and can be used to
 * display additional options or information.
 * based on: https://medium.com/@kerry.bisset/implementing-a-customside-sheet-in-jetpack-compose-49e5d61b9847
 *
 * @param isDrawerOpen Boolean indicating whether the drawer is open.
 * @param onDismiss Callback function that gets called when the drawer should be dismissed.
 * @param drawerContent Composable content that is displayed inside the drawer.
 * @param content Composable content of the main screen.
 * @param modifier Modifier to be applied to the drawer overlay container.
 * @param drawerSizeExpanded Size of the drawer in Dp.
 * @param animationDuration Duration of the drawer open/close animation in milliseconds.
 * @param drawerSide Side of the screen where the drawer appears (left or right).
 * @param cornerRadius Corner radius of the drawer for rounded edges.
 * @param dragThresholdFraction Fraction of the drawer's width that must be dragged to open/close it.
 * @param enableSwipe Boolean indicating whether swipe gestures are enabled to open/close the drawer.
 */
@Composable
fun CustomSideDrawerOverlay(
    isDrawerOpen: Boolean,
    onDismiss: () -> Unit,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    drawerSizeExpanded: Dp = 350.dp,
    drawerWidth: Dp = 400.dp,
    animationDuration: Int = 300,
    drawerSide: DrawerSide = DrawerSide.BOTTOM,
    cornerRadius: Dp = 10.dp,
    dragThresholdFraction: Float = 0.7f,
    enableSwipe: Boolean = true,
    fabOnClick: () -> Unit
) {
    // Coroutine scope for managing animations
    val scope = rememberCoroutineScope()
    val drawerHandleColor = MaterialTheme.colorScheme.secondary

    val density = LocalDensity.current

    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp

    // Size of the drawer in pixels
    val drawerSizeFullScreenPx = with(density) { screenHeightDp.toPx() }
    val drawerSizePx = with(density) { drawerSizeExpanded.toPx() }
    val drawerWidthPx = with(density) { drawerWidth.toPx() }

    // This is necessary to have the current value of drawerSide available in scope.launch{}
    // environments. Switching drawerSide between Left and Right does not propagate into the launch context.
    // It gets "frozen" because it's captured at the time the lambda is created, not updated dynamically.
    // As a workaround we have the currentDrawerSide variable which is always current even inside launch.
    val currentDrawerSide by rememberUpdatedState(drawerSide)

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

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Main screen content (add padding if the drawer is open and hides the left/right side)
        Box(modifier = when {
            (isDrawerOpen && drawerSide == DrawerSide.LEFT)  -> Modifier.padding(start = drawerWidth)
            (isDrawerOpen && drawerSide == DrawerSide.RIGHT) -> Modifier.padding(end = drawerWidth)
            else -> Modifier
        }
        ) {
            content()
        }

        if (!isDrawerOpen) {
            // Show an action button to open the drawer
            FloatingActionButton(
                onClick = fabOnClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd) // Aligns to the bottom-right corner
                    .padding(16.dp) // Adds padding between the FAB and the edge
                    .border(1.dp, Color.White, shape = MaterialTheme.shapes.medium)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings, // Default Add icon
                    contentDescription = "Open Drawer"
                )
            }
        }

        // Drawer content
        Box(
            modifier = Modifier.then(
                if (drawerSide == DrawerSide.BOTTOM) {
                    Modifier
                        //.fillMaxWidth()
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
                                MaterialTheme.colorScheme.surfaceVariant   // Use grey for the drag handle in portrait mode
                            else
                                MaterialTheme.colorScheme.surface,         // Use darkgrey if in landscape mode
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
                    // Draw a line at the top of the Column
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
        ) {
            // Content inside the drawer (use a box with padding for the drag handle)
            Box(modifier = when(drawerSide) {
                DrawerSide.LEFT   -> Modifier.padding(end   = cornerRadius)
                DrawerSide.RIGHT  -> Modifier.padding(start = cornerRadius)
                DrawerSide.BOTTOM -> Modifier.padding(top   = cornerRadius)
            }.background(MaterialTheme.colorScheme.surfaceVariant)  // background of the content
            ) {
                drawerContent()
            }
        }
    }
}