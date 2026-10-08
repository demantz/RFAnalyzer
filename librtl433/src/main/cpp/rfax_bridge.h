/*
 * rfax_bridge.h - RF Analyzer embedding bridge for the rtl_433 decoder.
 *
 * rtl_433 is designed to open the SDR itself. RF Analyzer already owns the SDR,
 * so this bridge drives the decoder in "push" mode instead: the application
 * hands over interleaved IQ samples and receives decoded events as JSON.
 *
 * The bridge is intentionally small and free of Android/JNI types so that it
 * can be unit-tested on the host. The JNI glue lives in rfax_jni.cpp.
 *
 * Copyright (C) 2026 RF Analyzer contributors
 *
 * Derived from / links with rtl_433 (https://github.com/merbanan/rtl_433),
 * which is licensed under the GNU General Public License version 2 or later.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#ifndef RFAX_BRIDGE_H_
#define RFAX_BRIDGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque decoder instance. */
typedef struct rfax_rtl433 rfax_rtl433;

/*
 * Called for every decoded event. `json` is a single-line JSON object produced
 * by rtl_433's own serializer (data_print_jsons) and is only valid for the
 * duration of the call. It is safe to call back into the bridge from `user`.
 */
typedef void (*rfax_output_cb)(char const *json, void *user);

/* Creates and initialises a decoder instance (registers all protocols). */
rfax_rtl433 *rfax_rtl433_create(void);

/* Stops decoding and releases all resources. Passing NULL is a no-op. */
void rfax_rtl433_destroy(rfax_rtl433 *h);

/*
 * Configures the decoder. `sample_rate` and `center_frequency` describe the IQ
 * stream as it arrives at rfax_rtl433_feed_*(); pass the real SDR values.
 * `decimation` decimates the input by that integer factor before decoding (the
 * effective decoder rate becomes sample_rate / decimation) which keeps the CPU
 * cost low when the SDR runs much faster than a decoder needs. Use 1 to disable.
 * Returns 0 on success.
 */
int rfax_rtl433_configure(rfax_rtl433 *h, uint32_t sample_rate,
        uint32_t center_frequency, uint32_t decimation);

/* Updates the center frequency reported in decoded events. */
void rfax_rtl433_set_center_frequency(rfax_rtl433 *h, uint32_t center_frequency);

/*
 * Optional channel filtering. When bandwidth_hz > 0 the input is mixed by
 * -offset_hz and low-pass filtered to bandwidth_hz before decimation, so strong
 * out-of-channel signals do not desensitise the decoder. offset_hz is the
 * channel offset from the SDR center; bandwidth_hz is the channel width (e.g.
 * the demodulation channel width). Set bandwidth_hz = 0 to disable (default).
 * Must be called on the same thread as rfax_rtl433_feed().
 */
void rfax_rtl433_set_channel(rfax_rtl433 *h, int32_t offset_hz, uint32_t bandwidth_hz);

/*
 * Registers an additional "flex" decoder from an rtl_433 `-X` spec string,
 * e.g. "n=ook,m=OOK_PWM,s=467,l=927,r=2000,g=0,t=0,y=0". This lets unknown
 * devices be decoded without building a dedicated protocol. May be called
 * multiple times to register several flex decoders. Returns 0 on success and
 * -1 if the spec is empty or could not be parsed.
 *
 * The flex device is added to the running decoder, so call it right after
 * rfax_rtl433_create() and before feeding samples (or recreate the decoder to
 * replace the set, since there is no API to remove a single flex decoder).
 * Must be called on the same thread as rfax_rtl433_feed().
 */
int rfax_rtl433_add_flex(rfax_rtl433 *h, char const *spec);

/* Registers the decoded-event callback. */
void rfax_rtl433_set_output(rfax_rtl433 *h, rfax_output_cb cb, void *user);

/* Unit conversion mode; values match rtl_433's conversion_mode_t. */
enum {
    RFAX_CONVERT_NATIVE = 0,
    RFAX_CONVERT_SI = 1,
    RFAX_CONVERT_CUSTOMARY = 2,
};

/*
 * Adjusts decoder behaviour and re-applies the pulse-detection levels:
 *  - conversion_mode: one of RFAX_CONVERT_* (units in decoded output)
 *  - auto_level: adapt the detection threshold to the noise floor
 *  - report_meta: include rssi/snr/freq/mod in decoded events
 *  - min_snr: minimum signal-to-noise ratio in dB (<= 0 keeps the current)
 * Must be called on the same thread as rfax_rtl433_feed().
 */
void rfax_rtl433_set_options(rfax_rtl433 *h, int conversion_mode,
        int auto_level, int report_meta, float min_snr);

/*
 * Feeds interleaved, normalised (roughly [-1, 1]) IQ samples. The format
 * matches the interleaved float buffer RF Analyzer already builds for the FFT,
 * so no per-source format handling is required. `num_samples` counts complex
 * samples (i.e. num_samples * 2 floats).
 */
void rfax_rtl433_feed(rfax_rtl433 *h, float const *iq, size_t num_samples);

/*
 * Flushes any buffered samples and appends a short run of zero samples so the
 * decoder can finish a frame (end-of-transmission). Call when the stream stops.
 */
void rfax_rtl433_flush(rfax_rtl433 *h);

/* rtl_433 version string (static, never NULL). */
char const *rfax_rtl433_version(void);

#ifdef __cplusplus
}
#endif

#endif /* RFAX_BRIDGE_H_ */
