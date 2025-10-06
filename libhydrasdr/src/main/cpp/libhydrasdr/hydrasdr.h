/*
Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
Copyright (C) 2013-2016, Youssef Touil <youssef@airspy.com>
Copyright (c) 2013-2025, Benjamin Vernoux <bvernoux@hydrasdr.com>

All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the 
documentation and/or other materials provided with the distribution.
Neither the name of HydraSDR nor the names of its contributors may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

#ifndef __HYDRASDR_H__
#define __HYDRASDR_H__

#include <stdint.h>
#include "hydrasdr_commands.h"

#define HYDRASDR_VERSION "1.0.2"
#define HYDRASDR_VER_MAJOR 1
#define HYDRASDR_VER_MINOR 0
#define HYDRASDR_VER_REVISION 2

#ifdef _WIN32
	 #define ADD_EXPORTS
	 
	/* You should define ADD_EXPORTS *only* when building the DLL. */
	#ifdef ADD_EXPORTS
		#define ADDAPI __declspec(dllexport)
	#else
		#define ADDAPI __declspec(dllimport)
	#endif

	/* Define calling convention in one place, for convenience. */
	#define ADDCALL __cdecl

#else /* _WIN32 not defined. */

	/* Define with no value on non-Windows OSes. */
	#define ADDAPI
	#define ADDCALL

#endif

