package com.mantz_it.rfanalyzer.decoder

import kotlinx.serialization.Serializable

/**
 * <h1>RF Analyzer - Flex Decoder</h1>
 *
 * Module:      FlexDecoder.kt
 * Description: Model for a user-defined rtl_433 "flex" (`-X`) decoder. It can be
 *              edited with a small structured form (name, modulation, pulse
 *              timings) or entered as a raw spec for advanced use. [toSpec]
 *              produces the exact string handed to the native rtl_433 decoder.
 *
 * Copyright (C) 2026 RF Analyzer contributors
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Serializable
data class FlexDecoder(
    val name: String = "",
    val modulation: String = "OOK_PWM",
    val shortWidth: Float? = null,
    val longWidth: Float? = null,
    val resetLimit: Float? = null,
    val gapLimit: Float? = null,
    val syncWidth: Float? = null,
    val tolerance: Float? = null,
    val bits: Int? = null,
    /** When non-blank this spec is used verbatim (advanced settings / unknown keys). */
    val rawSpec: String = "",
) {
    val usesRawSpec: Boolean get() = rawSpec.isNotBlank()

    /** Builds the `-X` spec string passed to rtl_433. */
    fun toSpec(): String {
        if (usesRawSpec) return rawSpec.trim()
        val parts = mutableListOf<String>()
        parts += "n=" + (name.trim().ifBlank { "flex" }.replace(',', ' '))
        parts += "m=" + modulation.trim()
        shortWidth?.let { parts += "s=" + formatNumber(it) }
        longWidth?.let { parts += "l=" + formatNumber(it) }
        resetLimit?.let { parts += "r=" + formatNumber(it) }
        gapLimit?.let { parts += "g=" + formatNumber(it) }
        syncWidth?.let { parts += "y=" + formatNumber(it) }
        tolerance?.let { parts += "t=" + formatNumber(it) }
        bits?.let { parts += "bits=$it" }
        return parts.joinToString(",")
    }

    /** A short one-line description for the list in the UI. */
    fun summary(): String = toSpec()

    fun isValid(): Boolean =
        if (usesRawSpec) rawSpec.contains("m=") || rawSpec.contains("modulation=")
        else modulation.isNotBlank()

    companion object {
        /** Common rtl_433 flex modulation names offered in the editor. */
        val MODULATIONS = listOf(
            "OOK_PWM", "OOK_PPM", "OOK_PCM", "OOK_PIWM_DC", "OOK_MC_ZEROBIT",
            "OOK_DMC", "FSK_PCM", "FSK_PWM", "FSK_MC_ZEROBIT",
        )

        fun formatNumber(v: Float): String =
            if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

        private val KNOWN_KEYS = setOf(
            "n", "name", "m", "modulation", "s", "short", "l", "long",
            "r", "reset", "g", "gap", "y", "sync", "t", "tolerance", "bits",
        )

        /** Best-effort parse of an existing spec; unknown keys are kept as raw. */
        fun fromSpec(spec: String): FlexDecoder {
            var name = ""
            var modulation = "OOK_PWM"
            var s: Float? = null; var l: Float? = null; var r: Float? = null
            var g: Float? = null; var y: Float? = null; var t: Float? = null
            var bits: Int? = null
            var unknown = false

            spec.split(',').forEach { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) return@forEach
                val key = token.substring(0, idx).trim().lowercase()
                val value = token.substring(idx + 1).trim()
                when (key) {
                    "n", "name" -> name = value
                    "m", "modulation" -> modulation = value
                    "s", "short" -> s = value.toFloatOrNull()
                    "l", "long" -> l = value.toFloatOrNull()
                    "r", "reset" -> r = value.toFloatOrNull()
                    "g", "gap" -> g = value.toFloatOrNull()
                    "y", "sync" -> y = value.toFloatOrNull()
                    "t", "tolerance" -> t = value.toFloatOrNull()
                    "bits" -> bits = value.toIntOrNull()
                    else -> if (key !in KNOWN_KEYS) unknown = true
                }
            }

            return FlexDecoder(
                name = name,
                modulation = modulation,
                shortWidth = s,
                longWidth = l,
                resetLimit = r,
                gapLimit = g,
                syncWidth = y,
                tolerance = t,
                bits = bits,
                rawSpec = if (unknown) spec else "",
            )
        }
    }
}
