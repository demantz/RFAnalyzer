package com.mantz_it.rfanalyzer.device.rtlsdr;

import java.util.Arrays;

/**
 * Created by Pavel on 21.03.2017.
 */

enum RtlsdrTuner {
	UNKNOWN(0, 0, new int[]{0}),
	E4000(52000000L, 3000000000L, new int[]{
			-10, 15, 40, 65, 90, 115, 140, 165,
			190, 215, 240, 290, 340, 420}),
	FC0012(22000000L, 3000000000L, new int[]{
			-99, -40, 71, 179, 192}),
	FC0013(22000000L, 3000000000L, new int[]{
			-99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67,
			68, 70, 71, 179, 181, 182, 184, 186, 188, 191, 197}),
	FC2580(146000000L, 3000000000L, new int[]{0}),
	R820T(24000000L, 3000000000L, new int[]{
			0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280,
			297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496}),
	R828D(24000000L, 3000000000L, new int[]{0});

public final long minFrequency;
public final long maxFrequency;
private final int[] gainValues;

public int[] getGainValues() {
	return Arrays.copyOf(gainValues, gainValues.length);
}

RtlsdrTuner(long minFrequency, long maxFrequency, int[] gainValues) {
	this.minFrequency = minFrequency;
	this.maxFrequency = maxFrequency;
	this.gainValues = gainValues;
}

public static RtlsdrTuner valueOf(int tuner) {
	if (tuner <= 0 || tuner >= values().length)
		return UNKNOWN;
	return values()[tuner];
}

////////////////////////////////////////  documentation  ///////////////////////////////////////////////////
//private static final int RTLSDR_TUNER_UNKNOWN = 0;
//private static final int RTLSDR_TUNER_E4000 = 1;
//private static final int RTLSDR_TUNER_FC0012 = 2;
//private static final int RTLSDR_TUNER_FC0013 = 3;
//private static final int RTLSDR_TUNER_FC2580 = 4;
//private static final int RTLSDR_TUNER_R820T = 5;
//private static final int RTLSDR_TUNER_R828D = 6;
//private static final String[] TUNER_STRING = {"UNKNOWN", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D"};


/*private static final long[] MIN_FREQUENCY = {0,            // invalid
		52000000L,    // E4000
		22000000L,    // FC0012
		22000000L,    // FC0013
		146000000L,    // FC2580
		24000000L,    // R820T
		24000000L};    // R828D
*/
/*
private static final long[] MAX_FREQUENCY = {0L,            // invalid
		3000000000L,    // E4000		actual max freq: 2200000000L
		3000000000L,    // FC0012		actual max freq: 948000000L
		3000000000L,    // FC0013		actual max freq: 1100000000L
		3000000000L,    // FC2580		actual max freq: 924000000L
		3000000000L,    // R820T		actual max freq: 1766000000L
		3000000000L};    // R828D		actual max freq: 1766000000L
*/
/*
private static final int[][] POSSIBLE_GAIN_VALUES = {    // Values from gr_osmocom rt_tcp_source_s.cc:
		{0},                                                                        // invalid
		{-10, 15, 40, 65, 90, 115, 140, 165, 190, 215, 240, 290, 340, 420},            // E4000
		{-99, -40, 71, 179, 192},                                                    // FC0012
		{-99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67, 68,
				70, 71, 179, 181, 182, 184, 186, 188, 191, 197},                    // FC0013
		{0},                                                                        // FC2580
		{0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280,
				297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496},    // R820T
		{0}                                                                            // R828D ??
};
*/

}

