package com.mantz_it.rfanalyzer.digiext.lora

import com.mantz_it.rfanalyzer_ipc.digiext.LoraPhy

/**
 * <h1>RF Analyzer - LoRa Test Support</h1>
 *
 * Module:      LoraTestSupport.kt
 * Description: Test helper shared by the LoRa tests: [loraFrame] builds a [LoraFrame] from an
 *              already-decoded message.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** A frame as the pipeline builds it from an already-decoded message - for tests of what is done
 *  with a frame, not of how its payload is parsed. */
internal fun loraFrame(
    message: LoraPayloadMessage,
    hasCrc: Boolean = true,
    crcValid: Boolean = true,
    id: Long = 0L,
    ts: Long = 1_000L,
    frequencyHz: Long = 869_618_000L,
    reliability: Float? = null,
): LoraFrame = LoraSpec.packet(
    timestampMs = ts, frequencyHz = frequencyHz, raw = null,
    phy = LoraPhy(spreadingFactor = 8, codingRate = 1, bandwidthHz = 62_500, hasCrc = hasCrc, crcValid = crcValid, reliability = reliability),
    data = message, rowId = id,
)
