package com.mantz_it.rfanalyzer.ui.screens

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.ui.composable.ScrollableColumnWithFadingEdge
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * <h1>RF Analyzer - Welcome Screen</h1>
 *
 * Module:      WelcomeScreen.kt
 * Description: The welcome screen which is shown at the first launch of the app
 * or when the user starts the tutorial from the AboutTab.
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

data class WelcomeScreenCard(
    val title: String,
    val description: String? = null,
    val image: Int? = null,
    val imageLink: String? = null
)

@Composable
fun WelcomeScreen(onFinish: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    val screenLongEdgeDp = if(screenHeightDp > screenWidthDp) screenHeightDp else screenWidthDp

    val headingStyle = when {
        screenLongEdgeDp < 700  -> TextStyle(fontSize = 28.sp) // Small phone
        screenLongEdgeDp < 1000 -> TextStyle(fontSize = 32.sp) // Normal phone
        else                    -> TextStyle(fontSize = 42.sp) // Tablet
    }
    val textStyle = when {
        screenLongEdgeDp < 700  -> TextStyle(fontSize = 14.sp, lineHeight = 16.sp) // Small phone
        screenLongEdgeDp < 1000 -> TextStyle(fontSize = 18.sp, lineHeight = 22.sp) // Normal phone
        else                    -> TextStyle(fontSize = 28.sp, lineHeight = 32.sp) // Tablet
    }

    val pages = listOf(
        WelcomeScreenCard("RF Analyzer 2.0", image = R.drawable.rfanalyzer2, description =
            "RF Analyzer turns your Android device into a real-time spectrum analyzer for " +
            "Software Defined Radio (SDR). \n\nVisualize and listen to radio signals around you" +
            " - from amateur radio to broadcast signals and beyond." +
            "\n\nThe TRIAL VERSION allows you to test compatibility with your hardware and lets you try all features."
        ),
        WelcomeScreenCard("How To Start",
            description = "This tutorial is also available on YouTube. \n\nThe 6-minute video guides you through the first steps with RF Analyzer. \n\nOr simply tap 'Next' to continue with the in-app tutorial.",
            image = R.drawable.youtube_quick_start_tutorial,
            imageLink = "https://www.youtube.com/watch?v=sui54fqbImw"),
        WelcomeScreenCard("Connect SDR","Plug your SDR dongle into the USB-OTG adapter and the USB-OTG adapter into your Android device.\n\nThe USB-OTG adapter sometimes comes with your phone or can be bought at very low cost. \nInstead of a USB-OTG adapter it is also possible to use a USB hub or docking station with USB-C connector.", image = R.drawable.tutorial_connect_sdr),
        WelcomeScreenCard("Select Source Type", "When you start the app, first select your SDR type in the Signal Source dropdown menu. \n\nThen press the PLAY button on the right.", image = R.drawable.tutorial_select_source),
        WelcomeScreenCard("RTL-SDR Driver", "To use RTL-SDR devices, an external driver app called \"RTL2832U\" must be installed first. If it's not found, RF Analyzer will prompt you to install it from the Google Play Store.\n\nOnce the driver is installed, return to RF Analyzer and press Play again.\n\nThis time, the driver should load and ask for permission to access the USB device.\n\nGrant permission - and the FFT plot should start displaying live spectrum data.", image = R.drawable.rtl2832u_driver_logo),
        WelcomeScreenCard("Explore the Spectrum","Explore the spectrum with scroll and zoom gestures. \n\nSwipe left or right inside the FFT or waterfall plot to browse the spectrum. The tuner automatically follows as you scroll.\n\nTo adjust how signals appear vertically, interact with the left axis of the FFT plot. Swipe up/down to shift the visible range or use pinch-to-zoom to adjust the scaling.", image = R.drawable.tutorial_explore),
        WelcomeScreenCard("Demodulate Signal","Go to the 'Demodulation' tab. \nSelect a mode, e.g. 'FM (wide)' for broadcast radio. \n\nTap on the signal in the FFT plot to move the channel selector. You can also use the tuning wheel. \nCongrats, now you should hear audio :-)", image = R.drawable.tutorial_enable_demodulation),
        WelcomeScreenCard("Tip: Context Help","A long-press on any label brings you directly to the respective section in the user manual. \n\nHave fun exploring!", image = R.drawable.tutorial_context_help),
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(vertical = 6.dp).weight(1f)
        ) { pageIndex ->
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = if (pageIndex == pagerState.currentPage) 1f else 0.9f
                        scaleX = scale
                        scaleY = scale
                        alpha = if (pageIndex == pagerState.currentPage) 1f else 0.6f
                    },
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(8.dp),
                onClick = {
                    coroutineScope.launch {
                        if (pagerState.currentPage < pages.lastIndex) {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        } else {
                            onFinish()
                        }
                    }
                }
            ) {
                ScrollableColumnWithFadingEdge(
                    backgroundColor = CardDefaults.cardColors().containerColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    if (isLandscape) {
                        Row {
                            pages[pageIndex].image?.let {
                                val image: @Composable () -> Unit = {
                                    Image(
                                        painter = painterResource(id = it),
                                        contentDescription = pages[pageIndex].title,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp)) // Rounded corners
                                            .widthIn(max = when {
                                                    screenLongEdgeDp < 700  -> 250.dp  // Small phone
                                                    screenLongEdgeDp < 1000 -> 350.dp  // Normal phone
                                                    else                    -> 500.dp  // Tablet
                                                })
                                            .align(Alignment.CenterVertically)
                                    )
                                }
                                if (pages[pageIndex].imageLink != null) {
                                    val context = LocalContext.current
                                    Button(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, pages[pageIndex].imageLink!!.toUri())
                                            context.startActivity(intent)
                                        },
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    ) {
                                        image()
                                    }
                                } else
                                    image()
                            }
                            Column {
                                Text(
                                    text = pages[pageIndex].title,
                                    style = headingStyle,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                                )
                                pages[pageIndex].description?.let {
                                    Text(
                                        text = it,
                                        style = textStyle,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Column {
                            Text(
                                text = pages[pageIndex].title,
                                style = headingStyle,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp, top = 16.dp)
                            )
                            pages[pageIndex].image?.let {
                                val image: @Composable () -> Unit = {
                                    Image(
                                        painter = painterResource(id = it),
                                        contentDescription = pages[pageIndex].title,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp)) // Rounded corners
                                    )
                                }
                                if (pages[pageIndex].imageLink != null) {
                                    val context = LocalContext.current
                                    Button(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, pages[pageIndex].imageLink!!.toUri())
                                            context.startActivity(intent)
                                        },
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        image()
                                    }
                                } else
                                    image()
                            }
                            pages[pageIndex].description?.let {
                                Text(
                                    text = it,
                                    style = textStyle,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isLandscape) {
            HorizontalPagerIndicator(
                pageCount = pages.size,
                currentPage = pagerState.currentPage,
                targetPage = pagerState.targetPage,
                currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = onFinish, shape = MaterialTheme.shapes.extraSmall) {
                Text("Skip Tutorial")
            }

            if (isLandscape) {
                HorizontalPagerIndicator(
                    pageCount = pages.size,
                    currentPage = pagerState.currentPage,
                    targetPage = pagerState.targetPage,
                    currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }

            Row {
                if (pagerState.currentPage > 0) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text("Back")
                    }
                }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            if (pagerState.currentPage < pages.lastIndex) {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            } else {
                                onFinish()
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(if (pagerState.currentPage == pages.lastIndex) "Finish" else "Next")
                }
            }
        }
    }
}

