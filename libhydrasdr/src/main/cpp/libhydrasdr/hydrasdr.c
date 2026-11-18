/*
Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
Copyright (c) 2014, Youssef Touil <youssef@airspy.com>
Copyright (c) 2015, Ian Gilmour <ian@sdrsharp.com>
Copyright (c) 2014-2026, Benjamin Vernoux <bvernoux@hydrasdr.com>

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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libusb.h>
#include "hydrasdr_internal.h"
#include "hydrasdr_shared.h"

/*
 * ============================================================================
 * DEVICE REGISTRY - Add new HydraSDR variants here
 *
 * To add a new device:
 * 1. Create hydrasdr_newdevice.c with HAL structure (extern declaration below)
 * 2. Add entry to hydrasdr_device_registry[] table
 * 3. Add board_id to hydrasdr_board_id enum in hydrasdr.h (if new)
 *
 * That's it! No other changes needed in this file.
 * ============================================================================
 */

/* HAL declarations - one per device type */
extern const hydrasdr_hal_t rfone_hal;

/* Device registry entry: maps VID/PID to HAL and board info */
typedef struct {
	uint16_t vid;
	uint16_t pid;
	const char *description;
	enum hydrasdr_board_id board_id;
	const hydrasdr_hal_t *hal;
} hydrasdr_device_entry_t;

/*
 * DEVICE REGISTRY TABLE
 * Add new devices here - one line per VID/PID combination
 */
static const hydrasdr_device_entry_t hydrasdr_device_registry[] = {
	/* RFOne devices */
	{ 0x1d50, 0x60a1, "HydraSDR RFOne Legacy VID/PID",    HYDRASDR_BOARD_ID_PROTO_HYDRASDR,          &rfone_hal },
	{ 0x38af, 0x0001, "HydraSDR RFOne Official VID/PID",  HYDRASDR_BOARD_ID_HYDRASDR_RFONE_OFFICIAL, &rfone_hal },
	/* Add new devices here:
	 * { 0xVVVV, 0xPPPP, "HydraSDR NewDevice", HYDRASDR_BOARD_ID_XXX, &newdevice_hal },
	 */
};
#define HYDRASDR_DEVICE_REGISTRY_COUNT (sizeof(hydrasdr_device_registry) / sizeof(hydrasdr_device_registry[0]))

#define SERIAL_NUMBER_UNUSED (0ULL)
#define FILE_DESCRIPTOR_UNUSED (-1)

#define HYDRASDR_EXPECTED_FW_PREFIX "HydraSDR RF"
#define HYDRASDR_EXPECTED_FW_PREFIX_LEN (11)

#define HYDRASDR_EXPECTED_SERIAL_PREFIX "HYDRASDR SN:"
#define HYDRASDR_EXPECTED_SERIAL_PREFIX_LEN (12)
#define HYDRASDR_EXPECTED_SERIAL_TOTAL_LEN (28)

/*
 * Find device entry by VID/PID
 * Returns pointer to registry entry, or NULL if not found
 */
static const hydrasdr_device_entry_t* find_device_by_vid_pid(uint16_t vid, uint16_t pid)
{
	for (size_t i = 0; i < HYDRASDR_DEVICE_REGISTRY_COUNT; i++) {
		if (hydrasdr_device_registry[i].vid == vid &&
		    hydrasdr_device_registry[i].pid == pid) {
			return &hydrasdr_device_registry[i];
		}
	}
	return NULL;
}

/*
 * Find device entry by board ID
 * Returns pointer to first matching registry entry, or NULL if not found
 */
static const hydrasdr_device_entry_t* find_device_by_board_id(enum hydrasdr_board_id board_id)
{
	for (size_t i = 0; i < HYDRASDR_DEVICE_REGISTRY_COUNT; i++) {
		if (hydrasdr_device_registry[i].board_id == board_id) {
			return &hydrasdr_device_registry[i];
		}
	}
	return NULL;
}

