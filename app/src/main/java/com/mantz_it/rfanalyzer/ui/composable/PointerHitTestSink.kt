package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * <h1>RF Analyzer - Pointer Hit Test Sink</h1>
 *
 * Module:      PointerHitTestSink.kt
 * Description: Makes an element opaque to Compose hit testing without taking any gesture from it.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * Registers a pointer-input node over this element's bounds that consumes nothing.
 *
 * Compose's hit test walks siblings in reverse draw order and stops at the first subtree that
 * reports a hit. `background()` and `drawBehind {}` install *draw* nodes, not pointer-input nodes,
 * so a tap on painted-but-otherwise-empty space produces no hit and falls through to whatever lies
 * behind - here the full-screen AnalyzerSurface, whose `onSingleTapUp` retunes the channel.
 * This modifier makes the element hit-testable, which stops the fall-through.
 *
 * It consumes nothing and observes on the default (Main) pass, so children keep every gesture they
 * had: they are hit-tested inside this subtree and dispatched to first. (Unlike DisplayTab's
 * `stopParentDrag`, which consumes every change: that would break the drawer's own sliders,
 * wheels and lists.)
 */
fun Modifier.pointerHitTestSink(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
