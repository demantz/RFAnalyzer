/*
 * HydraSDR Shared Utilities Implementation
 *
 * Copyright (C) 2013-2026, Benjamin Vernoux <bvernoux@hydrasdr.com>
 *
 * SPDX-License-Identifier: MIT
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libusb.h>
#include "hydrasdr_shared.h"
#include "filters.h"
#include "filters_opt.h"
#include "iqconverter_lut.h"
#include "iqconverter.h"
#include "iqconverter_decimator.h"

#ifdef _WIN32
#include <windows.h>
#endif

/*
 * Helper to update gain cache value after a successful set operation.
 * Updates the value field, and initializes type/max if not yet cached.
 */
static inline void update_gain_cache_value(hydrasdr_streaming_t* stream,
					   hydrasdr_gain_type_t type,
					   uint8_t value,
					   uint8_t max_value)
{
	if (!stream)
		return;
	stream->cached_gain_info[type].value = value;
	if (!stream->gains_cached[type]) {
		stream->cached_gain_info[type].type = (uint8_t)type;
		stream->cached_gain_info[type].max_value = max_value;
		stream->gains_cached[type] = true;
	}
}

/* Debug: Enable USB transfer diagnostics (set to 1 to enable) */
#ifndef HYDRASDR_USB_DEBUG
#define HYDRASDR_USB_DEBUG 0
#endif

#if HYDRASDR_USB_DEBUG
/* Helper to convert libusb transfer status to readable string */
static const char* libusb_transfer_status_name(enum libusb_transfer_status status)
{
	switch (status) {
	case LIBUSB_TRANSFER_COMPLETED:  return "COMPLETED";
	case LIBUSB_TRANSFER_ERROR:      return "ERROR";
	case LIBUSB_TRANSFER_TIMED_OUT:  return "TIMED_OUT";
	case LIBUSB_TRANSFER_CANCELLED:  return "CANCELLED";
	case LIBUSB_TRANSFER_STALL:      return "STALL";
	case LIBUSB_TRANSFER_NO_DEVICE:  return "NO_DEVICE";
	case LIBUSB_TRANSFER_OVERFLOW:   return "OVERFLOW";
	default:                         return "UNKNOWN";
	}
}
#endif

/* USB transfer management */
static int hydrasdr_cancel_transfers(hydrasdr_streaming_t* stream)
{
	uint32_t transfer_index;

	if (stream->transfers != NULL)
	{
		for (transfer_index = 0; transfer_index < stream->transfer_count; transfer_index++)
		{
			if (stream->transfers[transfer_index] != NULL)
			{
				libusb_cancel_transfer(stream->transfers[transfer_index]);
			}
		}
		return HYDRASDR_SUCCESS;
	}
	else {
		return HYDRASDR_ERROR_OTHER;
	}
}

/*
 * Helper to track buffer allocation for reliable cleanup
 * Buffers may move between USB transfers and SPSC slots during streaming,
 * so we track all allocations at allocation time for reliable cleanup.
 */
static inline void track_buffer(hydrasdr_streaming_t *stream, void *ptr)
{
	if (ptr && stream->tracked_buffer_count < BUFFER_TRACKING_MAX) {
		stream->tracked_buffers[stream->tracked_buffer_count++] = ptr;
	}
}

static int hydrasdr_free_transfers(hydrasdr_streaming_t* stream)
{
	uint32_t i;
	uint32_t transfer_index;

	if (stream->transfers != NULL)
	{
		/* Free USB transfer structures (but not buffers - tracked separately) */
		for (transfer_index = 0; transfer_index < stream->transfer_count; transfer_index++)
		{
			if (stream->transfers[transfer_index] != NULL)
			{
				/* Don't free buffer here - it's tracked in tracked_buffers[] */
				stream->transfers[transfer_index]->buffer = NULL;
				libusb_free_transfer(stream->transfers[transfer_index]);
				stream->transfers[transfer_index] = NULL;
			}
		}
		free(stream->transfers);
		stream->transfers = NULL;

		/* Clear pointer arrays (buffers freed from tracked_buffers) */
		for (i = 0; i < RAW_BUFFER_COUNT; i++) {
			stream->received_samples_queue[i] = NULL;
		}

		for (i = 0; i < SPSC_QUEUE_CAPACITY; i++) {
			stream->spsc.slots[i].buffer = NULL;
		}

		/*
		 * Free ALL tracked buffers
		 * This is the single authoritative source for buffer cleanup.
		 * Buffers may have moved between USB transfers and SPSC slots during
		 * streaming, but tracked_buffers[] always contains the original
		 * allocation pointers.
		 */
		for (i = 0; i < stream->tracked_buffer_count; i++)
		{
			if (stream->tracked_buffers[i] != NULL) {
				buffer_free_aligned(stream->tracked_buffers[i]);
				stream->tracked_buffers[i] = NULL;
			}
		}
		stream->tracked_buffer_count = 0;

		/* Destroy SPSC queue */
		if (spsc_queue_is_initialized(&stream->spsc)) {
			spsc_queue_destroy(&stream->spsc);
		}

		/* Free triple buffer */
		if (stream->output_triple.buffers[0] != NULL)
		{
			triple_buffer_destroy(&stream->output_triple);
		}

		/* Free unpacked samples buffer (always aligned) */
		if (stream->unpacked_samples != NULL)
		{
			opt_aligned_free(stream->unpacked_samples);
			stream->unpacked_samples = NULL;
		}
	}

	return HYDRASDR_SUCCESS;
}

static int hydrasdr_alloc_transfers(hydrasdr_streaming_t* stream)
{
	int i;
	size_t sample_count;
	uint32_t transfer_index;
	int result = HYDRASDR_SUCCESS;

	if (stream->transfers == NULL)
	{
		/* Initialize buffer tracking */
		stream->tracked_buffer_count = 0;

		/* Allocate input buffers with aligned memory */
		for (i = 0; i < RAW_BUFFER_COUNT; i++)
		{
			stream->received_samples_queue[i] = (uint16_t *)buffer_alloc_aligned(
				stream->buffer_size, NULL);

			if (stream->received_samples_queue[i] == NULL)
			{
				result = HYDRASDR_ERROR_NO_MEM;
				goto cleanup;
			}
			track_buffer(stream, stream->received_samples_queue[i]);
			memset(stream->received_samples_queue[i], 0, stream->buffer_size);
		}

		if (stream->packing_enabled)
		{
			/* Use 64-bit arithmetic to prevent overflow */
			sample_count = (((size_t)stream->buffer_size / 2) * 4) / 3;
		}
		else
		{
			sample_count = stream->buffer_size / 2;
		}

		/* Allocate output triple buffer for pipelined processing */
		result = triple_buffer_init(&stream->output_triple,
			sample_count * sizeof(float));
		if (result != 0)
		{
			result = HYDRASDR_ERROR_NO_MEM;
			goto cleanup;
		}

		if (stream->packing_enabled)
		{
			/* Allocate unpacked samples buffer with SIMD alignment */
			stream->unpacked_samples = (uint16_t*)opt_simd_aligned_alloc(sample_count * sizeof(uint16_t));
			if (stream->unpacked_samples == NULL)
			{
				result = HYDRASDR_ERROR_NO_MEM;
				goto cleanup;
			}
		}

		stream->transfers = (struct libusb_transfer**) calloc(stream->transfer_count, sizeof(struct libusb_transfer));
		if (stream->transfers == NULL)
		{
			result = HYDRASDR_ERROR_NO_MEM;
			goto cleanup;
		}

		for (transfer_index = 0; transfer_index < stream->transfer_count; transfer_index++)
		{
			stream->transfers[transfer_index] = libusb_alloc_transfer(0);
			if (stream->transfers[transfer_index] == NULL)
			{
				result = HYDRASDR_ERROR_LIBUSB;
				goto cleanup;
			}

			/* Allocate USB transfer buffer with aligned memory */
			unsigned char *usb_buf = (unsigned char *)buffer_alloc_aligned(
				stream->buffer_size, NULL);
			track_buffer(stream, usb_buf);

			libusb_fill_bulk_transfer(
				stream->transfers[transfer_index],
				stream->dev->usb_handle,
				0,
				usb_buf,
				stream->buffer_size,
				NULL,
				stream,
				0
				);

			if (stream->transfers[transfer_index]->buffer == NULL)
			{
				result = HYDRASDR_ERROR_NO_MEM;
				goto cleanup;
			}
		}

		/* Initialize SPSC queue */
		spsc_queue_init(&stream->spsc);

		/* Pre-allocate buffers for SPSC queue slots (for zero-copy swap)
		 * These buffers will be swapped with USB transfer buffers during streaming */
		for (transfer_index = 0; transfer_index < SPSC_QUEUE_CAPACITY; transfer_index++)
		{
			unsigned char *slot_buf = (unsigned char *)buffer_alloc_aligned(
				stream->buffer_size, NULL);

			if (slot_buf == NULL)
			{
				result = HYDRASDR_ERROR_NO_MEM;
				goto cleanup;
			}

			stream->spsc.slots[transfer_index].buffer = slot_buf;
			track_buffer(stream, slot_buf);
		}

		return HYDRASDR_SUCCESS;

cleanup:
		/* Free any partially allocated resources using tracked buffers */
		if (stream->transfers != NULL)
		{
			for (transfer_index = 0; transfer_index < stream->transfer_count; transfer_index++)
			{
				if (stream->transfers[transfer_index] != NULL)
				{
					/* Don't free buffer here - tracked in tracked_buffers */
					stream->transfers[transfer_index]->buffer = NULL;
					libusb_free_transfer(stream->transfers[transfer_index]);
					stream->transfers[transfer_index] = NULL;
				}
			}
			free(stream->transfers);
			stream->transfers = NULL;
		}

		if (stream->unpacked_samples != NULL)
		{
			opt_aligned_free(stream->unpacked_samples);
			stream->unpacked_samples = NULL;
		}

		/* Free triple buffer */
		if (stream->output_triple.buffers[0] != NULL)
		{
			triple_buffer_destroy(&stream->output_triple);
		}

		/* Clear pointer arrays (buffers freed from tracked_buffers) */
		for (i = 0; i < RAW_BUFFER_COUNT; i++) {
			stream->received_samples_queue[i] = NULL;
		}

		for (i = 0; i < (int)SPSC_QUEUE_CAPACITY; i++) {
			stream->spsc.slots[i].buffer = NULL;
		}

		/* Free ALL tracked buffers */
		for (i = 0; i < (int)stream->tracked_buffer_count; i++)
		{
			if (stream->tracked_buffers[i] != NULL) {
				buffer_free_aligned(stream->tracked_buffers[i]);
				stream->tracked_buffers[i] = NULL;
			}
		}
		stream->tracked_buffer_count = 0;

		return result;
	}
	else
	{
		return HYDRASDR_ERROR_BUSY;
	}
}

/* Sample conversion functions */

static void hydrasdr_convert_samples_int16(hydrasdr_streaming_t* stream, uint16_t *src, int16_t *dest, int count)
{
	(void)stream;
	int i;
	const int16_t *lut = iqconv_lut.lut_int16;
	for (i = 0; i < count; i += 4)
	{
		dest[i + 0] = lut[src[i + 0]];
		dest[i + 1] = lut[src[i + 1]];
		dest[i + 2] = lut[src[i + 2]];
		dest[i + 3] = lut[src[i + 3]];
	}
}

static void hydrasdr_convert_samples_float(hydrasdr_streaming_t* stream, uint16_t *src, float *dest, int count)
{
	(void)stream;
	int i;
	const float *lut = iqconv_lut.lut_float32;
	for (i = 0; i < count; i += 4)
	{
		dest[i + 0] = lut[src[i + 0]];
		dest[i + 1] = lut[src[i + 1]];
		dest[i + 2] = lut[src[i + 2]];
		dest[i + 3] = lut[src[i + 3]];
	}
}

/*
 * Convert int16 IQ samples to int8 IQ samples
 *
 * Hardware-agnostic: Works with any ADC bit depth (8, 10, 12, 14, or 16-bit)
 * because input is already normalized to int16 range by DDC/LUT.
 *
 * Scale from 16-bit range to 8-bit range: divide by 256 (shift right 8)
 * Input: int16 I/Q pairs (normalized), Output: int8 I/Q pairs (same count)
 */
static void hydrasdr_convert_int16_to_int8(const int16_t *src, int8_t *dest, int count)
{
	int i;
	for (i = 0; i < count; i += 4)
	{
		dest[i + 0] = (int8_t)(src[i + 0] >> 8);
		dest[i + 1] = (int8_t)(src[i + 1] >> 8);
		dest[i + 2] = (int8_t)(src[i + 2] >> 8);
		dest[i + 3] = (int8_t)(src[i + 3] >> 8);
	}
}

/*
 * Convert int16 IQ samples to uint8 IQ samples
 *
 * Hardware-agnostic: Works with any ADC bit depth (8, 10, 12, 14, or 16-bit)
 * because input is already normalized to int16 range by DDC/LUT.
 *
 * Scale from signed 16-bit to unsigned 8-bit: (value >> 8) + 128
 * Maps -32768..32767 to 0..255
 */
static void hydrasdr_convert_int16_to_uint8(const int16_t *src, uint8_t *dest, int count)
{
	int i;
	for (i = 0; i < count; i += 4)
	{
		dest[i + 0] = (uint8_t)((src[i + 0] >> 8) + 128);
		dest[i + 1] = (uint8_t)((src[i + 1] >> 8) + 128);
		dest[i + 2] = (uint8_t)((src[i + 2] >> 8) + 128);
		dest[i + 3] = (uint8_t)((src[i + 3] >> 8) + 128);
	}
}

/*
 * Convert raw ADC samples to int8 real samples via LUT
 *
 * Hardware-agnostic: Supports 8, 10, 12, 14, or 16-bit ADCs.
 * The LUT (initialized via iqconv_lut_init) normalizes any ADC value
 * to int16 range, then >> 8 converts to int8.
 *
 * Input: Raw ADC samples as uint16_t (max 16-bit ADC supported)
 * Output: int8 real samples [-128, +127]
 */
