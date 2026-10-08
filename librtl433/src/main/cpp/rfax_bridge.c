/*
 * rfax_bridge.c - RF Analyzer embedding bridge for the rtl_433 decoder.
 *
 * Drives rtl_433 in "push" mode: the application owns the SDR and hands over
 * interleaved normalised IQ samples; decoded events are delivered as JSON via
 * a callback. This file contains no Android/JNI types and can be compiled and
 * tested on the host (see README.md next to this file).
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

#include "rfax_bridge.h"
#include "rfax_channel.h"

#include <stdlib.h>
#include <string.h>

#include "rtl_433.h"
#include "r_private.h"
#include "r_device.h"
#include "r_api.h"
#include "data.h"
#include "list.h"
#include "pulse_detect.h"
#include "baseband.h"

/*
 * Provided by the small hook appended to the generated copy of rtl_433.c at
 * build time (see rfax_feed_hook.inc). It forwards to the static
 * sdr_callback(), the same entry point the file-input path uses to decode
 * samples without an SDR device.
 */
extern void rfax_sdr_feed(unsigned char *iq_buf, uint32_t len, void *ctx);

/*
 * Provided by rtl_433's flex decoder (src/devices/flex.c). Parses a `-X` spec
 * into an r_device that is then registered like any built-in protocol. Declared
 * here because rtl_433 only declares it locally in rtl_433.c.
 */
extern r_device *flex_create_device(char *spec);

/* CU8 samples staged before a block is handed to the decoder. Mirrors the
 * buffering the file-input path uses; the decoder handles frames that span
 * blocks. */
#define RFAX_BLOCK_SAMPLES 131072
/* Upper bound for a decoded JSON line (rtl_433 events are well below this). */
#define RFAX_JSON_MAX 4096
/* Silent tail appended on flush so the decoder can close a pending frame.
 * Must be at least one reference block, otherwise weak frames are not
 * finalised (this matters for the last frame of a capture). */
#define RFAX_FLUSH_BYTES 262144

/* Custom rtl_433 output that serialises each event to a JSON line. */
typedef struct {
    data_output_t output; /* must be the first member */
    rfax_output_cb cb;
    void *user;
} rfax_output_t;

struct rfax_rtl433 {
    r_cfg_t *cfg;
    rfax_output_t *out;

    uint32_t in_rate;          /* SDR sample rate */
    uint32_t center_frequency; /* SDR center frequency */
    uint32_t decimation;       /* integer decimation factor (>= 1) */
    uint32_t out_rate;         /* in_rate / decimation, fed to rtl_433 */

    /* Running boxcar decimator state (full-band path). */
    uint32_t dec_count;
    float dec_i;
    float dec_q;

    /* Optional channel filter (mix + low-pass before decimation). */
    rfax_channel *channel;
    int32_t channel_offset;
    uint32_t channel_bandwidth; /* 0 disables channel filtering */
    float *chan_out;            /* staging buffer for filtered samples */
    size_t chan_out_cap;        /* capacity in complex samples */

    /* CU8 staging buffer handed to the decoder in blocks. */
    uint8_t *block;
    size_t block_len; /* samples currently staged */
};

static inline uint8_t float_to_cu8(float v)
{
    float s = v * 127.0f + 128.0f;
    if (s <= 0.0f)
        return 0;
    if (s >= 255.0f)
        return 255;
    return (uint8_t)(s + 0.5f);
}

static void R_API_CALLCONV rfax_output_print(data_output_t *output, data_t *data)
{
    rfax_output_t *self = (rfax_output_t *)output; /* output is first */
    if (!data || !self->cb)
        return;

    char json[RFAX_JSON_MAX];
    size_t n = data_print_jsons(data, json, sizeof(json));
    if (n > 0 && n < sizeof(json))
        self->cb(json, self->user);
}

static void R_API_CALLCONV rfax_output_start(data_output_t *output,
        char const *const *fields, int num_fields)
{
    (void)output;
    (void)fields;
    (void)num_fields;
}

static void R_API_CALLCONV rfax_output_free(data_output_t *output)
{
    free(output);
}

static void rfax_flush_block(rfax_rtl433 *h)
{
    if (h->block_len == 0)
        return;
    rfax_sdr_feed(h->block, (uint32_t)(h->block_len * 2), h->cfg);
    h->block_len = 0;
}

static inline void rfax_push_sample(rfax_rtl433 *h, float i, float q)
{
    h->block[h->block_len * 2] = float_to_cu8(i);
    h->block[h->block_len * 2 + 1] = float_to_cu8(q);
    if (++h->block_len >= RFAX_BLOCK_SAMPLES)
        rfax_flush_block(h);
}

