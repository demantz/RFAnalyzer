/*
Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
Copyright (C) 2013-2016, Youssef Touil <youssef@airspy.com>
Copyright (c) 2013-2026, Benjamin Vernoux <bvernoux@hydrasdr.com>

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

/**
 * @file hydrasdr.h
 * @defgroup introduction Introduction
 * @ingroup hydrasdr_api
 * @brief HydraSDR Software Defined Radio Library API
 * 
 * This library provides a unified interface for controlling HydraSDR family
 * software-defined radio devices. The API is designed to be hardware-agnostic,
 * with device-specific capabilities discovered at runtime.
 * 
 * @section arch_overview Architecture Overview
 * 
 * The library uses a Hardware Abstraction Layer (HAL) design that allows
 * device-specific implementations while providing a consistent API:
 * 
 * - Core API: Device enumeration, opening, and function dispatch
 * - HAL Interface: Device-specific function implementations
 * - Shared Functions: Common operations used by all devices
 * - USB Transfer Management: Asynchronous streaming with libusb
 * - Sample Conversion: Hardware to float/int16 conversion with filtering
 * 
 * @section capability_discovery Capability Discovery
 * 
 * Different HydraSDR devices support different features. Applications must
 * query device capabilities at runtime using hydrasdr_get_device_info() rather
 * than assuming specific features are available.
 * 
 * The capability system uses:
 * - Feature flags (bitmask) indicating available functions
 * - Gain ranges for supported gain controls
 * - Frequency range limits
 * - Sample rate enumeration
 * - RF port availability
 * 
 * @section thread_safety Thread Safety
 * 
 * - Device enumeration and opening functions are NOT thread-safe
 * - Once opened, device control functions ARE thread-safe
 * - Callback functions execute in a separate high-priority thread
 * - Do NOT call blocking operations from callbacks
 * - Do NOT close the device from within a callback
 * 
 * @section typical_usage Typical Usage Pattern
 *
 * See hydrasdr-tools/src/hydrasdr_async_rx.c for a complete reference implementation.
 *
 * @section prestreaming_config Pre-streaming Configuration Order (v1.1.0)
 *
 * **Mandatory**: For correct auto-bandwidth behavior, call configuration
 * functions in the following order before starting streaming:
 *
 * @code
 * // 1. Query device capabilities first
 * hydrasdr_get_device_info(dev, &info);
 *
 * // 2. Set frequency
 * hydrasdr_set_freq(dev, freq_hz);
 *
 * // 3. Set decimation mode (BEFORE sample rate, v1.1.0+)
 * hydrasdr_set_decimation_mode(dev, HYDRASDR_DEC_MODE_HIGH_DEFINITION);
 *
 * // 4. Set bandwidth (BEFORE sample rate, optional - auto-selected if not called)
 * hydrasdr_set_bandwidth(dev, bandwidth_hz);
 *
 * // 5. Set sample rate (LAST - triggers auto-bandwidth if bandwidth not set)
 * hydrasdr_set_samplerate(dev, samplerate);
 *
 * // 6. Set sample type
 * hydrasdr_set_sample_type(dev, HYDRASDR_SAMPLE_FLOAT32_IQ);
 *
 * // 7. Configure gains (check capabilities first)
 * if (info.features & HYDRASDR_CAP_VGA_GAIN)
 *     hydrasdr_set_vga_gain(dev, value);
 *
 * // 8. Start streaming
 * hydrasdr_start_rx(dev, callback, user_data);
 * @endcode
 *
 * @note **Critical ordering**: hydrasdr_set_bandwidth() must be called BEFORE
 * hydrasdr_set_samplerate() if you want manual bandwidth control. If bandwidth
 * is not set before samplerate, the library auto-selects the smallest bandwidth
 * >= sample rate for optimal anti-aliasing.
 *
 * @since v1.1.0
 *
 * @section error_handling Error Handling
 * 
 * All functions return HYDRASDR_SUCCESS (0) on success or a negative error code.
 * Use hydrasdr_error_name() to get human-readable error descriptions.
 * 
 * Error codes are hardware-independent - check the return value and use
 * hydrasdr_error_name() for debugging. Specific error codes may vary between
 * hardware variants.
 * 
 * @section platform_notes Platform-Specific Notes
 * 
 * **Linux**: Requires appropriate udev rules for USB device access
 * **Windows**: Requires WinUSB or libusb-win32 driver
 * **macOS**: No special requirements, uses native USB stack
 * **Android**: Requires USB host support, use hydrasdr_open_fd() with
 *             file descriptor obtained from Android USB API
 */

#ifndef __HYDRASDR_H__
#define __HYDRASDR_H__

#include <stdint.h>
#include "hydrasdr_commands.h"

#define HYDRASDR_VERSION "1.1.2"
#define HYDRASDR_VER_MAJOR 1
#define HYDRASDR_VER_MINOR 1
#define HYDRASDR_VER_REVISION 2

/** @brief Auto-bandwidth mode for hydrasdr_set_bandwidth() */
#define HYDRASDR_BANDWIDTH_AUTO UINT32_MAX

/**
 * @brief Create a numeric version value for comparison
 *
 * Combines major, minor, and revision into a single 32-bit value that can be
 * compared using standard comparison operators (<, >, <=, >=, ==, !=).
 *
 * @param major Major version number (0-255)
 * @param minor Minor version number (0-255)
 * @param revision Revision number (0-65535)
 *
 * @return 32-bit version value: (major << 24) | (minor << 16) | revision
 *
 * @par Example - Runtime version checking:
 * @code
 * #if HYDRASDR_VERSION_NUM >= HYDRASDR_MAKE_VERSION(1, 1, 0)
 *     // Use v1.1.0+ features like hydrasdr_get_device_info()
 *     hydrasdr_device_info_t info;
 *     hydrasdr_get_device_info(dev, &info);
 * #else
 *     // Fallback for older versions
 *     hydrasdr_board_id_read(dev, &board_id);
 * #endif
 * @endcode
 *
 * @since v1.1.0
 */
#define HYDRASDR_MAKE_VERSION(major, minor, revision) \
	(((uint32_t)(major) << 24) | ((uint32_t)(minor) << 16) | (uint32_t)(revision))

/**
 * @brief Current library version as a numeric value
 *
 * Use this macro to compare against specific version numbers created with
 * HYDRASDR_MAKE_VERSION().
 *
 * @par Example:
 * @code
 * if (HYDRASDR_VERSION_NUM >= HYDRASDR_MAKE_VERSION(1, 1, 0)) {
 *     // v1.1.0 or newer features available
 * }
 * @endcode
 *
 * @since v1.1.0
 */
#define HYDRASDR_VERSION_NUM HYDRASDR_MAKE_VERSION(HYDRASDR_VER_MAJOR, HYDRASDR_VER_MINOR, HYDRASDR_VER_REVISION)

#ifdef _WIN32
	/* ADD_EXPORTS should be defined via compiler flags when building the DLL */
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

/**
 * @brief Deprecation attribute macro
 *
 * Marks functions as deprecated, generating compiler warnings when used.
 * Use this macro before the return type in function declarations.
 *
 * @note GCC/Clang: __attribute__((deprecated("message")))
 * @note MSVC: __declspec(deprecated("message"))
 */
#if defined(__GNUC__) || defined(__clang__)
	#define HYDRASDR_DEPRECATED(msg) __attribute__((deprecated(msg)))
#elif defined(_MSC_VER)
	#define HYDRASDR_DEPRECATED(msg) __declspec(deprecated(msg))
#else
	#define HYDRASDR_DEPRECATED(msg)
#endif