#ifdef __cplusplus
extern "C"
{
#endif

enum hydrasdr_error
{
	HYDRASDR_SUCCESS = 0,
	HYDRASDR_TRUE = 1,
	HYDRASDR_ERROR_INVALID_PARAM = -2,
	HYDRASDR_ERROR_NOT_FOUND = -5,
	HYDRASDR_ERROR_BUSY = -6,
	HYDRASDR_ERROR_NO_MEM = -11,
	HYDRASDR_ERROR_UNSUPPORTED = -12,
	HYDRASDR_ERROR_LIBUSB = -1000,
	HYDRASDR_ERROR_THREAD = -1001,
	HYDRASDR_ERROR_STREAMING_THREAD_ERR = -1002,
	HYDRASDR_ERROR_STREAMING_STOPPED = -1003,
	HYDRASDR_ERROR_OTHER = -9999,
};

enum hydrasdr_board_id
{
	HYDRASDR_BOARD_ID_PROTO_HYDRASDR  = 0,
	HYDRASDR_BOARD_ID_HYDRASDR_RFONE_OFFICIAL = 1,
	HYDRASDR_BOARD_ID_INVALID = 0xFF,
};

enum hydrasdr_sample_type
{
	HYDRASDR_SAMPLE_FLOAT32_IQ = 0,   /* 2 * 32bit float per sample */
	HYDRASDR_SAMPLE_FLOAT32_REAL = 1, /* 1 * 32bit float per sample */
	HYDRASDR_SAMPLE_INT16_IQ = 2,     /* 2 * 16bit int per sample */
	HYDRASDR_SAMPLE_INT16_REAL = 3,   /* 1 * 16bit int per sample */
	HYDRASDR_SAMPLE_UINT16_REAL = 4,  /* 1 * 16bit unsigned int per sample */
	HYDRASDR_SAMPLE_RAW = 5,          /* Raw packed samples from the device */
	HYDRASDR_SAMPLE_END = 6           /* Number of supported sample types */
};

#define MAX_CONFIG_PAGE_SIZE (0x10000)

struct hydrasdr_device;

typedef struct {
	struct hydrasdr_device* device;
	void* ctx;
	void* samples;
	int sample_count;
	uint64_t dropped_samples;
	enum hydrasdr_sample_type sample_type;
} hydrasdr_transfer_t, hydrasdr_transfer;

typedef struct {
	uint32_t part_id[2];
	uint32_t serial_no[4];
} hydrasdr_read_partid_serialno_t;

typedef struct {
	uint32_t major_version;
	uint32_t minor_version;
	uint32_t revision;
} hydrasdr_lib_version_t;

typedef int (*hydrasdr_sample_block_cb_fn)(hydrasdr_transfer* transfer);

extern ADDAPI void ADDCALL hydrasdr_lib_version(hydrasdr_lib_version_t* lib_version);

extern ADDAPI int ADDCALL hydrasdr_list_devices(uint64_t *serials, int count);

extern ADDAPI int ADDCALL hydrasdr_open_sn(struct hydrasdr_device** device, uint64_t serial_number);
extern ADDAPI int ADDCALL hydrasdr_open_fd(struct hydrasdr_device** device, int fd);
extern ADDAPI int ADDCALL hydrasdr_open(struct hydrasdr_device** device);
extern ADDAPI int ADDCALL hydrasdr_close(struct hydrasdr_device* device);

/* Use hydrasdr_get_samplerates(device, buffer, 0) to get the number of available sample rates. It will be returned in the first element of buffer */
extern ADDAPI int ADDCALL hydrasdr_get_samplerates(struct hydrasdr_device* device, uint32_t* buffer, const uint32_t len);

/* Parameter samplerate can be either the index of a samplerate or directly its value in Hz within the list returned by hydrasdr_get_samplerates() */
extern ADDAPI int ADDCALL hydrasdr_set_samplerate(struct hydrasdr_device* device, uint32_t samplerate);

extern ADDAPI int ADDCALL hydrasdr_set_conversion_filter_float32(struct hydrasdr_device* device, const float *kernel, const uint32_t len);
extern ADDAPI int ADDCALL hydrasdr_set_conversion_filter_int16(struct hydrasdr_device* device, const int16_t *kernel, const uint32_t len);

extern ADDAPI int ADDCALL hydrasdr_start_rx(struct hydrasdr_device* device, hydrasdr_sample_block_cb_fn callback, void* rx_ctx);
extern ADDAPI int ADDCALL hydrasdr_stop_rx(struct hydrasdr_device* device);

/* return HYDRASDR_TRUE if success */
extern ADDAPI int ADDCALL hydrasdr_is_streaming(struct hydrasdr_device* device);

extern ADDAPI int ADDCALL hydrasdr_si5351c_write(struct hydrasdr_device* device, uint8_t register_number, uint8_t value);
extern ADDAPI int ADDCALL hydrasdr_si5351c_read(struct hydrasdr_device* device, uint8_t register_number, uint8_t* value);

extern ADDAPI int ADDCALL hydrasdr_r82x_write(struct hydrasdr_device* device, uint8_t register_number, uint8_t value);
extern ADDAPI int ADDCALL hydrasdr_r82x_read(struct hydrasdr_device* device, uint8_t register_number, uint8_t* value);

/* Parameter value shall be 0=clear GPIO or 1=set GPIO */
extern ADDAPI int ADDCALL hydrasdr_gpio_write(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t value);
/* Parameter value corresponds to GPIO state 0 or 1 */
extern ADDAPI int ADDCALL hydrasdr_gpio_read(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t* value);

/* Parameter value shall be 0=GPIO Input direction or 1=GPIO Output direction */
extern ADDAPI int ADDCALL hydrasdr_gpiodir_write(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t value);
extern ADDAPI int ADDCALL hydrasdr_gpiodir_read(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t* value);

extern ADDAPI int ADDCALL hydrasdr_spiflash_erase(struct hydrasdr_device* device);
extern ADDAPI int ADDCALL hydrasdr_spiflash_write(struct hydrasdr_device* device, const uint32_t address, const uint16_t length, unsigned char* const data);
extern ADDAPI int ADDCALL hydrasdr_spiflash_read(struct hydrasdr_device* device, const uint32_t address, const uint16_t length, unsigned char* data);

extern ADDAPI int ADDCALL hydrasdr_board_id_read(struct hydrasdr_device* device, uint8_t* value);
/* Parameter length shall be at least 128bytes to avoid possible string clipping */
extern ADDAPI int ADDCALL hydrasdr_version_string_read(struct hydrasdr_device* device, char* version, uint8_t length);

extern ADDAPI int ADDCALL hydrasdr_board_partid_serialno_read(struct hydrasdr_device* device, hydrasdr_read_partid_serialno_t* read_partid_serialno);

extern ADDAPI int ADDCALL hydrasdr_set_sample_type(struct hydrasdr_device* device, enum hydrasdr_sample_type sample_type);

/* Parameter freq_hz shall be between 24000000(24MHz) and 1800000000(1.8GHz) and more with extensions */
extern ADDAPI int ADDCALL hydrasdr_set_freq(struct hydrasdr_device* device, const uint64_t freq_hz);

/* Parameter value shall be between 0 and 15 */
extern ADDAPI int ADDCALL hydrasdr_set_lna_gain(struct hydrasdr_device* device, uint8_t value);

/* Parameter value shall be between 0 and 15 */
extern ADDAPI int ADDCALL hydrasdr_set_mixer_gain(struct hydrasdr_device* device, uint8_t value);

/* Parameter value shall be between 0 and 15 */
extern ADDAPI int ADDCALL hydrasdr_set_vga_gain(struct hydrasdr_device* device, uint8_t value);

/* Parameter value:
	0=Disable LNA Automatic Gain Control
	1=Enable LNA Automatic Gain Control
*/
extern ADDAPI int ADDCALL hydrasdr_set_lna_agc(struct hydrasdr_device* device, uint8_t value);
/* Parameter value:
	0=Disable MIXER Automatic Gain Control
	1=Enable MIXER Automatic Gain Control
*/
extern ADDAPI int ADDCALL hydrasdr_set_mixer_agc(struct hydrasdr_device* device, uint8_t value);

/* Parameter value: 0..21 */
extern ADDAPI int ADDCALL hydrasdr_set_linearity_gain(struct hydrasdr_device* device, uint8_t value);

/* Parameter value: 0..21 */
extern ADDAPI int ADDCALL hydrasdr_set_sensitivity_gain(struct hydrasdr_device* device, uint8_t value);

/* Parameter value shall be 0=Disable BiasT or 1=Enable BiasT */
extern ADDAPI int ADDCALL hydrasdr_set_rf_bias(struct hydrasdr_device* dev, uint8_t value);

/* Parameter value shall be 0=Disable Packing or 1=Enable Packing */
extern ADDAPI int ADDCALL hydrasdr_set_packing(struct hydrasdr_device* device, uint8_t value);

extern ADDAPI const char* ADDCALL hydrasdr_error_name(enum hydrasdr_error errcode);
extern ADDAPI const char* ADDCALL hydrasdr_board_id_name(enum hydrasdr_board_id board_id);

/* Parameter sector_num shall be between 2 & 13 (sector 0 & 1 are reserved for the firmware) */
extern ADDAPI int ADDCALL hydrasdr_spiflash_erase_sector(struct hydrasdr_device* device, const uint16_t sector_num);

extern ADDAPI int ADDCALL hydrasdr_reset(struct hydrasdr_device* device);

extern ADDAPI int ADDCALL hydrasdr_set_rf_port(struct hydrasdr_device* device, hydrasdr_rf_port_t rf_port);

#ifdef __cplusplus
} // __cplusplus defined.
#endif

#endif//__HYDRASDR_H__
