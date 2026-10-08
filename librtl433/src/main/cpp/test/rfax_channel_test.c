/*
 * rfax_channel_test.c - unit test for the optional channel filter.
 *
 * Verifies that the channel filter passes the tuned channel and rejects a
 * strong out-of-channel tone, and that the mixer brings an offset channel to
 * baseband.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

#include "rfax_channel.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define RATE 1000000u
#define N 20000

static double rms_at(rfax_channel *c, double tone_hz)
{
    float *in = malloc(N * 2 * sizeof(float));
    float *out = malloc((N + 4) * 2 * sizeof(float));
    for (int n = 0; n < N; ++n) {
        double ph = 2.0 * M_PI * tone_hz * n / RATE;
        in[2 * n] = (float)cos(ph);
        in[2 * n + 1] = (float)sin(ph);
    }
    size_t on = rfax_channel_process(c, in, N, out);
    /* Skip the FIR warm-up. */
    double sum = 0.0;
    size_t start = on / 4;
    for (size_t k = start; k < on; ++k)
        sum += (double)out[2 * k] * out[2 * k] + (double)out[2 * k + 1] * out[2 * k + 1];
    free(in);
    free(out);
    return (on > start) ? sqrt(sum / (double)(on - start)) : 0.0;
}

int main(void)
{
    int failures = 0;
    rfax_channel *c = rfax_channel_create();
    if (!c) {
        fprintf(stderr, "create failed\n");
        return 2;
    }

    /* Channel at DC, 20 kHz wide: pass 2 kHz, reject 368 kHz. */
    rfax_channel_configure(c, RATE, 1, 0, 20000);
    rfax_channel_reset(c);
    double pass = rms_at(c, 2000.0);
    rfax_channel_reset(c);
    double reject = rms_at(c, 368000.0);
    printf("pass 2kHz=%.3f  reject 368kHz=%.4f  ratio=%.1f dB\n",
            pass, reject, 20.0 * log10(reject / (pass + 1e-12)));
    if (pass < 0.7) {
        printf("FAIL: passband attenuation too high\n");
        failures++;
    }
    if (reject > 0.1 * pass) {
        printf("FAIL: interferer not rejected (%.4f vs %.3f)\n", reject, pass);
        failures++;
    }

    /* Offset channel: tone at +50 kHz, mixer offset +50 kHz -> should pass. */
    rfax_channel_configure(c, RATE, 1, 50000, 20000);
    rfax_channel_reset(c);
    double shifted = rms_at(c, 50000.0);
    printf("offset passthrough 50kHz=%.3f\n", shifted);
    if (shifted < 0.7) {
        printf("FAIL: offset mixer did not bring channel to baseband\n");
        failures++;
    }

    rfax_channel_destroy(c);

    if (failures) {
        printf("CHANNEL TEST FAILED (%d)\n", failures);
        return 1;
    }
    printf("CHANNEL TEST OK\n");
    return 0;
}