#ifdef __cplusplus
extern "C"
{
#endif

/* ========================================================================
 * Core Type Definitions
 * ======================================================================== */

/**
 * @brief Error codes returned by HydraSDR API functions
 *
 * All API functions return HYDRASDR_SUCCESS on success or one of these
 * negative error codes on failure. Use hydrasdr_error_name() to convert
 * error codes to human-readable strings.
 *
 * @note Specific error codes may vary by hardware and situation
 * @note Always check for HYDRASDR_SUCCESS (0) rather than specific errors
 *
 * @since v1.0.0
 */
enum hydrasdr_error
{
	HYDRASDR_SUCCESS = 0,                       /**< Operation completed successfully */
	HYDRASDR_TRUE = 1,                          /**< Boolean true result */
	HYDRASDR_ERROR_INVALID_PARAM = -2,          /**< Invalid parameter passed to function */
	HYDRASDR_ERROR_NOT_FOUND = -5,              /**< Device not found */
	HYDRASDR_ERROR_BUSY = -6,                   /**< Device is busy */
	HYDRASDR_ERROR_NO_MEM = -11,                /**< Insufficient memory */
	HYDRASDR_ERROR_UNSUPPORTED = -12,           /**< Operation not supported by this device */
	HYDRASDR_ERROR_LIBUSB = -1000,              /**< LibUSB error occurred */
	HYDRASDR_ERROR_THREAD = -1001,              /**< Thread creation/management error */
	HYDRASDR_ERROR_STREAMING_THREAD_ERR = -1002, /**< Streaming thread error */
	HYDRASDR_ERROR_STREAMING_STOPPED = -1003,   /**< Streaming has been stopped */
	HYDRASDR_ERROR_OTHER = -9999,               /**< Other unspecified error */
};

/**
 * @brief HydraSDR board identifiers
 *
 * These identifiers uniquely identify different HydraSDR hardware variants.
 * Use hydrasdr_board_id_read() to query the board ID at runtime, or check
 * the board_id field in hydrasdr_device_info_t.
 *
 * @note Board ID values are hardware-specific
 * @note Use hydrasdr_board_id_name() to get human-readable board name
 * @note Use hydrasdr_get_device_info() for complete device capabilities
 *
 * @since v1.0.0
 */
enum hydrasdr_board_id
{
	HYDRASDR_BOARD_ID_PROTO_HYDRASDR  = 0,          /**< Prototype/Legacy USB VID/PID HydraSDR board @since v1.0.0 */
	HYDRASDR_BOARD_ID_HYDRASDR_RFONE_OFFICIAL = 1,  /**< Official HydraSDR RFOne board @since v1.0.0 */
	HYDRASDR_BOARD_ID_INVALID = 0xFF,               /**< Invalid board ID @since v1.0.0 */
};

/**
 * @brief Sample data types supported by HydraSDR devices
 *
 * These types control the format of sample data delivered to the callback
 * function. The sample type must be set before starting streaming and cannot
 * be changed while streaming is active.
 *
 * Not all devices support all sample types - check the sample_types field
 * in hydrasdr_device_info_t to determine supported formats.
 *
 * @note IQ types deliver complex samples (I and Q interleaved)
 * @note Real types deliver real-valued samples only
 * @note RAW type delivers unpacked samples directly from hardware
 *
 * @since v1.0.0
 */
enum hydrasdr_sample_type
{
	/* v1.0.0 sample types */
	HYDRASDR_SAMPLE_FLOAT32_IQ = 0,   /**< 32-bit float I/Q pairs (2 floats per sample) */
	HYDRASDR_SAMPLE_FLOAT32_REAL = 1, /**< 32-bit float real samples (1 float per sample) */
	HYDRASDR_SAMPLE_INT16_IQ = 2,     /**< 16-bit int I/Q pairs (2 int16 per sample) */
	HYDRASDR_SAMPLE_INT16_REAL = 3,   /**< 16-bit int real samples (1 int16 per sample) */
	HYDRASDR_SAMPLE_UINT16_REAL = 4,  /**< 16-bit unsigned int real samples (1 uint16 per sample) */
	HYDRASDR_SAMPLE_RAW = 5,          /**< Raw ADC samples */

	/* v1.1.0 sample types (check sample_types capability bitmask for availability) */
	HYDRASDR_SAMPLE_INT8_IQ = 6,      /**< 8-bit signed int I/Q pairs (2 int8 per sample) @since v1.1.0 */
	HYDRASDR_SAMPLE_UINT8_IQ = 7,     /**< 8-bit unsigned int I/Q pairs (2 uint8 per sample) @since v1.1.0 */
	HYDRASDR_SAMPLE_INT8_REAL = 8,    /**< 8-bit signed int real samples (1 int8 per sample) @since v1.1.0 */
	HYDRASDR_SAMPLE_UINT8_REAL = 9,   /**< 8-bit unsigned int real samples (1 uint8 per sample) @since v1.1.0 */
	HYDRASDR_SAMPLE_END = 10          /**< Total number of supported sample types (not a valid type) */
};

/**
 * @brief Decimation mode for sample rate selection
 *
 * Controls how the library selects hardware sample rate when decimation is needed
 * to achieve a target sample rate lower than the minimum hardware rate.
 *
 * @since v1.1.0
 */
enum hydrasdr_decimation_mode
{
	/**
	 * Minimize USB bandwidth (default).
	 * Uses the lowest hardware rate that can achieve the target rate via decimation.
	 * Example: For 625 kSPS with HW rates 10/5/2.5 MSPS, uses 2.5 MSPS / 4x.
	 */
	HYDRASDR_DEC_MODE_LOW_BANDWIDTH = 0,

	/**
	 * Maximize signal quality.
	 * Uses the highest hardware rate and more decimation stages for better filtering.
	 * More decimation stages provide better anti-aliasing, image rejection, and ~3 dB
	 * processing gain per octave of oversampling.
	 * Example: For 625 kSPS with HW rates 10/5/2.5 MSPS, uses 10 MSPS / 16x.
	 */
	HYDRASDR_DEC_MODE_HIGH_DEFINITION = 1
};

struct hydrasdr_device;

/**
 * @brief Transfer structure passed to the sample callback function
 *
 * This structure contains information about a received data transfer,
 * including the samples buffer, sample count, and dropped samples counter.
 *
 * @warning Do NOT free or modify the samples buffer - it's managed by the library
 * @warning Do NOT block for extended periods in the callback
 * @warning Do NOT close the device from within the callback
 *
 * @since v1.0.0
 */
typedef struct {
	struct hydrasdr_device* device;        /**< Pointer to the device that generated this transfer */
	void* ctx;                             /**< User context pointer passed to hydrasdr_start_rx() */
	void* samples;                         /**< Pointer to sample data buffer (cast based on sample_type) */
	int sample_count;                      /**< Number of samples in this transfer */
	uint64_t dropped_samples;              /**< Cumulative count of dropped samples since streaming started */
	enum hydrasdr_sample_type sample_type; /**< Type of samples in this transfer */
} hydrasdr_transfer_t, hydrasdr_transfer;

/**
 * @brief Sample callback function signature
 *
 * Your callback function must match this signature. It will be called repeatedly
 * with buffers of received samples while streaming is active.
 *
 * @param transfer Pointer to transfer structure containing received samples
 *
 * @return 0 to continue streaming, non-zero to stop streaming
 *
 * @warning Callback executes in a high-priority thread
 * @warning Do NOT block for extended periods
 * @warning Do NOT call hydrasdr_close() or hydrasdr_stop_rx() from callback
 * @warning To stop from callback, return non-zero value
 *
 * @note If you need to pass data to other threads, use a lock-free queue
 * @note Process samples quickly or copy to your own buffer and signal worker thread
 *
 * @since v1.0.0
 */
typedef int (*hydrasdr_sample_block_cb_fn)(hydrasdr_transfer* transfer);

/**
 * @brief Library version information structure
 *
 * Use hydrasdr_lib_version() to query the version of the library being used.
 * This is useful for ensuring compatibility and debugging.
 *
 * @since v1.0.0
 */
typedef struct {
	uint32_t major_version; /**< Major version number (potentially incompatible API changes) */
	uint32_t minor_version; /**< Minor version number (backward-compatible additions) */
	uint32_t revision;      /**< Revision number (bug fixes, no API changes) */
} hydrasdr_lib_version_t;

/**
 * @brief Device part ID and serial number structure
 *
 * Contains the MCU part identification and unique serial number.
 * These values are read-only and uniquely identify the device hardware.
 *
 * @since v1.0.0
 */
typedef struct {
	uint32_t part_id[2];   /**< MCU part ID (2 x 32-bit values, device-specific format) */
	uint32_t serial_no[4]; /**< Unique serial number (128-bit value in 4 x 32-bit words) */
} hydrasdr_read_partid_serialno_t;

/**
 * @brief RF frontend component types
 *
 * Identifies the type of RF frontend component in the device.
 * Used in hydrasdr_component_info_t to describe device components.
 *
 * @since v1.1.0
 */
typedef enum
{
	HYDRASDR_COMP_UNKNOWN     = 0, /**< Unknown or unspecified component type */
	HYDRASDR_COMP_RF_FRONTEND = 1, /**< RF frontend (tuner, RFIC, or hybrid - R828D, AD936x, etc.) */
	HYDRASDR_COMP_CLOCKGEN    = 2, /**< Clock generator/synthesizer (SI5351C, etc.) */
	HYDRASDR_COMP_LNA         = 3, /**< External Low Noise Amplifier */
	HYDRASDR_COMP_PA          = 4, /**< Power Amplifier (TX) */
	HYDRASDR_COMP_ADC         = 5, /**< Analog-to-Digital Converter (direct sampling) */
	HYDRASDR_COMP_DAC         = 6, /**< Digital-to-Analog Converter (TX) */
	HYDRASDR_COMP_FILTER      = 7, /**< External filter bank */
	HYDRASDR_COMP_MIXER       = 8, /**< External mixer */
} hydrasdr_component_type_t;

/**
 * @brief RF frontend component information
 *
 * Describes an RF frontend component in the device such as a tuner,
 * clock generator, LNA, or other RF subsystem.
 *
 * @note Use component type enum for programmatic identification
 * @note Use name field for display purposes
 * @note register_count is 0 if component registers are not accessible
 *
 * @since v1.1.0
 */
#define HYDRASDR_COMPONENT_NAME_MAX_LEN (32)
typedef struct
{
	uint8_t type;              /**< Component type (hydrasdr_component_type_t) */
	uint8_t reserved_type[3];  /**< Reserved for alignment */
	char name[HYDRASDR_COMPONENT_NAME_MAX_LEN]; /**< Human-readable name (e.g., "R828D", "SI5351C") */
	uint32_t register_count;   /**< Number of accessible registers (0 if N/A) */
	uint32_t reserved[6];      /**< Reserved for future use (64 bytes total) */
} hydrasdr_component_info_t;

/**
 * @brief Temperature sensor information structure
 *
 * Contains temperature reading from device's temperature sensor.
 * Check the valid field before using temperature values.
 *
 * @note Not all devices have temperature sensors
 * @note Check HYDRASDR_CAP_TEMPERATURE_SENSOR capability
 *
 * @since v1.1.0
 */
typedef struct
{
	float temperature_celsius;    /**< Temperature in Celsius */
	float temperature_fahrenheit; /**< Temperature in Fahrenheit */
	int8_t valid;                 /**< 1 if temperature reading is valid, 0 if sensor unavailable/error */
	uint8_t reserved[7];          /**< Reserved for 64-bit alignment */
} hydrasdr_temperature_t;

/**
 * @brief Gain range information structure
 *
 * Describes the valid range for a specific gain control, including minimum,
 * maximum, step size, and recommended default value.
 *
 * @note Values are hardware-specific
 * @note Only valid if corresponding capability flag is set
 *
 * @since v1.1.0
 */
typedef struct
{
	uint8_t min_value;     /**< Minimum gain value */
	uint8_t max_value;     /**< Maximum gain value */
	uint8_t step_value;    /**< Step size between valid values */
	uint8_t default_value; /**< Default/recommended gain value */
	uint8_t reserved[4];   /**< Reserved for 64bits alignment */
} hydrasdr_gain_range_t;

/**
 * @brief Bias Tee electrical characteristics structure
 *
 * Describes the electrical characteristics of the device's bias tee.
 * Only valid if HYDRASDR_CAP_BIAS_TEE capability is present.
 *
 * @note Values are device-specific
 * @note Check capability flags before using
 *
 * @since v1.1.0
 */
typedef struct
{
	float voltage;              /**< Output voltage in Volts */
	float max_current_milliamp; /**< Maximum safe current in milliamps */
} hydrasdr_bias_tee_t;

/**
 * @brief RF port information structure
 *
 * Describes characteristics of an individual RF port.
 * Only valid if HYDRASDR_CAP_RF_PORT_SELECT capability is present.
 *
 * @note Values are hardware-specific
 * @note Not all ports may have all information populated
 *
 * @since v1.1.0
 */
#define HYDRASDR_RF_PORT_INFO_NAME_MAX_LEN (16)
typedef struct
{
	char name[HYDRASDR_RF_PORT_INFO_NAME_MAX_LEN]; /**< Human-readable port name */
	uint64_t min_frequency;       /**< Minimum recommended frequency for this port in Hz */
	uint64_t max_frequency;       /**< Maximum recommended frequency for this port in Hz */
	uint8_t has_bias_tee;         /**< 1 if this port supports bias tee, 0 otherwise */
	uint8_t bias_tee_reserved[7]; /**< Reserved for 64bits alignment */
	hydrasdr_bias_tee_t bias_tee; /**< only valid if has_bias_tee = 1 */
	uint8_t reserved[8];          /**< Reserved for 64-bit alignment */
} hydrasdr_rf_port_info_t;

/**
 * @brief Comprehensive device information and capabilities structure
 *
 * This structure contains complete information about the connected device.
 * All specific values, ranges, and capabilities are device-dependent.
 *
 * Applications MUST query this structure at runtime to determine:
 * - Which features are available (check features bitmask)
 * - Valid ranges for gain controls
 * - Frequency range limits
 * - RF port availability
 * - Sample rate capabilities
 *
 * Use hydrasdr_get_device_info() to populate this structure at runtime.
 * This function can be called at any time, including during streaming,
 * to query current configuration state (fields ordered to match typical API call sequence):
 * - current_decimation_mode: 0=Low Bandwidth, 1=High Definition
 * - current_packing: 0=16-bit, 1=12-bit packed
 * - bandwidth_auto_selected: 1 if bandwidth was auto-selected, 0 if manually set
 * - current_adc_bits: ADC bit depth for current sample rate (8, 10, 12, 14, or 16)
 * - current_decimation_factor: Decimation factor (1, 2, 4, 8, 16, 32, or 64)
 * - current_bandwidth: Currently active RF bandwidth in Hz
 * - current_samplerate: Currently active effective sample rate in Hz
 * - current_hw_samplerate: Actual hardware/ADC sample rate in Hz
 * - current_sample_type: Current sample type (enum hydrasdr_sample_type)
 *
 * @note Structure version field allows future extensions without breaking compatibility
 * @note Reserved fields maintain memory layout for future additions
 * @note All device-specific values must be queried, never assumed
 *
 * @section version_compat Version Compatibility
 *
 * The struct_version field uses semantic versioning:
 * - Major: Incompatible structural changes
 * - Minor: New fields added at end (backward compatible)
 * - Revision: Documentation or clarification only
 *
 * Applications should check struct_version to ensure compatibility.
 *
 * @since v1.1.0
 */
#define HYDRASDR_DEVICE_NAME_MAX_LEN (32)
#define HYDRASDR_DEVICE_INFO_FW_VERSION_MAX_LEN (128)
typedef struct
{
	/* Structure version for compatibility */
	uint16_t struct_version_major;    /**< Structure major version */
	uint16_t struct_version_minor;    /**< Structure minor version */
	uint16_t struct_version_revision; /**< Structure revision version */
	uint16_t struct_reserved;         /**< Reserved for 64bits alignment */

	/* Board identification */
	uint8_t board_id;          /**< Board ID (enum hydrasdr_board_id) */
	uint8_t board_reserved[7]; /**< Reserved for 64bits alignment */
	char board_name[HYDRASDR_DEVICE_NAME_MAX_LEN]; /**< Human-readable board name (null-terminated) */
	char firmware_version[HYDRASDR_DEVICE_INFO_FW_VERSION_MAX_LEN]; /**< Firmware version string (null-terminated) */
	hydrasdr_read_partid_serialno_t part_serial; /**< MCU part ID and unique serial number */

	/* Feature capabilities bitmask */
	uint32_t features;             /**< Bitmask of supported features (hydrasdr_capability_t) */
	uint32_t features_reserved[3]; /**< Reserved for future capability flags */

	/* Gain ranges (only valid if corresponding capability flag is set) */
	hydrasdr_gain_range_t lna_gain;         /**< LNA gain range if HYDRASDR_CAP_LNA_GAIN */
	hydrasdr_gain_range_t gain1_res[4];     /**< Reserved for future gain controls */

	hydrasdr_gain_range_t rf_gain;          /**< RF gain range if HYDRASDR_CAP_RF_GAIN */
	hydrasdr_gain_range_t gain2_res[4];     /**< Reserved for future gain controls */

	hydrasdr_gain_range_t mixer_gain;       /**< Mixer gain range if HYDRASDR_CAP_MIXER_GAIN */
	hydrasdr_gain_range_t gain3_res[4];     /**< Reserved for future gain controls */

	hydrasdr_gain_range_t filter_gain;      /**< Filter gain range if HYDRASDR_CAP_FILTER_GAIN */
	hydrasdr_gain_range_t gain4_res[4];     /**< Reserved for future gain controls */

	hydrasdr_gain_range_t vga_gain;         /**< VGA gain range if HYDRASDR_CAP_VGA_GAIN */
	hydrasdr_gain_range_t gain5_res[4];     /**< Reserved for future gain controls */

	hydrasdr_gain_range_t linearity_gain;   /**< Linearity gain range if HYDRASDR_CAP_LINEARITY_GAIN */
	hydrasdr_gain_range_t sensitivity_gain; /**< Sensitivity gain range if HYDRASDR_CAP_SENSITIVITY_GAIN */
	hydrasdr_gain_range_t gain6_res[4];     /**< Reserved for future gain controls */

	/* RF Frontend Components (tuner, clock generator, etc.)
	 * Component 0 is typically the RF frontend (if HYDRASDR_CAP_RF_FRONTEND)
	 * Component 1 is typically the clock generator (if HYDRASDR_CAP_CLOCKGEN)
	 * Additional components may be present for enhanced hardware */
#define HYDRASDR_MAX_COMPONENTS (2)
	uint8_t component_count;       /**< Number of RF frontend components (0-HYDRASDR_MAX_COMPONENTS) */
	uint8_t component_reserved[7]; /**< Reserved for alignment */
	hydrasdr_component_info_t components[HYDRASDR_MAX_COMPONENTS]; /**< RF frontend component details */

	/* Frequency range in Hz */
	uint64_t min_frequency; /**< Minimum supported frequency in Hz */
	uint64_t max_frequency; /**< Maximum supported frequency in Hz */
	uint32_t freq_res[8];   /**< Reserved for future frequency parameters */

	/* RF ports (bitmask of available ports) */
	uint32_t rf_ports;           /**< Bitmask: Bit 0=RX0, Bit 1=RX1, Bit 2=RX2, etc. (max 32 ports) */
	uint8_t rf_port_count;       /**< Number of RF ports available */
	uint8_t rf_port_reserved[3]; /**< Reserved for 64bits alignment */
	/* Bias tee details for each rf port if HYDRASDR_CAP_BIAS_TEE */
	hydrasdr_rf_port_info_t rf_port_info[32]; /**< Detailed info for up to 32 RF ports */
	uint32_t rf_ports_res[8]; /**< Reserved for future RF port parameters */

	/* GPIO availability if HYDRASDR_CAP_GPIO */
	uint16_t gpio_count;        /**< Total number of GPIO pins available */
	uint8_t gpio_reserved_1[6]; /**< Reserved for 64bits alignment */
	uint32_t gpio_res[4];       /**< Reserved for future GPIO parameters */

	/* Sample types supported (bitmask) */
	uint16_t sample_types;            /**< Bitfield of enum hydrasdr_sample_type values */
	uint16_t sample_type_reserved[3]; /**< Reserved for 64bits alignment */
	uint32_t sample_res[2];           /**< Reserved for future sample type parameters */

	/* Power and thermal information */
	float typical_power_mw;         /**< Typical power consumption in milliwatts */
	float max_power_mw;             /**< Maximum power consumption in milliwatts */
	float max_safe_temp_celsius;    /**< Maximum safe internal PCB temperature in Celsius (as measured by temp sensor) */
	float max_safe_temp_fahrenheit; /**< Maximum safe internal PCB temperature in Fahrenheit (as measured by temp sensor) */
	uint32_t power_res[4];          /**< Reserved for future power parameters */

	/* Current configuration state (populated by hydrasdr_get_device_info, can be queried during streaming)
	 * Fields ordered to match typical API call sequence: decimation_mode -> bandwidth -> samplerate */
	uint8_t current_decimation_mode;    /**< Current decimation mode (0=Low Bandwidth, 1=High Definition) */
	uint8_t current_packing;            /**< Current packing mode (0=16-bit, 1=12-bit packed) */
	uint8_t bandwidth_auto_selected;    /**< 1 if bandwidth was auto-selected, 0 if manually set */
	uint8_t current_adc_bits;           /**< ADC bit depth for current sample rate (8, 10, 12, 14, or 16); 0 if unknown @since v1.1.2 */
	uint32_t current_decimation_factor; /**< Current decimation factor (1, 2, 4, 8, 16, 32, or 64) */
	uint32_t current_bandwidth;         /**< Currently selected RF bandwidth in Hz (0 if not set) */
	uint32_t current_samplerate;        /**< Currently selected effective sample rate in Hz (0 if not set) */
	uint32_t current_hw_samplerate;     /**< Actual hardware/ADC sample rate in Hz (may differ from effective when decimating) */
	uint32_t current_sample_type;       /**< Current sample type (enum hydrasdr_sample_type) */
	uint32_t config_state_reserved2[2]; /**< Reserved for 64-bit alignment (32 bytes total) */

	/* Reserved for future expansion (maintain 64bits alignment and total size 4096 bytes) */
	uint64_t reserved[189]; /**< Reserved bytes for future extensions */
} hydrasdr_device_info_t;

/* ============================================================================
 * Compile-Time Structure Size Verification
 * ========================================================================== */
#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
	/* C11 standard _Static_assert */
	_Static_assert(sizeof(hydrasdr_device_info_t) == 4096,
	               "hydrasdr_device_info_t must be exactly 4096 bytes");

#elif defined(__cplusplus) && __cplusplus >= 201103L
	/* C++11 standard static_assert */
	static_assert(sizeof(hydrasdr_device_info_t) == 4096,
	              "hydrasdr_device_info_t must be exactly 4096 bytes");

#elif defined(_MSC_VER) && _MSC_VER >= 1600
	/* MSVC static_assert (VS2010+) */
	static_assert(sizeof(hydrasdr_device_info_t) == 4096,
	              "hydrasdr_device_info_t must be exactly 4096 bytes");

#else
	/* Fallback for older compilers */
	typedef char hydrasdr_device_info_size_check[
		(sizeof(hydrasdr_device_info_t) == 4096) ? 1 : -1
	];
#endif

/* ============================================================================
 * Streaming Statistics Structure
 * ========================================================================== */

/**
 * @brief Streaming statistics for performance monitoring and debugging
 *
 * This structure contains counters for monitoring streaming performance,
 * detecting buffer overflows, and tracking buffer allocation.
 *
 * @note All counters are cumulative since streaming started
 * @note Use hydrasdr_get_streaming_stats() to retrieve current values
 * @note Counters are reset when streaming starts
 *
 * @par Example:
 * @code
 * hydrasdr_streaming_stats_t stats;
 * if (hydrasdr_get_streaming_stats(dev, &stats) == HYDRASDR_SUCCESS) {
 *     printf("Received: %llu buffers\n", stats.buffers_received);
 *     printf("Processed: %llu buffers\n", stats.buffers_processed);
 *     if (stats.buffers_dropped > 0) {
 *         printf("WARNING: %llu buffers dropped!\n", stats.buffers_dropped);
 *     }
 * }
 * @endcode
 */
typedef struct {
	uint64_t buffers_received;   /**< Total USB buffers received from hardware */
	uint64_t buffers_processed;  /**< Total buffers processed by consumer thread */
	uint64_t buffers_dropped;    /**< Buffers dropped due to queue overflow (data loss!) */
	uint64_t reserved[5];        /**< Reserved for future expansion */
} hydrasdr_streaming_stats_t;

/* ========================================================================
 * Core API Functions
 * ======================================================================== */

/**
 * @defgroup core_api Core Device Management
 * @ingroup hydrasdr_api
 * @brief Functions for library version, device enumeration, opening, and closing
 * @{
 */

/**
 * @brief Get the library version information
 * 
 * Retrieves the version of the HydraSDR library being used.
 * 
 * @param[out] lib_version Pointer to structure to receive version information
 * 
 * @note This function always succeeds and does not return an error code
 * @note Thread-safe
 * 
 * @par Example:
 * @code
 * hydrasdr_lib_version_t version;
 * hydrasdr_lib_version(&version);
 * printf("Library version: %d.%d.%d\n",
 *        version.major_version,
 *        version.minor_version,
 *        version.revision);
 * @endcode
 *
 * @since v1.0.0
 */
extern ADDAPI void ADDCALL hydrasdr_lib_version(hydrasdr_lib_version_t* lib_version);

/**
 * @brief List all connected HydraSDR devices
 * 
 * Enumerates all HydraSDR devices connected to the system and returns
 * their serial numbers. This function can be called in two modes:
 * 
 * 1. Query mode (serials=NULL): Returns count of devices found
 * 2. Retrieval mode (serials!=NULL): Fills array with serial numbers
 * 
 * @param[out] serials Array to receive device serial numbers (can be NULL)
 * @param[in]  count   Size of the serials array (ignored if serials is NULL)
 * 
 * @return Number of devices found (positive value), or error code on failure
 * 
 * @note Serial numbers are unique 64-bit identifiers
 * @note Not thread-safe (do not call simultaneously from multiple threads)
 * @note Requires USB enumeration which may take 100-500ms
 * 
 * @warning On Linux, requires appropriate udev rules for USB access
 * 
 * @par Example:
 * @code
 * // First, get count of devices
 * int count = hydrasdr_list_devices(NULL, 0);
 * if (count > 0) {
 *     uint64_t *serials = malloc(count * sizeof(uint64_t));
 *     hydrasdr_list_devices(serials, count);
 *     for (int i = 0; i < count; i++) {
 *         uint32_t serial_number_msb_val = (uint32_t)(serials[i] >> 32);
 *         uint32_t serial_number_lsb_val = (uint32_t)(serials[i] & 0xFFFFFFFF);
 *         printf("Device %d: 0x%08X%08X\n", i, serial_number_msb_val, serial_number_lsb_val);
 *     }
 *     free(serials);
 * } else if (count == 0) {
 *     printf("No devices found\n");
 * } else {
 *     printf("Error: %s\n", hydrasdr_error_name(count));
 * }
 * @endcode
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_list_devices(uint64_t *serials, int count);

/**
 * @brief Open HydraSDR device by serial number
 * 
 * Opens a specific HydraSDR device identified by its serial number.
 * Use hydrasdr_list_devices() to enumerate available devices first.
 * 
 * @param[out] device        Pointer to receive device handle (set to NULL on error)
 * @param[in]  serial_number 64-bit serial number of device to open
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Device must be closed with hydrasdr_close() when done
 * @note Only one device handle can exist per physical device
 * @note Not thread-safe during device opening
 * @note Device is initialized to default state
 * 
 * @warning Do not call from signal handler or interrupt context
 * 
 * @par Example:
 * @code
 * struct hydrasdr_device *dev;
 * int result = hydrasdr_open_sn(&dev, serial_number);
 * if (result == HYDRASDR_SUCCESS) {
 *     // Use device...
 *     hydrasdr_close(dev);
 * } else {
 *     printf("Error: %s\n", hydrasdr_error_name(result));
 * }
 * @endcode
 * 
 * @see hydrasdr_list_devices()
 * @see hydrasdr_close()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_open_sn(struct hydrasdr_device** device, uint64_t serial_number);

/**
 * @brief Open HydraSDR device by file descriptor (Android/embedded)
 * 
 * Opens a HydraSDR device using an existing file descriptor.
 * This is primarily used on Android or embedded systems.
 * 
 * @param[out] device Pointer to receive device handle (set to NULL on error)
 * @param[in]  fd     File descriptor of opened USB device
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note File descriptor must remain valid for lifetime of device handle
 * @note Device must be closed with hydrasdr_close() when done
 * @note Not thread-safe during device opening
 * 
 * @warning File descriptor must refer to a HydraSDR USB device
 * @warning Do not close file descriptor while device handle is active
 *
 * @see hydrasdr_close()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_open_fd(struct hydrasdr_device** device, int fd);

/**
 * @brief Open first available HydraSDR device
 * 
 * Opens the first HydraSDR device found on the system.
 * 
 * @param[out] device Pointer to receive device handle (set to NULL on error)
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Device must be closed with hydrasdr_close() when done
 * @note Not deterministic if multiple devices present
 * @note Not thread-safe during device opening
 * 
 * @par Example:
 * @code
 * struct hydrasdr_device *dev;
 * if (hydrasdr_open(&dev) == HYDRASDR_SUCCESS) {
 *     hydrasdr_device_info_t info;
 *     hydrasdr_get_device_info(dev, &info);
 *     printf("Opened: %s\n", info.board_name);
 *     hydrasdr_close(dev);
 * }
 * @endcode
 * 
 * @see hydrasdr_open_sn()
 * @see hydrasdr_close()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_open(struct hydrasdr_device** device);

/**
 * @brief Close HydraSDR device
 * 
 * Closes the device handle and releases all associated resources.
 * If streaming is active, it will be stopped automatically.
 * 
 * @param[in] device Device handle to close (can be NULL for no-op)
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note After calling, the device handle is invalid and must not be used
 * @note Safe to call with NULL device pointer
 * @note Blocks until all streaming threads have terminated
 * @note Thread-safe
 * 
 * @warning Do NOT call from within the sample callback function
 * @warning Do NOT use device handle after calling this function
 * 
 * @see hydrasdr_open()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_close(struct hydrasdr_device* device);

/** @} */ // end of core_api group

/**
 * @defgroup config_api Device Configuration
 * @ingroup hydrasdr_api
 * @brief Functions for configuring device parameters
 * 
 * All configuration functions are hardware-dependent. Use hydrasdr_get_device_info()
 * to determine valid ranges and supported features before calling configuration functions.
 * @{
 */

/**
 * @brief Get list of supported sample rates
 * 
 * Retrieves the list of sample rates supported by the device.
 * 
 * @param[in]  device Device handle
 * @param[out] buffer Array to receive sample rates in Hz (or count if len=0)
 * @param[in]  len    Size of buffer array (0 to query count)
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note When len=0, buffer[0] will contain the count of available sample rates
 * @note Sample rates are returned in Hz
 * @note Thread-safe after device is opened
 * 
 * @par Example:
 * @code
 * uint32_t count;
 * hydrasdr_get_samplerates(dev, &count, 0);
 * uint32_t *rates = malloc(count * sizeof(uint32_t));
 * hydrasdr_get_samplerates(dev, rates, count);
 * free(rates);
 * @endcode
 * 
 * @see hydrasdr_set_samplerate()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_get_samplerates(struct hydrasdr_device* device, uint32_t* buffer, const uint32_t len);

/**
 * @brief Set device sample rate
 * 
 * Sets the sample rate for the device. The parameter can be either:
 * - An index into the list returned by hydrasdr_get_samplerates()
 * - A direct sample rate value in Hz
 * 
 * @param[in] device     Device handle
 * @param[in] samplerate Sample rate index or value in Hz
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Use hydrasdr_get_samplerates() to query supported rates
 * @note Sample rate should be set before starting streaming
 * @note Can be called while streaming (may cause brief glitch)
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_get_samplerates()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_samplerate(struct hydrasdr_device* device, uint32_t samplerate);

/**
 * @brief Set decimation mode for sample rate selection
 *
 * Controls how the library selects hardware sample rate when decimation is needed.
 * This affects the tradeoff between USB bandwidth and signal quality.
 *
 * @param[in] device Device handle
 * @param[in] mode   Decimation mode (enum hydrasdr_decimation_mode)
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @note Default mode is HYDRASDR_DEC_MODE_LOW_BANDWIDTH
 * @note Should be called before hydrasdr_set_samplerate() to take effect
 * @note Thread-safe after device is opened
 *
 * @par Example:
 * @code
 * // Query available sample rates first (device-specific)
 * uint32_t count;
 * hydrasdr_get_samplerates(dev, &count, 0);
 * uint32_t *rates = malloc(count * sizeof(uint32_t));
 * hydrasdr_get_samplerates(dev, rates, count);
 *
 * // Use high definition mode for better signal quality
 * hydrasdr_set_decimation_mode(dev, HYDRASDR_DEC_MODE_HIGH_DEFINITION);
 * hydrasdr_set_samplerate(dev, rates[0]);  // Use a value from hydrasdr_get_samplerates()
 * @endcode
 *
 * @note Always query available sample rates with hydrasdr_get_samplerates() - never hardcode values
 *
 * @see hydrasdr_get_decimation_mode()
 * @see hydrasdr_set_samplerate()
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_decimation_mode(struct hydrasdr_device* device, enum hydrasdr_decimation_mode mode);

/**
 * @brief Get current decimation mode
 *
 * @param[in]  device Device handle
 * @param[out] mode   Pointer to receive current decimation mode
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @see hydrasdr_set_decimation_mode()
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_get_decimation_mode(struct hydrasdr_device* device, enum hydrasdr_decimation_mode* mode);

/**
 * @brief Get list of supported bandwidths
 *
 * Retrieves the list of bandwidths supported by the device.
 * Not all devices support bandwidth selection - use hydrasdr_get_device_info()
 * to check for HYDRASDR_CAP_BANDWIDTH capability first.
 *
 * @param[in]  device Device handle
 * @param[out] buffer Array to receive bandwidths in Hz (or count if len=0)
 * @param[in]  len    Size of buffer array (0 to query count)
 *
 * @return HYDRASDR_SUCCESS on success, HYDRASDR_ERROR_UNSUPPORTED if bandwidth
 *         selection not supported, error code on failure
 *
 * @note When len=0, buffer[0] will contain the count of available bandwidths
 * @note Bandwidths are returned in Hz
 * @note Thread-safe after device is opened
 *
 * @par Example:
 * @code
 * uint32_t count;
 * hydrasdr_get_bandwidths(dev, &count, 0);
 * if (count > 0) {
 *     uint32_t *bws = malloc(count * sizeof(uint32_t));
 *     hydrasdr_get_bandwidths(dev, bws, count);
 *     free(bws);
 * }
 * @endcode
 *
 * @see hydrasdr_set_bandwidth()
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_get_bandwidths(struct hydrasdr_device* device, uint32_t* buffer, const uint32_t len);

/**
 * @brief Set device bandwidth
 *
 * Sets the bandwidth (IF filter width) for the device. The parameter can be:
 * - **HYDRASDR_BANDWIDTH_AUTO** = Auto mode: library auto-selects smallest bandwidth >= hardware sample rate
 * - An index (0 to count-1) into the list returned by hydrasdr_get_bandwidths()
 * - A direct bandwidth value in Hz (>= 1000)
 *
 * **Auto-bandwidth behavior:**
 * When HYDRASDR_BANDWIDTH_AUTO is passed, the library resets to auto-bandwidth mode.
 * If streaming is active, the optimal bandwidth is calculated and applied immediately
 * based on the current hardware sample rate. The hardware rate is used (not effective
 * rate) because the RF frontend filter is applied before decimation in High Definition mode.
 *
 * Not all devices support bandwidth selection - use hydrasdr_get_device_info()
 * to check for HYDRASDR_CAP_BANDWIDTH capability first.
 *
 * @param[in] device    Device handle
 * @param[in] bandwidth Bandwidth: HYDRASDR_BANDWIDTH_AUTO, or index, or value in Hz
 *
 * @return HYDRASDR_SUCCESS on success, HYDRASDR_ERROR_UNSUPPORTED if bandwidth
 *         selection not supported, error code on failure
 *
 * @note Use hydrasdr_get_bandwidths() to query supported bandwidths
 * @note Pass HYDRASDR_BANDWIDTH_AUTO to enable auto-bandwidth selection
 * @note Thread-safe after device is opened
 *
 * Example:
 * @code
 * // Set explicit bandwidth by Hz value
 * hydrasdr_set_bandwidth(dev, 2500000);  // 2.5 MHz
 *
 * // Set bandwidth by index
 * hydrasdr_set_bandwidth(dev, 0);  // First bandwidth in list
 *
 * // Reset to auto-bandwidth mode
 * hydrasdr_set_bandwidth(dev, HYDRASDR_BANDWIDTH_AUTO);
 * @endcode
 *
 * @see hydrasdr_get_bandwidths()
 * @see HYDRASDR_BANDWIDTH_AUTO
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_bandwidth(struct hydrasdr_device* device, uint32_t bandwidth);


/**
 * @brief List available DDC conversion algorithms
 *
 * Retrieves the list of high-level algorithm names that can be used with
 * hydrasdr_set_conversion_algorithm(). These are user-friendly names
 * representing matched filter + algorithm combinations.
 *
 * @param[out] names   Array to receive algorithm name pointers (can be NULL to query count)
 * @param[out] descriptions Array to receive description pointers (can be NULL)
 * @param[in]  max     Maximum number of entries to return (size of arrays)
 *
 * @return Number of available algorithms, or negative error code
 *
 * Example usage:
 * @code
 * // First query the count
 * int count = hydrasdr_list_conversion_algorithms(NULL, NULL, 0);
 *
 * // Then retrieve the names
 * const char *names[16];
 * const char *descriptions[16];
 * count = hydrasdr_list_conversion_algorithms(names, descriptions, 16);
 * for (int i = 0; i < count; i++) {
 *     printf("%s: %s\n", names[i], descriptions[i]);
 * }
 * @endcode
 *
 * Available algorithms:
 * | Name         | Taps | Description                    |
 * |--------------|------|--------------------------------|
 * | 47_opt       | 47   | 47-Tap Optimized (~63 dB)     |
 * | 33_fast      | 33   | 33-Tap Fast, low latency (~62 dB) |
 * | 65_pow2      | 65   | 65-Tap Power-of-2 delay (~66 dB) |
 * | 83_highperf  | 83   | 83-Tap High Performance (>100 dB) |
 * | legacy       | 47   | Original Airspy design (~63 dB) |
 *
 * @note The returned pointers are static strings and remain valid for the
 *       lifetime of the library
 * @note Thread-safe
 *
 * @see hydrasdr_set_conversion_algorithm() to configure the algorithm
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_list_conversion_algorithms(const char **names, const char **descriptions, int max);

/**
 * @brief Set DDC conversion algorithm
 *
 * Configures both float32 and int16 DDC converters with a matched
 * filter and algorithm pair. This is the recommended API for most users.
 *
 * This function atomically sets:
 * - The appropriate filter coefficients (float32 and int16)
 * - The matching algorithm implementation for both converters
 *
 * @param[in] device    Device handle
 * @param[in] algorithm Algorithm name (case-insensitive), or NULL for default
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * @retval HYDRASDR_ERROR_INVALID_PARAM Unknown algorithm name
 * @retval HYDRASDR_ERROR_BUSY Device is streaming
 * @retval HYDRASDR_ERROR_NO_MEM Memory allocation failed
 *
 * Available algorithms:
 * | Name          | Taps | Rejection | Latency | Use Case                    |
 * |---------------|------|-----------|---------|------------------------------|
 * | "47_opt"      |  47  |   ~63 dB  | Medium  | Default, good balance        |
 * | "33_fast"     |  33  |   ~62 dB  | Low     | Low-latency applications     |
 * | "65_pow2"     |  65  |   ~66 dB  | Medium  | Power-of-2 delay optimization|
 * | "83_highperf" |  83  |  >100 dB  | High    | Weak signal, high dynamic range|
 * | "legacy"      |  47  |   ~63 dB  | Medium  | Original Airspy algorithm    |
 *
 * @note Must be called before hydrasdr_start_rx()
 * @note Completely replaces any previous filter/algorithm configuration
 * @note Pass NULL or "default" to use "47_opt"
 * @note Thread-safe after device is opened
 *
 * @code
 * // Use high-performance filter for weak signal work
 * hydrasdr_set_conversion_algorithm(dev, "83_highperf");
 *
 * // Use fast filter for low-latency applications
 * hydrasdr_set_conversion_algorithm(dev, "33_fast");
 *
 * // Reset to default
 * hydrasdr_set_conversion_algorithm(dev, NULL);
 * @endcode
 *
 * @see hydrasdr_set_conversion_filter_float32() for custom filters (advanced)
 * @see hydrasdr_set_conversion_filter_int16() for custom filters (advanced)
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_conversion_algorithm(struct hydrasdr_device* device, const char *algorithm);

/**
 * @brief Set custom conversion filter for float32 samples (advanced API)
 *
 * Configures a custom FIR halfband filter kernel for float32 sample conversion.
 * This is an advanced/expert API for users who need custom filter characteristics.
 *
 * @param[in] device Device handle
 * @param[in] kernel Array of float filter coefficients (halfband FIR)
 * @param[in] len    Number of filter coefficients (typically 33, 47, 65, or 83)
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * @retval HYDRASDR_ERROR_BUSY Device is streaming
 * @retval HYDRASDR_ERROR_NO_MEM Memory allocation failed
 *
 * @warning This is an advanced API. For most use cases, prefer
 *          hydrasdr_set_conversion_algorithm() which automatically
 *          configures matched filter and algorithm pairs.
 *
 * @note Filter kernel is copied internally
 * @note Uses a default algorithm matched to the filter length
 * @note Only affects float32 sample types (HYDRASDR_SAMPLE_FLOAT32_IQ/REAL)
 * @note Calling hydrasdr_set_conversion_algorithm() will override this setting
 * @note Must be called before starting streaming
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_set_conversion_algorithm() for the recommended high-level API
 * @see hydrasdr_set_conversion_filter_int16() for int16 custom filters
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_conversion_filter_float32(struct hydrasdr_device* device, const float *kernel, const uint32_t len);

/**
 * @brief Set custom conversion filter for int16 samples (advanced API)
 *
 * Configures a custom FIR halfband filter kernel for int16 sample conversion.
 * This is an advanced/expert API for users who need custom filter characteristics.
 *
 * @param[in] device Device handle
 * @param[in] kernel Array of int16 filter coefficients (halfband FIR, scaled)
 * @param[in] len    Number of filter coefficients (typically 33, 47, 65, or 83)
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * @retval HYDRASDR_ERROR_BUSY Device is streaming
 * @retval HYDRASDR_ERROR_NO_MEM Memory allocation failed
 *
 * @warning This is an advanced API. For most use cases, prefer
 *          hydrasdr_set_conversion_algorithm() which automatically
 *          configures matched filter and algorithm pairs.
 *
 * @note Filter kernel is copied internally
 * @note Uses a default algorithm matched to the filter length
 * @note Only affects int16 sample types (HYDRASDR_SAMPLE_INT16_IQ/REAL)
 * @note Calling hydrasdr_set_conversion_algorithm() will override this setting
 * @note Must be called before starting streaming
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_set_conversion_algorithm() for the recommended high-level API
 * @see hydrasdr_set_conversion_filter_float32() for float32 custom filters
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_conversion_filter_int16(struct hydrasdr_device* device, const int16_t *kernel, const uint32_t len);

/**
 * @brief Set sample data type
 *
 * Configures the format of samples delivered to the callback function.
 * Must be set before starting streaming.
 * 
 * @param[in] device      Device handle
 * @param[in] sample_type Sample format (enum hydrasdr_sample_type)
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check sample_types field in hydrasdr_device_info_t for supported types
 * @note Must be called before hydrasdr_start_rx()
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_sample_type(struct hydrasdr_device* device, enum hydrasdr_sample_type sample_type);

/**
 * @brief Set receiver center frequency
 * 
 * Sets the center frequency of the receiver in Hz.
 * 
 * @param[in] device  Device handle
 * @param[in] freq_hz Frequency in Hz
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Use hydrasdr_get_device_info() to query min/max frequency
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 * 
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_freq(struct hydrasdr_device* device, const uint64_t freq_hz);

/**
 * @brief Enable/disable sample packing
 * 
 * Controls sample packing mode to reduce USB bandwidth.
 * 
 * @param[in] device Device handle
 * @param[in] value  0 = disable packing, 1 = enable packing
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_PACKING capability before using
 * @note Must be set before starting streaming
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_packing(struct hydrasdr_device* device, uint8_t value);

/** @} */ // end of config_api group

/**
 * @defgroup streaming_api Streaming Control
 * @ingroup hydrasdr_api
 * @brief Functions for starting, stopping, and monitoring data acquisition
 * @{
 */

/**
 * @brief Start receiving samples from device
 * 
 * Begins streaming samples from the device. The callback function
 * will be called repeatedly with buffers of received samples.
 * 
 * @param[in] device   Device handle
 * @param[in] callback Function to call with received sample buffers
 * @param[in] rx_ctx   User context pointer passed to callback (can be NULL)
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Callback is called from a separate thread
 * @note Callback should process samples quickly
 * @note Configure device before calling this function
 * @note Thread-safe
 * 
 * @warning Callback must NOT block for extended periods
 * @warning Callback must NOT call hydrasdr_close() or hydrasdr_stop_rx()
 * @warning To stop from callback, return non-zero value
 * 
 * @see hydrasdr_stop_rx()
 * @see hydrasdr_sample_block_cb_fn
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_start_rx(struct hydrasdr_device* device, hydrasdr_sample_block_cb_fn callback, void* rx_ctx);

/**
 * @brief Stop receiving samples from device
 * 
 * Stops the streaming operation started by hydrasdr_start_rx().
 * This function blocks until all streaming threads have terminated.
 * 
 * @param[in] device Device handle
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Safe to call even if streaming is not active
 * @note After this function returns, no more callbacks will occur
 * @note Thread-safe
 * 
 * @warning Do NOT call from within the sample callback
 * 
 * @see hydrasdr_start_rx()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_stop_rx(struct hydrasdr_device* device);

/**
 * @brief Check if device is currently streaming
 * 
 * Returns the current streaming state of the device.
 * 
 * @param[in] device Device handle
 * 
 * @return HYDRASDR_TRUE (1) if streaming is active, 0 if not streaming
 * 
 * @note Thread-safe
 * @note Returns 0 if device handle is NULL
 * 
 * @see hydrasdr_start_rx()
 * @see hydrasdr_stop_rx()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_is_streaming(struct hydrasdr_device* device);

/**
 * @brief Get streaming statistics for performance monitoring
 *
 * Retrieves statistics about the current or last streaming session,
 * including buffer counts and overflow detection.
 *
 * @param[in]  device   Pointer to an open HydraSDR device
 * @param[out] stats    Pointer to structure to receive statistics
 *
 * @return HYDRASDR_SUCCESS on success
 * @return HYDRASDR_ERROR_INVALID_PARAM if device or stats is NULL
 *
 * @note Statistics are cumulative since streaming started
 * @note If buffers_dropped > 0, data was lost due to slow processing
 * @note Thread-safe
 *
 * @par Example:
 * @code
 * hydrasdr_streaming_stats_t stats;
 * hydrasdr_get_streaming_stats(dev, &stats);
 * printf("Buffers: received=%llu, processed=%llu, dropped=%llu\n",
 *        stats.buffers_received, stats.buffers_processed, stats.buffers_dropped);
 * if (stats.buffers_dropped > 0) {
 *     fprintf(stderr, "WARNING: %llu buffers lost!\n", stats.buffers_dropped);
 * }
 * @endcode
 *
 * @see hydrasdr_start_rx()
 * @see hydrasdr_stop_rx()
 * @see hydrasdr_streaming_stats_t
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_get_streaming_stats(struct hydrasdr_device* device, hydrasdr_streaming_stats_t* stats);

/** @} */ // end of streaming_api group

/**
 * @defgroup gain_api Gain Control
 * @ingroup hydrasdr_api
 * @brief Functions for controlling receiver gain stages
 * 
 * Gain control availability varies by hardware. Use hydrasdr_get_device_info()
 * to check which gain controls are supported and their valid ranges.
 * @{
 */

/**
 * @brief Set LNA (Low Noise Amplifier) gain
 *
 * @deprecated Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_LNA, value) instead.
 *
 * Sets the gain of the LNA stage.
 *
 * @param[in] device Device handle
 * @param[in] value  Gain value (hardware-specific range)
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @note Check HYDRASDR_CAP_LNA_GAIN capability before using
 * @note Use hydrasdr_get_device_info() to query valid range
 * @note Disable LNA AGC before setting manual gain
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_set_gain()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_set_gain() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_LNA, value) instead")
int ADDCALL hydrasdr_set_lna_gain(struct hydrasdr_device* device, uint8_t value);

/**
 * @brief Set mixer gain
 *
 * @deprecated Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_MIXER, value) instead.
 *
 * Sets the gain of the mixer stage.
 *
 * @param[in] device Device handle
 * @param[in] value  Gain value (hardware-specific range)
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @note Check HYDRASDR_CAP_MIXER_GAIN capability before using
 * @note Use hydrasdr_get_device_info() to query valid range
 * @note Disable mixer AGC before setting manual gain
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_set_gain()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_set_gain() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_MIXER, value) instead")
int ADDCALL hydrasdr_set_mixer_gain(struct hydrasdr_device* device, uint8_t value);

/**
 * @brief Set VGA (Variable Gain Amplifier) gain
 *
 * @deprecated Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_VGA, value) instead.
 *
 * Sets the gain of the VGA stage.
 *
 * @param[in] device Device handle
 * @param[in] value  Gain value (hardware-specific range)
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @note Check HYDRASDR_CAP_VGA_GAIN capability before using
 * @note Use hydrasdr_get_device_info() to query valid range
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_set_gain()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_set_gain() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_VGA, value) instead")
int ADDCALL hydrasdr_set_vga_gain(struct hydrasdr_device* device, uint8_t value);

/**
 * @brief Enable/disable LNA automatic gain control
 *
 * @deprecated Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_LNA_AGC, value) instead.
 *
 * Controls the LNA AGC (Automatic Gain Control).
 *
 * @param[in] device Device handle
 * @param[in] value  0 = disable AGC, 1 = enable AGC
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @note Check HYDRASDR_CAP_LNA_AGC capability before using
 * @note When AGC is enabled, manual LNA gain setting is ignored
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_set_gain()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_set_gain() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_LNA_AGC, value) instead")
int ADDCALL hydrasdr_set_lna_agc(struct hydrasdr_device* device, uint8_t value);

/**
 * @brief Enable/disable mixer automatic gain control
 *
 * @deprecated Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_MIXER_AGC, value) instead.
 *
 * Controls the mixer AGC (Automatic Gain Control).
 *
 * @param[in] device Device handle
 * @param[in] value  0 = disable AGC, 1 = enable AGC
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @note Check HYDRASDR_CAP_MIXER_AGC capability before using
 * @note When AGC is enabled, manual mixer gain setting is ignored
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_set_gain()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_set_gain() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_MIXER_AGC, value) instead")
int ADDCALL hydrasdr_set_mixer_agc(struct hydrasdr_device* device, uint8_t value);

/**
 * @brief Set linearity gain mode
 *
 * @deprecated Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_LINEARITY, value) instead.
 *
 * Sets a global gain profile optimized for linearity.
 *
 * @param[in] device Device handle
 * @param[in] value  Gain index (hardware-specific range)
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @note Check HYDRASDR_CAP_LINEARITY_GAIN capability before using
 * @note Use hydrasdr_get_device_info() to query valid range
 * @note Automatically disables all AGC modes
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_set_gain()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_set_gain() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_LINEARITY, value) instead")
int ADDCALL hydrasdr_set_linearity_gain(struct hydrasdr_device* device, uint8_t value);

/**
 * @brief Set sensitivity gain mode
 *
 * @deprecated Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_SENSITIVITY, value) instead.
 *
 * Sets a global gain profile optimized for sensitivity.
 *
 * @param[in] device Device handle
 * @param[in] value  Gain index (hardware-specific range)
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @note Check HYDRASDR_CAP_SENSITIVITY_GAIN capability before using
 * @note Use hydrasdr_get_device_info() to query valid range
 * @note Automatically disables all AGC modes
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_set_gain()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_set_gain() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_SENSITIVITY, value) instead")
int ADDCALL hydrasdr_set_sensitivity_gain(struct hydrasdr_device* device, uint8_t value);

/**
 * @brief Get gain information for a specific gain type (unified API)
 *
 * Retrieves the cached gain info for the specified gain type.
 * This unified API replaces the need to check individual capability flags
 * and call separate functions for each gain type.
 *
 * @param[in]  device Device handle
 * @param[in]  type   Gain type to query (hydrasdr_gain_type_t)
 * @param[out] info   Pointer to structure to receive gain information
 *
 * @return HYDRASDR_SUCCESS on success, HYDRASDR_ERROR_UNSUPPORTED if gain
 *         has not been cached, error code on failure
 *
 * @note Returns cached values (no USB query)
 * @note Thread-safe after device is opened
 *
 * @par Example:
 * @code
 * hydrasdr_gain_info_t info;
 * if (hydrasdr_get_gain(dev, HYDRASDR_GAIN_TYPE_VGA, &info) == HYDRASDR_SUCCESS) {
 *     printf("VGA gain: %d (range: %d-%d)\n", info.value, info.min_value, info.max_value);
 * }
 * @endcode
 *
 * @see hydrasdr_set_gain()
 * @see hydrasdr_get_all_gains()
 * @see hydrasdr_gain_type_t
 * @see hydrasdr_gain_info_t
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_get_gain(struct hydrasdr_device* device, hydrasdr_gain_type_t type, hydrasdr_gain_info_t* info);

/**
 * @brief Set gain value for a specific gain type (unified API)
 *
 * Sets the gain value for the specified gain type. This unified API provides
 * a single interface for all gain types instead of separate functions.
 *
 * For manual gains (LNA, RF, Mixer, Filter, VGA): value is the gain level.
 * For presets (Linearity, Sensitivity): value is the preset index.
 * For AGC enables: value is 0=off, 1=on.
 *
 * @param[in] device Device handle
 * @param[in] type   Gain type to set (hydrasdr_gain_type_t)
 * @param[in] value  Value to set (interpretation depends on gain type)
 *
 * @return HYDRASDR_SUCCESS on success, HYDRASDR_ERROR_UNSUPPORTED if type
 *         not supported, error code on failure
 *
 * @note Requires HYDRASDR_CAP_EXTENDED_GAIN for full functionality
 * @note Falls back to legacy commands for basic types (LNA, Mixer, VGA, AGCs)
 *       on firmware without extended gain support
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 *
 * @par Example:
 * @code
 * // Set VGA gain to 10
 * hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_VGA, 10);
 *
 * // Enable LNA AGC
 * hydrasdr_set_gain(dev, HYDRASDR_GAIN_TYPE_LNA_AGC, 1);
 * @endcode
 *
 * @see hydrasdr_get_gain()
 * @see hydrasdr_gain_type_t
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_gain(struct hydrasdr_device* device, hydrasdr_gain_type_t type, uint8_t value);

/**
 * @brief Get cached info for all gains (unified API)
 *
 * Retrieves cached gain information for all gain types that have been
 * cached. Only gains that have been cached will be returned.
 *
 * @param[in]     device Device handle
 * @param[out]    gains  Array to receive gain information structures
 * @param[in,out] count  On input: maximum number of entries in gains array.
 *                       On output: number of cached gains actually returned.
 *
 * @return HYDRASDR_SUCCESS on success, HYDRASDR_ERROR_UNSUPPORTED if
 *         no gains have been cached, error code on failure
 *
 * @note Returns cached values (no USB query)
 * @note Thread-safe after device is opened
 *
 * @par Example:
 * @code
 * hydrasdr_gain_info_t gains[HYDRASDR_GAIN_TYPE_COUNT];
 * uint8_t count = HYDRASDR_GAIN_TYPE_COUNT;
 * if (hydrasdr_get_all_gains(dev, gains, &count) == HYDRASDR_SUCCESS) {
 *     for (uint8_t i = 0; i < count; i++) {
 *         printf("Gain type %d: value=%d range=[%d-%d]\n",
 *                gains[i].type, gains[i].value,
 *                gains[i].min_value, gains[i].max_value);
 *     }
 * }
 * @endcode
 *
 * @see hydrasdr_get_gain()
 * @see hydrasdr_gain_info_t
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_get_all_gains(struct hydrasdr_device* device, hydrasdr_gain_info_t* gains, uint8_t* count);

/** @} */ // end of gain_api group

/**
 * @defgroup rf_api RF Control Functions
 * @ingroup hydrasdr_api
 * @brief Functions for RF port selection and bias tee control
 * 
 * RF control availability varies by hardware. Use hydrasdr_get_device_info()
 * to check which controls are supported.
 * @{
 */

/**
 * @brief Enable/disable bias tee
 * 
 * Controls the bias tee (phantom power) on the RF input.
 * 
 * @param[in] dev   Device handle
 * @param[in] value 0 = disable bias tee, 1 = enable bias tee
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_BIAS_TEE capability before using
 * @note Use hydrasdr_get_device_info() to query voltage and current specs
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 * 
 * @warning ONLY enable if you have an active antenna or LNA that requires power
 * @warning Do NOT enable with passive antennas - may cause damage
 *
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_rf_bias(struct hydrasdr_device* dev, uint8_t value);

/**
 * @brief Select RF input port
 * 
 * Selects which RF input port to use for receiving.
 * 
 * @param[in] device  Device handle
 * @param[in] rf_port RF port to select (enum hydrasdr_rf_port_t)
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_RF_PORT_SELECT capability before using
 * @note Use hydrasdr_get_device_info() to query available ports
 * @note Can be called while streaming
 * @note Thread-safe after device is opened
 * 
 * @see hydrasdr_rf_port_t
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_set_rf_port(struct hydrasdr_device* device, hydrasdr_rf_port_t rf_port);

/** @} */ // end of rf_api group

/**
 * @defgroup peripheral_api Peripheral Access Functions
 * @ingroup hydrasdr_api
 * @brief Low-level access to GPIO, SPI flash, clock generator, and tuner
 * 
 * These are low-level functions for advanced users. Peripheral availability
 * varies by hardware - check capabilities before using.
 * 
 * @warning These functions provide direct hardware access
 * @warning Incorrect use may damage device or cause malfunction
 * @{
 */

/**
 * @brief Write GPIO pin state
 * 
 * Sets a GPIO pin to high or low state.
 * Pin must be configured as output first.
 * 
 * @param[in] device Device handle
 * @param[in] port   GPIO port (hardware-specific)
 * @param[in] pin    GPIO pin (hardware-specific)
 * @param[in] value  Pin state: 0 = low, 1 = high
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_GPIO capability before using
 * @note Pin must be configured as output with hydrasdr_gpiodir_write()
 * @note Thread-safe after device is opened
 * 
 * @warning Consult device documentation before using GPIO
 * 
 * @see hydrasdr_gpiodir_write()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_gpio_write(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t value);

/**
 * @brief Read GPIO pin state
 * 
 * Reads the current state of a GPIO pin.
 * 
 * @param[in]  device Device handle
 * @param[in]  port   GPIO port (hardware-specific)
 * @param[in]  pin    GPIO pin (hardware-specific)
 * @param[out] value  Pointer to receive pin state (0 or 1)
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_GPIO capability before using
 * @note Works for both input and output pins
 * @note Thread-safe after device is opened
 * 
 * @see hydrasdr_gpio_write()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_gpio_read(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t* value);

/**
 * @brief Set GPIO pin direction
 * 
 * Configures a GPIO pin as input or output.
 * 
 * @param[in] device Device handle
 * @param[in] port   GPIO port (hardware-specific)
 * @param[in] pin    GPIO pin (hardware-specific)
 * @param[in] value  Direction: 0 = input, 1 = output
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_GPIO capability before using
 * @note Must be called before hydrasdr_gpio_write()
 * @note Thread-safe after device is opened
 * 
 * @warning Consult device documentation before configuring pins
 *
 * @see hydrasdr_gpio_write()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_gpiodir_write(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t value);

/**
 * @brief Read GPIO pin direction
 * 
 * Reads the current direction configuration of a GPIO pin.
 * 
 * @param[in]  device Device handle
 * @param[in]  port   GPIO port (hardware-specific)
 * @param[in]  pin    GPIO pin (hardware-specific)
 * @param[out] value  Pointer to receive direction (0 = input, 1 = output)
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_GPIO capability before using
 * @note Thread-safe after device is opened
 * 
 * @see hydrasdr_gpiodir_write()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_gpiodir_read(struct hydrasdr_device* device, hydrasdr_gpio_port_t port, hydrasdr_gpio_pin_t pin, uint8_t* value);

/**
 * @brief Write to clock generator register
 * 
 * Writes a value to a clock generator register.
 * 
 * @param[in] device          Device handle
 * @param[in] register_number Register address
 * @param[in] value           Value to write
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_CLOCKGEN capability before using
 * @note Thread-safe after device is opened
 * 
 * @warning For advanced users only
 * @warning Consult IC datasheet before use
 * 
 * @see hydrasdr_clockgen_read()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_clockgen_write(struct hydrasdr_device* device, uint8_t register_number, uint8_t value);

/**
 * @brief Read from clock generator register
 * 
 * Reads a value from a clock generator register.
 * 
 * @param[in]  device          Device handle
 * @param[in]  register_number Register address
 * @param[out] value           Pointer to receive register value
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_CLOCKGEN capability before using
 * @note Thread-safe after device is opened
 * 
 * @see hydrasdr_clockgen_write()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_clockgen_read(struct hydrasdr_device* device, uint8_t register_number, uint8_t* value);

/**
 * @brief Legacy: Write to SI5351C clock generator register
 * 
 * @deprecated Use hydrasdr_clockgen_write() instead. This function is kept
 *             for backward compatibility only.
 * 
 * Writes a value to a clock generator register.
 * 
 * @param[in] device          Device handle
 * @param[in] register_number Register address
 * @param[in] value           Value to write
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_CLOCKGEN capability before using
 * @note Thread-safe after device is opened
 * 
 * @warning For advanced users only
 * @warning Consult IC datasheet before use
 * 
 * @see hydrasdr_clockgen_write()
 * @see hydrasdr_si5351c_read()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_clockgen_write() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_clockgen_write() instead")
int ADDCALL hydrasdr_si5351c_write(struct hydrasdr_device* device, uint8_t register_number, uint8_t value);

/**
 * @brief Legacy: Read from SI5351C clock generator register
 * 
 * @deprecated Use hydrasdr_clockgen_read() instead. This function is kept
 *             for backward compatibility only.
 * 
 * Reads a value from a clock generator register.
 * 
 * @param[in]  device          Device handle
 * @param[in]  register_number Register address
 * @param[out] value           Pointer to receive register value
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_CLOCKGEN capability before using
 * @note Thread-safe after device is opened
 * 
 * @see hydrasdr_clockgen_read()
 * @see hydrasdr_si5351c_write()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_clockgen_read() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_clockgen_read() instead")
int ADDCALL hydrasdr_si5351c_read(struct hydrasdr_device* device, uint8_t register_number, uint8_t* value);

/**
 * @brief Write to RF frontend register
 *
 * Writes a value to an RF frontend (tuner/RFIC) register.
 * Uses wider types for future-proofing (supports up to 65536 registers and 32-bit values).
 *
 * @param[in] device           Device handle
 * @param[in] register_address Register address (0-65535)
 * @param[in] value            Value to write (0-4294967295)
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @note Check HYDRASDR_CAP_RF_FRONTEND capability before using
 * @note Use hydrasdr_get_device_info() to query number of registers
 * @note Thread-safe after device is opened
 * @note For current device (RFOne), only lower 8 bits of register_address
 *       and value are used. Future RF ASICs may use full range.
 *
 * @warning For advanced users only
 * @warning Normal users should use high-level functions
 *
 * @see hydrasdr_rf_frontend_read()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_rf_frontend_write(struct hydrasdr_device* device, uint16_t register_address, uint32_t value);

/**
 * @brief Read from RF frontend register
 *
 * Reads a value from an RF front end (tuner/RFIC) register.
 * Uses wider types for future-proofing (supports up to 65536 registers and 32-bit values).
 *
 * @param[in]  device           Device handle
 * @param[in]  register_address Register address (0-65535)
 * @param[out] value            Pointer to receive register value (32-bit)
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @note Check HYDRASDR_CAP_RF_FRONTEND capability before using
 * @note Thread-safe after device is opened
 * @note For current device (RFOne), only lower 8 bits are meaningful.
 *       Future RF ASICs may return full 32-bit values.
 *
 * @see hydrasdr_rf_frontend_write()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_rf_frontend_read(struct hydrasdr_device* device, uint16_t register_address, uint32_t* value);

/**
 * @brief Legacy: Write to RF front end (tuner/RFIC) register (backward compatibility only for HydraSDR RFOne)
 *
 * @deprecated Use hydrasdr_rf_frontend_write() for new applications
 *
 * @param[in] device          Device handle
 * @param[in] register_number Register address
 * @param[in] value           Value to write
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @see hydrasdr_rf_frontend_write()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_rf_frontend_write() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_rf_frontend_write() instead")
int ADDCALL hydrasdr_r82x_write(struct hydrasdr_device* device, uint8_t register_number, uint8_t value);

/**
 * @brief Legacy: Read from tuner register (backward compatibility only for HydraSDR RFOne)
 *
 * @deprecated Use hydrasdr_rf_frontend_read() for new applications
 *
 * @param[in]  device          Device handle
 * @param[in]  register_number Register address
 * @param[out] value           Pointer to receive register value
 *
 * @return HYDRASDR_SUCCESS on success, error code on failure
 *
 * @see hydrasdr_rf_frontend_read()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_rf_frontend_read() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_rf_frontend_read() instead")
int ADDCALL hydrasdr_r82x_read(struct hydrasdr_device* device, uint8_t register_number, uint8_t* value);

/**
 * @brief Erase entire SPI flash chip
 * 
 * Erases the entire SPI flash memory including firmware sectors.
 * 
 * @param[in] device Device handle
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_SPIFLASH capability before using
 * @note Thread-safe after device is opened
 * 
 * @warning THIS WILL ERASE FIRMWARE - Use hydrasdr_spiflash_erase_sector() instead
 * @warning Device may become non-functional if firmware is erased
 * 
 * @see hydrasdr_spiflash_erase_sector()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_spiflash_erase(struct hydrasdr_device* device);

/**
 * @brief Erase a single SPI flash sector
 * 
 * Erases a specific sector of the SPI flash memory.
 * User-accessible sectors are protected from erasing firmware.
 * 
 * @param[in] device     Device handle
 * @param[in] sector_num Sector number to erase
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_SPIFLASH capability before using
 * @note Consult device documentation for valid sector numbers
 * @note Flash must be erased before writing
 * @note Thread-safe after device is opened
 * 
 * @see hydrasdr_spiflash_write()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_spiflash_erase_sector(struct hydrasdr_device* device, const uint16_t sector_num);

/**
 * @brief Write data to SPI flash
 * 
 * Writes data to the SPI flash memory at the specified address.
 * The target area must be erased first.
 * 
 * @param[in] device  Device handle
 * @param[in] address Flash address to write to
 * @param[in] length  Number of bytes to write
 * @param[in] data    Pointer to data to write
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_SPIFLASH capability before using
 * @note Flash must be erased before writing
 * @note Thread-safe after device is opened
 * 
 * @see hydrasdr_spiflash_erase_sector()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_spiflash_write(struct hydrasdr_device* device, const uint32_t address, const uint16_t length, unsigned char* const data);

/**
 * @brief Read data from SPI flash
 * 
 * Reads data from the SPI flash memory at the specified address.
 * 
 * @param[in]  device  Device handle
 * @param[in]  address Flash address to read from
 * @param[in]  length  Number of bytes to read
 * @param[out] data    Buffer to receive data
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_SPIFLASH capability before using
 * @note Thread-safe after device is opened
 * 
 * @see hydrasdr_spiflash_write()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_spiflash_read(struct hydrasdr_device* device, const uint32_t address, const uint16_t length, unsigned char* data);

/** @} */ // end of peripheral_api group

/**
 * @defgroup info_api Device Information Functions
 * @ingroup hydrasdr_api
 * @brief Functions for querying device identification, version, and capabilities
 * @{
 */

/**
 * @brief Read device board ID
 * 
 * @deprecated Use hydrasdr_get_device_info() instead. This function is kept
 * 
 * Returns the board identifier for the connected device.
 * 
 * @param[in]  device Device handle
 * @param[out] value  Pointer to receive board ID (enum hydrasdr_board_id)
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Thread-safe after device is opened
 * @note Use hydrasdr_board_id_name() to get human-readable name
 * 
 * @see hydrasdr_board_id_name()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_get_device_info() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_get_device_info() instead")
int ADDCALL hydrasdr_board_id_read(struct hydrasdr_device* device, uint8_t* value);

/**
 * @brief Read device firmware version string
 * 
 * @deprecated Use hydrasdr_get_device_info() instead. This function is kept
 * 
 * Retrieves the firmware version string from the device.
 * 
 * @param[in]  device  Device handle
 * @param[out] version Buffer to receive version string (null-terminated)
 * @param[in]  length  Size of version buffer
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note String is null-terminated
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_get_device_info() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_get_device_info() instead")
int ADDCALL hydrasdr_version_string_read(struct hydrasdr_device* device, char* version, uint8_t length);

/**
 * @brief Read device part ID and serial number
 * 
 * @deprecated Use hydrasdr_get_device_info() instead. This function is kept
 * 
 * Retrieves the MCU part identification and unique serial number.
 * 
 * @param[in]  device               Device handle
 * @param[out] read_partid_serialno Pointer to structure to receive data
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Serial number is a 128-bit unique identifier
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_get_device_info() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_get_device_info() instead")
int ADDCALL hydrasdr_board_partid_serialno_read(struct hydrasdr_device* device, hydrasdr_read_partid_serialno_t* read_partid_serialno);

/**
 * @brief Get temperature from device sensor
 * 
 * Reads the current temperature from the device's temperature sensor.
 * 
 * @param[in]  device      Device handle
 * @param[out] temperature Pointer to structure to receive temperature data
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note Check HYDRASDR_CAP_TEMPERATURE_SENSOR capability before using
 * @note Check temperature.valid field to verify reading is valid
 * @note Thread-safe after device is opened
 *
 * @see hydrasdr_get_device_info()
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_get_temperature(struct hydrasdr_device* device, hydrasdr_temperature_t* temperature);

/**
 * @brief Get comprehensive device information and capabilities
 * 
 * Retrieves complete information about the connected device including
 * all hardware-specific capabilities, ranges, and characteristics.
 * 
 * This is the primary function for capability discovery. Applications MUST
 * call this function to determine which features are available rather than
 * assuming based on device type.
 * 
 * @param[in]  device Device handle
 * @param[out] info   Pointer to structure to receive device information
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note This function queries all device information in a single call
 * @note Check struct_version fields to ensure compatibility
 * @note Check features bitmask to determine which features are available
 * @note Only fields with corresponding capability flags have valid data
 * @note Thread-safe after device is opened
 * 
 * @par Example - Basic Usage:
 * @code
 * hydrasdr_device_info_t info;
 * if (hydrasdr_get_device_info(dev, &info) == HYDRASDR_SUCCESS) {
 *     printf("Device: %s\n", info.board_name);
 *     printf("Firmware: %s\n", info.firmware_version);
 *     
 *     // Check capabilities before using features
 *     if (info.features & HYDRASDR_CAP_VGA_GAIN) {
 *         printf("VGA gain range: %d-%d\n",
 *                info.vga_gain.min_value,
 *                info.vga_gain.max_value);
 *         hydrasdr_set_vga_gain(dev, info.vga_gain.default_value);
 *     }
 * }
 * @endcode
 * 
 * @see hydrasdr_capability_t
 * @see hydrasdr_gain_range_t
 *
 * @since v1.1.0
 */
extern ADDAPI int ADDCALL hydrasdr_get_device_info(struct hydrasdr_device* device, hydrasdr_device_info_t* info);

/** @} */ // end of info_api group

/**
 * @defgroup util_api Utility Functions
 * @ingroup hydrasdr_api
 * @brief Helper functions for error handling and string conversion
 * @{
 */

/**
 * @brief Get error name string
 * 
 * Converts an error code to a human-readable string.
 * 
 * @param[in] errcode Error code (enum hydrasdr_error)
 * 
 * @return Pointer to constant string describing the error
 * 
 * @note The returned string is statically allocated and must not be freed
 * @note Thread-safe
 *
 * @see hydrasdr_error
 *
 * @since v1.0.0
 */
extern ADDAPI const char* ADDCALL hydrasdr_error_name(enum hydrasdr_error errcode);

/**
 * @brief Get board ID name string
 * 
 * @deprecated Use hydrasdr_get_device_info() instead. This function is kept
 * 
 * Converts a board ID to a human-readable string.
 * 
 * @param[in] board_id Board identifier (enum hydrasdr_board_id)
 * 
 * @return Pointer to constant string describing the board
 * 
 * @note The returned string is statically allocated and must not be freed
 * @note Thread-safe
 * 
 * @see hydrasdr_board_id
 * @see hydrasdr_board_id_read()
 * @see hydrasdr_get_device_info()
 *
 * @since v1.0.0
 * @deprecated Since v1.1.0 - Use hydrasdr_get_device_info() instead
 */
extern ADDAPI HYDRASDR_DEPRECATED("Use hydrasdr_get_device_info() instead")
const char* ADDCALL hydrasdr_board_id_name(enum hydrasdr_board_id board_id);

/**
 * @brief Reset device
 * 
 * Performs a software reset of the device.
 * The device will disconnect and reconnect on the USB bus.
 * 
 * @param[in] device Device handle
 * 
 * @return HYDRASDR_SUCCESS on success, error code on failure
 * 
 * @note After reset, the device handle becomes invalid
 * @note Device will need to be reopened after reset
 * @note Thread-safe after device is opened
 * 
 * @see hydrasdr_open()
 * @see hydrasdr_close()
 *
 * @since v1.0.0
 */
extern ADDAPI int ADDCALL hydrasdr_reset(struct hydrasdr_device* device);

/** @} */ // end of util_api group

#ifdef __cplusplus
} // __cplusplus defined.
#endif

#endif//__HYDRASDR_H__
