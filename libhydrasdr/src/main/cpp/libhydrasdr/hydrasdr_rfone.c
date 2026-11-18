/*
 * HydraSDR RF-ONE Device Support
 *
 * Copyright (C) 2025-2026, Benjamin Vernoux <bvernoux@hydrasdr.com>
 *
 * SPDX-License-Identifier: MIT
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libusb.h>
#include "hydrasdr_internal.h"
#include "hydrasdr_shared.h"

/* ============================================================================
 * RFOne Hardware Configuration Constants
 * ============================================================================ */

/* USB/Transfer configuration */
#define RFONE_TRANSFER_COUNT      (16)
#define RFONE_BUFFER_SIZE         DEFAULT_BUFFER_SIZE  /* 256KB from hydrasdr_shared.h */
#define RFONE_RX_ENDPOINT         (1)

/* Gain configuration */
#define RFONE_LNA_MAX_GAIN        (14)
#define RFONE_MIXER_MAX_GAIN      (15)
#define RFONE_VGA_MAX_GAIN        (15)
#define RFONE_GAIN_TABLE_SIZE     (22)
#define RFONE_DEFAULT_GAIN_INDEX  (10)

/* Frequency range */
#define RFONE_MIN_FREQ_HZ         (24000000ULL)   /* 24 MHz */
#define RFONE_MAX_FREQ_HZ         (1800000000ULL) /* 1.8 GHz */

/* Power consumption */
#define RFONE_TYPICAL_POWER_MW    (1800.0f)  /* VUSB 5V * 0.36A */
#define RFONE_MAX_POWER_MW        (3200.0f)  /* VUSB 5V * 0.64A */

/* Thermal limits */
#define RFONE_MAX_SAFE_TEMP_C     (70.0f)

/* Bias tee specifications */
#define RFONE_BIAS_TEE_VOLTAGE_V  (4.5f)
#define RFONE_BIAS_TEE_MAX_MA     (300.0f)

/* RF ports */
#define RFONE_RF_PORT_COUNT       (3)

/* GPIO */
#define RFONE_GPIO_COUNT          (18)

/* Hardware components */
#define RFONE_COMPONENT_COUNT     (2)
#define RFONE_RF_FRONTEND_REGS    (32)   /* R828D tuner registers */
#define RFONE_CLOCKGEN_REGS       (256)  /* SI5351C clock generator registers */

/* Supported sample types bitmask */
#define RFONE_SAMPLE_TYPES ( \
	(1 << HYDRASDR_SAMPLE_FLOAT32_IQ) | \
	(1 << HYDRASDR_SAMPLE_FLOAT32_REAL) | \
	(1 << HYDRASDR_SAMPLE_INT16_IQ) | \
	(1 << HYDRASDR_SAMPLE_INT16_REAL) | \
	(1 << HYDRASDR_SAMPLE_UINT16_REAL) | \
	(1 << HYDRASDR_SAMPLE_RAW))

/* Helper macro for error propagation in gain control */
#define RFONE_CHECK_RC(call) do { int _rc = (call); if (_rc < 0) return _rc; } while(0)

/* RFOne capabilities - hardcoded fallback for old firmware without HYDRASDR_GET_CAPABILITIES */
#define RFONE_HARDCODED_CAPS ( \
	HYDRASDR_CAP_RX | \
	HYDRASDR_CAP_LNA_GAIN | \
	HYDRASDR_CAP_MIXER_GAIN | \
	HYDRASDR_CAP_VGA_GAIN | \
	HYDRASDR_CAP_LNA_AGC | \
	HYDRASDR_CAP_MIXER_AGC | \
	HYDRASDR_CAP_LINEARITY_GAIN | \
	HYDRASDR_CAP_SENSITIVITY_GAIN | \
	HYDRASDR_CAP_BIAS_TEE | \
	HYDRASDR_CAP_PACKING | \
	HYDRASDR_CAP_RF_PORT_SELECT | \
	HYDRASDR_CAP_GPIO | \
	HYDRASDR_CAP_SPIFLASH | \
	HYDRASDR_CAP_CLOCKGEN | \
	HYDRASDR_CAP_RF_FRONTEND )

