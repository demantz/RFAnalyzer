package com.mantz_it.rfanalyzer.device.rtlsdr;

/**
 * Created by Pavel on 21.03.2017.
 */

enum RtlsdrCommand {
	SET_FREQUENCY(0x01),
	SET_SAMPLERATE(0x02),
	SET_GAIN_MODE(0x03),
	SET_GAIN(0x04),
	SET_FREQ_CORRECTION(0x05),
	SET_IF_GAIN(0x06),
	SET_TEST_MODE(0x07),
	SET_AGC_MODE(0x08),
	SET_DIRECT_SAMPLING_MODE(0x09),
	SET_OFFSET_TUNING_MODE(0x0a),
	SET_RTL_XTAL_FREQUENCY(0x0b),
	SET_TUNER_XTAL_FREQUENCY(0x0c),
	SET_GAIN_BY_INDEX(0x0d),
	;

private final int code;

RtlsdrCommand(int code) {this.code = code;}

static String commandName(int code) {
	if (code < 1 || code > values().length) {
		return "invalid command";
	}
	return values()[code - 1].toString();
}


/**
 * Will pack a rtl_tcp command into a byte buffer
 *
 * @param argument command argument (see rtl_tcp documentation)
 * @return command buffer
 */
byte[] marshall(int argument) {
	byte[] commandArray = new byte[5];
	commandArray[0] = (byte) code;
	commandArray[1] = (byte) ((argument >> 24) & 0xff);
	commandArray[2] = (byte) ((argument >> 16) & 0xff);
	commandArray[3] = (byte) ((argument >> 8) & 0xff);
	commandArray[4] = (byte) (argument & 0xff);
	return commandArray;
}

/**
 * Will pack a rtl_tcp command into a byte buffer
 *
 * @param argument1 first command argument (see rtl_tcp documentation)
 * @param argument2 second command argument (see rtl_tcp documentation)
 * @return command buffer
 */
byte[] marshall(short argument1, short argument2) {
	byte[] commandArray = new byte[5];
	commandArray[0] = (byte) code;
	commandArray[1] = (byte) ((argument1 >> 8) & 0xff);
	commandArray[2] = (byte) (argument1 & 0xff);
	commandArray[3] = (byte) ((argument2 >> 8) & 0xff);
	commandArray[4] = (byte) (argument2 & 0xff);
	return commandArray;
}
}
