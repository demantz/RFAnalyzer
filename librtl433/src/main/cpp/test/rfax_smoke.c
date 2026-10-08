/*
 * rfax_smoke.c - host smoke test for the rfax_bridge decoder.
 *
 * Builds and runs without Android. With no argument it feeds synthetic IQ and
 * only checks that the pipeline runs. With a CU8 capture (optionally followed
 * by center frequency, sample rate and decimation) it prints decoded events.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "rfax_bridge.h"

static int g_events = 0;

static void on_event(char const *json, void *user)
{
    (void)user;
    printf("EVENT: %s\n", json);
    g_events++;
}

static int feed_cu8(rfax_rtl433 *h, char const *path, unsigned center, unsigned rate, unsigned decim)
{
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror("fopen");
        return 0;
    }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);

    unsigned char *raw = malloc((size_t)sz);
    if (!raw || fread(raw, 1, (size_t)sz, f) != (size_t)sz) {
        fclose(f);
        free(raw);
        return 0;
    }
    fclose(f);

    size_t n = (size_t)sz / 2;
    float *iq = malloc(n * 2 * sizeof(float));
    for (size_t i = 0; i < n * 2; ++i)
        iq[i] = ((float)raw[i] - 127.5f) / 127.5f;

    rfax_rtl433_configure(h, rate, center, decim);
    const size_t chunk = 20000;
    for (size_t off = 0; off < n; off += chunk) {
        size_t c = (off + chunk <= n) ? chunk : (n - off);
        rfax_rtl433_feed(h, iq + off * 2, c);
    }
    rfax_rtl433_flush(h);
    printf("fed %zu samples at %u Hz (decim %u)\n", n, rate, decim);

    free(raw);
    free(iq);
    return 1;
}

int main(int argc, char **argv)
{
    printf("rtl_433: %s\n", rfax_rtl433_version());

    rfax_rtl433 *h = rfax_rtl433_create();
    if (!h) {
        fprintf(stderr, "rfax_rtl433_create failed\n");
        return 2;
    }
    rfax_rtl433_set_output(h, on_event, NULL);

    if (argc > 1) {
        unsigned center = (argc > 2) ? (unsigned)strtoul(argv[2], NULL, 0) : 433920000u;
        unsigned rate = (argc > 3) ? (unsigned)strtoul(argv[3], NULL, 0) : 250000u;
        unsigned decim = (argc > 4) ? (unsigned)strtoul(argv[4], NULL, 0) : 1u;
        if (!feed_cu8(h, argv[1], center, rate, decim)) {
            rfax_rtl433_destroy(h);
            return 2;
        }
    } else {
        rfax_rtl433_configure(h, 1000000, 433920000, 4);
        const size_t total = 500000;
        float *buf = malloc(total * 2 * sizeof(float));
        for (size_t n = 0; n < total; ++n) {
            double t = (double)n / 1000000.0;
            double gate = (fmod(t, 0.001) < 0.0005) ? 1.0 : 0.05;
            double ph = 2.0 * M_PI * 10000.0 * t;
            buf[2 * n] = (float)(gate * cos(ph));
            buf[2 * n + 1] = (float)(gate * sin(ph));
        }
        rfax_rtl433_feed(h, buf, total);
        rfax_rtl433_flush(h);
        free(buf);
        printf("fed %zu synthetic samples\n", total);
    }

    printf("decoded events: %d\n", g_events);
    rfax_rtl433_destroy(h);
    printf("OK\n");
    return 0;
}
