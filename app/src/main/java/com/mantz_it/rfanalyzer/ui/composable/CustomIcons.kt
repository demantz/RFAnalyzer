package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.ui.RFAnalyzerTheme

/**
 * <h1>RF Analyzer - CustomIcons</h1>
 *
 * Module:      CustomIcons.kt
 * Description: Custom Icons for the App
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

val IconTuneStation: ImageVector
    @Composable
    get() = remember {
        ImageVector.Builder(
            name = "TuneStationLargeText",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 1. The Text "TUNE" (Expanded and Bold)
            // Occupies Y: 0.5 to 4.5
            path(fill = SolidColor(Color.Black)) {
                // Letter T
                moveTo(2f, 0.5f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(-1f)
                verticalLineToRelative(-3f)
                horizontalLineToRelative(-2f)
                close()

                // Letter U
                moveTo(8f, 0.5f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-3f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(-4f)
                close()

                // Letter N
                moveTo(13f, 0.5f)
                horizontalLineToRelative(1.2f)
                lineToRelative(1.8f, 3f)
                verticalLineToRelative(-3f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(-1.2f)
                lineToRelative(-1.8f, -3f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(-1f)
                close()

                // Letter E
                moveTo(18f, 0.5f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(-3f)
                verticalLineToRelative(0.5f)
                horizontalLineToRelative(2.5f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(-2.5f)
                verticalLineToRelative(0.5f)
                horizontalLineToRelative(3f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(-4f)
                close()
            }

            // 2. The Pointer (Inverted Triangle)
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 11.5f) // Tip pointing down
                lineTo(7.5f, 5.5f) // Top left
                lineTo(16.5f, 5.5f)// Top right
                close()
            }

            // 3. The Frequency Scale (The "Ruler" bottom)
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(21f, 13f)
                horizontalLineTo(3f)
                curveTo(1.9f, 13f, 1f, 13.9f, 1f, 15f)
                verticalLineToRelative(4f)
                curveTo(1f, 20.1f, 1.9f, 21f, 3f, 21f)
                horizontalLineToRelative(18f)
                curveTo(22.1f, 21f, 23f, 20.1f, 23f, 19f)
                verticalLineToRelative(-4f)
                curveTo(23f, 13.9f, 22.1f, 13f, 21f, 13f)
                close()

                // Ticks
                moveTo(21f, 19f)
                horizontalLineTo(3f)
                verticalLineToRelative(-4f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineTo(19f)
                close()
            }
        }.build()
    }

val IconTuneBand: ImageVector
    @Composable
    get() = remember {
        ImageVector.Builder(
            name = "TuneStationLargeText",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 1. The Text "TUNE" (Expanded and Bold)
            // Occupies Y: 0.5 to 4.5
            path(fill = SolidColor(Color.Black)) {
                // Letter T
                moveTo(2f, 0.5f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(-1f)
                verticalLineToRelative(-3f)
                horizontalLineToRelative(-2f)
                close()

                // Letter U
                moveTo(8f, 0.5f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-3f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(-4f)
                close()

                // Letter N
                moveTo(13f, 0.5f)
                horizontalLineToRelative(1.2f)
                lineToRelative(1.8f, 3f)
                verticalLineToRelative(-3f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(-1.2f)
                lineToRelative(-1.8f, -3f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(-1f)
                close()

                // Letter E
                moveTo(18f, 0.5f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(-3f)
                verticalLineToRelative(0.5f)
                horizontalLineToRelative(2.5f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(-2.5f)
                verticalLineToRelative(0.5f)
                horizontalLineToRelative(3f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(-4f)
                close()
            }

            // 2. The Pointer (Inverted Triangle)
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 11.5f) // Tip pointing down
                lineTo(1.5f, 5.5f) // Top left
                lineTo(10.5f, 5.5f)// Top right
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 11.5f) // Tip pointing down
                lineTo(13.5f, 5.5f) // Top left
                lineTo(22.5f, 5.5f)// Top right
                close()
            }

            // 3. The Frequency Scale (The "Ruler" bottom)
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(21f, 13f)
                horizontalLineTo(3f)
                curveTo(1.9f, 13f, 1f, 13.9f, 1f, 15f)
                verticalLineToRelative(4f)
                curveTo(1f, 20.1f, 1.9f, 21f, 3f, 21f)
                horizontalLineToRelative(18f)
                curveTo(22.1f, 21f, 23f, 20.1f, 23f, 19f)
                verticalLineToRelative(-4f)
                curveTo(23f, 13.9f, 22.1f, 13f, 21f, 13f)
                close()

                // Ticks
                moveTo(21f, 19f)
                horizontalLineTo(3f)
                verticalLineToRelative(-4f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineTo(19f)
                close()
            }
        }.build()
    }

val IconFrequencyFilter: ImageVector
    @Composable
    get() = remember {
        ImageVector.Builder(
            name = "FrequencyFilterHzFinal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 1. The Text "Hz"
            // Occupies roughly x=7 to x=17, y=2 to y=8
            path(fill = SolidColor(Color.Black)) {
                // Letter H
                moveTo(7f, 2f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(2.5f)
                horizontalLineToRelative(2f)
                verticalLineTo(2f)
                horizontalLineTo(12f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(5.5f)
                horizontalLineTo(8.5f)
                verticalLineTo(8f)
                horizontalLineTo(7f)
                close()

                // Letter z (lowercase height)
                moveTo(17f, 3.5f)
                horizontalLineToRelative(-4f)
                verticalLineTo(5f)
                horizontalLineToRelative(2.2f)
                lineTo(13f, 7f) // diagonal down to the left
                verticalLineToRelative(1f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(-1.5f)
                horizontalLineToRelative(-2.2f)
                lineTo(17f, 5f) // diagonal up to the right
                close()
            }

            // 2. The Filter Range Arrows (Triangles pointing inward)
            path(fill = SolidColor(Color.Black)) {
                // Left Triangle (Points Right) tip at x=6, y=9
                moveTo(6f, 9f)
                lineTo(2f, 6f)
                lineTo(2f, 12f)
                close()

                // Right Triangle (Points Left) tip at x=18, y=9
                moveTo(18f, 9f)
                lineTo(22f, 6f)
                lineTo(22f, 12f)
                close()
            }

            // 3. The Frequency Scale (Ruler at the bottom)
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(21f, 13f)
                horizontalLineTo(3f)
                curveTo(1.9f, 13f, 1f, 13.9f, 1f, 15f)
                verticalLineToRelative(4f)
                curveTo(1f, 20.1f, 1.9f, 21f, 3f, 21f)
                horizontalLineToRelative(18f)
                curveTo(22.1f, 21f, 23f, 20.1f, 23f, 19f)
                verticalLineToRelative(-4f)
                curveTo(23f, 13.9f, 22.1f, 13f, 21f, 13f)
                close()
                // Ticks
                moveTo(21f, 19f)
                horizontalLineTo(3f)
                verticalLineToRelative(-4f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineTo(19f)
                close()
            }
        }.build()
    }

val IconFrequencyBand: ImageVector = ImageVector.Builder(
    name = "FrequencyBand",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    // 1. The Spectrum Scale (Baseline ruler at the bottom)
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.5f,
        strokeLineCap = StrokeCap.Round
    ) {
        // Main baseline from x=2 to x=22 at y=20
        moveTo(2f, 20f)
        lineTo(22f, 20f)

        // Tick marks (simplified scale)
        moveTo(2f, 20f); lineTo(2f, 18f)  // Start tick
        moveTo(7f, 20f); lineTo(7f, 19f)  // Minor tick
        moveTo(12f, 20f); lineTo(12f, 18f) // Middle tick
        moveTo(17f, 20f); lineTo(17f, 19f) // Minor tick
        moveTo(22f, 20f); lineTo(22f, 18f) // End tick
    }

    // 2. The Defined Band (The block sitting on the scale)
    path(fill = SolidColor(Color.Black)) {
        // The rectangular band block
        moveTo(5f, 11.5f) // Bottom left of the band
        lineTo(5f, 7f)  // Top left
        lineTo(19f, 7f) // Top right
        lineTo(19f, 11.5f)// Bottom right
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        // The left subband
        moveTo(5f, 16f) // Bottom left of the band
        lineTo(5f, 12f)  // Top left
        lineTo(14.5f, 12f) // Top right
        lineTo(14.5f, 16f)// Bottom right
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        // The right subband
        moveTo(15f, 16f) // Bottom left of the band
        lineTo(15f, 12f)  // Top left
        lineTo(19f, 12f) // Top right
        lineTo(19f, 16f)// Bottom right
        close()
    }

    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 0.5f,
        strokeLineCap = StrokeCap.Square
    ) {
        moveTo(5f, 6f); lineTo(5f, 17f)
        moveTo(19f, 6f); lineTo(19f, 17f)
    }
}.build()

val IconFrequencyBandOpen: ImageVector = ImageVector.Builder(
    name = "FrequencyBandOpen",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    // 1. The Spectrum Scale (Baseline ruler)
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.5f,
        strokeLineCap = StrokeCap.Round
    ) {
        moveTo(2f, 20f); lineTo(17.5f, 20f) // Shortened slightly to make room for arrow
        moveTo(2f, 20f); lineTo(2f, 18f)
        moveTo(7f, 20f); lineTo(7f, 19f)
        moveTo(12f, 20f); lineTo(12f, 18f)
        moveTo(17f, 20f); lineTo(17f, 19f)
    }

    // 2. The Defined Bands (Main blocks)
    path(fill = SolidColor(Color.Black)) {
        moveTo(5f, 11.5f); lineTo(5f, 7f); lineTo(19f, 7f); lineTo(19f, 11.5f); close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(5f, 16f); lineTo(5f, 12f); lineTo(14.5f, 12f); lineTo(14.5f, 16f); close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(15f, 16f); lineTo(15f, 12f); lineTo(19f, 12f); lineTo(19f, 16f); close()
    }

    // Vertical boundary lines
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 0.5f,
        strokeLineCap = StrokeCap.Square
    ) {
        moveTo(5f, 6f); lineTo(5f, 17f)
        moveTo(19f, 6f); lineTo(19f, 15f)
    }

    // 3. The "FileOpen" style Arrow (Bottom Right)
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        // The L-shaped arrow head
        moveTo(20f, 20f)
        lineTo(20f, 17f)
        lineTo(23f, 17f)
        // The diagonal stem
        moveTo(23f, 20f)
        lineTo(20f, 17f)
    }
}.build()

@Preview(showBackground = true)
@Composable
fun BandIconPreview() {
    Icon(
        imageVector = IconFrequencyBandOpen,
        contentDescription = "Frequency Band",
        modifier = Modifier.padding(8.dp).size(32.dp),
        tint = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun OverlayIcon(
    mainIcon: ImageVector,
    badgeIcon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    mainIconSize: Dp = 24.dp,
    badgeIconSize: Dp = 12.dp,
    badgeTint: Color = MaterialTheme.colorScheme.onPrimary,
    badgePadding: Dp = 0.dp,
    badgeAlignment: Alignment = Alignment.BottomEnd
) {
    Box(modifier = modifier) {
        Icon(
            imageVector = mainIcon,
            contentDescription = contentDescription,
            modifier = Modifier.size(mainIconSize)
        )
        Icon(
            imageVector = badgeIcon,
            contentDescription = contentDescription,
            tint = badgeTint,
            modifier = Modifier
                .padding(badgePadding)
                .size(badgeIconSize)
                .align(badgeAlignment)
        )
    }
}

@Preview
@Composable
fun OverlayIconPreview() {
    RFAnalyzerTheme {
        Row {
            OverlayIcon(Icons.Default.Public, Icons.Default.Add, contentDescription = "test")
            IconButton(onClick = {}) { OverlayIcon(Icons.Default.Radio, Icons.Default.Favorite, contentDescription = "Station Favorites", badgeIconSize = 9.dp, badgePadding = 2.dp, badgeTint = Color.Red) }
        }
    }
}

@Preview
@Composable
fun IconPreview() {
    Column {
        Icon(IconFrequencyFilter, contentDescription = null)
        OverlayIcon(
            Icons.Default.MenuBook,
            Icons.Default.Favorite,
            contentDescription = "Favorites",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            mainIconSize = 32.dp,
            badgeIconSize = 12.dp,
            badgePadding = 3.dp,
            badgeTint = Color.Red,
            badgeAlignment = Alignment.CenterStart
        )
    }
}