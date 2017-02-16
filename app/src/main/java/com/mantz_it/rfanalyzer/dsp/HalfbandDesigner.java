package com.mantz_it.rfanalyzer.dsp;

/**
 * Created by Pavel on 16.12.2016.
 */

public class HalfbandDesigner {
	// todo: please, it's easy, just add some zeros between taps, except the middle ones
	public static float[] lowpass(int sampleRate, int transitionBand, double attenuation_dB) {
		throw new UnsupportedOperationException();
	}

	public static int halfbandTapsCount(int sampleRate, int transitionBand, double attenuation_dB) {
		return 1 | (int) Math.ceil(sampleRate * (attenuation_dB - 8) / transitionBand / 14 + 2);
	}
}
