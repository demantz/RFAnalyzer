/*
 * rfax_channel.c - optional channel filter for the rtl_433 bridge.
 *
 * See rfax_channel.h. Implements a complex mixer followed by a Hamming-windowed
 * sinc low-pass (FIR) and integer decimation.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#include "rfax_channel.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define RFAX_CHANNEL_MIN_TAPS 31
#define RFAX_CHANNEL_MAX_TAPS 127

struct rfax_channel {
    float *taps;
    int taps_len;
    float *dly_i;
    float *dly_q;
    int pos;

    uint32_t decimation;
    size_t dec_count;

    int32_t offset;
    double dphase; /* radians per sample */
    double phase;
};

static void design_lowpass(float *taps, int len, double fc)
{
    double sum = 0.0;
    int m = len - 1;
    for (int n = 0; n < len; ++n) {
        double x = n - m / 2.0;
        double sinc;
        if (fabs(x) < 1e-9)
            sinc = 2.0 * fc;
        else
            sinc = sin(2.0 * M_PI * fc * x) / (M_PI * x);
        double w = 0.54 - 0.46 * cos(2.0 * M_PI * n / m); /* Hamming */
        taps[n] = (float)(sinc * w);
        sum += taps[n];
    }
    if (fabs(sum) > 1e-12) {
        for (int n = 0; n < len; ++n)
            taps[n] = (float)(taps[n] / sum); /* unity DC gain */
    }
}

rfax_channel *rfax_channel_create(void)
{
    rfax_channel *c = calloc(1, sizeof(*c));
    if (!c)
        return NULL;
    c->decimation = 1;
    return c;
}

void rfax_channel_destroy(rfax_channel *c)
{
    if (!c)
        return;
    free(c->taps);
    free(c->dly_i);
    free(c->dly_q);
    free(c);
}

void rfax_channel_reset(rfax_channel *c)
{
    if (!c)
        return;
    c->phase = 0.0;
    c->pos = 0;
    c->dec_count = 0;
    if (c->dly_i && c->taps_len > 0)
        memset(c->dly_i, 0, sizeof(float) * c->taps_len);
    if (c->dly_q && c->taps_len > 0)
        memset(c->dly_q, 0, sizeof(float) * c->taps_len);
}

void rfax_channel_configure(rfax_channel *c, uint32_t sample_rate,
        uint32_t decimation, int32_t offset_hz, uint32_t bandwidth_hz)
{
    if (!c || sample_rate == 0)
        return;

    c->decimation = decimation > 0 ? decimation : 1;
    c->offset = offset_hz;
    c->dphase = 2.0 * M_PI * (double)offset_hz / (double)sample_rate;

    uint32_t out_rate = sample_rate / c->decimation;
    if (out_rate == 0)
        out_rate = 1;

    /* Cutoff: half the channel width, never above the output Nyquist. */
    double cutoff = bandwidth_hz / 2.0;
    if (cutoff <= 0.0 || cutoff > out_rate / 2.0)
        cutoff = out_rate / 2.0;
    double fc = cutoff / (double)sample_rate;

    /* Longer filter for larger decimation so the stop band covers the aliases. */
    int len = (int)(4 * c->decimation) + 1;
    if (len < RFAX_CHANNEL_MIN_TAPS)
        len = RFAX_CHANNEL_MIN_TAPS;
    if (len > RFAX_CHANNEL_MAX_TAPS)
        len = RFAX_CHANNEL_MAX_TAPS;
    if ((len & 1) == 0)
        len++;

    if (len != c->taps_len) {
        free(c->taps);
        free(c->dly_i);
        free(c->dly_q);
        c->taps = malloc(sizeof(float) * len);
        c->dly_i = calloc(len, sizeof(float));
        c->dly_q = calloc(len, sizeof(float));
        c->taps_len = (c->taps && c->dly_i && c->dly_q) ? len : 0;
        if (c->taps_len == 0) {
            free(c->taps);
            free(c->dly_i);
            free(c->dly_q);
            c->taps = NULL;
            c->dly_i = NULL;
            c->dly_q = NULL;
            return;
        }
    }

    design_lowpass(c->taps, c->taps_len, fc);
    rfax_channel_reset(c);
}

size_t rfax_channel_process(rfax_channel *c, float const *in, size_t n, float *out)
{
    if (!c || !in || !out || c->taps_len == 0)
        return 0;

    const int len = c->taps_len;
    const int dec = (int)c->decimation;
    size_t o = 0;

    for (size_t k = 0; k < n; ++k) {
        float si = in[2 * k];
        float sq = in[2 * k + 1];
        float yi = si;
        float yq = sq;

        if (c->offset != 0) {
            double ph = c->phase;
            float co = (float)cos(ph);
            float sn = (float)sin(ph);
            yi = si * co + sq * sn;
            yq = sq * co - si * sn;
            ph += c->dphase;
            if (ph > M_PI)
                ph -= 2.0 * M_PI;
            else if (ph < -M_PI)
                ph += 2.0 * M_PI;
            c->phase = ph;
        }

        c->dly_i[c->pos] = yi;
        c->dly_q[c->pos] = yq;

        float ai = 0.0f;
        float aq = 0.0f;
        int idx = c->pos;
        for (int t = 0; t < len; ++t) {
            ai += c->taps[t] * c->dly_i[idx];
            aq += c->taps[t] * c->dly_q[idx];
            if (--idx < 0)
                idx = len - 1;
        }
        if (++c->pos >= len)
            c->pos = 0;

        if (++c->dec_count >= (size_t)dec) {
            c->dec_count = 0;
            out[2 * o] = ai;
            out[2 * o + 1] = aq;
            o++;
        }
    }

    return o;
}