static void rfax_reset_state(rfax_rtl433 *h)
{
    h->dec_count = 0;
    h->dec_i = 0.0f;
    h->dec_q = 0.0f;
    h->block_len = 0;
    rfax_channel_reset(h->channel);

    struct dm_state *demod = h->cfg->demod;
    demod->noise_level = 0.0f;
    demod->min_level_auto = 0.0f;
    demod->frame_start_ago = 0;
    demod->frame_end_ago = 0;
    demod->frame_event_count = 0;
    baseband_low_pass_filter_reset(&demod->lowpass_filter_state);
    baseband_demod_FM_reset(&demod->demod_FM_state);
    pulse_detect_reset(demod->pulse_detect);
}

rfax_rtl433 *rfax_rtl433_create(void)
{
    rfax_rtl433 *h = calloc(1, sizeof(*h));
    if (!h)
        return NULL;

    h->decimation = 1;
    h->in_rate = DEFAULT_SAMPLE_RATE;
    h->out_rate = DEFAULT_SAMPLE_RATE;
    h->center_frequency = DEFAULT_FREQUENCY;

    h->cfg = r_create_cfg();
    if (!h->cfg) {
        free(h);
        return NULL;
    }

    r_cfg_t *cfg = h->cfg;
    cfg->exit_async = 0;
    cfg->report_meta = 1; /* include rssi/snr/freq/mod in events */
    cfg->frequencies = 1;
    cfg->frequency[0] = DEFAULT_FREQUENCY;
    cfg->center_frequency = DEFAULT_FREQUENCY;
    cfg->samp_rate = DEFAULT_SAMPLE_RATE;

    struct dm_state *demod = cfg->demod;
    /* The pipeline is fed CU8 samples; see float_to_cu8(). */
    demod->sample_size = 2;
    /* Process every frame: the push path has already paid for the samples and
     * dropping "noise" frames here would hide weak but valid signals. */
    demod->squelch_offset = 0.0f;
    /* Adapt the detection threshold to the noise floor, which varies with the
     * SDR gain and the location; useful while roaming. */
    demod->auto_level = 1.0f;

    register_all_protocols(cfg, 0);

    /* FM demodulation is only needed when an FSK protocol is registered. */
    for (size_t i = 0; i < demod->r_devs.len; ++i) {
        r_device *dev = demod->r_devs.elems[i];
        if (dev && dev->modulation >= FSK_DEMOD_MIN_VAL) {
            demod->enable_FM_demod = 1;
            break;
        }
    }

    pulse_detect_set_levels(demod->pulse_detect, demod->use_mag_est,
            demod->level_limit, demod->min_level, demod->min_snr,
            demod->detect_verbosity);

    rfax_output_t *out = calloc(1, sizeof(*out));
    if (!out) {
        r_free_cfg(cfg);
        free(cfg);
        free(h);
        return NULL;
    }
    out->output.output_print = rfax_output_print;
    out->output.output_start = rfax_output_start;
    out->output.output_free = rfax_output_free;
    list_push(&cfg->output_handler, out);
    h->out = out;

    h->channel = rfax_channel_create();
    h->block = malloc(RFAX_BLOCK_SAMPLES * 2);
    if (!h->block || !h->channel) {
        rfax_channel_destroy(h->channel);
        free(h->block);
        r_free_cfg(cfg);
        free(cfg);
        free(h);
        return NULL;
    }

    return h;
}

void rfax_rtl433_destroy(rfax_rtl433 *h)
{
    if (!h)
        return;
    if (h->cfg) {
        r_free_cfg(h->cfg); /* also frees the output handler */
        free(h->cfg);       /* r_create_cfg() heap-allocates it */
    }
    rfax_channel_destroy(h->channel);
    free(h->chan_out);
    free(h->block);
    free(h);
}

int rfax_rtl433_configure(rfax_rtl433 *h, uint32_t sample_rate,
        uint32_t center_frequency, uint32_t decimation)
{
    if (!h || !h->cfg || sample_rate == 0)
        return -1;
    if (decimation == 0)
        decimation = 1;

    h->in_rate = sample_rate;
    h->decimation = decimation;
    h->out_rate = sample_rate / decimation;
    if (h->out_rate == 0)
        h->out_rate = 1;
    h->center_frequency = center_frequency;

    h->cfg->samp_rate = h->out_rate;
    h->cfg->center_frequency = center_frequency;
    h->cfg->frequency[0] = center_frequency;

    rfax_reset_state(h);
    /* The decimation changed, so the channel filter must be re-derived. */
    rfax_channel_configure(h->channel, h->in_rate, h->decimation,
            h->channel_offset, h->channel_bandwidth);
    return 0;
}

void rfax_rtl433_set_channel(rfax_rtl433 *h, int32_t offset_hz, uint32_t bandwidth_hz)
{
    if (!h || !h->cfg)
        return;
    /* Keep the channel within the captured band. */
    if (h->in_rate > 0 && offset_hz > (int32_t)h->in_rate / 2)
        offset_hz = (int32_t)h->in_rate / 2;
    if (h->in_rate > 0 && offset_hz < -(int32_t)h->in_rate / 2)
        offset_hz = -(int32_t)h->in_rate / 2;
    h->channel_offset = offset_hz;
    h->channel_bandwidth = bandwidth_hz;
    rfax_channel_configure(h->channel, h->in_rate, h->decimation,
            h->channel_offset, h->channel_bandwidth);
}