// source: https://medium.com/@domen.lanisnik/exploring-the-official-pager-in-compose-8c2698c49a98
@Composable
private fun HorizontalPagerIndicator(
    pageCount: Int,
    currentPage: Int,
    targetPage: Int,
    currentPageOffsetFraction: Float,
    modifier: Modifier = Modifier,
    unselectedIndicatorSize: Dp = 8.dp,
    selectedIndicatorSize: Dp = 10.dp,
    indicatorCornerRadius: Dp = 2.dp,
    indicatorPadding: Dp = 2.dp
) {
    val indicatorColor = MaterialTheme.colorScheme.primary
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .wrapContentSize()
            .height(selectedIndicatorSize + indicatorPadding * 2)
    ) {

        // draw an indicator for each page
        repeat(pageCount) { page ->
            // calculate color and size of the indicator
            val (color, size) =
                if (currentPage == page || targetPage == page) {
                    // calculate page offset
                    val pageOffset = ((currentPage - page) + currentPageOffsetFraction).absoluteValue
                    // calculate offset percentage between 0.0 and 1.0
                    val offsetPercentage = 1f - pageOffset.coerceIn(0f, 1f)
                    val size = unselectedIndicatorSize + ((selectedIndicatorSize - unselectedIndicatorSize) * offsetPercentage)
                    indicatorColor.copy( alpha = offsetPercentage ) to size
                } else {
                    indicatorColor.copy(alpha = 0.1f) to unselectedIndicatorSize
                }

            // draw indicator
            Box(
                modifier = Modifier
                    .padding(
                        // apply horizontal padding, so that each indicator is same width
                        horizontal = ((selectedIndicatorSize + indicatorPadding * 2) - size) / 2,
                        vertical = size / 4
                    )
                    .clip(RoundedCornerShape(indicatorCornerRadius))
                    .background(color)
                    .width(size)
                    .height(size / 2)
            )
        }
    }
}

@Preview
@Composable
fun PrevWelcomeScreen() {
    WelcomeScreen(onFinish = {})
}

@Preview(
    name = "Landscape",
    showBackground = true,
    widthDp = 640,
    heightDp = 360
)
@Composable
fun LandscapePreview() {
    WelcomeScreen(onFinish = {})
}