static void hydrasdr_open_exit(struct hydrasdr_device* device)
{
	struct timeval timeout = { 0, 10000 }; /* 10ms - quick check for pending events */
	int i;

	/* Drain any pending USB events before closing.
	 * This ensures all transfer cancellation callbacks have fired.
	 * Use short timeout and minimal iterations to avoid unnecessary delay. */
	if (device->usb_context != NULL)
	{
		for (i = 0; i < 2; i++)
		{
			libusb_handle_events_timeout_completed(device->usb_context, &timeout, NULL);
		}
	}

	if (device->usb_handle != NULL)
	{
		libusb_release_interface(device->usb_handle, 0);
		libusb_close(device->usb_handle);
		device->usb_handle = NULL;
	}

	libusb_exit(device->usb_context);
	device->usb_context = NULL;
}

static int verify_and_claim_device(struct hydrasdr_device* dev_wrapper, libusb_device* usb_dev) {
	int result;
	char fw_version[255 + 1];
	bool interface_claimed = false;

	if (libusb_open(usb_dev, &dev_wrapper->usb_handle) != 0) {
		dev_wrapper->usb_handle = NULL;
		return HYDRASDR_ERROR_LIBUSB;
	}

#ifdef __linux__
	if (libusb_kernel_driver_active(dev_wrapper->usb_handle, 0)) {
		libusb_detach_kernel_driver(dev_wrapper->usb_handle, 0);
	}
#endif

	result = libusb_set_configuration(dev_wrapper->usb_handle, 1);
	if (result != 0) {
		result = HYDRASDR_ERROR_LIBUSB;
		goto cleanup;
	}

	result = libusb_claim_interface(dev_wrapper->usb_handle, 0);
	if (result != 0) {
		result = HYDRASDR_ERROR_LIBUSB;
		goto cleanup;
	}
	interface_claimed = true;

	// This check is generic for any HydraSDR device.
	// We read the version string and ensure it starts with the expected prefix.
	// Note: Using deprecated function internally is intentional here (pre-HAL setup)
#ifdef _MSC_VER
#pragma warning(push)
#pragma warning(disable: 4996)  /* deprecated function */
#elif defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
#endif
	result = hydrasdr_version_string_read(dev_wrapper, fw_version, 255);
#ifdef _MSC_VER
#pragma warning(pop)
#elif defined(__GNUC__)
#pragma GCC diagnostic pop
#endif
	if (result != HYDRASDR_SUCCESS) {
		goto cleanup;
	}

	if (strncmp(fw_version, HYDRASDR_EXPECTED_FW_PREFIX, HYDRASDR_EXPECTED_FW_PREFIX_LEN) != 0) {
		result = HYDRASDR_ERROR_NOT_FOUND; // Not a valid HydraSDR firmware
		goto cleanup;
	}

	return HYDRASDR_SUCCESS;

cleanup:
	if (interface_claimed) {
		libusb_release_interface(dev_wrapper->usb_handle, 0);
	}
	libusb_close(dev_wrapper->usb_handle);
	dev_wrapper->usb_handle = NULL;
	return result;
}


static void hydrasdr_open_device(struct hydrasdr_device* device, int* ret, uint64_t serial_number_val)
{
	libusb_device** usb_devices = NULL;
	ssize_t cnt = libusb_get_device_list(device->usb_context, &usb_devices);
	if (cnt < 0) {
		*ret = HYDRASDR_ERROR_NOT_FOUND;
		return;
	}

	for (int i = 0; usb_devices[i] != NULL; i++) {
		struct libusb_device_descriptor desc;
		if (libusb_get_device_descriptor(usb_devices[i], &desc) != 0) {
			continue;
		}

		/* Look up device in registry */
		const hydrasdr_device_entry_t *entry = find_device_by_vid_pid(desc.idVendor, desc.idProduct);
		if (!entry) {
			continue;  /* Not a known HydraSDR device */
		}

		/* If a specific serial is requested, check it */
		if (serial_number_val != SERIAL_NUMBER_UNUSED) {
			libusb_device_handle* temp_handle;
			if (libusb_open(usb_devices[i], &temp_handle) != 0) {
				continue;
			}
			char serial_str[HYDRASDR_EXPECTED_SERIAL_TOTAL_LEN + 1];
			int len = libusb_get_string_descriptor_ascii(temp_handle, desc.iSerialNumber, (unsigned char *)serial_str, sizeof(serial_str));
			libusb_close(temp_handle);

			/* Validate length and ensure null termination */
			if (len <= 0 || len > HYDRASDR_EXPECTED_SERIAL_TOTAL_LEN) {
				continue;
			}
			serial_str[len] = '\0';

			if (len == HYDRASDR_EXPECTED_SERIAL_TOTAL_LEN) {
				if (strncmp(serial_str, HYDRASDR_EXPECTED_SERIAL_PREFIX, HYDRASDR_EXPECTED_SERIAL_PREFIX_LEN) != 0) {
					continue;
				}
				uint64_t serial = strtoull(serial_str + HYDRASDR_EXPECTED_SERIAL_PREFIX_LEN, NULL, 16);
				if (serial != serial_number_val) {
					continue;
				}
			} else {
				continue;
			}
		}

		/* Set the HAL from registry */
		device->hal = entry->hal;

		if (verify_and_claim_device(device, usb_devices[i]) == HYDRASDR_SUCCESS) {
			*ret = HYDRASDR_SUCCESS;
			libusb_free_device_list(usb_devices, 1);
			return;
		}
	}

	libusb_free_device_list(usb_devices, 1);
	*ret = HYDRASDR_ERROR_NOT_FOUND;
}