static void hydrasdr_convert_samples_int8(hydrasdr_streaming_t* stream, uint16_t *src, int8_t *dest, int count)
{
	(void)stream;
	int i;
	const int16_t *lut = iqconv_lut.lut_int16;
	for (i = 0; i < count; i += 4)
	{
		dest[i + 0] = (int8_t)(lut[src[i + 0]] >> 8);
		dest[i + 1] = (int8_t)(lut[src[i + 1]] >> 8);
		dest[i + 2] = (int8_t)(lut[src[i + 2]] >> 8);
		dest[i + 3] = (int8_t)(lut[src[i + 3]] >> 8);
	}
}

/*
 * Convert raw ADC samples to uint8 real samples via LUT
 *
 * Hardware-agnostic: Supports 8, 10, 12, 14, or 16-bit ADCs.
 * The LUT (initialized via iqconv_lut_init) normalizes any ADC value
 * to int16 range, then >> 8 + 128 converts to uint8.
 *
 * Input: Raw ADC samples as uint16_t (max 16-bit ADC supported)
 * Output: uint8 real samples [0, 255]
 */
static void hydrasdr_convert_samples_uint8(hydrasdr_streaming_t* stream, uint16_t *src, uint8_t *dest, int count)
{
	(void)stream;
	int i;
	const int16_t *lut = iqconv_lut.lut_int16;
	for (i = 0; i < count; i += 4)
	{
		dest[i + 0] = (uint8_t)((lut[src[i + 0]] >> 8) + 128);
		dest[i + 1] = (uint8_t)((lut[src[i + 1]] >> 8) + 128);
		dest[i + 2] = (uint8_t)((lut[src[i + 2]] >> 8) + 128);
		dest[i + 3] = (uint8_t)((lut[src[i + 3]] >> 8) + 128);
	}
}

/*
 * Optimized 12-bit sample unpacking
 *
 * Packing format: 8 x 12-bit samples packed into 3 x 32-bit words (96 bits)
 *
 * Word layout (big-endian bit numbering within each 32-bit word):
 *   Word 0: [S0:11-0][S1:11-0][S2:11-4]  = bits 31-20, 19-8, 7-0
 *   Word 1: [S2:3-0][S3:11-0][S4:11-0][S5:11-8] = bits 31-28, 27-16, 15-4, 3-0
 *   Word 2: [S5:7-0][S6:11-0][S7:11-0] = bits 31-24, 23-12, 11-0
 *
 * Optimizations applied:
 *   - restrict pointers for no-aliasing guarantee (enables better vectorization)
 *   - Local caching of input words to reduce memory loads
 *   - Explicit unsigned types for defined shift behavior
 *   - Simple for loop structure lets compiler auto-vectorize with -O3
 *   - Prefetch hints for streaming memory access pattern
 */

/* Check for SSE2 support */
#if defined(__SSE2__) || (defined(_MSC_VER) && (defined(_M_X64) || (defined(_M_IX86_FP) && _M_IX86_FP >= 2)))
#define HYDRASDR_USE_SSE2 1
#include <emmintrin.h>
#endif

void hydrasdr_unpack_samples(const uint16_t* HYDRASDR_RESTRICT input,
			     uint16_t* HYDRASDR_RESTRICT output,
			     int length)
{
	const uint32_t* HYDRASDR_RESTRICT in32 = (const uint32_t*)input;
	int i, j;

#ifdef HYDRASDR_USE_SSE2
	/*
	 * SSE2 optimized path: Process 16 samples (2 groups of 8) at once
	 * This reduces loop overhead and enables better instruction pipelining
	 */
	const int simd_length = length & ~15;  /* Round down to multiple of 16 */

	for (i = 0, j = 0; j < simd_length; i += 6, j += 16) {
		/* Prefetch next cache line (64 bytes ahead = ~5 groups) */
		OPT_PREFETCH_READ(&in32[i + 15]);

		/* First group of 8 samples */
		const uint32_t w0 = in32[i];
		const uint32_t w1 = in32[i + 1];
		const uint32_t w2 = in32[i + 2];

		output[j + 0] = (uint16_t)((w0 >> 20) & 0xFFFu);
		output[j + 1] = (uint16_t)((w0 >> 8) & 0xFFFu);
		output[j + 2] = (uint16_t)(((w0 & 0xFFu) << 4) | ((w1 >> 28) & 0xFu));
		output[j + 3] = (uint16_t)((w1 >> 16) & 0xFFFu);
		output[j + 4] = (uint16_t)((w1 >> 4) & 0xFFFu);
		output[j + 5] = (uint16_t)(((w1 & 0xFu) << 8) | ((w2 >> 24) & 0xFFu));
		output[j + 6] = (uint16_t)((w2 >> 12) & 0xFFFu);
		output[j + 7] = (uint16_t)(w2 & 0xFFFu);

		/* Second group of 8 samples (interleaved for pipelining) */
		const uint32_t w3 = in32[i + 3];
		const uint32_t w4 = in32[i + 4];
		const uint32_t w5 = in32[i + 5];

		output[j + 8] = (uint16_t)((w3 >> 20) & 0xFFFu);
		output[j + 9] = (uint16_t)((w3 >> 8) & 0xFFFu);
		output[j + 10] = (uint16_t)(((w3 & 0xFFu) << 4) | ((w4 >> 28) & 0xFu));
		output[j + 11] = (uint16_t)((w4 >> 16) & 0xFFFu);
		output[j + 12] = (uint16_t)((w4 >> 4) & 0xFFFu);
		output[j + 13] = (uint16_t)(((w4 & 0xFu) << 8) | ((w5 >> 24) & 0xFFu));
		output[j + 14] = (uint16_t)((w5 >> 12) & 0xFFFu);
		output[j + 15] = (uint16_t)(w5 & 0xFFFu);
	}

	/* Handle remaining samples with scalar code */
	for (; j < length; i += 3, j += 8) {
		const uint32_t w0 = in32[i];
		const uint32_t w1 = in32[i + 1];
		const uint32_t w2 = in32[i + 2];

		output[j + 0] = (uint16_t)((w0 >> 20) & 0xFFFu);
		output[j + 1] = (uint16_t)((w0 >> 8) & 0xFFFu);
		output[j + 2] = (uint16_t)(((w0 & 0xFFu) << 4) | ((w1 >> 28) & 0xFu));
		output[j + 3] = (uint16_t)((w1 >> 16) & 0xFFFu);
		output[j + 4] = (uint16_t)((w1 >> 4) & 0xFFFu);
		output[j + 5] = (uint16_t)(((w1 & 0xFu) << 8) | ((w2 >> 24) & 0xFFu));
		output[j + 6] = (uint16_t)((w2 >> 12) & 0xFFFu);
		output[j + 7] = (uint16_t)(w2 & 0xFFFu);
	}

#else
	/* Scalar fallback for non-SSE2 platforms */
	for (i = 0, j = 0; j < length; i += 3, j += 8) {
		const uint32_t w0 = in32[i];
		const uint32_t w1 = in32[i + 1];
		const uint32_t w2 = in32[i + 2];

		output[j + 0] = (uint16_t)((w0 >> 20) & 0xFFFu);
		output[j + 1] = (uint16_t)((w0 >> 8) & 0xFFFu);
		output[j + 2] = (uint16_t)(((w0 & 0xFFu) << 4) | ((w1 >> 28) & 0xFu));
		output[j + 3] = (uint16_t)((w1 >> 16) & 0xFFFu);
		output[j + 4] = (uint16_t)((w1 >> 4) & 0xFFFu);
		output[j + 5] = (uint16_t)(((w1 & 0xFu) << 8) | ((w2 >> 24) & 0xFFu));
		output[j + 6] = (uint16_t)((w2 >> 12) & 0xFFFu);
		output[j + 7] = (uint16_t)(w2 & 0xFFFu);
	}
#endif
}

/*
 * Reference implementation for testing (simple, unoptimized)
 */
void hydrasdr_unpack_samples_reference(const uint16_t* input,
				       uint16_t* output,
				       int length)
{
	const uint32_t* in32 = (const uint32_t*)input;
	int i, j;

	for (i = 0, j = 0; j < length; i += 3, j += 8) {
		output[j + 0] = (in32[i] >> 20) & 0xfff;
		output[j + 1] = (in32[i] >> 8) & 0xfff;
		output[j + 2] = ((in32[i] & 0xff) << 4) | ((in32[i + 1] >> 28) & 0xf);
		output[j + 3] = ((in32[i + 1] & 0xfff0000) >> 16);
		output[j + 4] = ((in32[i + 1] & 0xfff0) >> 4);
		output[j + 5] = ((in32[i + 1] & 0xf) << 8) | ((in32[i + 2] & 0xff000000) >> 24);
		output[j + 6] = ((in32[i + 2] >> 12) & 0xfff);
		output[j + 7] = ((in32[i + 2] & 0xfff));
	}
}

/* USB transfer callback */

static void LIBUSB_CALL hydrasdr_libusb_transfer_callback(struct libusb_transfer* usb_transfer)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)usb_transfer->user_data;

	if (!OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.streaming) ||
	    OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.stop_requested))
	{
		return;
	}

	if (usb_transfer->status == LIBUSB_TRANSFER_COMPLETED && usb_transfer->actual_length == usb_transfer->length)
	{
		stream->stats.total_buffers_received++;

		/* Lock-free SPSC queue with zero-copy buffer swap */
		void *buffer_ptr = (void *)usb_transfer->buffer;
		uint32_t dropped = stream->usb_hot.dropped_buffers;

		if (spsc_queue_enqueue_swap(&stream->spsc, &buffer_ptr, dropped))
		{
			/* Success: buffer_ptr now contains the old queue buffer */
			usb_transfer->buffer = (unsigned char *)buffer_ptr;
			stream->usb_hot.dropped_buffers = 0;

			/* Signal consumer thread that data is available
			 * Use trylock to avoid blocking in USB callback hot path.
			 * If mutex is already held by consumer (processing), that's fine -
			 * consumer will check queue again after processing anyway. */
			if (pthread_mutex_trylock(&stream->consumer_mutex) == 0) {
				pthread_cond_signal(&stream->consumer_cond);
				pthread_mutex_unlock(&stream->consumer_mutex);
			}
			/* If trylock fails, consumer is already awake and processing,
			 * so it will see the new data when it loops back to dequeue */
		}
		else
		{
			/* Queue full - buffer overflow */
			stream->usb_hot.dropped_buffers++;
			stream->stats.total_dropped_buffers++;
		}

		int submit_result = libusb_submit_transfer(usb_transfer);
		if (submit_result != 0)
		{
#if HYDRASDR_USB_DEBUG
			fprintf(stderr, "[USB_DEBUG] libusb_submit_transfer failed: %d (%s)\n",
				submit_result, libusb_error_name(submit_result));
#endif
			OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);
		}
	}
	else
	{
#if HYDRASDR_USB_DEBUG
		fprintf(stderr, "[USB_DEBUG] Transfer failed - status: %s (%d), "
			"actual_length: %d, expected: %d (after %llu buffers)\n",
			libusb_transfer_status_name(usb_transfer->status),
			usb_transfer->status,
			usb_transfer->actual_length,
			usb_transfer->length,
			(unsigned long long)stream->stats.total_buffers_received);
#endif
		OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);
	}
}

/* Threading functions */

static void* hydrasdr_consumer_threadproc(void *arg)
{
	int sample_count;
	uint16_t* input_samples;
	uint32_t dropped_buffers;
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)arg;
	hydrasdr_transfer_t transfer;
	void *current_output;

	/* Set high thread priority for low-latency streaming (Windows only) */
#ifdef _WIN32
	SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST);
