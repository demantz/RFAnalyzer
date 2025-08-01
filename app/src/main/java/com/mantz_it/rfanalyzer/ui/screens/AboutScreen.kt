package com.mantz_it.rfanalyzer.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.mantz_it.rfanalyzer.R
import androidx.core.net.toUri

/**
 * <h1>RF Analyzer - About Screen</h1>
 *
 * Module:      AboutScreen.kt
 * Description: The About Screen of the app. Contains license information about the app.
 *
 * @author Dennis Mantz
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(versionName: String, navController: NavController) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val htmlText = context.getString(R.string.about_screen_html)
    val annotatedText = parseHtmlToAnnotatedString(htmlText, linkColor)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RF Analyzer $versionName") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            ClickableText(
                text = annotatedText,
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                onClick = { offset ->
                    annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            val intent = Intent(Intent.ACTION_VIEW, annotation.item.toUri())
                            context.startActivity(intent)
                        }
                }
            )
            //AndroidView(
            //    factory = { context -> TextView(context).apply {
            //        text = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY)
            //        setTextAppearance(android.R.style.TextAppearance_Small)
            //        movementMethod = LinkMovementMethod.getInstance() // Enables link clicking
            //    } },
            //    modifier = Modifier.fillMaxWidth()
            //)
        }
    }
}

/**
 * Converts HTML to an [AnnotatedString] while preserving clickable links and bold/italic styling.
 */
fun parseHtmlToAnnotatedString(html: String, linkColor: Color): AnnotatedString {
    val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    return buildAnnotatedString {
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)

            when (span) {
                is android.text.style.StyleSpan -> {
                    if (span.style == android.graphics.Typeface.BOLD) {
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                    } else if (span.style == android.graphics.Typeface.ITALIC) {
                        addStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, end)
                    }
                }
                is android.text.style.URLSpan -> {
                    addStyle(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        ),
                        start, end
                    )
                    addStringAnnotation(tag = "URL", annotation = span.url, start, end)
                }
            }
        }
        append(spanned.toString()) // Append the final text content
    }
}

@Preview(showBackground = true)
@Composable
fun AboutScreenPreview() {
    AboutScreen("2.1337", navController = rememberNavController())
}