void rfax_rtl433_set_center_frequency(rfax_rtl433 *h, uint32_t center_frequency)
{
    if (!h || !h->cfg)
        return;
    h->center_frequency = center_frequency;
    h->cfg->center_frequency = center_frequency;
    h->cfg->frequency[0] = center_frequency;
}

int rfax_rtl433_add_flex(rfax_rtl433 *h, char const *spec)
{
    if (!h || !h->cfg || !spec || !*spec)
        return -1;

    /* flex_create_device() takes ownership of a mutable string and parses it;
     * give it a private copy so the caller's buffer is untouched. */
    char *dup = strdup(spec);
    if (!dup)
        return -1;
    r_device *dev = flex_create_device(dup);
    free(dup);
    if (!dev)
        return -1;

    register_protocol(h->cfg, dev, "");

    /* A flex spec may add an FSK protocol, which needs the FM demod path that
     * rfax_rtl433_create() only enables for the built-in protocols. */
    if (dev->modulation >= FSK_DEMOD_MIN_VAL)
        h->cfg->demod->enable_FM_demod = 1;

    return 0;
}

void rfax_rtl433_set_output(rfax_rtl433 *h, rfax_output_cb cb, void *user)
{
    if (!h || !h->out)
        return;
    h->out->cb = cb;
    h->out->user = user;
}

void rfax_rtl433_set_options(rfax_rtl433 *h, int conversion_mode,
        int auto_level, int report_meta, float min_snr)
{
    if (!h || !h->cfg)
        return;

    r_cfg_t *cfg = h->cfg;
    struct dm_state *demod = cfg->demod;

    if (conversion_mode >= RFAX_CONVERT_NATIVE && conversion_mode <= RFAX_CONVERT_CUSTOMARY)
        cfg->conversion_mode = (conversion_mode_t)conversion_mode;

    demod->auto_level = auto_level ? 1.0f : 0.0f;
    cfg->report_meta = report_meta ? 1 : 0;
    if (min_snr > 0.0f)
        demod->min_snr = min_snr;

    pulse_detect_set_levels(demod->pulse_detect, demod->use_mag_est,
            demod->level_limit, demod->min_level, demod->min_snr,
            demod->detect_verbosity);
}

void rfax_rtl433_feed(rfax_rtl433 *h, float const *iq, size_t num_samples)
{
    if (!h || !h->cfg || !iq || num_samples == 0)
        return;

    /* Channelized path: mix to baseband and low-pass before decimating so
     * strong out-of-channel signals do not desensitise the decoder. */
    if (h->channel_bandwidth > 0) {
        size_t needed = num_samples / h->decimation + 2;
        if (h->chan_out_cap < needed) {
            float *buf = realloc(h->chan_out, needed * 2 * sizeof(float));
            if (buf) {
                h->chan_out = buf;
                h->chan_out_cap = needed;
            }
        }
        if (h->chan_out && h->chan_out_cap >= needed) {
            size_t out_n = rfax_channel_process(h->channel, iq, num_samples, h->chan_out);
            for (size_t k = 0; k < out_n; ++k)
                rfax_push_sample(h, h->chan_out[2 * k], h->chan_out[2 * k + 1]);
            return;
        }
        /* Allocation failure: fall through to the plain decimation path. */
    }

    uint32_t dec = h->decimation;
    for (size_t n = 0; n < num_samples; ++n) {
        float i = iq[2 * n];
        float q = iq[2 * n + 1];

        if (dec > 1) {
            h->dec_i += i;
            h->dec_q += q;
            if (++h->dec_count < dec)
                continue;
            float inv = 1.0f / (float)dec;
            i = h->dec_i * inv;
            q = h->dec_q * inv;
            h->dec_i = 0.0f;
            h->dec_q = 0.0f;
            h->dec_count = 0;
        }

        rfax_push_sample(h, i, q);
    }
}

void rfax_rtl433_flush(rfax_rtl433 *h)
{
    if (!h || !h->cfg)
        return;

    rfax_flush_block(h);

    /* 128 is "zero" in unsigned CU8; a silent tail lets the decoder close a
     * frame (end-of-transmission) exactly like the file-input path does. */
    uint8_t *tail = malloc(RFAX_FLUSH_BYTES);
    if (!tail)
        return;
    memset(tail, 128, RFAX_FLUSH_BYTES);
    rfax_sdr_feed(tail, (uint32_t)RFAX_FLUSH_BYTES, h->cfg);
    free(tail);
}

char const *rfax_rtl433_version(void)
{
    return version_string();
}