#endif

	/* Hybrid lock-free consumer loop:
	 * - Lock-free SPSC queue for data transfer (zero-copy, no mutex overhead)
	 * - Condition variable for efficient wakeup (low CPU, low latency)
	 */
	pthread_mutex_lock(&stream->consumer_mutex);

	while (OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.streaming) &&
	       !OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.stop_requested))
	{
		void *buffer_ptr = NULL;

		/* Try to dequeue without blocking first */
		while (!spsc_queue_dequeue(&stream->spsc, &buffer_ptr, &dropped_buffers))
		{
			if (!OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.streaming) ||
			    OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.stop_requested))
			{
				pthread_mutex_unlock(&stream->consumer_mutex);
				goto exit_loop;
			}

			/* Wait for signal from USB callback - releases mutex while waiting */
			pthread_cond_wait(&stream->consumer_cond, &stream->consumer_mutex);
		}

		/* Release mutex during processing to allow USB callback to signal */
		pthread_mutex_unlock(&stream->consumer_mutex);

		input_samples = (uint16_t *)buffer_ptr;
		stream->stats.total_buffers_processed++;

		/* Get output buffer from triple buffer */
		current_output = triple_buffer_get_write(&stream->output_triple);

		if (stream->packing_enabled)
		{
			/* Packing only available for 12-bit mode (not 8-bit) */
			sample_count = (((size_t)stream->buffer_size / 2) * 4) / 3;

			if (stream->sample_type != HYDRASDR_SAMPLE_RAW)
			{
				hydrasdr_unpack_samples(input_samples, stream->unpacked_samples, sample_count);
				input_samples = stream->unpacked_samples;
			}
		}
		else if (stream->adc_bits == IQCONV_ADC_8BIT)
		{
			/* 8-bit ADC mode: 1 byte per sample */
			sample_count = stream->buffer_size;
		}
		else
		{
			/* 10/12/14/16-bit ADC mode: 2 bytes per sample in uint16_t */
			sample_count = stream->buffer_size / 2;
		}

		switch (stream->sample_type)
		{
		case HYDRASDR_SAMPLE_FLOAT32_IQ:
			/* Unified ADC->IQ processing (handles both fused and legacy) */
			if (stream->adc_bits == IQCONV_ADC_8BIT) {
				iqconverter_process_u8(stream->cnv_f, (const uint8_t *)buffer_ptr,
					(float *)current_output, sample_count);
			} else {
				iqconverter_process(stream->cnv_f, input_samples,
					(float *)current_output, sample_count);
			}
			/*
			 * DDC outputs I/Q pairs, so sample_count /= 2.
			 * With decimation, output is further reduced by decimation_factor.
			 */
			sample_count /= (2 * stream->decimation_factor);
			transfer.samples = current_output;
			break;

		case HYDRASDR_SAMPLE_FLOAT32_REAL:
			hydrasdr_convert_samples_float(stream, input_samples, (float *)current_output, sample_count);
			transfer.samples = current_output;
			break;

		case HYDRASDR_SAMPLE_INT16_IQ:
			/* Unified ADC->IQ processing (handles both fused and legacy) */
			if (stream->adc_bits == IQCONV_ADC_8BIT) {
				iqconverter_process_u8(stream->cnv_i, (const uint8_t *)buffer_ptr,
					(int16_t *)current_output, sample_count);
			} else {
				iqconverter_process(stream->cnv_i, input_samples,
					(int16_t *)current_output, sample_count);
			}
			/*
			 * DDC outputs I/Q pairs, so sample_count /= 2.
			 * With decimation, output is further reduced by decimation_factor.
			 */
			sample_count /= (2 * stream->decimation_factor);
			transfer.samples = current_output;
			break;

		case HYDRASDR_SAMPLE_INT16_REAL:
			hydrasdr_convert_samples_int16(stream, input_samples, (int16_t *)current_output, sample_count);
			transfer.samples = current_output;
			break;

		case HYDRASDR_SAMPLE_UINT16_REAL:
		case HYDRASDR_SAMPLE_RAW:
			transfer.samples = input_samples;
			break;

		case HYDRASDR_SAMPLE_INT8_IQ:
			/*
			 * Use int16 DDC then convert to int8.
			 * First pass: DDC outputs int16 I/Q pairs to temp buffer.
			 * Second pass: Convert int16 to int8.
			 */
			{
				int16_t *temp_buf = (int16_t *)current_output;
				int8_t *out_buf;
				int iq_pairs;

				if (stream->adc_bits == IQCONV_ADC_8BIT) {
					iqconverter_process_u8(stream->cnv_i, (const uint8_t *)buffer_ptr,
						temp_buf, sample_count);
				} else {
					iqconverter_process(stream->cnv_i, input_samples,
						temp_buf, sample_count);
				}
				iq_pairs = sample_count / (2 * stream->decimation_factor);

				/* Convert int16 to int8 in-place (int8 is smaller) */
				out_buf = (int8_t *)current_output;
				hydrasdr_convert_int16_to_int8(temp_buf, out_buf, iq_pairs * 2);

				sample_count = iq_pairs;
				transfer.samples = current_output;
			}
			break;

		case HYDRASDR_SAMPLE_UINT8_IQ:
			/*
			 * Use int16 DDC then convert to uint8.
			 */
			{
				int16_t *temp_buf = (int16_t *)current_output;
				uint8_t *out_buf;
				int iq_pairs;

				if (stream->adc_bits == IQCONV_ADC_8BIT) {
					iqconverter_process_u8(stream->cnv_i, (const uint8_t *)buffer_ptr,
						temp_buf, sample_count);
				} else {
					iqconverter_process(stream->cnv_i, input_samples,
						temp_buf, sample_count);
				}
				iq_pairs = sample_count / (2 * stream->decimation_factor);

				/* Convert int16 to uint8 in-place */
				out_buf = (uint8_t *)current_output;
				hydrasdr_convert_int16_to_uint8(temp_buf, out_buf, iq_pairs * 2);

				sample_count = iq_pairs;
				transfer.samples = current_output;
			}
			break;

		case HYDRASDR_SAMPLE_INT8_REAL:
			hydrasdr_convert_samples_int8(stream, input_samples, (int8_t *)current_output, sample_count);
			transfer.samples = current_output;
			break;

		case HYDRASDR_SAMPLE_UINT8_REAL:
			hydrasdr_convert_samples_uint8(stream, input_samples, (uint8_t *)current_output, sample_count);
			transfer.samples = current_output;
			break;

		case HYDRASDR_SAMPLE_END:
			break;
		}

		/* Publish output buffer and get read pointer for callback */
		if (transfer.samples == current_output)
		{
			transfer.samples = triple_buffer_publish_for_read(&stream->output_triple);
		}

		transfer.device = stream->dev;
		transfer.ctx = stream->ctx;
		transfer.sample_count = sample_count;
		transfer.sample_type = stream->sample_type;
		/* Use cumulative dropped buffer count * samples per buffer for true cumulative dropped_samples */
		transfer.dropped_samples = stream->stats.total_dropped_buffers * (uint64_t) sample_count;

		if (stream->callback(&transfer) != 0)
		{
			OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);
		}

		/* Re-acquire mutex before checking queue again */
		pthread_mutex_lock(&stream->consumer_mutex);
	}

	pthread_mutex_unlock(&stream->consumer_mutex);

exit_loop:
	OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);

	return NULL;
}

static void* hydrasdr_transfer_threadproc(void* arg)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)arg;
	int error;
	struct timeval timeout = { 0, TRANSFER_THREAD_TIMEOUT_US };

	/* Set high thread priority for low-latency USB transfers (Windows only) */
#ifdef _WIN32
	SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST);
#endif

	while (OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.streaming) &&
	       !OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.stop_requested))
	{
		error = libusb_handle_events_timeout_completed(stream->dev->usb_context, &timeout, NULL);
		if (error < 0)
		{
			if (error != LIBUSB_ERROR_INTERRUPTED)
			{
#if HYDRASDR_USB_DEBUG
				fprintf(stderr, "[USB_DEBUG] libusb_handle_events failed: %d (%s)\n",
					error, libusb_error_name(error));
#endif
				OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);
			}
		}
	}

	OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);

	return NULL;
}

/* Prepare and submit transfers */

static int hydrasdr_prepare_transfers(hydrasdr_streaming_t* stream, uint8_t endpoint_address)
{
	int error;
	uint32_t transfer_index;
	if (stream->transfers != NULL)
	{
		for (transfer_index = 0; transfer_index<stream->transfer_count; transfer_index++)
		{
			stream->transfers[transfer_index]->endpoint = endpoint_address;
			stream->transfers[transfer_index]->callback = hydrasdr_libusb_transfer_callback;

			error = libusb_submit_transfer(stream->transfers[transfer_index]);
			if (error != 0)
			{
				return HYDRASDR_ERROR_LIBUSB;
			}
		}
		return HYDRASDR_SUCCESS;
	}
	else {
		return HYDRASDR_ERROR_OTHER;
	}
}

/* High-level streaming control (used by device drivers) */

static int hydrasdr_kill_io_threads(hydrasdr_streaming_t* stream)
{
	struct timeval timeout = { 0, EVENT_DRAIN_TIMEOUT_US };
	int i;

	/* Idempotent: safe to call multiple times */
	if (!stream->transfer_thread_running && !stream->consumer_thread_running)
	{
		OPT_ATOMIC_STORE_REL(&stream->usb_hot.stop_requested, 0);
		OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);
		return HYDRASDR_SUCCESS;
	}

	if (OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.stop_requested))
	{
		OPT_ATOMIC_STORE_REL(&stream->usb_hot.stop_requested, 0);
		OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);

		/* Cancel pending USB transfers */
		hydrasdr_cancel_transfers(stream);

		/* Wake up consumer thread so it can exit */
		pthread_mutex_lock(&stream->consumer_mutex);
		pthread_cond_signal(&stream->consumer_cond);
		pthread_mutex_unlock(&stream->consumer_mutex);

		/* Wait for threads to finish */
		if (stream->transfer_thread_running) {
			pthread_join(stream->transfer_thread, NULL);
			stream->transfer_thread_running = false;
		}
		if (stream->consumer_thread_running) {
			pthread_join(stream->consumer_thread, NULL);
			stream->consumer_thread_running = false;
		}

		/* Drain remaining USB events to ensure all cancellation callbacks have fired.
		 * This is critical - libusb_close() will hang if transfers are still pending. */
		for (i = 0; i < EVENT_DRAIN_ITERATIONS; i++)
		{
			libusb_handle_events_timeout_completed(stream->dev->usb_context, &timeout, NULL);
		}
	}

	return HYDRASDR_SUCCESS;
}

static int hydrasdr_create_io_threads(hydrasdr_streaming_t* stream, hydrasdr_sample_block_cb_fn callback)
{
	int result;
	pthread_attr_t attr;
	(void)callback;

	/* Note: streaming flag is already set by hydrasdr_start_streaming()
	 * before USB transfers are submitted (to avoid race condition).
	 * We just verify the flag is set correctly here. */
	if (OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.streaming) &&
	    !OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.stop_requested))
	{
		/* Reset SPSC queue and populate with initial buffer pointers */
		spsc_queue_reset(&stream->spsc);

		/* Pre-populate SPSC queue slots with buffer pointers for zero-copy swap */
		for (int i = 0; i < RAW_BUFFER_COUNT; i++)
		{
			stream->spsc.slots[i].buffer = stream->received_samples_queue[i];
			stream->spsc.slots[i].dropped = 0;
		}

		/* Reset triple buffer */
		triple_buffer_reset(&stream->output_triple);

		pthread_attr_init(&attr);
		pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

		result = pthread_create(&stream->consumer_thread, &attr, hydrasdr_consumer_threadproc, stream);
		if (result != 0)
		{
			pthread_attr_destroy(&attr);
			OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);
			return HYDRASDR_ERROR_THREAD;
		}
		stream->consumer_thread_running = true;

		result = pthread_create(&stream->transfer_thread, &attr, hydrasdr_transfer_threadproc, stream);
		if (result != 0)
		{
			/* Cleanup consumer thread before returning error */
			OPT_ATOMIC_STORE_REL(&stream->usb_hot.stop_requested, 1);
			OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);
			pthread_join(stream->consumer_thread, NULL);
			stream->consumer_thread_running = false;
			OPT_ATOMIC_STORE_REL(&stream->usb_hot.stop_requested, 0);

			pthread_attr_destroy(&attr);
			return HYDRASDR_ERROR_THREAD;
		}
		stream->transfer_thread_running = true;

		pthread_attr_destroy(&attr);
	}
	else {
		return HYDRASDR_ERROR_BUSY;
	}

	return HYDRASDR_SUCCESS;
}

static int hydrasdr_start_streaming(struct hydrasdr_device* dev, 
	hydrasdr_sample_block_cb_fn callback, 
	void* rx_ctx, 
	uint8_t endpoint_address)
{
	int result;
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;

	iqconverter_reset(stream->cnv_f);
	iqconverter_reset(stream->cnv_i);

	stream->usb_hot.dropped_buffers = 0;

	/* Reset streaming statistics */
	stream->stats.total_buffers_received = 0;
	stream->stats.total_buffers_processed = 0;
	stream->stats.total_dropped_buffers = 0;

	stream->ctx = rx_ctx;

	/* CRITICAL: Set streaming flag BEFORE submitting USB transfers!
	 * The USB callback checks this flag and will ignore transfers if false.
	 * This must be done before prepare_transfers to avoid a race condition
	 * where USB callbacks fire before streaming is set. */
	stream->callback = callback;
	OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 1);

	result = hydrasdr_prepare_transfers(stream, endpoint_address);
	if (result != HYDRASDR_SUCCESS)
	{
		OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);
		return result;
	}

	result = hydrasdr_create_io_threads(stream, callback);

	return result;
}

static int hydrasdr_stop_streaming(struct hydrasdr_device* dev)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	if (!stream) return HYDRASDR_SUCCESS;

	OPT_ATOMIC_STORE_REL(&stream->usb_hot.stop_requested, 1);
	return hydrasdr_kill_io_threads(stream);
}

/* Generic device initialization/cleanup helpers */