/* RFOne-specific gain tables */
static uint8_t rfone_linearity_vga_gains[RFONE_GAIN_TABLE_SIZE] = { 13, 12, 11, 11, 11, 11, 11, 10, 10, 10, 10, 10, 10, 10, 10, 10, 9, 8, 7, 6, 5, 4 };
static uint8_t rfone_linearity_mixer_gains[RFONE_GAIN_TABLE_SIZE] = { 12, 12, 11, 9, 8, 7, 6, 6, 5, 0, 0, 1, 0, 0, 2, 2, 1, 1, 1, 1, 0, 0 };
static uint8_t rfone_linearity_lna_gains[RFONE_GAIN_TABLE_SIZE] = { 14, 14, 14, 13, 12, 10, 9, 9, 8, 9, 8, 6, 5, 3, 1, 0, 0, 0, 0, 0, 0, 0 };
static uint8_t rfone_sensitivity_vga_gains[RFONE_GAIN_TABLE_SIZE] = { 13, 12, 11, 10, 9, 8, 7, 6, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4 };
static uint8_t rfone_sensitivity_mixer_gains[RFONE_GAIN_TABLE_SIZE] = { 12, 12, 12, 12, 11, 10, 10, 9, 9, 8, 7, 4, 4, 4, 3, 2, 2, 1, 0, 0, 0, 0 };
static uint8_t rfone_sensitivity_lna_gains[RFONE_GAIN_TABLE_SIZE] = { 14, 14, 14, 14, 14, 14, 14, 14, 14, 13, 12, 12, 9, 9, 8, 7, 6, 5, 3, 2, 1, 0 };

/* Gain definition for table-driven initialization */
typedef struct {
	hydrasdr_gain_type_t type;
	uint8_t max_value;
	uint8_t default_value;
} gain_def_t;

/* RFOne gain definitions - used to initialize gain cache at device open */
static const gain_def_t rfone_gain_defs[] = {
	{ HYDRASDR_GAIN_TYPE_LNA,         RFONE_LNA_MAX_GAIN,         RFONE_LNA_MAX_GAIN },
	{ HYDRASDR_GAIN_TYPE_MIXER,       RFONE_MIXER_MAX_GAIN,       RFONE_MIXER_MAX_GAIN },
	{ HYDRASDR_GAIN_TYPE_VGA,         RFONE_VGA_MAX_GAIN,         RFONE_VGA_MAX_GAIN },
	{ HYDRASDR_GAIN_TYPE_LINEARITY,   RFONE_GAIN_TABLE_SIZE - 1,  RFONE_DEFAULT_GAIN_INDEX },
	{ HYDRASDR_GAIN_TYPE_SENSITIVITY, RFONE_GAIN_TABLE_SIZE - 1,  RFONE_DEFAULT_GAIN_INDEX },
	{ HYDRASDR_GAIN_TYPE_LNA_AGC,     1,                          0 },
	{ HYDRASDR_GAIN_TYPE_MIXER_AGC,   1,                          0 },
};
#define RFONE_GAIN_DEFS_COUNT (sizeof(rfone_gain_defs) / sizeof(rfone_gain_defs[0]))

/* Forward declarations for RFOne-specific functions */
static int rfone_init(struct hydrasdr_device* dev);
static int rfone_exit(struct hydrasdr_device* dev);
static int rfone_set_linearity_gain(struct hydrasdr_device* dev, uint8_t value);
static int rfone_set_sensitivity_gain(struct hydrasdr_device* dev, uint8_t value);
static int rfone_start_rx(struct hydrasdr_device* dev, hydrasdr_sample_block_cb_fn callback, void* rx_ctx);
static int rfone_stop_rx(struct hydrasdr_device* dev);

/* Forward declarations for capability functions */
static int rfone_get_gain_range(struct hydrasdr_device* dev, int gain_type, hydrasdr_gain_range_t* range);
static int rfone_get_device_info(struct hydrasdr_device* dev, hydrasdr_device_info_t* info);
static int rfone_get_temperature(struct hydrasdr_device* dev, hydrasdr_temperature_t* temperature);

/* RFOne-specific device control wrappers */
static int rfone_set_lna_gain(struct hydrasdr_device* dev, uint8_t value)
{
	return hydrasdr_generic_set_lna_gain(dev, value, RFONE_LNA_MAX_GAIN);
}