/*
 * Open a HydraSDR device from a file descriptor (Android USB host API).
 * On Android, Java opens the USB device and passes the FD to native code.
 * We use libusb_wrap_sys_device() to wrap the FD into a libusb handle,
 * then claim the interface and look up the HAL from the device registry.
 */
static void hydrasdr_open_device_fd(struct hydrasdr_device* device, int* ret, int fd)
{
#ifdef __ANDROID__
	int result;
	struct libusb_device_descriptor desc;
	const hydrasdr_device_entry_t *entry;
	char fw_version[255 + 1];

	/* Wrap the Java-provided FD into a libusb device handle */
	result = libusb_wrap_sys_device(device->usb_context, (intptr_t)fd, &device->usb_handle);
	if (result != 0 || device->usb_handle == NULL) {
		device->usb_handle = NULL;
		*ret = HYDRASDR_ERROR_LIBUSB;
		return;
	}

	/* Detach kernel driver if active */
	if (libusb_kernel_driver_active(device->usb_handle, 0)) {
		libusb_detach_kernel_driver(device->usb_handle, 0);
	}

	/* Set configuration and claim interface */
	result = libusb_set_configuration(device->usb_handle, 1);
	if (result != 0) {
		libusb_close(device->usb_handle);
		device->usb_handle = NULL;
		*ret = HYDRASDR_ERROR_LIBUSB;
		return;
	}

	result = libusb_claim_interface(device->usb_handle, 0);
	if (result != 0) {
		libusb_close(device->usb_handle);
		device->usb_handle = NULL;
		*ret = HYDRASDR_ERROR_LIBUSB;
		return;
	}

	/* Get device descriptor to determine VID/PID for HAL lookup */
	if (libusb_get_device_descriptor(libusb_get_device(device->usb_handle), &desc) != 0) {
		libusb_release_interface(device->usb_handle, 0);
		libusb_close(device->usb_handle);
		device->usb_handle = NULL;
		*ret = HYDRASDR_ERROR_LIBUSB;
		return;
	}

	/* Look up device in registry to attach the correct HAL */
	entry = find_device_by_vid_pid(desc.idVendor, desc.idProduct);
	if (!entry) {
		libusb_release_interface(device->usb_handle, 0);
		libusb_close(device->usb_handle);
		device->usb_handle = NULL;
		*ret = HYDRASDR_ERROR_NOT_FOUND;
		return;
	}
	device->hal = entry->hal;

	/* Verify firmware */
#ifdef _MSC_VER
#pragma warning(push)
#pragma warning(disable: 4996)
#elif defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
#endif
	result = hydrasdr_version_string_read(device, fw_version, 255);
#ifdef _MSC_VER
#pragma warning(pop)
#elif defined(__GNUC__)
#pragma GCC diagnostic pop
#endif
	if (result != HYDRASDR_SUCCESS) {
		libusb_release_interface(device->usb_handle, 0);
		libusb_close(device->usb_handle);
		device->usb_handle = NULL;
		*ret = result;
		return;
	}

	if (strncmp(fw_version, HYDRASDR_EXPECTED_FW_PREFIX, HYDRASDR_EXPECTED_FW_PREFIX_LEN) != 0) {
		libusb_release_interface(device->usb_handle, 0);
		libusb_close(device->usb_handle);
		device->usb_handle = NULL;
		*ret = HYDRASDR_ERROR_NOT_FOUND;
		return;
	}

	*ret = HYDRASDR_SUCCESS;
#else
	(void)device;
	(void)fd;
	*ret = HYDRASDR_ERROR_UNSUPPORTED;
#endif
}

