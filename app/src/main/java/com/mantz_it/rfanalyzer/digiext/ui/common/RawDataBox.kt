package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * <h1>RF Analyzer - Raw Data Box</h1>
 *
 * Module:      RawDataBox.kt
 * Description: Container for raw/monospace packet content (hex dumps, bit strings, decoded text),
 *              in the theme's colours.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Composable
fun RawDataBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp), content = content)
    }
}

/** [RawDataBox] wrapping a single monospace text block (decoded text, bit strings, ASCII view, ...). */
@Composable
fun MonoTextBox(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 10.sp) {
    RawDataBox(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                lineHeight = fontSize * 1.4f,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The bytes in `hexdump -C` style: address, hex and ASCII columns. */
fun ByteArray.toHexDump(widthInByte: Int = 16, widthOfAddress: Int = 8): String {
    val result = StringBuilder()
    val hexPart = StringBuilder()
    val asciiPart = StringBuilder()

    for (i in indices) {
        val firstInRow = i % widthInByte == 0
        val lastInRow = i % widthInByte == widthInByte - 1
        if (firstInRow) {
            result.append("%0${widthOfAddress}x  ".format(Locale.US, i))
        }

        val b = this[i].toInt() and 0xFF
        hexPart.append("%02x ".format(Locale.US, b))

        asciiPart.append(if (b in 32..126) b.toChar() else '.')

        if (lastInRow || i == size - 1) {
            // Pad hex part if last line is short
            if (i == size - 1 && !lastInRow) {
                val remaining = widthInByte - 1 - (i % widthInByte)
                for (j in 0 until remaining) {
                    hexPart.append("   ")
                }
            }
            result.append(hexPart.toString())
            result.append(" |")
            result.append(asciiPart.toString())
            result.append("|\n")
            hexPart.setLength(0)
            asciiPart.setLength(0)
        }
    }
    return result.toString().trimEnd()
}

