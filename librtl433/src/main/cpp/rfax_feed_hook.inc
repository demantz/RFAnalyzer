/*
 * rfax_feed_hook.inc - appended to a generated copy of rtl_433.c (see CMakeLists.txt).
 *
 * rtl_433's sample-processing entry point, sdr_callback(), is static. This
 * additive hook exposes it so an application that already owns the SDR can
 * push IQ buffers into the decoder pipeline. It performs no work of its own.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

void rfax_sdr_feed(unsigned char *iq_buf, uint32_t len, void *ctx)
{
    sdr_callback(iq_buf, len, ctx);
}