static int hydrasdr_open_init(struct hydrasdr_device** device, uint64_t serial_number, int fd)
{
	struct hydrasdr_device* lib_device;
	int result;

	if (device == NULL) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	*device = NULL;
	lib_device = (struct hydrasdr_device*)calloc(1, sizeof(struct hydrasdr_device));
	if (lib_device == NULL) {
		return HYDRASDR_ERROR_NO_MEM;
	}

#ifdef __ANDROID__
	// LibUSB does not support device discovery on native Android,
	// Termux provides support with a custom libusb package and termux-usb wrapper.
	if (getenv("TERMUX_VERSION") == NULL) {
		libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);
	}
#endif

	if (libusb_init(&lib_device->usb_context) != 0) {
		free(lib_device);
		return HYDRASDR_ERROR_LIBUSB;
	}

	if (fd == FILE_DESCRIPTOR_UNUSED) {
		hydrasdr_open_device(lib_device, &result, serial_number);
	} else {
		hydrasdr_open_device_fd(lib_device, &result, fd);
	}

	if (result != HYDRASDR_SUCCESS) {
		libusb_exit(lib_device->usb_context);
		free(lib_device);
		return result;
	}

	// The HAL is now attached, call its init function
	if (lib_device->hal && lib_device->hal->init) {
		result = lib_device->hal->init(lib_device);
		if (result != HYDRASDR_SUCCESS) {
			hydrasdr_open_exit(lib_device);
			free(lib_device);
			return result;
		}
	} else {
		hydrasdr_open_exit(lib_device);
		free(lib_device);
		return HYDRASDR_ERROR_OTHER; // Should not happen if HAL is attached
	}
	
	*device = lib_device;
	return HYDRASDR_SUCCESS;
}


#ifdef __cplusplus
extern "C"
{
#endif

ADDAPI void ADDCALL hydrasdr_lib_version(hydrasdr_lib_version_t* lib_version)
{
	lib_version->major_version = HYDRASDR_VER_MAJOR;
	lib_version->minor_version = HYDRASDR_VER_MINOR;
	lib_version->revision = HYDRASDR_VER_REVISION;
}

ADDAPI int ADDCALL hydrasdr_list_devices(uint64_t *serials, int count)
{
	libusb_context *context;
	libusb_device **usb_devices;
	int output_count = 0;

#ifdef __ANDROID__
	// LibUSB does not support device discovery on native Android,
	// Termux provides support with a custom libusb package and termux-usb wrapper.
	if (getenv("TERMUX_VERSION") == NULL) {
		libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);
	}
#endif
	if (libusb_init(&context) != 0) return HYDRASDR_ERROR_LIBUSB;

	ssize_t cnt = libusb_get_device_list(context, &usb_devices);
	if (cnt < 0) {
		libusb_exit(context);
		return HYDRASDR_ERROR_NOT_FOUND;
	}

	for (int i = 0; usb_devices[i] != NULL && (!serials || output_count < count); i++) {
		struct libusb_device_descriptor desc;
		if (libusb_get_device_descriptor(usb_devices[i], &desc) != 0) {
			continue;
		}

		/* Use device registry to check if this is a known HydraSDR device */
		if (!find_device_by_vid_pid(desc.idVendor, desc.idProduct)) {
			continue;
		}

		if (desc.iSerialNumber > 0) {
			libusb_device_handle* handle;
			if (libusb_open(usb_devices[i], &handle) == 0) {
				char serial_str[HYDRASDR_EXPECTED_SERIAL_TOTAL_LEN + 1];
				int len = libusb_get_string_descriptor_ascii(handle, desc.iSerialNumber, (unsigned char *)serial_str, sizeof(serial_str));
				/* Validate length and ensure null termination */
				if (len > 0 && len <= HYDRASDR_EXPECTED_SERIAL_TOTAL_LEN) {
					serial_str[len] = '\0';
					if (len == HYDRASDR_EXPECTED_SERIAL_TOTAL_LEN &&
					    strncmp(serial_str, HYDRASDR_EXPECTED_SERIAL_PREFIX, HYDRASDR_EXPECTED_SERIAL_PREFIX_LEN) == 0) {
						uint64_t serial = strtoull(serial_str + HYDRASDR_EXPECTED_SERIAL_PREFIX_LEN, NULL, 16);
						if (serials) {
							serials[output_count] = serial;
						}
						output_count++;
					}
				}
				libusb_close(handle);
			}
		}
	}

	libusb_free_device_list(usb_devices, 1);
	libusb_exit(context);
	return output_count;
}

