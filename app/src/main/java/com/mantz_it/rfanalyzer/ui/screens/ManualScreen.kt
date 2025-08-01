package com.mantz_it.rfanalyzer.ui.screens

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.core.net.toUri

/**
 * <h1>RF Analyzer - Manual Screen</h1>
 *
 * Module:      ManualScreen.kt
 * Description: The manual screen which contains a embedded browser for the
 * mkdocs manual.
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
fun ManualScreen(navController: NavController, subUrl: String = "index.html") {
    val BASE_URL = "file:///android_asset/docs/"
    val HOME_SUB_URL = "index.html"
    var currentUrl by rememberSaveable { mutableStateOf(BASE_URL+subUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Intercept system back gesture/button
    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            currentUrl = wv.url ?: currentUrl
        } else {
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RF Analyzer Manual") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { currentUrl = BASE_URL+HOME_SUB_URL }) {
                        Icon(Icons.Filled.Home, contentDescription = "Go to Start")
                    }
                }
            )
        }
    ) { paddingValues ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            factory = { context ->
                WebView(context).apply {
                    webViewRef = this
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    //setWebContentsDebuggingEnabled(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url.toString()
                            return if (url.startsWith("file:///")) {
                                view?.loadUrl(url)
                                currentUrl = url // Save last visited page
                                true
                            } else {
                                // Open external links in system browser
                                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                true
                            }
                        }
                    }
                    loadUrl(currentUrl)
                }
            },
            update = { webView ->
                if (webView.url != currentUrl) {
                    webView.loadUrl(currentUrl)
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ManualScreenPreview() {
    ManualScreen(navController = rememberNavController())
}
