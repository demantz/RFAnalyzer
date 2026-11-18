package com.mantz_it.libhydrasdr

import android.util.Log
import org.json.JSONObject

/**
 * <h1>RF Analyzer - HydraSDR Device Info</h1>
 *
 * Module:      HydraSdrDeviceInfo.kt
 * Description: Kotlin model for the runtime-queried, hardware-agnostic HydraSDR v1.1.x device
 *              capabilities (board/firmware identity, gain descriptors, RF ports, frequency
 *              range). Parsed from the JSON produced by HydraSdrDevice.nativeGetDeviceInfo(),
 *              which in turn is built from hydrasdr_get_device_info()/hydrasdr_get_all_gains()
 *              in hydrasdr_device_native.cpp - see that file for the exact JSON schema.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

enum class HydraSdrGainCategory { MANUAL, PRESET, AGC }

/** Mirrors hydrasdr_gain_type_t (hydrasdr_commands.h); [category] groups the types into manual
 * controls, presets and AGC switches. */
enum class HydraSdrGainType(val nativeValue: Int, val category: HydraSdrGainCategory) {
    LNA(0, HydraSdrGainCategory.MANUAL),
    RF(1, HydraSdrGainCategory.MANUAL),
    MIXER(2, HydraSdrGainCategory.MANUAL),
    FILTER(3, HydraSdrGainCategory.MANUAL),
    VGA(4, HydraSdrGainCategory.MANUAL),
    LINEARITY(5, HydraSdrGainCategory.PRESET),
    SENSITIVITY(6, HydraSdrGainCategory.PRESET),
    LNA_AGC(7, HydraSdrGainCategory.AGC),
    RF_AGC(8, HydraSdrGainCategory.AGC),
    MIXER_AGC(9, HydraSdrGainCategory.AGC),
    FILTER_AGC(10, HydraSdrGainCategory.AGC);

    companion object {
        fun fromNativeValue(value: Int): HydraSdrGainType? = entries.firstOrNull { it.nativeValue == value }
    }
}

/** Mirrors hydrasdr_gain_info_t. */
data class HydraSdrGainDescriptor(
    val type: HydraSdrGainType,
    val minValue: Int,
    val maxValue: Int,
    val stepValue: Int,
    val defaultValue: Int,
    val isAgc: Boolean,
    val isPreset: Boolean
)

/** Mirrors hydrasdr_rf_port_info_t. */
data class HydraSdrRfPortDescriptor(
    val index: Int,
    val name: String,
    val minFrequency: Long,
    val maxFrequency: Long,
    val hasBiasTee: Boolean
)

/** Mirrors the HYDRASDR_CAP_* bitmask constants in hydrasdr_commands.h. Bit values, not ordinals -
 * combine with [HydraSdrDeviceInfo.hasCapability]. */
object HydraSdrCapability {
    const val LNA_GAIN = 1 shl 0
    const val RF_GAIN = 1 shl 1
    const val MIXER_GAIN = 1 shl 2
    const val FILTER_GAIN = 1 shl 3
    const val VGA_GAIN = 1 shl 4
    const val LNA_AGC = 1 shl 5
    const val RF_AGC = 1 shl 6
    const val MIXER_AGC = 1 shl 7
    const val FILTER_AGC = 1 shl 8
    const val LINEARITY_GAIN = 1 shl 9
    const val SENSITIVITY_GAIN = 1 shl 10
    const val BIAS_TEE = 1 shl 11
    const val PACKING = 1 shl 12
    const val RF_PORT_SELECT = 1 shl 13
    const val GPIO = 1 shl 14
    const val SPIFLASH = 1 shl 15
    const val CLOCKGEN = 1 shl 16
    const val RF_FRONTEND = 1 shl 17
    const val BANDWIDTH = 1 shl 18
    const val TEMPERATURE_SENSOR = 1 shl 19
    const val RX = 1 shl 20
    const val EXTENDED_SAMPLERATES = 1 shl 21
    const val EXTENDED_GAIN = 1 shl 22
}

/** Runtime-queried HydraSDR device capabilities - see HydraSdrDevice.getDeviceInfo(). */
data class HydraSdrDeviceInfo(
    val boardName: String,
    val firmwareVersion: String,
    val features: Int,
    val minFrequency: Long,
    val maxFrequency: Long,
    val rfPorts: List<HydraSdrRfPortDescriptor>,
    val gains: List<HydraSdrGainDescriptor>
) {
    fun hasCapability(capability: Int): Boolean = (features and capability) != 0

    fun getGainDescriptor(type: HydraSdrGainType): HydraSdrGainDescriptor? =
        gains.firstOrNull { it.type == type }

    /** Gain types this device actually exposes as user-facing manual controls (excludes AGC
     * enable toggles and presets, which the UI surfaces separately). */
    val manualGains: List<HydraSdrGainDescriptor> get() = gains.filter { it.type.category == HydraSdrGainCategory.MANUAL }

    companion object {
        private const val TAG = "HydraSdrDeviceInfo"

        // Frequency range before a device is queried (or if the query fails): wide enough not to
        // clip any real device.
        const val DEFAULT_MIN_FREQUENCY = 1_000_000L
        const val DEFAULT_MAX_FREQUENCY = 6_000_000_000L

        /** Parses the JSON string produced by HydraSdrDevice.nativeGetDeviceInfo(). Returns null
         * (and logs) on any malformed/missing field rather than throwing - a device info query
         * failure should degrade to the pre-connect fallback bounds, not crash. */
        fun parse(json: String): HydraSdrDeviceInfo? {
            return try {
                val root = JSONObject(json)
                val rfPorts = root.getJSONArray("rfPorts").let { array ->
                    (0 until array.length()).map { i ->
                        val port = array.getJSONObject(i)
                        HydraSdrRfPortDescriptor(
                            index = port.getInt("index"),
                            name = port.getString("name"),
                            minFrequency = port.getLong("minFrequency"),
                            maxFrequency = port.getLong("maxFrequency"),
                            hasBiasTee = port.getBoolean("hasBiasTee")
                        )
                    }
                }
                val gains = root.getJSONArray("gains").let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        val gain = array.getJSONObject(i)
                        val type = HydraSdrGainType.fromNativeValue(gain.getInt("type"))
                        if (type == null) {
                            Log.w(TAG, "parse: unknown gain type ${gain.getInt("type")}, skipping")
                            null
                        } else {
                            HydraSdrGainDescriptor(
                                type = type,
                                minValue = gain.getInt("min"),
                                maxValue = gain.getInt("max"),
                                stepValue = gain.getInt("step"),
                                defaultValue = gain.getInt("default"),
                                isAgc = gain.getBoolean("isAgc"),
                                isPreset = gain.getBoolean("isPreset")
                            )
                        }
                    }
                }
                HydraSdrDeviceInfo(
                    boardName = root.getString("boardName"),
                    firmwareVersion = root.getString("firmwareVersion"),
                    features = root.getLong("features").toInt(),
                    minFrequency = root.getLong("minFrequency"),
                    maxFrequency = root.getLong("maxFrequency"),
                    rfPorts = rfPorts,
                    gains = gains
                )
            } catch (e: Exception) {
                Log.w(TAG, "parse: failed to parse device info JSON: ${e.message}")
                null
            }
        }
    }
}