ADDAPI int ADDCALL hydrasdr_open_sn(struct hydrasdr_device** device, uint64_t serial_number)
{
	return hydrasdr_open_init(device, serial_number, FILE_DESCRIPTOR_UNUSED);
}

ADDAPI int ADDCALL hydrasdr_open_fd(struct hydrasdr_device** device, int fd)
{
	return hydrasdr_open_init(device, SERIAL_NUMBER_UNUSED, fd);
}

ADDAPI int ADDCALL hydrasdr_open(struct hydrasdr_device** device)
{
	return hydrasdr_open_init(device, SERIAL_NUMBER_UNUSED, FILE_DESCRIPTOR_UNUSED);
}

ADDAPI int ADDCALL hydrasdr_close(struct hydrasdr_device* device)
{
	int result = HYDRASDR_SUCCESS;
	if (device != NULL)
	{
		if (device->hal && device->hal->exit) {
			result = device->hal->exit(device);
		}

		hydrasdr_open_exit(device);
		free(device);
	}
	return result;
}

ADDAPI int ADDCALL hydrasdr_is_streaming(struct hydrasdr_device* device)
{
	return hydrasdr_generic_is_streaming(device);
}

ADDAPI int ADDCALL hydrasdr_get_streaming_stats(struct hydrasdr_device* device, hydrasdr_streaming_stats_t* stats)
{
	if (device == NULL || stats == NULL) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	/* Access internal streaming structure */
	hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)device->private_data;
	if (stream == NULL) {
		/* No streaming context - return zeros */
		memset(stats, 0, sizeof(hydrasdr_streaming_stats_t));
		return HYDRASDR_SUCCESS;
	}

	/* Copy stats from internal structure */
	stats->buffers_received = stream->stats.total_buffers_received;
	stats->buffers_processed = stream->stats.total_buffers_processed;
	stats->buffers_dropped = stream->stats.total_dropped_buffers;
	memset(stats->reserved, 0, sizeof(stats->reserved));

	return HYDRASDR_SUCCESS;
}

ADDAPI const char* ADDCALL hydrasdr_error_name(enum hydrasdr_error errcode)
{
	switch (errcode)
	{
	case HYDRASDR_SUCCESS: return "HYDRASDR_SUCCESS";
	case HYDRASDR_TRUE: return "HYDRASDR_TRUE";
	case HYDRASDR_ERROR_INVALID_PARAM: return "HYDRASDR_ERROR_INVALID_PARAM";
	case HYDRASDR_ERROR_NOT_FOUND: return "HYDRASDR_ERROR_NOT_FOUND";
	case HYDRASDR_ERROR_BUSY: return "HYDRASDR_ERROR_BUSY";
	case HYDRASDR_ERROR_NO_MEM: return "HYDRASDR_ERROR_NO_MEM";
	case HYDRASDR_ERROR_UNSUPPORTED: return "HYDRASDR_ERROR_UNSUPPORTED";
	case HYDRASDR_ERROR_LIBUSB: return "HYDRASDR_ERROR_LIBUSB";
	case HYDRASDR_ERROR_THREAD: return "HYDRASDR_ERROR_THREAD";
	case HYDRASDR_ERROR_STREAMING_THREAD_ERR: return "HYDRASDR_ERROR_STREAMING_THREAD_ERR";
	case HYDRASDR_ERROR_STREAMING_STOPPED: return "HYDRASDR_ERROR_STREAMING_STOPPED";
	case HYDRASDR_ERROR_OTHER: return "HYDRASDR_ERROR_OTHER";
	default: return "hydrasdr unknown error";
	}
}