int hydrasdr_generic_init_streaming(struct hydrasdr_device* dev,
	uint32_t transfer_count,
	uint32_t buffer_size,
	enum hydrasdr_sample_type default_sample_type,
	uint32_t fallback_caps)
{
	int result;
	/* hydrasdr_streaming_t carries OPT_ALIGN(OPT_CACHE_LINE_SIZE) members,
	 * so plain malloc/calloc is not aligned enough (8 bytes on 32-bit
	 * glibc) and compiler-emitted aligned SIMD stores fault. */
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)opt_aligned_alloc(
		sizeof(hydrasdr_streaming_t), OPT_CACHE_LINE_SIZE);
	if (!stream) {
		return HYDRASDR_ERROR_NO_MEM;
	}
	memset(stream, 0, sizeof(*stream));

	dev->private_data = stream;
	stream->dev = dev;

	stream->transfers = NULL;
	stream->callback = NULL;
	stream->transfer_count = transfer_count;
	stream->buffer_size = buffer_size;
	stream->packing_enabled = false;
	stream->adc_bits = IQCONV_ADC_12BIT;	/* Default: 12-bit ADC (updated by firmware) */
	stream->data_format = HYDRASDR_DATA_RAW_ADC;	/* Default: raw ADC (updated by firmware) */
	OPT_ATOMIC_STORE_REL(&stream->usb_hot.streaming, 0);
	OPT_ATOMIC_STORE_REL(&stream->usb_hot.stop_requested, 0);
	stream->sample_type = default_sample_type;
	dev->sample_type = default_sample_type;

	/* Initialize consumer thread synchronization */
	pthread_mutex_init(&stream->consumer_mutex, NULL);
	pthread_cond_init(&stream->consumer_cond, NULL);

	/* Query supported sample rates */
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_GET_SAMPLERATES,
		0, 0,
		(unsigned char*)&stream->supported_samplerate_count,
		(uint16_t)sizeof(uint32_t),
		LIBUSB_CTRL_TIMEOUT_MS);
	
	if (result < (int)sizeof(uint32_t)) {
		stream->supported_samplerate_count = 0;
	}

	if (stream->supported_samplerate_count > 0 && stream->supported_samplerate_count < MAX_SUPPORTED_RATE_COUNT) {
		stream->supported_samplerates = (uint32_t *) malloc(stream->supported_samplerate_count * sizeof(uint32_t));
		if (stream->supported_samplerates == NULL) {
			opt_aligned_free(stream);
			dev->private_data = NULL;
			return HYDRASDR_ERROR_NO_MEM;
		}
		result = libusb_control_transfer(
			dev->usb_handle,
			((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
			HYDRASDR_GET_SAMPLERATES,
			0, stream->supported_samplerate_count,
			(unsigned char*)stream->supported_samplerates,
			(uint16_t)(stream->supported_samplerate_count * sizeof(uint32_t)),
			LIBUSB_CTRL_TIMEOUT_MS);

		if (result < (int)(stream->supported_samplerate_count * sizeof(uint32_t))) {
			free(stream->supported_samplerates);
			opt_aligned_free(stream);
			dev->private_data = NULL;
			return HYDRASDR_ERROR_LIBUSB;
		}
	} else {
		stream->supported_samplerate_count = 0;
		stream->supported_samplerates = NULL;
	}

	/* Query supported bandwidths */
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_GET_BANDWIDTHS,
		0, 0,
		(unsigned char*)&stream->supported_bandwidth_count,
		(uint16_t)sizeof(uint32_t),
		LIBUSB_CTRL_TIMEOUT_MS);

	if (result < (int)sizeof(uint32_t)) {
		stream->supported_bandwidth_count = 0;
	}

	if (stream->supported_bandwidth_count > 0 && stream->supported_bandwidth_count < MAX_SUPPORTED_RATE_COUNT) {
		stream->supported_bandwidths = (uint32_t *) malloc(stream->supported_bandwidth_count * sizeof(uint32_t));
		if (stream->supported_bandwidths == NULL) {
			free(stream->supported_samplerates);
			opt_aligned_free(stream);
			dev->private_data = NULL;
			return HYDRASDR_ERROR_NO_MEM;
		}
		result = libusb_control_transfer(
			dev->usb_handle,
			((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
			HYDRASDR_GET_BANDWIDTHS,
			0, stream->supported_bandwidth_count,
			(unsigned char*)stream->supported_bandwidths,
			(uint16_t)(stream->supported_bandwidth_count * sizeof(uint32_t)),
			LIBUSB_CTRL_TIMEOUT_MS);

		if (result < (int)(stream->supported_bandwidth_count * sizeof(uint32_t))) {
			free(stream->supported_bandwidths);
			free(stream->supported_samplerates);
			opt_aligned_free(stream);
			dev->private_data = NULL;
			return HYDRASDR_ERROR_LIBUSB;
		}
	} else {
		stream->supported_bandwidth_count = 0;
		stream->supported_bandwidths = NULL;
	}

	/*
	 * Cache device info by calling existing generic functions.
	 * Since device_info_cached is false, they will do USB queries.
	 * Results are stored in cache fields, then cache is marked valid.
	 */
	stream->device_info_cached = false;

	/* Cache board ID - use existing function */
	if (hydrasdr_generic_board_id_read(dev, &stream->cached_board_id) != HYDRASDR_SUCCESS) {
		stream->cached_board_id = 0xFF;  /* Invalid board ID marker */
	}

	/* Cache version string - use existing function */
	memset(stream->cached_version_string, 0, VERSION_STRING_SIZE);
	hydrasdr_generic_version_string_read(dev, stream->cached_version_string, VERSION_STRING_SIZE);

	/* Cache part ID and serial - use existing function */
	memset(&stream->cached_part_serial, 0, sizeof(stream->cached_part_serial));
	hydrasdr_generic_board_partid_serialno_read(dev, &stream->cached_part_serial);

	/* Cache capabilities - use device-specific fallback for old firmware */
	stream->cached_features = 0;
	memset(stream->cached_features_reserved, 0, sizeof(stream->cached_features_reserved));
	hydrasdr_generic_get_capabilities(dev, &stream->cached_features,
		stream->cached_features_reserved, fallback_caps);

	stream->device_info_cached = true;

	/*
	 * Query extended sample rate info if capability is present.
	 * This provides per-rate ADC configuration (bit depth, data format).
	 * Use wValue=1 to request extended format from firmware.
	 */
	stream->samplerate_info = NULL;
	stream->has_extended_samplerate_info = false;

	if ((stream->cached_features & HYDRASDR_CAP_EXTENDED_SAMPLERATES) &&
	    stream->supported_samplerate_count > 0) {
		stream->samplerate_info = (hydrasdr_samplerate_info_t *)malloc(
			stream->supported_samplerate_count * sizeof(hydrasdr_samplerate_info_t));
		if (stream->samplerate_info != NULL) {
			/*
			 * Query extended sample rate info:
			 * wValue = 1 (extended format flag)
			 * wIndex = count
			 * Returns array of hydrasdr_samplerate_info_t
			 */
			result = libusb_control_transfer(
				dev->usb_handle,
				((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
				HYDRASDR_GET_SAMPLERATES,
				1, stream->supported_samplerate_count,  /* wValue=1 for extended format */
				(unsigned char*)stream->samplerate_info,
				(uint16_t)(stream->supported_samplerate_count * sizeof(hydrasdr_samplerate_info_t)),
				LIBUSB_CTRL_TIMEOUT_MS);

			if (result >= (int)(stream->supported_samplerate_count * sizeof(hydrasdr_samplerate_info_t))) {
				stream->has_extended_samplerate_info = true;
			} else {
				/* Query failed - fall back to basic mode (12-bit RAW_ADC) */
				free(stream->samplerate_info);
				stream->samplerate_info = NULL;
			}
		}
	}

	/* Allocate USB transfers */
	result = hydrasdr_alloc_transfers(stream);
	if (result != HYDRASDR_SUCCESS) {
		hydrasdr_free_transfers(stream);
		free(stream->supported_bandwidths);
		free(stream->supported_samplerates);
		opt_aligned_free(stream);
		dev->private_data = NULL;
		return result;
	}

	/* Initialize LUT for ultra-fast ADC conversion */
	if (!iqconv_lut_is_initialized() ||
		iqconv_lut_get_adc_bits() != stream->adc_bits) {
		iqconv_lut_init(stream->adc_bits);
	}

	/* Initialize DDC instances with default filters */
	stream->cnv_f = iqconverter_create(HB_KERNEL_FLOAT, HB_KERNEL_FLOAT_LEN, "float32_opt");
	if (stream->cnv_f == NULL) {
		hydrasdr_free_transfers(stream);
		free(stream->supported_bandwidths);
		free(stream->supported_samplerates);
		opt_aligned_free(stream);
		dev->private_data = NULL;
		return HYDRASDR_ERROR_NO_MEM;
	}

	stream->cnv_i = iqconverter_create(HB_KERNEL_INT16, HB_KERNEL_INT16_LEN, "int16_opt");
	if (stream->cnv_i == NULL) {
		iqconverter_free(stream->cnv_f);
		hydrasdr_free_transfers(stream);
		free(stream->supported_bandwidths);
		free(stream->supported_samplerates);
		opt_aligned_free(stream);
		dev->private_data = NULL;
		return HYDRASDR_ERROR_NO_MEM;
	}

	/* Initialize decimation state (no decimation by default, low bandwidth mode) */
	stream->decimation_factor = 1;
	stream->decimation_mode = HYDRASDR_DEC_MODE_LOW_BANDWIDTH;
	stream->hardware_samplerate = 0;
	stream->effective_samplerate = 0;
	stream->virtual_samplerates = NULL;
	stream->virtual_samplerate_count = 0;
	stream->bandwidth_explicitly_set = false;
	stream->current_bandwidth = 0;
	stream->pending_auto_bandwidth_idx = 0;
	stream->pending_auto_bandwidth_hz = 0;

	/* Build virtual sample rate table (hardware rates + decimated variants) */
	if (stream->supported_samplerate_count > 0) {
		hydrasdr_generic_build_virtual_samplerates(dev);
	}

	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_cleanup_streaming(struct hydrasdr_device* dev)
{
	if (dev && dev->private_data) {
		hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;

		/* Stop any active streaming */
		hydrasdr_stop_streaming(dev);

		/* Destroy consumer thread synchronization */
		pthread_cond_destroy(&stream->consumer_cond);
		pthread_mutex_destroy(&stream->consumer_mutex);

		/* Free DDC instances */
		iqconverter_free(stream->cnv_f);
		iqconverter_free(stream->cnv_i);

		/* Free transfers and buffers */
		hydrasdr_free_transfers(stream);
		free(stream->supported_bandwidths);
		free(stream->supported_samplerates);
		free(stream->samplerate_info);
		free(stream->virtual_samplerates);
		opt_aligned_free(stream);
		dev->private_data = NULL;
	}
	return HYDRASDR_SUCCESS;
}

/* Generic device control functions */

int hydrasdr_generic_set_freq(struct hydrasdr_device* dev, const uint64_t freq_hz)
{
	typedef struct {
		uint64_t freq_hz;
	} set_freq_params_t;

	set_freq_params_t set_freq_params;
	uint8_t length;
	int result;

	/* Reject extreme invalid frequency values */
	/* 0 Hz is never valid, and >10 GHz is way beyond any current SDR hardware */
	#define MAX_FREQ_HZ (10000000000ULL)  /* 10 GHz */
	if (freq_hz == 0 || freq_hz > MAX_FREQ_HZ) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	#undef MAX_FREQ_HZ

	set_freq_params.freq_hz = TO_LE_64(freq_hz);
	length = sizeof(set_freq_params_t);

	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_FREQ,
		0,
		0,
		(unsigned char*)&set_freq_params,
		length,
		LIBUSB_CTRL_TIMEOUT_MS);

	if (result < length) {
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

/* ========================================================================
 * DECIMATION-AWARE SAMPLE RATE FUNCTIONS
 *
 * These functions enable virtual sample rates by combining hardware rates
 * with DDC decimation (2x, 4x, 8x). This minimizes USB bandwidth while
 * providing more sample rate options to applications.
 *
 * Example: Hardware supports 10 MHz, 20 MHz
 * Virtual rates: 10M, 5M, 2.5M, 1.25M, 20M, 10M, 5M, 2.5M (deduplicated)
 * ======================================================================== */

/*
 * Find optimal hardware rate and decimation factor for a desired sample rate.
 *
 * Strategy depends on decimation_mode:
 * - LOW_BANDWIDTH: Find the LOWEST hardware rate (minimizes USB bandwidth)
 * - HIGH_DEFINITION: Find the HIGHEST hardware rate (maximizes signal quality)
 *
 * Returns: 0 on success, -1 if no valid configuration exists
 */
static int find_optimal_rate_config(hydrasdr_streaming_t *stream,
				    uint32_t desired_rate,
				    uint32_t *out_hw_rate,
				    uint32_t *out_decimation)
{
	uint32_t i;
	uint32_t best_hw_rate = 0;
	uint32_t best_decimation = 0;
	int high_def_mode = (stream->decimation_mode == HYDRASDR_DEC_MODE_HIGH_DEFINITION);
	static const uint32_t dec_factors[] = {64, 32, 16, 8, 4, 2, 1};

	/* Try each hardware rate with each decimation factor */
	for (i = 0; i < stream->supported_samplerate_count; i++) {
		uint32_t hw_rate = stream->supported_samplerates[i];
		uint32_t d;

		for (d = 0; d < DEC_NUM_FACTORS; d++) {
			uint32_t dec = dec_factors[d];
			uint32_t effective = hw_rate / dec;

			if (effective == desired_rate) {
				int is_better = 0;

				if (best_hw_rate == 0) {
					is_better = 1;
				} else if (high_def_mode) {
					/*
					 * HIGH_DEFINITION mode: Prefer configuration with:
					 * 1. Highest hardware rate (more oversampling)
					 * 2. If tied, highest decimation (more stages)
					 */
					if (hw_rate > best_hw_rate ||
					    (hw_rate == best_hw_rate && dec > best_decimation)) {
						is_better = 1;
					}
				} else {
					/*
					 * LOW_BANDWIDTH mode (default): Prefer configuration with:
					 * 1. Lowest hardware rate (saves USB bandwidth)
					 * 2. If tied, highest decimation (better filtering)
					 */
					if (hw_rate < best_hw_rate ||
					    (hw_rate == best_hw_rate && dec > best_decimation)) {
						is_better = 1;
					}
				}

				if (is_better) {
					best_hw_rate = hw_rate;
					best_decimation = dec;
				}
			}
		}
	}

	if (best_hw_rate == 0) {
		return -1;	/* No valid configuration found */
	}

	*out_hw_rate = best_hw_rate;
	*out_decimation = best_decimation;
	return 0;
}

/*
 * Comparison function for qsort - descending order for sample rates
 */
static int rate_compare_desc(const void *a, const void *b)
{
	uint32_t ra = *(const uint32_t *)a;
	uint32_t rb = *(const uint32_t *)b;
	if (ra > rb) return -1;
	if (ra < rb) return 1;
	return 0;
}

/*
 * Build virtual sample rate table from hardware rates + decimation factors.
 * The table includes all unique rates achievable via decimation.
 */
int hydrasdr_generic_build_virtual_samplerates(struct hydrasdr_device* dev)
{
	hydrasdr_streaming_t* stream;
	uint32_t *temp_rates;
	uint32_t temp_count = 0;
	uint32_t max_rates;
	uint32_t i, d, j;
	uint32_t unique_count;
	static const uint32_t dec_factors[] = {1, 2, 4, 8, 16, 32, 64};

	if (!dev) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	stream = (hydrasdr_streaming_t*)dev->private_data;
	if (!stream) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	/* Free existing virtual rate table */
	if (stream->virtual_samplerates) {
		free(stream->virtual_samplerates);
		stream->virtual_samplerates = NULL;
		stream->virtual_samplerate_count = 0;
	}

	/* Maximum possible rates: hw_count * DEC_NUM_FACTORS decimation factors */
	max_rates = stream->supported_samplerate_count * DEC_NUM_FACTORS;
	temp_rates = (uint32_t *)malloc(max_rates * sizeof(uint32_t));
	if (!temp_rates) {
		return HYDRASDR_ERROR_NO_MEM;
	}

	/* Generate all possible rates */
	for (i = 0; i < stream->supported_samplerate_count; i++) {
		uint32_t hw_rate = stream->supported_samplerates[i];

		for (d = 0; d < DEC_NUM_FACTORS; d++) {
			uint32_t dec = dec_factors[d];
			uint32_t effective = hw_rate / dec;

			/*
			 * Add if rate is valid. Use 10 kHz minimum to allow
			 * low sample rates via decimation while avoiding
			 * impractically low rates.
			 */
			#define MIN_VIRTUAL_RATE_HZ 10000  /* 10 kHz */
			if (effective >= MIN_VIRTUAL_RATE_HZ) {
				temp_rates[temp_count++] = effective;
			}
			#undef MIN_VIRTUAL_RATE_HZ
		}
	}

	/* Sort descending */
	qsort(temp_rates, temp_count, sizeof(uint32_t), rate_compare_desc);

	/* Remove duplicates */
	unique_count = 0;
	for (i = 0; i < temp_count; i++) {
		int is_duplicate = 0;
		for (j = 0; j < unique_count; j++) {
			if (temp_rates[j] == temp_rates[i]) {
				is_duplicate = 1;
				break;
			}
		}
		if (!is_duplicate) {
			temp_rates[unique_count++] = temp_rates[i];
		}
	}

	/* Allocate final array */
	stream->virtual_samplerates = (uint32_t *)malloc(unique_count * sizeof(uint32_t));
	if (!stream->virtual_samplerates) {
		free(temp_rates);
		return HYDRASDR_ERROR_NO_MEM;
	}

	memcpy(stream->virtual_samplerates, temp_rates, unique_count * sizeof(uint32_t));
	stream->virtual_samplerate_count = unique_count;
	free(temp_rates);

	return HYDRASDR_SUCCESS;
}

/*
 * Get current decimation factor (1, 2, 4, 8, 16, 32, or 64)
 */
int hydrasdr_generic_get_decimation_factor(struct hydrasdr_device* dev, uint32_t* factor)
{
	hydrasdr_streaming_t* stream;

	if (!dev || !dev->private_data || !factor) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	stream = (hydrasdr_streaming_t*)dev->private_data;
	*factor = stream->decimation_factor ? stream->decimation_factor : 1;
	return HYDRASDR_SUCCESS;
}

/*
 * Get current hardware sample rate (before decimation)
 */
int hydrasdr_generic_get_hardware_samplerate(struct hydrasdr_device* dev, uint32_t* rate)
{
	hydrasdr_streaming_t* stream;

	if (!dev || !dev->private_data || !rate) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	stream = (hydrasdr_streaming_t*)dev->private_data;
	*rate = stream->hardware_samplerate;
	return HYDRASDR_SUCCESS;
}

/*
 * Set decimation mode (LOW_BANDWIDTH or HIGH_DEFINITION)
 *
 * If a sample rate is already configured, re-apply it with the new mode
 * so that the decimation pipeline is immediately reconfigured.
 * On failure, the previous mode is restored for consistency.
 */
int hydrasdr_generic_set_decimation_mode(struct hydrasdr_device* dev, uint32_t mode)
{
	hydrasdr_streaming_t* stream;
	uint32_t prev_mode;
	int result;

	if (!dev || !dev->private_data) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	if (mode != HYDRASDR_DEC_MODE_LOW_BANDWIDTH &&
	    mode != HYDRASDR_DEC_MODE_HIGH_DEFINITION) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	stream = (hydrasdr_streaming_t*)dev->private_data;
	prev_mode = stream->decimation_mode;

	if (mode == prev_mode) {
		return HYDRASDR_SUCCESS;
	}

	stream->decimation_mode = mode;

	/* Re-apply current sample rate with new mode */
	if (stream->effective_samplerate > 0) {
		result = hydrasdr_generic_set_samplerate(dev,
						stream->effective_samplerate);
		if (result != HYDRASDR_SUCCESS) {
			stream->decimation_mode = prev_mode;
			return result;
		}
	}

	return HYDRASDR_SUCCESS;
}

/*
 * Get current decimation mode
 */
int hydrasdr_generic_get_decimation_mode(struct hydrasdr_device* dev, uint32_t* mode)
{
	hydrasdr_streaming_t* stream;

	if (!dev || !dev->private_data || !mode) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	stream = (hydrasdr_streaming_t*)dev->private_data;
	*mode = stream->decimation_mode;
	return HYDRASDR_SUCCESS;
}

/*
 * Helper to send hardware sample rate to device
 *
 * Request size depends on HYDRASDR_CAP_EXTENDED_SAMPLERATES capability:
 * - With capability: requests 4 bytes, ADC config from cached samplerate_info
 * - Without capability: requests 1 byte only (safe for old firmware)
 *
 * Old firmware assumes 12-bit RAW_ADC for backward compatibility.
 */
static int send_hw_samplerate(struct hydrasdr_device* dev,
			      hydrasdr_streaming_t* stream,
			      uint32_t hw_rate)
{
	int result;
	uint8_t response[4] = {0, IQCONV_ADC_12BIT, HYDRASDR_DATA_RAW_ADC, 0};
	uint32_t rate_param;
	uint32_t rate_idx = (uint32_t)-1;
	uint32_t i;
	uint16_t request_len;

	/* Convert rate to index or kHz format */
	rate_param = hw_rate;
	for (i = 0; i < stream->supported_samplerate_count; i++) {
		if (hw_rate == stream->supported_samplerates[i]) {
			rate_param = i;
			rate_idx = i;
			break;
		}
	}
	if (rate_param >= MIN_SAMPLERATE_BY_VALUE) {
		if (SAMPLE_TYPE_IS_IQ(stream->sample_type)) {
			rate_param *= 2;
		}
		rate_param /= 1000;
	}

	libusb_clear_halt(dev->usb_handle, LIBUSB_ENDPOINT_IN | 1);

	/*
	 * Request size depends on capability:
	 * - With HYDRASDR_CAP_EXTENDED_SAMPLERATES: 4 bytes (result + adc_bits + data_format + reserved)
	 * - Without capability: 1 byte (result only) - safe for old firmware
	 */
	request_len = stream->has_extended_samplerate_info ? 4 : 1;

	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_SAMPLERATE,
		0,
		rate_param,
		response,
		request_len,
		LIBUSB_CTRL_TIMEOUT_MS);

	if (result < 1) {
		return HYDRASDR_ERROR_LIBUSB;
	}

	/*
	 * Determine ADC configuration for this sample rate.
	 * With HYDRASDR_CAP_EXTENDED_SAMPLERATES: use cached info (already queried at init)
	 * Without capability: assume 12-bit RAW_ADC (backward compatible with old firmware)
	 */
	if (stream->has_extended_samplerate_info && rate_idx < stream->supported_samplerate_count) {
		/* Use cached extended info - most reliable */
		stream->adc_bits = stream->samplerate_info[rate_idx].adc_bits;
		stream->data_format = stream->samplerate_info[rate_idx].data_format;
	} else {
		/* Old firmware: assume 12-bit RAW_ADC (backward compatible) */
		stream->adc_bits = IQCONV_ADC_12BIT;
		stream->data_format = HYDRASDR_DATA_RAW_ADC;
	}

	/* Reinitialize LUT for actual ADC bits (only for RAW_ADC mode) */
	if (stream->data_format == HYDRASDR_DATA_RAW_ADC) {
		if (iqconv_lut_get_adc_bits() != stream->adc_bits) {
			iqconv_lut_init(stream->adc_bits);
		}
	}

	return HYDRASDR_SUCCESS;
}

/*
 * Recreate DDC instances with new decimation factor
 */
static int update_ddc_decimation(hydrasdr_streaming_t* stream, uint32_t decimation)
{
	iqconverter_t *new_cnv_f = NULL;
	iqconverter_t *new_cnv_i = NULL;
	const char *algo_f, *algo_i;

	/* Select algorithm based on decimation factor */
	if (decimation > 1) {
		algo_f = "float32_opt_dec";
		algo_i = "int16_opt_dec";
	} else {
		algo_f = "float32_opt";
		algo_i = "int16_opt";
	}

	/* Create new float32 DDC */
	new_cnv_f = iqconverter_create_ex(HB_KERNEL_FLOAT, HB_KERNEL_FLOAT_LEN,
					  algo_f, (iqconv_decimation_t)decimation);
	if (!new_cnv_f) {
		return HYDRASDR_ERROR_NO_MEM;
	}

	/* Create new int16 DDC */
	new_cnv_i = iqconverter_create_ex(HB_KERNEL_INT16, HB_KERNEL_INT16_LEN,
					  algo_i, (iqconv_decimation_t)decimation);
	if (!new_cnv_i) {
		iqconverter_free(new_cnv_f);
		return HYDRASDR_ERROR_NO_MEM;
	}

	/* Replace old converters */
	iqconverter_free(stream->cnv_f);
	iqconverter_free(stream->cnv_i);
	stream->cnv_f = new_cnv_f;
	stream->cnv_i = new_cnv_i;

	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_samplerate(struct hydrasdr_device* dev, uint32_t samplerate)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	uint32_t hw_rate = 0;
	uint32_t decimation = 1;
	uint32_t i;
	int result;
	uint32_t *rates;
	uint32_t rate_count;

	/* Reject extreme invalid values */
	if (samplerate > MAX_SAMPLERATE_HZ) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	/*
	 * Determine which rate table to use for index lookups.
	 * Use virtual rates if available (for IQ mode), otherwise hardware rates.
	 */
	if (stream->virtual_samplerates && stream->virtual_samplerate_count > 0 &&
	    SAMPLE_TYPE_IS_IQ(stream->sample_type)) {
		rates = stream->virtual_samplerates;
		rate_count = stream->virtual_samplerate_count;
	} else {
		rates = stream->supported_samplerates;
		rate_count = stream->supported_samplerate_count;
	}

	/*
	 * Sample rate interpretation logic:
	 * 1. First check if value matches any supported rate exactly (incl. virtual rates)
	 * 2. If not, and value is small (< rate_count), treat as index
	 * 3. Otherwise, pass through as Hz value for find_optimal_rate_config
	 */
	{
		int is_direct_rate = 0;

		/* Check if value matches any rate in the table */
		for (i = 0; i < rate_count; i++) {
			if (samplerate == rates[i]) {
				is_direct_rate = 1;
				break;
			}
		}

		if (!is_direct_rate && samplerate < rate_count) {
			/* Small value not matching any rate - treat as index */
			samplerate = rates[samplerate];
		}
		/* Otherwise samplerate is already a Hz value */
	}

	/*
	 * Now samplerate is always a value (Hz).
	 * Strategy depends on decimation_mode:
	 * - HIGH_DEFINITION: Always find highest HW rate for maximum oversampling
	 * - LOW_BANDWIDTH: Prefer direct HW rate if available (no decimation)
	 */
	{
		int found = 0;

		if (stream->decimation_mode == HYDRASDR_DEC_MODE_HIGH_DEFINITION) {
			/*
			 * HIGH_DEFINITION mode: Always use find_optimal_rate_config
			 * to find the highest HW rate that can achieve the desired rate.
			 */
			if (find_optimal_rate_config(stream, samplerate,
						     &hw_rate, &decimation) == 0) {
				found = 1;
			}
		} else {
			/*
			 * LOW_BANDWIDTH mode (default): Check direct hardware rates
			 * first to avoid unnecessary decimation overhead.
			 */
			for (i = 0; i < stream->supported_samplerate_count; i++) {
				if (samplerate == stream->supported_samplerates[i]) {
					hw_rate = samplerate;
					decimation = 1;
					found = 1;
					break;
				}
			}
		}

		/* If not found yet, find optimal HW rate + decimation */
		if (!found) {
			if (find_optimal_rate_config(stream, samplerate,
						     &hw_rate, &decimation) == 0) {
				found = 1;
			}
		}

		if (!found) {
			/* Let firmware decide (alt config rates) */
			hw_rate = samplerate;
			decimation = 1;
		}
	}

	/* Update decimation if needed */
	if (decimation != stream->decimation_factor) {
		result = update_ddc_decimation(stream, decimation);
		if (result != HYDRASDR_SUCCESS) {
			return result;
		}
	}

	/* Send hardware rate to device */
	result = send_hw_samplerate(dev, stream, hw_rate);
	if (result != HYDRASDR_SUCCESS) {
		return result;
	}

	/* Update state */
	stream->hardware_samplerate = hw_rate;
	stream->effective_samplerate = samplerate;
	stream->decimation_factor = decimation;

	/*
	 * Note: ADC bit depth and data format are now automatically set by
	 * send_hw_samplerate() based on firmware response. This is fully
	 * transparent to the API user and supports future ADC variants.
	 */

	/*
	 * Auto-bandwidth selection: if bandwidth was not explicitly set by user,
	 * automatically select the smallest bandwidth >= hardware sample rate.
	 * This ensures proper anti-aliasing filtering without excessive noise.
	 *
	 * IMPORTANT: Use hardware sample rate (not effective rate) because the
	 * RF frontend filter is applied BEFORE decimation. The filter must cover
	 * the full ADC bandwidth to avoid aliasing artifacts.
	 *
	 * NOTE: We store the pending bandwidth here but don't send to FW yet.
	 * The FW sets a default bandwidth when streaming starts, so we need to
	 * apply our auto-selected bandwidth AFTER streaming starts (in start_rx).
	 */
	if (!stream->bandwidth_explicitly_set && stream->supported_bandwidth_count > 0) {
		uint32_t best_bw = 0;
		uint32_t best_bw_idx = 0;

		/* Find smallest bandwidth >= hardware_samplerate */
		for (i = 0; i < stream->supported_bandwidth_count; i++) {
			uint32_t bw = stream->supported_bandwidths[i];
			if (bw >= hw_rate) {
				if (best_bw == 0 || bw < best_bw) {
					best_bw = bw;
					best_bw_idx = i;
				}
			}
		}

		/* Store pending auto-bandwidth to apply after streaming starts */
		if (best_bw > 0) {
			/* Store index+1 so that 0 means "no pending bandwidth" */
			stream->pending_auto_bandwidth_idx = best_bw_idx + 1;
			stream->pending_auto_bandwidth_hz = best_bw;
		} else {
			stream->pending_auto_bandwidth_idx = 0;
			stream->pending_auto_bandwidth_hz = 0;
		}
	}

	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_get_samplerates(struct hydrasdr_device* dev, uint32_t* buffer, const uint32_t len)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	uint32_t *rates;
	uint32_t count;
	uint32_t i;

	/*
	 * Return virtual rates (with decimation) if available and IQ mode,
	 * otherwise fall back to hardware rates (backward compatible).
	 */
	if (stream->virtual_samplerates && stream->virtual_samplerate_count > 0 &&
	    SAMPLE_TYPE_IS_IQ(stream->sample_type)) {
		rates = stream->virtual_samplerates;
		count = stream->virtual_samplerate_count;
	} else {
		rates = stream->supported_samplerates;
		count = stream->supported_samplerate_count;
	}

	if (len == 0) {
		*buffer = count;
	} else if (len <= count) {
		memcpy(buffer, rates, len * sizeof(uint32_t));

		/*
		 * For non-IQ mode (raw samples), rates need to be doubled since
		 * DDC produces I+Q pairs from each sample. Virtual rates are
		 * already the effective output rates and don't need adjustment.
		 */
		if (!SAMPLE_TYPE_IS_IQ(stream->sample_type)) {
			for (i = 0; i < len; i++) {
				buffer[i] *= 2;
			}
		}
	} else {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_bandwidth(struct hydrasdr_device* dev, uint32_t bandwidth)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;
	uint8_t retval;
	uint8_t length;
	uint32_t i;
	uint32_t original_bandwidth = bandwidth; /* Save for tracking */

	/* If no bandwidths supported, return unsupported */
	if (stream->supported_bandwidth_count == 0) {
		return HYDRASDR_ERROR_UNSUPPORTED;
	}

	/*
	 * Special case: HYDRASDR_BANDWIDTH_AUTO means "reset to auto mode".
	 * This allows applications to switch back to auto-bandwidth selection
	 * after previously setting an explicit bandwidth.
	 */
	if (bandwidth == HYDRASDR_BANDWIDTH_AUTO) {
		stream->bandwidth_explicitly_set = false;
		stream->current_bandwidth = 0;
		stream->pending_auto_bandwidth_idx = 0;
		stream->pending_auto_bandwidth_hz = 0;

		/*
		 * If we have a hardware sample rate, calculate auto-bandwidth now.
		 * This handles the case where user switches to "Auto" while streaming.
		 * Use hardware rate (not effective) because RF filter is before decimation.
		 */
		if (stream->hardware_samplerate > 0 && stream->supported_bandwidth_count > 0) {
			uint32_t best_bw = 0;
			uint32_t best_bw_idx = 0;
			uint32_t j;

			/* Find smallest bandwidth >= hardware_samplerate */
			for (j = 0; j < stream->supported_bandwidth_count; j++) {
				uint32_t bw = stream->supported_bandwidths[j];
				if (bw >= stream->hardware_samplerate) {
					if (best_bw == 0 || bw < best_bw) {
						best_bw = bw;
						best_bw_idx = j;
					}
				}
			}

			/* Apply auto-bandwidth immediately if streaming */
			if (best_bw > 0) {
				libusb_clear_halt(dev->usb_handle, LIBUSB_ENDPOINT_IN | 1);
				libusb_control_transfer(
					dev->usb_handle,
					((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
					HYDRASDR_SET_BANDWIDTH,
					0,
					best_bw_idx,
					&retval,
					1,
					LIBUSB_CTRL_TIMEOUT_MS);
				stream->current_bandwidth = best_bw;
			}
		}
		return HYDRASDR_SUCCESS;
	}

	/* Mark that bandwidth was explicitly set by user (disables auto-selection) */
	stream->bandwidth_explicitly_set = true;
	/* Clear any pending auto-bandwidth since user explicitly set bandwidth */
	stream->pending_auto_bandwidth_idx = 0;
	stream->pending_auto_bandwidth_hz = 0;

	/* Reject extreme invalid values */
	if (bandwidth > MAX_SAMPLERATE_HZ) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	/* If bandwidth is a direct value (>= 1kHz), try to find in list or convert */
	if (bandwidth >= MIN_BANDWIDTH_BY_VALUE) {
		for (i = 0; i < stream->supported_bandwidth_count; i++) {
			if (bandwidth == stream->supported_bandwidths[i]) {
				/* Found in list - save as Hz value, convert to index for USB */
				original_bandwidth = bandwidth;
				bandwidth = i;
				break;
			}
		}
		/* If not found in list, convert to kHz and let firmware handle it */
		if (bandwidth >= MIN_BANDWIDTH_BY_VALUE) {
			original_bandwidth = bandwidth; /* Keep as Hz */
			bandwidth /= (MIN_BANDWIDTH_BY_VALUE);
		}
	} else {
		/* Index-based - look up actual Hz value */
		if (bandwidth < stream->supported_bandwidth_count) {
			original_bandwidth = stream->supported_bandwidths[bandwidth];
		} else {
			/* Invalid index - exceeds available bandwidth count */
			return HYDRASDR_ERROR_INVALID_PARAM;
		}
	}

	libusb_clear_halt(dev->usb_handle, LIBUSB_ENDPOINT_IN | 1);
	length = 1;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_BANDWIDTH,
		0,
		bandwidth,
		&retval,
		length,
		LIBUSB_CTRL_TIMEOUT_MS);

	if (result < length) {
		return HYDRASDR_ERROR_LIBUSB;
	}

	/* Track the current bandwidth value in Hz */
	stream->current_bandwidth = original_bandwidth;

	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_get_bandwidths(struct hydrasdr_device* dev, uint32_t* buffer, const uint32_t len)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;

	/* If no bandwidths supported, return unsupported */
	if (stream->supported_bandwidth_count == 0) {
		return HYDRASDR_ERROR_UNSUPPORTED;
	}

	if (len == 0) {
		*buffer = stream->supported_bandwidth_count;
	} else if (len <= stream->supported_bandwidth_count) {
		memcpy(buffer, stream->supported_bandwidths, len * sizeof(uint32_t));
	} else {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_rf_bias(struct hydrasdr_device* dev, uint8_t value)
{
	int result;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_RF_BIAS_CMD, 0, value, NULL, 0, LIBUSB_CTRL_TIMEOUT_MS);
	if (result != 0) {
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_packing(struct hydrasdr_device* dev, uint8_t value)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;
	uint8_t retval;
	bool packing_enabled;

	if (OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.streaming)) {
		return HYDRASDR_ERROR_BUSY;
	}

	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_PACKING, 0, value, &retval, 1, LIBUSB_CTRL_TIMEOUT_MS);
	if (result < 1) {
		return HYDRASDR_ERROR_LIBUSB;
	}

	packing_enabled = (value == 1);
	if (packing_enabled != stream->packing_enabled) {
		hydrasdr_cancel_transfers(stream);
		hydrasdr_free_transfers(stream);
		stream->packing_enabled = packing_enabled;
		stream->buffer_size = packing_enabled ? PACKED_BUFFER_SIZE : DEFAULT_BUFFER_SIZE;
		result = hydrasdr_alloc_transfers(stream);
		if (result != HYDRASDR_SUCCESS) {
			return HYDRASDR_ERROR_NO_MEM;
		}
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_rf_port(struct hydrasdr_device* dev, hydrasdr_rf_port_t rf_port)
{
	int result;
	uint8_t retval;

	/*
	 * Send RF port selection to firmware - let firmware validate.
	 * Firmware returns 1 for success, USB_REQUEST_STATUS_STALL for error.
	 * This keeps the generic function hardware-agnostic.
	 */
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_RF_PORT, 0, (uint8_t)rf_port, &retval, 1, LIBUSB_CTRL_TIMEOUT_MS);
	if (result < 1) {
		return HYDRASDR_ERROR_LIBUSB;
	}
	if (retval != 1) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_reset(struct hydrasdr_device* dev)
{
	uint8_t retval;
	libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_RESET, 0, 0, &retval, 1, LIBUSB_CTRL_TIMEOUT_MS);
	dev->reset_command = true;
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_sample_type(struct hydrasdr_device* dev, enum hydrasdr_sample_type sample_type)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;

	/* Validate sample_type is within valid range */
	if ((int)sample_type < 0 || sample_type >= HYDRASDR_SAMPLE_END) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	stream->sample_type = sample_type;
	dev->sample_type = sample_type;
	return HYDRASDR_SUCCESS;
}

/* Generic board information functions */

int hydrasdr_generic_board_id_read(struct hydrasdr_device* dev, uint8_t* value)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;

	/* Return cached value if available */
	if (stream && stream->device_info_cached) {
		*value = stream->cached_board_id;
		return HYDRASDR_SUCCESS;
	}

	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_BOARD_ID_READ,
		0,
		0,
		value,
		1,
		LIBUSB_CTRL_TIMEOUT_MS);

	if (result < 1) {
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_version_string_read(struct hydrasdr_device* dev, char* version, uint8_t length)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;
	char version_local[VERSION_STRING_SIZE] = "";

	/* Return cached value if available */
	if (stream && stream->device_info_cached) {
		if (length > 0) {
			const int num_bytes_to_copy = (length > VERSION_STRING_SIZE ? VERSION_STRING_SIZE : length) - 1;
			memcpy(version, stream->cached_version_string, num_bytes_to_copy);
			version[num_bytes_to_copy] = 0;
			return HYDRASDR_SUCCESS;
		}
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_VERSION_STRING_READ,
		0,
		0,
		(unsigned char*)version_local,
		(VERSION_STRING_SIZE - 1),
		LIBUSB_CTRL_TIMEOUT_MS);

	if (result < 0) {
		return HYDRASDR_ERROR_LIBUSB;
	}

	if (length > 0) {
		const int num_bytes_to_copy = (length > VERSION_STRING_SIZE ? VERSION_STRING_SIZE : length) - 1;
		memcpy(version, version_local, num_bytes_to_copy);
		version[num_bytes_to_copy] = 0;
		return HYDRASDR_SUCCESS;
	}
	return HYDRASDR_ERROR_INVALID_PARAM;
}

int hydrasdr_generic_board_partid_serialno_read(struct hydrasdr_device* dev, hydrasdr_read_partid_serialno_t* read_partid_serialno)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	uint8_t length;
	int result;

	/* Return cached value if available */
	if (stream && stream->device_info_cached) {
		memcpy(read_partid_serialno, &stream->cached_part_serial, sizeof(hydrasdr_read_partid_serialno_t));
		return HYDRASDR_SUCCESS;
	}

	length = sizeof(hydrasdr_read_partid_serialno_t);
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_BOARD_PARTID_SERIALNO_READ,
		0,
		0,
		(unsigned char*)read_partid_serialno,
		length,
		LIBUSB_CTRL_TIMEOUT_MS);

	if (result < length) {
		return HYDRASDR_ERROR_LIBUSB;
	}

	/* Convert to little endian */
	read_partid_serialno->part_id[0] = TO_LE_32(read_partid_serialno->part_id[0]);
	read_partid_serialno->part_id[1] = TO_LE_32(read_partid_serialno->part_id[1]);
	read_partid_serialno->serial_no[0] = TO_LE_32(read_partid_serialno->serial_no[0]);
	read_partid_serialno->serial_no[1] = TO_LE_32(read_partid_serialno->serial_no[1]);
	read_partid_serialno->serial_no[2] = TO_LE_32(read_partid_serialno->serial_no[2]);
	read_partid_serialno->serial_no[3] = TO_LE_32(read_partid_serialno->serial_no[3]);

	return HYDRASDR_SUCCESS;
}

/* Generic capability functions */

int hydrasdr_generic_get_capabilities(struct hydrasdr_device* dev, uint32_t* features, uint32_t* features_reserved, uint32_t fallback_caps)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;
	uint32_t i;

	/* Return cached value if available */
	if (stream && stream->device_info_cached) {
		*features = stream->cached_features;
		for (i = 0; i < 3; i++) {
			features_reserved[i] = stream->cached_features_reserved[i];
		}
		return HYDRASDR_SUCCESS;
	}

	/* Try to get word 0 (primary features) from firmware */
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_GET_CAPABILITIES,
		0,
		0,  /* wIndex = 0 for primary features */
		(unsigned char*)features,
		(uint16_t)sizeof(uint32_t),
		LIBUSB_CTRL_TIMEOUT_MS);

	if (result != (int)sizeof(uint32_t)) {
		/* Fallback for old firmware without HYDRASDR_GET_CAPABILITIES */
		*features = fallback_caps;
		for (i = 0; i < 3; i++) {
			features_reserved[i] = 0;
		}
		return HYDRASDR_SUCCESS;
	}

	/* Try to get extended capability words (wIndex 1-3) */
	for (i = 0; i < 3; i++) {
		result = libusb_control_transfer(
			dev->usb_handle,
			((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
			HYDRASDR_GET_CAPABILITIES,
			0,
			(uint16_t)(i + 1),  /* wIndex 1, 2, 3 for extended caps */
			(unsigned char*)&features_reserved[i],
			(uint16_t)sizeof(uint32_t),
			LIBUSB_CTRL_TIMEOUT_MS);

		if (result != (int)sizeof(uint32_t)) {
			/* Word not supported, set to 0 */
			features_reserved[i] = 0;
		}
	}

	return HYDRASDR_SUCCESS;
}

/* Generic gain control functions */

int hydrasdr_generic_set_lna_gain(struct hydrasdr_device* dev, uint8_t value, uint8_t max_value)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;
	uint8_t retval;
	uint8_t length;

	if (value > max_value)
		value = max_value;

	length = 1;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_LNA_GAIN, 0, value, &retval, length, LIBUSB_CTRL_TIMEOUT_MS);

	if (result < length) {
		return HYDRASDR_ERROR_LIBUSB;
	}

	update_gain_cache_value(stream, HYDRASDR_GAIN_TYPE_LNA, value, max_value);
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_mixer_gain(struct hydrasdr_device* dev, uint8_t value, uint8_t max_value)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;
	uint8_t retval;
	uint8_t length;

	if (value > max_value)
		value = max_value;

	length = 1;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_MIXER_GAIN, 0, value, &retval, length, LIBUSB_CTRL_TIMEOUT_MS);

	if (result < length) {
		return HYDRASDR_ERROR_LIBUSB;
	}

	update_gain_cache_value(stream, HYDRASDR_GAIN_TYPE_MIXER, value, max_value);
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_vga_gain(struct hydrasdr_device* dev, uint8_t value, uint8_t max_value)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;
	uint8_t retval;
	uint8_t length;

	if (value > max_value)
		value = max_value;

	length = 1;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_VGA_GAIN, 0, value, &retval, length, LIBUSB_CTRL_TIMEOUT_MS);

	if (result < length) {
		return HYDRASDR_ERROR_LIBUSB;
	}

	update_gain_cache_value(stream, HYDRASDR_GAIN_TYPE_VGA, value, max_value);
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_lna_agc(struct hydrasdr_device* dev, uint8_t value)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;
	uint8_t retval;
	uint8_t length;

	length = 1;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_LNA_AGC, 0, value, &retval, length, LIBUSB_CTRL_TIMEOUT_MS);
	if (result < length) {
		return HYDRASDR_ERROR_LIBUSB;
	}

	update_gain_cache_value(stream, HYDRASDR_GAIN_TYPE_LNA_AGC, value, 1);
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_mixer_agc(struct hydrasdr_device* dev, uint8_t value)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;
	uint8_t retval;
	uint8_t length;

	length = 1;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SET_MIXER_AGC, 0, value, &retval, length, LIBUSB_CTRL_TIMEOUT_MS);
	if (result < length) {
		return HYDRASDR_ERROR_LIBUSB;
	}

	update_gain_cache_value(stream, HYDRASDR_GAIN_TYPE_MIXER_AGC, value, 1);
	return HYDRASDR_SUCCESS;
}