static int rfone_set_mixer_gain(struct hydrasdr_device* dev, uint8_t value)
{
	return hydrasdr_generic_set_mixer_gain(dev, value, RFONE_MIXER_MAX_GAIN);
}

static int rfone_set_vga_gain(struct hydrasdr_device* dev, uint8_t value)
{
	return hydrasdr_generic_set_vga_gain(dev, value, RFONE_VGA_MAX_GAIN);
}

/* HAL structure for RFOne */
const hydrasdr_hal_t rfone_hal = {
	.init = rfone_init,
	.exit = rfone_exit,
	/* Use generic board information functions */
	.board_id_read = hydrasdr_generic_board_id_read,
	.version_string_read = hydrasdr_generic_version_string_read,
	.board_partid_serialno_read = hydrasdr_generic_board_partid_serialno_read,
	/* Use generic sample rate management */
	.get_samplerates = hydrasdr_generic_get_samplerates,
	.set_samplerate = hydrasdr_generic_set_samplerate,
	/* Bandwidth control - uses generic functions (returns unsupported if no bandwidths configured) */
	.get_bandwidths = hydrasdr_generic_get_bandwidths,
	.set_bandwidth = hydrasdr_generic_set_bandwidth,

	/* Use generic frequency control */
	.set_freq = hydrasdr_generic_set_freq,
	
	/* Gain controls */
	.set_lna_gain = rfone_set_lna_gain,
	.set_rf_gain = NULL,  /* RFOne doesn't have separate RF gain control */
	.set_mixer_gain = rfone_set_mixer_gain,
	.set_filter_gain = NULL,  /* RFOne doesn't have filter gain control */
	.set_vga_gain = rfone_set_vga_gain,
	.set_lna_agc = hydrasdr_generic_set_lna_agc,
	.set_rf_agc = NULL,  /* RFOne doesn't have RF AGC */
	.set_mixer_agc = hydrasdr_generic_set_mixer_agc,
	.set_filter_agc = NULL,  /* RFOne doesn't have filter AGC */
	.set_linearity_gain = rfone_set_linearity_gain,
	.set_sensitivity_gain = rfone_set_sensitivity_gain,
	/* RF and device control */
	.set_rf_bias = hydrasdr_generic_set_rf_bias,
	.set_packing = hydrasdr_generic_set_packing,
	.set_rf_port = hydrasdr_generic_set_rf_port,
	.reset = hydrasdr_generic_reset,

	/* Hardware monitoring */
	.get_temperature = rfone_get_temperature,

	/* Use shared peripheral modules */
	.clockgen_write = hydrasdr_generic_clockgen_write,
	.clockgen_read = hydrasdr_generic_clockgen_read,
	.rf_frontend_write = hydrasdr_generic_rf_frontend_write,
	.rf_frontend_read = hydrasdr_generic_rf_frontend_read,
	.gpio_write = hydrasdr_generic_gpio_write,
	.gpio_read = hydrasdr_generic_gpio_read,
	.gpiodir_write = hydrasdr_generic_gpiodir_write,
	.gpiodir_read = hydrasdr_generic_gpiodir_read,
	.spiflash_erase = hydrasdr_generic_spiflash_erase,
	.spiflash_write = hydrasdr_generic_spiflash_write,
	.spiflash_read = hydrasdr_generic_spiflash_read,
	.spiflash_erase_sector = hydrasdr_generic_spiflash_erase_sector,

	/* Streaming functions */
	.start_rx = rfone_start_rx,
	.stop_rx = rfone_stop_rx,

	/* Use generic sample type and filter management */
	.set_sample_type = hydrasdr_generic_set_sample_type,
	.set_conversion_algorithm = hydrasdr_generic_set_conversion_algorithm,
	.set_conversion_filter_float32 = hydrasdr_generic_set_conversion_filter_float32,
	.set_conversion_filter_int16 = hydrasdr_generic_set_conversion_filter_int16,

	/* Capability functions */
	.get_device_info = rfone_get_device_info,
};