ADDAPI const char* ADDCALL hydrasdr_board_id_name(enum hydrasdr_board_id board_id)
{
	if (board_id == HYDRASDR_BOARD_ID_INVALID) {
		return "Invalid Board ID";
	}

	/* Look up in device registry */
	const hydrasdr_device_entry_t *entry = find_device_by_board_id(board_id);
	if (entry) {
		return entry->description;
	}

	return "Unknown Board ID";
}

// --- HAL Dispatcher Functions ---

#define HAL_DISPATCH(device, func, ...) \
    ( (device && device->hal && device->hal->func) ? \
      device->hal->func(device, ##__VA_ARGS__) : \
      HYDRASDR_ERROR_UNSUPPORTED )

ADDAPI int ADDCALL hydrasdr_get_samplerates(struct hydrasdr_device* device, uint32_t* buffer, const uint32_t len)
{
	return HAL_DISPATCH(device, get_samplerates, buffer, len);
}

ADDAPI int ADDCALL hydrasdr_set_samplerate(struct hydrasdr_device* device, uint32_t samplerate)
{
	return HAL_DISPATCH(device, set_samplerate, samplerate);
}

ADDAPI int ADDCALL hydrasdr_set_decimation_mode(struct hydrasdr_device* device, enum hydrasdr_decimation_mode mode)
{
	return hydrasdr_generic_set_decimation_mode(device, (uint32_t)mode);
}

ADDAPI int ADDCALL hydrasdr_get_decimation_mode(struct hydrasdr_device* device, enum hydrasdr_decimation_mode* mode)
{
	return hydrasdr_generic_get_decimation_mode(device, (uint32_t*)mode);
}

ADDAPI int ADDCALL hydrasdr_get_bandwidths(struct hydrasdr_device* device, uint32_t* buffer, const uint32_t len)
{
	return HAL_DISPATCH(device, get_bandwidths, buffer, len);
}

ADDAPI int ADDCALL hydrasdr_set_bandwidth(struct hydrasdr_device* device, uint32_t bandwidth)
{
	return HAL_DISPATCH(device, set_bandwidth, bandwidth);
}

ADDAPI int ADDCALL hydrasdr_set_conversion_algorithm(struct hydrasdr_device* device, const char* algorithm)
{
	return HAL_DISPATCH(device, set_conversion_algorithm, algorithm);
}

ADDAPI int ADDCALL hydrasdr_list_conversion_algorithms(const char **names, const char **descriptions, int max)
{
	return hydrasdr_generic_list_conversion_algorithms(names, descriptions, max);
}

ADDAPI int ADDCALL hydrasdr_set_conversion_filter_float32(struct hydrasdr_device* device, const float* kernel, const uint32_t len)
{
	return HAL_DISPATCH(device, set_conversion_filter_float32, kernel, len);
}

ADDAPI int ADDCALL hydrasdr_set_conversion_filter_int16(struct hydrasdr_device* device, const int16_t* kernel, const uint32_t len)
{
	return HAL_DISPATCH(device, set_conversion_filter_int16, kernel, len);
}

ADDAPI int ADDCALL hydrasdr_start_rx(struct hydrasdr_device* device, hydrasdr_sample_block_cb_fn callback, void* rx_ctx)
{
	return HAL_DISPATCH(device, start_rx, callback, rx_ctx);
}

ADDAPI int ADDCALL hydrasdr_stop_rx(struct hydrasdr_device* device)
{
	return HAL_DISPATCH(device, stop_rx);
}

/* GPIO value functions */
ADDAPI int ADDCALL hydrasdr_gpio_write(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t val)
{
	return HAL_DISPATCH(device, gpio_write, port, pin, val);
}

ADDAPI int ADDCALL hydrasdr_gpio_read(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t* val)
{
	return HAL_DISPATCH(device, gpio_read, port, pin, val);
}

/* GPIO direction functions */
ADDAPI int ADDCALL hydrasdr_gpiodir_write(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t val)
{
	return HAL_DISPATCH(device, gpiodir_write, port, pin, val);
}

ADDAPI int ADDCALL hydrasdr_gpiodir_read(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t* val)
{
	return HAL_DISPATCH(device, gpiodir_read, port, pin, val);
}

/* Clock generator register access functions */
ADDAPI int ADDCALL hydrasdr_clockgen_write(struct hydrasdr_device* device, uint8_t reg, uint8_t val)
{
	return HAL_DISPATCH(device, clockgen_write, reg, val);
}

ADDAPI int ADDCALL hydrasdr_clockgen_read(struct hydrasdr_device* device, uint8_t reg, uint8_t* val)
{
	return HAL_DISPATCH(device, clockgen_read, reg, val);
}

/* Legacy SI5351C functions - deprecated but kept for backward compatibility */
ADDAPI int ADDCALL hydrasdr_si5351c_write(struct hydrasdr_device* device, uint8_t reg, uint8_t val)
{
	return HAL_DISPATCH(device, clockgen_write, reg, val);
}

ADDAPI int ADDCALL hydrasdr_si5351c_read(struct hydrasdr_device* device, uint8_t reg, uint8_t* val)
{
	return HAL_DISPATCH(device, clockgen_read, reg, val);
}

/* SPI flash functions */
ADDAPI int ADDCALL hydrasdr_spiflash_erase(struct hydrasdr_device* device)
{
	return HAL_DISPATCH(device, spiflash_erase);
}

ADDAPI int ADDCALL hydrasdr_spiflash_write(struct hydrasdr_device* device, const uint32_t addr, const uint16_t len, unsigned char* const data)
{
	return HAL_DISPATCH(device, spiflash_write, addr, len, data);
}

ADDAPI int ADDCALL hydrasdr_spiflash_read(struct hydrasdr_device* device, const uint32_t addr, const uint16_t len, unsigned char* data)
{
	return HAL_DISPATCH(device, spiflash_read, addr, len, data);
}

ADDAPI int ADDCALL hydrasdr_spiflash_erase_sector(struct hydrasdr_device* device, const uint16_t sector_num)
{
	return HAL_DISPATCH(device, spiflash_erase_sector, sector_num);
}

/* RF frontend register access functions */

// New API: hydrasdr_rf_frontend_write() - generic RF frontend register write (future-proof types)
ADDAPI int ADDCALL hydrasdr_rf_frontend_write(struct hydrasdr_device* device, uint16_t reg, uint32_t val)
{
	return HAL_DISPATCH(device, rf_frontend_write, reg, val);
}

// New API: hydrasdr_rf_frontend_read() - generic RF frontend register read (future-proof types)
ADDAPI int ADDCALL hydrasdr_rf_frontend_read(struct hydrasdr_device* device, uint16_t reg, uint32_t* val)
{
	return HAL_DISPATCH(device, rf_frontend_read, reg, val);
}

// Legacy API: hydrasdr_r82x_write() for RFOne - kept for backward compatibility
ADDAPI int ADDCALL hydrasdr_r82x_write(struct hydrasdr_device* device, uint8_t reg, uint8_t val)
{
	return HAL_DISPATCH(device, rf_frontend_write, (uint16_t)reg, (uint32_t)val);
}

// Legacy API: hydrasdr_r82x_read() for RFOne - kept for backward compatibility
ADDAPI int ADDCALL hydrasdr_r82x_read(struct hydrasdr_device* device, uint8_t reg, uint8_t* val)
{
	uint32_t val32 = 0;
	int result = HAL_DISPATCH(device, rf_frontend_read, (uint16_t)reg, &val32);
	if (result == HYDRASDR_SUCCESS && val != NULL) {
		*val = (uint8_t)(val32 & 0xFF);
	}
	return result;
}

ADDAPI int ADDCALL hydrasdr_board_id_read(struct hydrasdr_device* device, uint8_t* value)
{
	return HAL_DISPATCH(device, board_id_read, value);
}

ADDAPI int ADDCALL hydrasdr_version_string_read(struct hydrasdr_device* device, char* version, uint8_t length)
{
	return HAL_DISPATCH(device, version_string_read, version, length);
}

ADDAPI int ADDCALL hydrasdr_board_partid_serialno_read(struct hydrasdr_device* device, hydrasdr_read_partid_serialno_t* read_partid_serialno)
{
	return HAL_DISPATCH(device, board_partid_serialno_read, read_partid_serialno);
}

ADDAPI int ADDCALL hydrasdr_set_sample_type(struct hydrasdr_device* device, enum hydrasdr_sample_type sample_type)
{
	return HAL_DISPATCH(device, set_sample_type, sample_type);
}

ADDAPI int ADDCALL hydrasdr_set_freq(struct hydrasdr_device* device, const uint64_t freq_hz)
{
	return HAL_DISPATCH(device, set_freq, freq_hz);
}

ADDAPI int ADDCALL hydrasdr_set_lna_gain(struct hydrasdr_device* device, uint8_t value)
{
	return HAL_DISPATCH(device, set_lna_gain, value);
}

ADDAPI int ADDCALL hydrasdr_set_mixer_gain(struct hydrasdr_device* device, uint8_t value)
{
	return HAL_DISPATCH(device, set_mixer_gain, value);
}

ADDAPI int ADDCALL hydrasdr_set_vga_gain(struct hydrasdr_device* device, uint8_t value)
{
	return HAL_DISPATCH(device, set_vga_gain, value);
}

ADDAPI int ADDCALL hydrasdr_set_lna_agc(struct hydrasdr_device* device, uint8_t value)
{
	return HAL_DISPATCH(device, set_lna_agc, value);
}

ADDAPI int ADDCALL hydrasdr_set_mixer_agc(struct hydrasdr_device* device, uint8_t value)
{
	return HAL_DISPATCH(device, set_mixer_agc, value);
}

ADDAPI int ADDCALL hydrasdr_set_linearity_gain(struct hydrasdr_device* device, uint8_t value)
{
	return HAL_DISPATCH(device, set_linearity_gain, value);
}

ADDAPI int ADDCALL hydrasdr_set_sensitivity_gain(struct hydrasdr_device* device, uint8_t value)
{
	return HAL_DISPATCH(device, set_sensitivity_gain, value);
}

/* Unified gain API functions (HYDRASDR_CAP_EXTENDED_GAIN) */
ADDAPI int ADDCALL hydrasdr_get_gain(struct hydrasdr_device* device, hydrasdr_gain_type_t type, hydrasdr_gain_info_t* info)
{
	return hydrasdr_generic_get_gain(device, type, info);
}

ADDAPI int ADDCALL hydrasdr_set_gain(struct hydrasdr_device* device, hydrasdr_gain_type_t type, uint8_t value)
{
	return hydrasdr_generic_set_gain(device, type, value);
}

ADDAPI int ADDCALL hydrasdr_get_all_gains(struct hydrasdr_device* device, hydrasdr_gain_info_t* gains, uint8_t* count)
{
	return hydrasdr_generic_get_all_gains(device, gains, count);
}

ADDAPI int ADDCALL hydrasdr_set_rf_bias(struct hydrasdr_device* device, uint8_t value)
{
	return HAL_DISPATCH(device, set_rf_bias, value);
}

ADDAPI int ADDCALL hydrasdr_set_packing(struct hydrasdr_device* device, uint8_t value)
{
	return HAL_DISPATCH(device, set_packing, value);
}

ADDAPI int ADDCALL hydrasdr_reset(struct hydrasdr_device* device)
{
	return HAL_DISPATCH(device, reset);
}

ADDAPI int ADDCALL hydrasdr_set_rf_port(struct hydrasdr_device* device, hydrasdr_rf_port_t rf_port)
{
	return HAL_DISPATCH(device, set_rf_port, rf_port);
}

ADDAPI int ADDCALL hydrasdr_get_temperature(struct hydrasdr_device* device, hydrasdr_temperature_t* temperature)
{
	return HAL_DISPATCH(device, get_temperature, temperature);
}

ADDAPI int ADDCALL hydrasdr_get_device_info(struct hydrasdr_device* device, hydrasdr_device_info_t* info)
{
	return HAL_DISPATCH(device, get_device_info, info);
}

#ifdef __cplusplus
} // __cplusplus defined.
#endif