/* ========================================================================
 * Unified Gain Control Functions (HYDRASDR_CAP_EXTENDED_GAIN)
 *
 * These functions provide a single interface for all gain types:
 * - Manual gains (LNA, RF, Mixer, Filter, VGA)
 * - Composite presets (Linearity, Sensitivity)
 * - AGC enables (LNA_AGC, RF_AGC, Mixer_AGC, Filter_AGC)
 *
 * Gain values are cached on the host side when set_gain() is called.
 * The get_gain() functions return cached values (no USB query).
 *
 * When HYDRASDR_CAP_EXTENDED_GAIN is supported by firmware, uses the
 * unified HYDRASDR_SET_GAIN command. Otherwise falls back to legacy
 * per-gain commands for backward compatibility.
 * ======================================================================== */

int hydrasdr_generic_get_gain(struct hydrasdr_device* dev, hydrasdr_gain_type_t type, hydrasdr_gain_info_t* info)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;

	if (!info) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	if (type >= HYDRASDR_GAIN_TYPE_COUNT) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	/*
	 * Return cached gain info instead of querying hardware.
	 * The cache is populated when hydrasdr_generic_set_gain_info() is called.
	 */
	if (stream && stream->gains_cached[type]) {
		memcpy(info, &stream->cached_gain_info[type], sizeof(hydrasdr_gain_info_t));
		return HYDRASDR_SUCCESS;
	}

	/* Gain not cached - user must call set_gain_info first */
	return HYDRASDR_ERROR_UNSUPPORTED;
}

