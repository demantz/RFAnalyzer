package com.mantz_it.rfanalyzer.device.hiqsdr;

import java.nio.ByteBuffer;

/**
 * Created by Pavel on 28.03.2017.
 */

class HiqsdrHelper {
//---------------------------------------
// todo: check names and actual modes
public static final byte TX_MODE_INVALID = 0x0;
public static final byte TX_MODE_KEYED_CONTINIOUS_WAVE = 0x1;
public static final byte TX_MODE_RECEIVED_PTT = 0x2;
public static final byte TX_MODE_EXTENDED_IO = 0x4;
public static final byte TX_MODE_HW_CONTNIOUS_WAVE = 0x8;
protected static final int CLOCK_RATE = 122880000;
public static final long MAX_FREQUENCY = CLOCK_RATE / 2; // (?)61 MHz, but there is info, that 66 MHz
protected static final int UDP_CLK_RATE = CLOCK_RATE / 64; // actually 64 is prescaler*8, and default prescaler is 8, TBD.
// TODO: determine exact frequency limits
public static final long MIN_FREQUENCY = 100000; // 100 kHz
public static final int RX_PACKET_SIZE = 1442;
public static final int RX_PAYLOAD_OFFSET = 2;
public static final int RX_PAYLOAD_SIZE = 1440;
public static final int CFG_PACKET_SIZE = 22;
public static final int CMD_PACKET_SIZE = 2;
// commands
public final static ByteBuffer START_RECEIVING_CMD = ByteBuffer.allocate(2).put(new byte[]{'r', 'r'}).asReadOnlyBuffer();
public final static ByteBuffer STOP_RECEIVING_CMD = ByteBuffer.allocate(2).put(new byte[]{'s', 's'}).asReadOnlyBuffer();
public static int MIN_SAMPLE_RATE = 48000;
public static int MAX_SAMPLE_RATE = 960000;
public static int[] SAMPLE_RATES = {
		960000,  // decimation 2
		640000,  // d3
		480000,  // d4
		384000,  // d5
		320000,  // d6
		240000,  // d8
		192000,  // d10
		120000,  // d16
		96000,   // d20
		60000,   // d32
		48000    // d40
};
public static byte[] SAMPLE_RATE_CODES = {1, 2, 3, 4, 5, 7, 9, 15, 19, 31, 39}; // decimation-1

public static long frequencyToTunePhase(long frequency) {
	return (long) (((frequency / (double) CLOCK_RATE) * (1L << 32)) + 0.5);
}

public static long tunePhaseToFrequency(long phase) {
	return (long) ((phase - 0.5) * CLOCK_RATE / (1L << 32));
}

public static int rxControlToSampleRate(byte rxCtrl) {
	return UDP_CLK_RATE / (rxCtrl + 1);
}
}
