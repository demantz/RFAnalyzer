package com.mantz_it.rfanalyzer.dsp.impl;

import com.mantz_it.rfanalyzer.dsp.FFT_Tests;
import com.mantz_it.rfanalyzer.dsp.spi.FFT;

import org.junit.Test;

/**
 * Created by Pavel on 11.12.2016.
 */
public class FFTTest {
	@Test
	public void SoftFFT() throws Exception {
		// Test the FFT to make sure it's working
		final float delta = 1e-3f;
		final int N = 1024;

		FFT fft = new SoftFFT(N);

		float[] interleaved = new float[N * 2];
		float[] golden = new float[N * 2];

		FFT_Tests.impulse(fft, interleaved, golden, delta);

		FFT_Tests.nyquist(fft, interleaved, golden, delta);

		FFT_Tests.singleSin(fft, interleaved, golden, delta);

		/* todo: performance test
		long time = System.currentTimeMillis();
		double iter = 30000;
		for (int i = 0; i < iter; i++)
			fft.apply(interleaved, 0);
		time = System.currentTimeMillis() - time;
		System.out.println("Averaged " + (time / iter) + "ms per iteration");
		*/
	}


}
