/*
 * rfax_channel.h - optional channel filter for the rtl_433 bridge.
 *
 * rtl_433 normally sees the whole SDR band. A strong signal elsewhere in the
 * band (e.g. a broadcast carrier a few hundred kHz away) can desensitise the
 * pulse detector, so weak signals are missed. When channel mode is enabled the
 * input is mixed by -offset_hz (bringing the tuned channel to baseband) and low
 * -pass filtered to bandwidth_hz before decimation, which rejects the
 * out-of-channel interferers.
 *
 * The module has no Android/JNI dependencies and is unit-tested on the host
 * (see test/rfax_channel_test.c).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#ifndef RFAX_CHANNEL_H_
#define RFAX_CHANNEL_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct rfax_channel rfax_channel;

rfax_channel *rfax_channel_create(void);
void rfax_channel_destroy(rfax_channel *c);

/*
 * Configures the filter. `bandwidth_hz` is the channel width to keep (use 0 to
 * disable: the module then only decimates). `decimation` is the integer
 * decimation factor; the output rate is sample_rate / decimation. The filter
 * cutoff is min(bandwidth_hz/2, output_rate/2).
 */
void rfax_channel_configure(rfax_channel *c, uint32_t sample_rate,
        uint32_t decimation, int32_t offset_hz, uint32_t bandwidth_hz);

/* Clears filter and mixer state (call when the stream restarts). */
void rfax_channel_reset(rfax_channel *c);

/*
 * Processes `n` interleaved complex samples from `in` and writes the filtered,
 * decimated interleaved complex samples to `out`. `out` must have room for at
 * least 2 * (n / decimation + 2) floats. Returns the number of complex samples
 * written.
 */
size_t rfax_channel_process(rfax_channel *c, float const *in, size_t n, float *out);

#ifdef __cplusplus
}
#endif

#endif /* RFAX_CHANNEL_H_ */
