package com.mantz_it.rfanalyzer.dsp;

import com.mantz_it.rfanalyzer.dsp.spi.FFT;

import java.util.Arrays;

/**
 * Created by Pavel on 17.02.2017.
 */

public class FFT_Tests {

	public static void impulse(FFT fft, float[] interleaved, float[] golden, float delta) {
		Arrays.fill(interleaved, 0);
		interleaved[0] = 1;
		interleaved[1] = 1;
		Arrays.fill(golden, 1);
		Util.testFFT(fft, interleaved, golden, delta);
	}

	public static void nyquist(FFT fft, float[] interleaved, float[] golden, float delta) {
		final int n = interleaved.length / 2;
		Arrays.fill(interleaved, 0);
		for (int i = 0; i < n; i++) {
			interleaved[i * 2] = (float) Math.pow(-1, i);
		}
		Arrays.fill(golden, 0);
		golden[n] = n;
		Util.testFFT(fft, interleaved, golden, delta);
	}

	public static void singleSin(FFT fft, float[] interleaved, float[] golden, float delta) {
		final int n = interleaved.length / 2;
		Arrays.fill(interleaved, 0);
		for (int i = 0; i < n; i++) {
			interleaved[i * 2] = (float) Math.cos(2 * Math.PI * i / n);
		}
		Arrays.fill(golden, 0);
		golden[1 * 2] = n / 2;
		golden[(n - 1) * 2] = n / 2;
		Util.testFFT(fft, interleaved, golden, delta);
	}
}
