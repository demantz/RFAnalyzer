package com.mantz_it.rfanalyzer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * <h1>RF Analyzer - Theme</h1>
 *
 * Module:      RFAnalyzerTheme.kt
 * Description: The App Theme (RFAnalyzerTheme)
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


enum class ColorTheme(val displayName: String) {
    RFANALYZER_DARK("RF Analyzer Theme (dark)"),
    RFANALYZER_LIGHT("RF Analyzer Theme (light)"),
    RFANALYZER_AUTO("RF Analyzer Theme (system setting)"),
    SYSTEM_DARK("System Theme (dark)"),
    SYSTEM_LIGHT("System Theme (light)"),
    SYSTEM_AUTO("System Theme (system setting)"),
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FC9FF),
    onPrimary = Color(0xFF00315B),
    primaryContainer = Color(0xFF0D497C),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253141),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD7E4F8),
    tertiary = Color(0xFFDDBAF0),
    onTertiary = Color(0xFF402551),
    tertiaryContainer = Color(0xFF573B69),
    onTertiaryContainer = Color(0xFFF5D9FF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF10141A),
    onBackground = Color(0xFFDFE2EB),
    surface = Color(0xFF10141A),
    onSurface = Color(0xFFDFE2EB),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    inverseOnSurface = Color(0xFF2D3137),
    inverseSurface = Color(0xFFDFE2EB),
    inversePrimary = Color(0xFF2F6095)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2F6095),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF545F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E4F8),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF705383),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5D9FF),
    onTertiaryContainer = Color(0xFF290F3A),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF5FAFF),
    onBackground = Color(0xFF171C22),
    surface = Color(0xFFF5FAFF),
    onSurface = Color(0xFF171C22),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF72767E),
    inverseOnSurface = Color(0xFFEDF1FA),
    inverseSurface = Color(0xFF2D3137),
    inversePrimary = Color(0xFF9FC9FF)
)

@Composable
fun RFAnalyzerTheme(
    colorTheme: ColorTheme = ColorTheme.RFANALYZER_DARK,
    content: @Composable () -> Unit
) {
    // Dynamic color is available on Android 12+
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current

    val colorScheme = when (colorTheme) {
        ColorTheme.RFANALYZER_AUTO -> if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
        ColorTheme.RFANALYZER_DARK -> DarkColorScheme
        ColorTheme.RFANALYZER_LIGHT -> LightColorScheme
        ColorTheme.SYSTEM_AUTO -> if (dynamicColorSupported) {
            if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        } else {
            if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
        }
        ColorTheme.SYSTEM_DARK -> if (dynamicColorSupported) dynamicDarkColorScheme(context) else DarkColorScheme
        ColorTheme.SYSTEM_LIGHT -> if (dynamicColorSupported) dynamicLightColorScheme(context) else LightColorScheme
    }

    val typography = Typography(
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.5.sp
        )
        //titleLarge = TextStyle(
        //    fontFamily = FontFamily.Default,
        //    fontWeight = FontWeight.Normal,
        //    fontSize = 22.sp,
        //    lineHeight = 28.sp,
        //    letterSpacing = 0.sp
        //),
        //labelSmall = TextStyle(
        //    fontFamily = FontFamily.Default,
        //    fontWeight = FontWeight.Medium,
        //    fontSize = 11.sp,
        //    lineHeight = 16.sp,
        //    letterSpacing = 0.5.sp
        //)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}