/* HAL Implementation - use generic functions */
static int rfone_init(struct hydrasdr_device* dev)
{
	int result;
	hydrasdr_gain_info_t info;
	size_t i;

	result = hydrasdr_generic_device_init(dev, RFONE_TRANSFER_COUNT, RFONE_BUFFER_SIZE, 0, RFONE_HARDCODED_CAPS);
	if (result != HYDRASDR_SUCCESS)
		return result;

	/* Initialize gain cache from table */
	memset(&info, 0, sizeof(info));
	info.step_value = 1;
	for (i = 0; i < RFONE_GAIN_DEFS_COUNT; i++) {
		info.type = rfone_gain_defs[i].type;
		info.max_value = rfone_gain_defs[i].max_value;
		info.default_value = rfone_gain_defs[i].default_value;
		info.value = info.default_value;
		hydrasdr_generic_set_gain_info(dev, info.type, &info);
	}

	return HYDRASDR_SUCCESS;
}

static int rfone_exit(struct hydrasdr_device* dev)
{
	return hydrasdr_generic_device_exit(dev);
}

static int rfone_start_rx(struct hydrasdr_device* dev, hydrasdr_sample_block_cb_fn callback, void* rx_ctx)
{
	return hydrasdr_generic_start_rx(dev, callback, rx_ctx, LIBUSB_ENDPOINT_IN | RFONE_RX_ENDPOINT);
}

static int rfone_stop_rx(struct hydrasdr_device* dev)
{
	return hydrasdr_generic_stop_rx(dev);
}

/* RFOne-specific gain control modes */

static int rfone_set_linearity_gain(struct hydrasdr_device* dev, uint8_t value)
{
	if (value >= RFONE_GAIN_TABLE_SIZE) {
		value = RFONE_GAIN_TABLE_SIZE - 1;
	}
	value = RFONE_GAIN_TABLE_SIZE - 1 - value;

	/* Set gains according to linearity table */
	RFONE_CHECK_RC(hydrasdr_generic_set_mixer_agc(dev, 0));
	RFONE_CHECK_RC(hydrasdr_generic_set_lna_agc(dev, 0));
	RFONE_CHECK_RC(hydrasdr_generic_set_vga_gain(dev, rfone_linearity_vga_gains[value], RFONE_VGA_MAX_GAIN));
	RFONE_CHECK_RC(hydrasdr_generic_set_mixer_gain(dev, rfone_linearity_mixer_gains[value], RFONE_MIXER_MAX_GAIN));
	RFONE_CHECK_RC(hydrasdr_generic_set_lna_gain(dev, rfone_linearity_lna_gains[value], RFONE_LNA_MAX_GAIN));

	return HYDRASDR_SUCCESS;
}

static int rfone_set_sensitivity_gain(struct hydrasdr_device* dev, uint8_t value)
{
	if (value >= RFONE_GAIN_TABLE_SIZE) {
		value = RFONE_GAIN_TABLE_SIZE - 1;
	}
	value = RFONE_GAIN_TABLE_SIZE - 1 - value;

	/* Set gains according to sensitivity table */
	RFONE_CHECK_RC(hydrasdr_generic_set_mixer_agc(dev, 0));
	RFONE_CHECK_RC(hydrasdr_generic_set_lna_agc(dev, 0));
	RFONE_CHECK_RC(hydrasdr_generic_set_vga_gain(dev, rfone_sensitivity_vga_gains[value], RFONE_VGA_MAX_GAIN));
	RFONE_CHECK_RC(hydrasdr_generic_set_mixer_gain(dev, rfone_sensitivity_mixer_gains[value], RFONE_MIXER_MAX_GAIN));
	RFONE_CHECK_RC(hydrasdr_generic_set_lna_gain(dev, rfone_sensitivity_lna_gains[value], RFONE_LNA_MAX_GAIN));

	return HYDRASDR_SUCCESS;
}


/* RFOne temperature sensor (not supported) */
static int rfone_get_temperature(struct hydrasdr_device* dev,
                                 hydrasdr_temperature_t* temperature)
{
	(void)dev;
	
	if (!temperature) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	
	temperature->valid = 0;
	temperature->temperature_celsius = 0.0f;
	temperature->temperature_fahrenheit = 0.0f;
	
	return HYDRASDR_ERROR_UNSUPPORTED;
}

/* ========================================================================
 * Capability API Implementation for RFOne
 * ======================================================================== */