int hydrasdr_generic_set_gain_info(struct hydrasdr_device* dev, hydrasdr_gain_type_t type, const hydrasdr_gain_info_t* info)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;

	if (!info) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	if (type >= HYDRASDR_GAIN_TYPE_COUNT) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	if (!stream) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	/* Cache the full gain info */
	memcpy(&stream->cached_gain_info[type], info, sizeof(hydrasdr_gain_info_t));
	stream->cached_gain_info[type].type = (uint8_t)type; /* Ensure type field is correct */
	stream->gains_cached[type] = true;

	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_gain(struct hydrasdr_device* dev, hydrasdr_gain_type_t type, uint8_t value)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	int result;
	uint8_t response;
	int ret = HYDRASDR_ERROR_INVALID_PARAM;

	if (type >= HYDRASDR_GAIN_TYPE_COUNT) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	/* Check if unified gain API is supported */
	if (stream && stream->device_info_cached &&
	    (stream->cached_features & HYDRASDR_CAP_EXTENDED_GAIN)) {
		/*
		 * Use unified command: SET_GAIN
		 * wValue = gain type
		 * wIndex = value to set
		 * Returns 1-byte status
		 */
		result = libusb_control_transfer(
			dev->usb_handle,
			((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
			HYDRASDR_SET_GAIN,
			(uint16_t)type,
			(uint16_t)value,
			&response,
			1,
			LIBUSB_CTRL_TIMEOUT_MS);

		if (result < 1) {
			return HYDRASDR_ERROR_LIBUSB;
		}
		ret = HYDRASDR_SUCCESS;
	} else {
		/*
		 * Fallback: use legacy per-gain commands for backward compatibility.
		 * Note: Legacy commands don't have range checking here - device-specific
		 * HAL functions do that with their max_value parameters.
		 */
		switch (type) {
		case HYDRASDR_GAIN_TYPE_LNA:
			ret = libusb_control_transfer(
				dev->usb_handle,
				((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
				HYDRASDR_SET_LNA_GAIN, 0, value, &response, 1, LIBUSB_CTRL_TIMEOUT_MS) < 1
				? HYDRASDR_ERROR_LIBUSB : HYDRASDR_SUCCESS;
			break;

		case HYDRASDR_GAIN_TYPE_MIXER:
			ret = libusb_control_transfer(
				dev->usb_handle,
				((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
				HYDRASDR_SET_MIXER_GAIN, 0, value, &response, 1, LIBUSB_CTRL_TIMEOUT_MS) < 1
				? HYDRASDR_ERROR_LIBUSB : HYDRASDR_SUCCESS;
			break;

		case HYDRASDR_GAIN_TYPE_VGA:
			ret = libusb_control_transfer(
				dev->usb_handle,
				((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
				HYDRASDR_SET_VGA_GAIN, 0, value, &response, 1, LIBUSB_CTRL_TIMEOUT_MS) < 1
				? HYDRASDR_ERROR_LIBUSB : HYDRASDR_SUCCESS;
			break;

		case HYDRASDR_GAIN_TYPE_LNA_AGC:
			ret = libusb_control_transfer(
				dev->usb_handle,
				((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
				HYDRASDR_SET_LNA_AGC, 0, value, &response, 1, LIBUSB_CTRL_TIMEOUT_MS) < 1
				? HYDRASDR_ERROR_LIBUSB : HYDRASDR_SUCCESS;
			break;

		case HYDRASDR_GAIN_TYPE_MIXER_AGC:
			ret = libusb_control_transfer(
				dev->usb_handle,
				((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
				HYDRASDR_SET_MIXER_AGC, 0, value, &response, 1, LIBUSB_CTRL_TIMEOUT_MS) < 1
				? HYDRASDR_ERROR_LIBUSB : HYDRASDR_SUCCESS;
			break;

		case HYDRASDR_GAIN_TYPE_RF:
		case HYDRASDR_GAIN_TYPE_FILTER:
		case HYDRASDR_GAIN_TYPE_RF_AGC:
		case HYDRASDR_GAIN_TYPE_FILTER_AGC:
			/* No legacy command for these types */
			ret = HYDRASDR_ERROR_UNSUPPORTED;
			break;

		case HYDRASDR_GAIN_TYPE_LINEARITY:
			/* Dispatch to device HAL for composite gain mode */
			if (dev->hal && dev->hal->set_linearity_gain)
				ret = dev->hal->set_linearity_gain(dev, value);
			else
				ret = HYDRASDR_ERROR_UNSUPPORTED;
			break;

		case HYDRASDR_GAIN_TYPE_SENSITIVITY:
			/* Dispatch to device HAL for composite gain mode */
			if (dev->hal && dev->hal->set_sensitivity_gain)
				ret = dev->hal->set_sensitivity_gain(dev, value);
			else
				ret = HYDRASDR_ERROR_UNSUPPORTED;
			break;

		default:
			ret = HYDRASDR_ERROR_INVALID_PARAM;
			break;
		}
	}

	/* Update cached gain value on success */
	if (ret == HYDRASDR_SUCCESS && stream) {
		stream->cached_gain_info[type].value = value;
		/* If not previously cached, initialize type field */
		if (!stream->gains_cached[type]) {
			stream->cached_gain_info[type].type = (uint8_t)type;
			stream->gains_cached[type] = true;
		}
	}

	return ret;
}

int hydrasdr_generic_get_all_gains(struct hydrasdr_device* dev, hydrasdr_gain_info_t* gains, uint8_t* count)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	uint8_t max_gains;
	uint8_t i;
	uint8_t n = 0;

	if (!gains || !count) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	max_gains = *count;
	if (max_gains == 0) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	/*
	 * Return cached gain info instead of querying hardware.
	 * The cache is populated when hydrasdr_generic_set_gain_info() is called.
	 */
	if (!stream) {
		*count = 0;
		return HYDRASDR_ERROR_UNSUPPORTED;
	}

	for (i = 0; i < HYDRASDR_GAIN_TYPE_COUNT && n < max_gains; i++) {
		if (stream->gains_cached[i]) {
			memcpy(&gains[n], &stream->cached_gain_info[i], sizeof(hydrasdr_gain_info_t));
			n++;
		}
	}

	*count = n;
	return (n > 0) ? HYDRASDR_SUCCESS : HYDRASDR_ERROR_UNSUPPORTED;
}

/* Generic conversion filter management */

int hydrasdr_generic_set_conversion_filter_float32(struct hydrasdr_device* dev, const float* kernel, const uint32_t len)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	const char *algo;

	/* Validate parameters - iqconverter_create() requires valid kernel and specific lengths */
	if (!kernel) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	/* Valid halfband filter lengths: 33, 47, 65, 83 taps */
	if (!IQCONV_VALID_TAPS(len)) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	if (OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.streaming)) {
		return HYDRASDR_ERROR_BUSY;
	}

	/* Preserve decimation: use decimation algorithm if decimation > 1 */
	algo = (stream->decimation_factor > 1) ? "float32_opt_dec" : "float32_opt";

	iqconverter_free(stream->cnv_f);
	stream->cnv_f = iqconverter_create_ex(kernel, len, algo,
					      (iqconv_decimation_t)stream->decimation_factor);
	if (!stream->cnv_f) {
		return HYDRASDR_ERROR_NO_MEM;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_set_conversion_filter_int16(struct hydrasdr_device* dev, const int16_t* kernel, const uint32_t len)
{
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
	const char *algo;

	/* Validate parameters - iqconverter_create() requires valid kernel and specific lengths */
	if (!kernel) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	/* Valid halfband filter lengths: 33, 47, 65, 83 taps */
	if (!IQCONV_VALID_TAPS(len)) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	if (OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.streaming)) {
		return HYDRASDR_ERROR_BUSY;
	}

	/* Preserve decimation: use decimation algorithm if decimation > 1 */
	algo = (stream->decimation_factor > 1) ? "int16_opt_dec" : "int16_opt";

	iqconverter_free(stream->cnv_i);
	stream->cnv_i = iqconverter_create_ex(kernel, len, algo,
					      (iqconv_decimation_t)stream->decimation_factor);
	if (!stream->cnv_i) {
		return HYDRASDR_ERROR_NO_MEM;
	}
	return HYDRASDR_SUCCESS;
}

/*
 * Algorithm configuration table
 * Maps user-facing algorithm names to filter coefficients and internal algorithm names
 */
typedef struct {
	const char *name;		/* User-facing name (case-insensitive) */
	const char *description;	/* Human-readable description */
	const float *kernel_f;		/* Float32 filter coefficients */
	const int16_t *kernel_i;	/* Int16 filter coefficients */
	int len;			/* Filter length (taps) */
	const char *algo_f;		/* Internal float32 algorithm name */
	const char *algo_i;		/* Internal int16 algorithm name */
	int is_alias;			/* Non-zero if this is an alias (not listed) */
} conversion_algorithm_config_t;

static const conversion_algorithm_config_t algorithm_configs[] = {
	{
		"47_opt", "47-Tap Optimized (~63 dB)",
		HB_KERNEL_FLOAT_47_OPT, HB_KERNEL_INT16_47_OPT,
		HB_KERNEL_FLOAT_LEN_47_OPT,
		"float32_opt", "int16_opt", 0
	},
	{
		"33_fast", "33-Tap Fast, low latency (~62 dB)",
		HB_KERNEL_FLOAT_33_FAST, HB_KERNEL_INT16_33_FAST,
		HB_KERNEL_FLOAT_LEN_33_FAST,
		"float32_opt33", "int16_opt33", 0
	},
	{
		"65_pow2", "65-Tap Power-of-2 delay (~66 dB)",
		HB_KERNEL_FLOAT_65_POW2, HB_KERNEL_INT16_65_POW2,
		HB_KERNEL_FLOAT_LEN_65_POW2,
		"float32_opt65", "int16_opt65", 0
	},
	{
		"83_highperf", "83-Tap High Performance (>100 dB)",
		HB_KERNEL_FLOAT_83_HIGHPERF, HB_KERNEL_INT16_83_HIGHPERF,
		HB_KERNEL_FLOAT_LEN_83_HIGHPERF,
		"float32_opt83", "int16_opt83", 0
	},
	{
		"legacy", "47-Tap Legacy Airspy (~63 dB)",
		HB_KERNEL_FLOAT_47_OPT, HB_KERNEL_INT16_47_OPT,
		HB_KERNEL_FLOAT_LEN_47_OPT,
		"float32_legacy", "int16_legacy", 0
	},
	/* Aliases - not listed but still usable */
	{
		"default", "Default (47-Tap Optimized)",
		HB_KERNEL_FLOAT_47_OPT, HB_KERNEL_INT16_47_OPT,
		HB_KERNEL_FLOAT_LEN_47_OPT,
		"float32_opt", "int16_opt", 1
	},
	{ NULL, NULL, NULL, NULL, 0, NULL, NULL, 0 } /* Sentinel */
};

/*
 * List available high-level DDC conversion algorithms
 */
int hydrasdr_generic_list_conversion_algorithms(const char **names,
						const char **descriptions,
						int max)
{
	int count = 0;
	int i;

	/* Count non-alias algorithms */
	for (i = 0; algorithm_configs[i].name != NULL; i++) {
		if (!algorithm_configs[i].is_alias) {
			if (names && count < max) {
				names[count] = algorithm_configs[i].name;
			}
			if (descriptions && count < max) {
				descriptions[count] = algorithm_configs[i].description;
			}
			count++;
		}
	}

	return count;
}

int hydrasdr_generic_set_conversion_algorithm(struct hydrasdr_device* dev, const char* algorithm)
{
	hydrasdr_streaming_t* stream;
	const conversion_algorithm_config_t *config;
	iqconverter_t *new_cnv_f, *new_cnv_i;
	const char *algo_f, *algo_i;

	if (!dev || !dev->private_data) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	stream = (hydrasdr_streaming_t*)dev->private_data;

	if (OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.streaming)) {
		return HYDRASDR_ERROR_BUSY;
	}

	/* NULL or empty string means default */
	if (!algorithm || algorithm[0] == '\0') {
		algorithm = "default";
	}

	/* Find matching algorithm configuration */
	config = NULL;
	for (int i = 0; algorithm_configs[i].name != NULL; i++) {
		if (opt_strcasecmp(algorithm, algorithm_configs[i].name) == 0) {
			config = &algorithm_configs[i];
			break;
		}
	}

	if (!config) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	/*
	 * Preserve decimation: when decimation > 1, use decimation-aware
	 * algorithms instead of the base algorithms from config table.
	 * The decimation algorithms use the same filter coefficients.
	 */
	if (stream->decimation_factor > 1) {
		algo_f = "float32_opt_dec";
		algo_i = "int16_opt_dec";
	} else {
		algo_f = config->algo_f;
		algo_i = config->algo_i;
	}

	/* Create new converters with matched filter + algorithm */
	new_cnv_f = iqconverter_create_ex(config->kernel_f, config->len, algo_f,
					  (iqconv_decimation_t)stream->decimation_factor);
	if (!new_cnv_f) {
		return HYDRASDR_ERROR_NO_MEM;
	}

	new_cnv_i = iqconverter_create_ex(config->kernel_i, config->len, algo_i,
					  (iqconv_decimation_t)stream->decimation_factor);
	if (!new_cnv_i) {
		iqconverter_free(new_cnv_f);
		return HYDRASDR_ERROR_NO_MEM;
	}

	/* Replace old converters atomically */
	iqconverter_free(stream->cnv_f);
	iqconverter_free(stream->cnv_i);
	stream->cnv_f = new_cnv_f;
	stream->cnv_i = new_cnv_i;

	return HYDRASDR_SUCCESS;
}

/* ===================================
 * Generic Device-Specific Operations
 * =================================== */

int hydrasdr_generic_set_receiver_mode(struct hydrasdr_device* dev, hydrasdr_receiver_mode_t value)
{
	int result;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_RECEIVER_MODE,
		value,
		0,
		NULL,
		0,
		LIBUSB_CTRL_TIMEOUT_MS);

	if (result != 0) {
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_device_init(struct hydrasdr_device* dev,
	uint32_t transfer_count,
	uint32_t buffer_size,
	uint8_t enable_packing_default,
	uint32_t fallback_caps)
{
	int result;

	result = hydrasdr_generic_init_streaming(dev,
		transfer_count,
		buffer_size,
		HYDRASDR_SAMPLE_FLOAT32_IQ,
		fallback_caps);

	if (result != HYDRASDR_SUCCESS) {
		return result;
	}

	result = hydrasdr_generic_set_packing(dev, enable_packing_default);
	if (result != HYDRASDR_SUCCESS) {
		hydrasdr_generic_cleanup_streaming(dev);
		return result;
	}

	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_device_exit(struct hydrasdr_device* dev)
{
	/* Turn off receiver mode first (controls LED and hardware state) */
	hydrasdr_generic_set_receiver_mode(dev, HYDRASDR_RECEIVER_MODE_OFF);
	/* Turn off Bias-T for safety (prevent power on antenna port) */
	hydrasdr_generic_set_rf_bias(dev, 0);
	hydrasdr_stop_streaming(dev);
	return hydrasdr_generic_cleanup_streaming(dev);
}

int hydrasdr_generic_start_rx(struct hydrasdr_device* dev,
	hydrasdr_sample_block_cb_fn callback,
	void* rx_ctx,
	uint8_t endpoint)
{
	int result;

	result = hydrasdr_generic_set_receiver_mode(dev, HYDRASDR_RECEIVER_MODE_OFF);
	if (result != HYDRASDR_SUCCESS) {
		return result;
	}

	result = hydrasdr_generic_set_receiver_mode(dev, HYDRASDR_RECEIVER_MODE_RX);
	if (result != HYDRASDR_SUCCESS) {
		return result;
	}

	/*
	 * Apply pending auto-bandwidth AFTER streaming starts.
	 * The FW sets a default bandwidth when RX mode is enabled, so we need
	 * to override it here with our auto-selected bandwidth.
	 */
	{
		hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
		if (stream->pending_auto_bandwidth_idx > 0) {
			uint8_t retval;
			uint32_t bw_idx = stream->pending_auto_bandwidth_idx - 1;

			libusb_clear_halt(dev->usb_handle, LIBUSB_ENDPOINT_IN | 1);
			libusb_control_transfer(
				dev->usb_handle,
				((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
				HYDRASDR_SET_BANDWIDTH,
				0,
				bw_idx,
				&retval,
				1,
				LIBUSB_CTRL_TIMEOUT_MS);

			/* Track auto-selected bandwidth (don't set bandwidth_explicitly_set) */
			stream->current_bandwidth = stream->pending_auto_bandwidth_hz;
			/* Clear pending (already applied) */
			stream->pending_auto_bandwidth_idx = 0;
			stream->pending_auto_bandwidth_hz = 0;
		}
	}

	/* Clear any stall condition on endpoint after firmware has enabled it */
	result = libusb_clear_halt(dev->usb_handle, endpoint);
#if HYDRASDR_USB_DEBUG
	fprintf(stderr, "[USB_DEBUG] libusb_clear_halt(endpoint=0x%02X) returned: %d (%s)\n",
		endpoint, result, libusb_error_name(result));
#endif

	result = hydrasdr_start_streaming(dev, callback, rx_ctx, endpoint);

	return result;
}

int hydrasdr_generic_stop_rx(struct hydrasdr_device* dev)
{
	int result1, result2;

	result1 = hydrasdr_generic_set_receiver_mode(dev, HYDRASDR_RECEIVER_MODE_OFF);
	result2 = hydrasdr_stop_streaming(dev);

	if (result1 != HYDRASDR_SUCCESS && !dev->reset_command) {
		return result1;
	}
	return result2;
}

int hydrasdr_generic_is_streaming(struct hydrasdr_device* dev)
{
	hydrasdr_streaming_t* stream;
	if (!dev || !dev->private_data) {
		return 0;
	}
	stream = (hydrasdr_streaming_t*)dev->private_data;
	return OPT_ATOMIC_LOAD_ACQ(&stream->usb_hot.streaming) ? 1 : 0;
}

/* ========================================================================
 * GPIO Functions
 * ======================================================================== */

/* Validate GPIO port (0-7) and pin (0-31) */
#define GPIO_VALIDATE_PORT_PIN(port, pin) \
	if ((uint8_t)(port) > HYDRASDR_GPIO_PORT7 || (uint8_t)(pin) > HYDRASDR_GPIO_PIN31) { \
		return HYDRASDR_ERROR_INVALID_PARAM; \
	}

int hydrasdr_generic_gpio_write(struct hydrasdr_device* dev, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t val)
{
	int result;
	uint8_t port_pin;

	GPIO_VALIDATE_PORT_PIN(port, pin);

	port_pin = (((uint8_t)port) << 5) | pin;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_GPIO_WRITE, val, port_pin, NULL, 0, LIBUSB_CTRL_TIMEOUT_MS);
	if (result != 0)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_gpio_read(struct hydrasdr_device* dev, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t* val)
{
	int result;
	uint8_t port_pin;

	GPIO_VALIDATE_PORT_PIN(port, pin);

	port_pin = (((uint8_t)port) << 5) | pin;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_GPIO_READ, 0, port_pin, val, 1, LIBUSB_CTRL_TIMEOUT_MS);
	if (result < 1)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_gpiodir_write(struct hydrasdr_device* dev, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t val)
{
	int result;
	uint8_t port_pin;

	GPIO_VALIDATE_PORT_PIN(port, pin);

	port_pin = (((uint8_t)port) << 5) | pin;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_GPIODIR_WRITE, val, port_pin, NULL, 0, LIBUSB_CTRL_TIMEOUT_MS);
	if (result != 0)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_gpiodir_read(struct hydrasdr_device* dev, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t* val)
{
	int result;
	uint8_t port_pin;

	GPIO_VALIDATE_PORT_PIN(port, pin);

	port_pin = (((uint8_t)port) << 5) | pin;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_GPIODIR_READ, 0, port_pin, val, 1, LIBUSB_CTRL_TIMEOUT_MS);
	if (result < 1)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

/* ========================================================================
 * Clock Generator Functions
 * ======================================================================== */

int hydrasdr_generic_clockgen_write(struct hydrasdr_device* dev, uint8_t reg, uint8_t val)
{
	int result;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_CLOCKGEN_WRITE, val, reg, NULL, 0, LIBUSB_CTRL_TIMEOUT_MS);
	if (result != 0)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_clockgen_read(struct hydrasdr_device* dev, uint8_t reg, uint8_t* val)
{
	int result;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_CLOCKGEN_READ, 0, reg, val, 1, LIBUSB_CTRL_TIMEOUT_MS);
	if (result < 1)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

/* ========================================================================
 * SPI Flash Functions
 * ======================================================================== */

int hydrasdr_generic_spiflash_erase(struct hydrasdr_device* dev)
{
	int result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SPIFLASH_ERASE, 0, 0, NULL, 0, LIBUSB_CTRL_TIMEOUT_CHIPERASE_MS);
	if (result != 0)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_spiflash_write(struct hydrasdr_device* dev, const uint32_t addr, const uint16_t len, unsigned char* const data)
{
	int result;
	if (addr > 0x0FFFFF)
	{
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SPIFLASH_WRITE, addr >> 16, addr & 0xFFFF, data, len, 0);
	if (result < len)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_spiflash_read(struct hydrasdr_device* dev, const uint32_t addr, const uint16_t len, unsigned char* data)
{
	int result;
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SPIFLASH_READ, addr >> 16, addr & 0xFFFF, data, len, 0);
	if (result < len)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_spiflash_erase_sector(struct hydrasdr_device* dev, const uint16_t sector_num)
{
	int result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_SPIFLASH_ERASE_SECTOR, sector_num, 0, NULL, 0, LIBUSB_CTRL_TIMEOUT_CHIPERASE_MS);
	if (result != 0)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

/* ========================================================================
 * RF Frontend Functions (future-proof types: uint16_t reg, uint32_t val)
 * Current device use only lower 8 bits.
 * ======================================================================== */

int hydrasdr_generic_rf_frontend_write(struct hydrasdr_device* dev, uint16_t reg, uint32_t val)
{
	int result;
	/* Current USB protocol uses 8-bit reg/val - cast for backward compatibility */
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_RF_FRONTEND_WRITE, (uint16_t)(val & 0xFF), (uint16_t)(reg & 0xFF), NULL, 0, LIBUSB_CTRL_TIMEOUT_MS);
	if (result != 0)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	return HYDRASDR_SUCCESS;
}

int hydrasdr_generic_rf_frontend_read(struct hydrasdr_device* dev, uint16_t reg, uint32_t* val)
{
	int result;
	uint8_t val8 = 0;
	/* Current USB protocol uses 8-bit reg/val - cast for backward compatibility */
	result = libusb_control_transfer(
		dev->usb_handle,
		((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | (uint8_t)LIBUSB_RECIPIENT_DEVICE),
		HYDRASDR_RF_FRONTEND_READ, 0, (uint16_t)(reg & 0xFF), &val8, 1, LIBUSB_CTRL_TIMEOUT_MS);
	if (result < 1)
	{
		return HYDRASDR_ERROR_LIBUSB;
	}
	if (val != NULL)
	{
		*val = (uint32_t)val8;
	}
	return HYDRASDR_SUCCESS;
}
