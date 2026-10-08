package com.mantz_it.rfanalyzer.decoder

/**
 * <h1>RF Analyzer - Decoded Event</h1>
 *
 * A single event decoded by the rtl_433 based decoder. [fields] preserves the
 * order of the JSON object produced by rtl_433 so the UI can present the values
 * as they were reported. [json] is the raw line, kept for logging and export.
 *
 * Copyright (C) 2026 RF Analyzer contributors
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
data class DecodedEvent(
    val timestampMs: Long,
    val model: String,
    val fields: List<Pair<String, String>>,
    val json: String
)