/* RFOne gain ranges */
static int rfone_get_gain_range(struct hydrasdr_device* dev,
                                int gain_type,
                                hydrasdr_gain_range_t* range)
{
	(void)dev;
	
	if (!range) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}
	
	switch (gain_type) {
		case HYDRASDR_GAIN_LNA:
			range->min_value = 0;
			range->max_value = RFONE_LNA_MAX_GAIN;
			range->step_value = 1;
			range->default_value = RFONE_LNA_MAX_GAIN;
			return HYDRASDR_SUCCESS;
			
		case HYDRASDR_GAIN_MIXER:
			range->min_value = 0;
			range->max_value = RFONE_MIXER_MAX_GAIN;
			range->step_value = 1;
			range->default_value = RFONE_MIXER_MAX_GAIN;
			return HYDRASDR_SUCCESS;
			
		case HYDRASDR_GAIN_VGA:
			range->min_value = 0;
			range->max_value = RFONE_VGA_MAX_GAIN;
			range->step_value = 1;
			range->default_value = RFONE_VGA_MAX_GAIN;
			return HYDRASDR_SUCCESS;
			
		case HYDRASDR_GAIN_LINEARITY:
		case HYDRASDR_GAIN_SENSITIVITY:
			range->min_value = 0;
			range->max_value = RFONE_GAIN_TABLE_SIZE - 1;
			range->step_value = 1;
			range->default_value = RFONE_DEFAULT_GAIN_INDEX;
			return HYDRASDR_SUCCESS;
			
		default:
			return HYDRASDR_ERROR_UNSUPPORTED;
	}
}

