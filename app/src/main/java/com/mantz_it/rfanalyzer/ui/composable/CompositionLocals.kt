package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * <h1>RF Analyzer - Composition Locals</h1>
 *
 * Module:      CompositionLocals.kt
 * Description: Composition locals the app's screens share: the SnackbarHostState and LocalShowHelp,
 *              which opens a page of the user manual (both provided by MainActivity), and
 *              DEFAULT_MIN_BOX_HEIGHT, the minimum height of the outlined form fields.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

val DEFAULT_MIN_BOX_HEIGHT = 70.dp

val LocalSnackbarHostState = compositionLocalOf<SnackbarHostState> {
    error("SnackbarHostState not provided")
}

val LocalShowHelp = compositionLocalOf<(String) -> Unit> {
    error("LocalShowHelp not provided")
}
