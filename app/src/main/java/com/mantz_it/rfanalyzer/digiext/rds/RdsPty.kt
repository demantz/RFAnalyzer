package com.mantz_it.rfanalyzer.digiext.rds

import androidx.compose.runtime.compositionLocalOf
import com.mantz_it.rfanalyzer.location.usesRbds

/**
 * <h1>RF Analyzer - RDS Programme Types</h1>
 *
 * Module:      RdsPty.kt
 * Description: The names of the RDS programme type (PTY) codes. Europe (RDS) and North America
 *              (RBDS) assign different programmes to the same codes, so a stored code is named at
 *              display time, by the "RDS Programme Types" setting ([LocalRdsPtyUs]) - by default
 *              the table of the user's country.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

enum class RdsPtyTable(val displayName: String) {
    AUTO("Automatic (from your location)"),
    RDS("Europe and the rest of the world (RDS)"),
    RBDS("North America (RBDS)");

    fun isRbds(countryCode: String?): Boolean = when (this) {
        AUTO -> usesRbds(countryCode)
        RDS -> false
        RBDS -> true
    }
}

/** Whether PTY codes are named by the North American (RBDS) table. */
val LocalRdsPtyUs = compositionLocalOf { false }

fun rdsPtyName(pty: Int, us: Boolean): String = (if (us) RDS_PTY_TABLE_US else RDS_PTY_TABLE_EU).getOrNull(pty) ?: "Unknown"

private val RDS_PTY_TABLE_EU = listOf(
    "Undefined",
    "News",
    "Current Affairs",
    "Information",
    "Sport",
    "Education",
    "Drama",
    "Culture",
    "Science",
    "Varied",
    "Pop Music",
    "Rock Music",
    "Easy Listening",
    "Light Classical",
    "Serious Classical",
    "Other Music",
    "Weather",
    "Finance",
    "Children’s Programmes",
    "Social Affairs",
    "Religion",
    "Phone-In",
    "Travel",
    "Leisure",
    "Jazz Music",
    "Country Music",
    "National Music",
    "Oldies Music",
    "Folk Music",
    "Documentary",
    "Alarm Test",
    "Alarm",
)

private val RDS_PTY_TABLE_US = listOf(
    "Undefined",
    "News",
    "Information",
    "Sports",
    "Talk",
    "Rock",
    "Classic Rock",
    "Adult Hits",
    "Soft Rock",
    "Top 40",
    "Country",
    "Oldies",
    "Soft",
    "Nostalgia",
    "Jazz",
    "Classical",
    "Rhythm & Blues",
    "Soft Rhythm & Blues",
    "Language",
    "Religious Music",
    "Religious Talk",
    "Personality",
    "Public",
    "College",
    "Spanish Talk",
    "Spanish Music",
    "Hip Hop",
    "Unassigned",
    "Unassigned",
    "Weather",
    "Emergency Test",
    "Emergency",
)