/* RFOne device info */
static int rfone_get_device_info(struct hydrasdr_device* dev,
                                 hydrasdr_device_info_t* info)
{
	int result;
	uint8_t board_id;
	int rf_port_num;
	int rf_port_name_max_size;

	if (!info) {
		return HYDRASDR_ERROR_INVALID_PARAM;
	}

	memset(info, 0, sizeof(*info));

	/* Structure version for compatibility */
	info->struct_version_major = 1;
	info->struct_version_minor = 0;
	info->struct_version_revision = 1;

	/* Board ID */
	result = hydrasdr_generic_board_id_read(dev, &board_id);
	if (result != HYDRASDR_SUCCESS) {
		return result;
	}
	info->board_id = board_id;

	/* Board name */
	strncpy(info->board_name, "HydraSDR RFOne", HYDRASDR_DEVICE_NAME_MAX_LEN - 1);
	info->board_name[HYDRASDR_DEVICE_NAME_MAX_LEN - 1] = '\0';
	
	/* Firmware version */
	result = hydrasdr_generic_version_string_read(dev, info->firmware_version,
	                                              HYDRASDR_DEVICE_INFO_FW_VERSION_MAX_LEN);
	if (result != HYDRASDR_SUCCESS) {
		return result;
	}
	
	/* Part ID and Serial Number */
	result =  hydrasdr_generic_board_partid_serialno_read(dev, &info->part_serial);
	if (result != HYDRASDR_SUCCESS) {
		return result;
	}

	/* Feature capabilities bitmask */
	hydrasdr_generic_get_capabilities(dev, &info->features, info->features_reserved, RFONE_HARDCODED_CAPS);

	/* Gain ranges */
	rfone_get_gain_range(dev, HYDRASDR_GAIN_LNA, &info->lna_gain);
	// rf_gain not supported
	rfone_get_gain_range(dev, HYDRASDR_GAIN_MIXER, &info->mixer_gain);
	// filter_gain not supported
	rfone_get_gain_range(dev, HYDRASDR_GAIN_VGA, &info->vga_gain);
	rfone_get_gain_range(dev, HYDRASDR_GAIN_LINEARITY, &info->linearity_gain);
	rfone_get_gain_range(dev, HYDRASDR_GAIN_SENSITIVITY, &info->sensitivity_gain);

	/* RF Frontend Components */
	info->component_count = RFONE_COMPONENT_COUNT;

	/* Component 0: RF Frontend (Tuner) */
	info->components[0].type = HYDRASDR_COMP_RF_FRONTEND;
	strncpy(info->components[0].name, "RafaelMicro R828D", HYDRASDR_COMPONENT_NAME_MAX_LEN - 1);
	info->components[0].name[HYDRASDR_COMPONENT_NAME_MAX_LEN - 1] = '\0';
	info->components[0].register_count = RFONE_RF_FRONTEND_REGS;

	/* Component 1: Clock Generator */
	info->components[1].type = HYDRASDR_COMP_CLOCKGEN;
	strncpy(info->components[1].name, "Skyworks SI5351C", HYDRASDR_COMPONENT_NAME_MAX_LEN - 1);
	info->components[1].name[HYDRASDR_COMPONENT_NAME_MAX_LEN - 1] = '\0';
	info->components[1].register_count = RFONE_CLOCKGEN_REGS;

	/* Frequency range */
	info->min_frequency = RFONE_MIN_FREQ_HZ;
	info->max_frequency = RFONE_MAX_FREQ_HZ;

	/* RF ports */
	info->rf_port_count = RFONE_RF_PORT_COUNT;
	info->rf_ports = HYDRASDR_RF_PORTS_MASK(info->rf_port_count);
	rf_port_name_max_size = HYDRASDR_RF_PORT_INFO_NAME_MAX_LEN;

	rf_port_num = HYDRASDR_RF_PORT_RX0;
	strncpy(info->rf_port_info[rf_port_num].name, "ANT", rf_port_name_max_size - 1);
	info->rf_port_info[rf_port_num].name[rf_port_name_max_size - 1] = '\0';
	info->rf_port_info[rf_port_num].min_frequency = info->min_frequency;
	info->rf_port_info[rf_port_num].max_frequency = info->max_frequency;
	info->rf_port_info[rf_port_num].has_bias_tee = 1;
	info->rf_port_info[rf_port_num].bias_tee.voltage = RFONE_BIAS_TEE_VOLTAGE_V;
	info->rf_port_info[rf_port_num].bias_tee.max_current_milliamp = RFONE_BIAS_TEE_MAX_MA;

	rf_port_num = HYDRASDR_RF_PORT_RX1;
	strncpy(info->rf_port_info[rf_port_num].name, "CABLE1", rf_port_name_max_size - 1);
	info->rf_port_info[rf_port_num].name[rf_port_name_max_size - 1] = '\0';
	info->rf_port_info[rf_port_num].min_frequency = info->min_frequency;
	info->rf_port_info[rf_port_num].max_frequency = info->max_frequency;
	info->rf_port_info[rf_port_num].has_bias_tee = 0;

	rf_port_num = HYDRASDR_RF_PORT_RX2;
	strncpy(info->rf_port_info[rf_port_num].name, "CABLE2", rf_port_name_max_size - 1);
	info->rf_port_info[rf_port_num].name[rf_port_name_max_size - 1] = '\0';
	info->rf_port_info[rf_port_num].min_frequency = info->min_frequency;
	info->rf_port_info[rf_port_num].max_frequency = info->max_frequency;
	info->rf_port_info[rf_port_num].has_bias_tee = 0;

	/* GPIO */
	info->gpio_count = RFONE_GPIO_COUNT;

	/* Sample types */
	info->sample_types = RFONE_SAMPLE_TYPES;

	/* Power and thermal information */
	info->typical_power_mw = RFONE_TYPICAL_POWER_MW;
	info->max_power_mw = RFONE_MAX_POWER_MW;
	info->max_safe_temp_celsius = RFONE_MAX_SAFE_TEMP_C;
	info->max_safe_temp_fahrenheit = CELSIUS_TO_FAHRENHEIT(info->max_safe_temp_celsius);

	/* Current configuration state (can be queried during streaming) */
	{
		hydrasdr_streaming_t* stream = (hydrasdr_streaming_t*)dev->private_data;
		if (stream) {
			info->current_bandwidth = stream->current_bandwidth;
			info->current_samplerate = stream->effective_samplerate;
			info->current_hw_samplerate = stream->hardware_samplerate;
			info->current_decimation_factor = stream->decimation_factor;
			info->current_decimation_mode = (uint8_t)stream->decimation_mode;
			info->current_sample_type = (uint32_t)stream->sample_type;
			info->current_packing = stream->packing_enabled ? 1 : 0;
			info->bandwidth_auto_selected = stream->bandwidth_explicitly_set ? 0 : 1;
			info->current_adc_bits = stream->adc_bits;
		}
	}

	return HYDRASDR_SUCCESS;
}
