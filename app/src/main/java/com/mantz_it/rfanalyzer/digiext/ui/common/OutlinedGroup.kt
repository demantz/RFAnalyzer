package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * <h1>RF Analyzer - Outlined Group</h1>
 *
 * Module:      OutlinedGroup.kt
 * Description: The app's outlined box with its label set into the border (like "Tune Frequency"
 *              in the Source tab), drawn without a background so it works on any surface. The
 *              Digital tab's ticker and the details page's groups use it.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

private val LABEL_INSET = 10.dp
private val LABEL_GAP = 4.dp

@Composable
fun OutlinedGroup(
    label: String,
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = PaddingValues(start = 10.dp, end = 10.dp, top = 1.dp, bottom = 6.dp),
    cornerRadius: Dp = 6.dp,
    /** Set into the border at the right, like the label at the left (the ticker's open/close chevron). */
    endLabel: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Where the labels sit, written by the layout and read by the border drawing: the border is
    // drawn around them, not under them, so no background colour has to be guessed.
    var labelRect by remember { mutableStateOf(Rect.Zero) }
    var endRect by remember { mutableStateOf(Rect.Zero) }
    val labelInset = LABEL_INSET
    val gap = LABEL_GAP
    Layout(
        content = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, color = labelColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.foundation.layout.Box(Modifier.padding(contentPadding)) { content() }
            endLabel?.invoke()
        },
        modifier = modifier.drawBehind {
            val top = labelRect.center.y
            val stroke = 1.dp.toPx()
            val inset = stroke / 2
            val gapPx = gap.toPx()
            clipRect(left = labelRect.left - gapPx, top = 0f, right = labelRect.right + gapPx, bottom = labelRect.bottom, clipOp = ClipOp.Difference) {
                clipRect(left = endRect.left - gapPx, top = 0f, right = endRect.right + gapPx, bottom = endRect.bottom, clipOp = ClipOp.Difference) {
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(inset, top + inset),
                        size = Size(size.width - stroke, size.height - top - stroke),
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                        style = Stroke(width = stroke),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val insetPx = labelInset.roundToPx()
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val endPlaceable = measurables.getOrNull(2)?.measure(loose)
        val endRoom = endPlaceable?.let { it.width + insetPx + 2 * gapPx } ?: 0
        val labelPlaceable = measurables[0].measure(
            loose.copy(maxWidth = (constraints.maxWidth - 2 * (insetPx + gapPx) - endRoom).coerceAtLeast(0)),
        )
        val labelHeight = maxOf(labelPlaceable.height, endPlaceable?.height ?: 0)
        val contentPlaceable = measurables[1].measure(constraints.copy(minHeight = 0))
        val width = maxOf(contentPlaceable.width, constraints.minWidth)
        val height = labelHeight + contentPlaceable.height
        layout(width, height) {
            val labelX = insetPx + gapPx
            val labelY = (labelHeight - labelPlaceable.height) / 2
            labelRect = Rect(labelX.toFloat(), labelY.toFloat(), (labelX + labelPlaceable.width).toFloat(), (labelY + labelPlaceable.height).toFloat())
            labelPlaceable.place(labelX, labelY)
            endRect = endPlaceable?.let { end ->
                val endX = width - insetPx - gapPx - end.width
                val endY = (labelHeight - end.height) / 2
                end.place(endX, endY)
                Rect(endX.toFloat(), endY.toFloat(), (endX + end.width).toFloat(), (endY + end.height).toFloat())
            } ?: Rect.Zero
            contentPlaceable.place(0, labelHeight)
        }
    }
}
