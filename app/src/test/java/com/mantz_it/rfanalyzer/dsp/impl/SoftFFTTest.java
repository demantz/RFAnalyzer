package com.mantz_it.rfanalyzer.dsp.impl;

import com.mantz_it.rfanalyzer.dsp.spi.FFT;

import org.junit.Before;
import org.junit.Test;

/**
 * Created by Pavel on 11.12.2016.
 */
public class SoftFFTTest {
	@Before
	public void setUp(){
	}
	@Test
	public void apply() throws Exception {
		// Test the FFT to make sure it's working
		int N = 1024;

		FFT fft = new SoftFFT(N);

		//float[] window = fft.getWindow();
		//float[] re = new float[N];
		//float[] im = new float[N];
		float[] interleaved = new float[N * 2];
		// Impulse
		interleaved[0] = 1;
		//for (int i = 1; i < N; i++)	re[i] = im[i] = 0; //already zero, it's Java, you know..
		beforeAfter(fft, interleaved, 0);

		// Nyquist
		for (int i = 0; i < N; i++) {
			interleaved[i * 2] = (float) Math.pow(-1, i);
		}
		beforeAfter(fft, interleaved, 0);

		// Single sin
		for (int i = 0; i < N; i++) {
			interleaved[i * 2] = (float) Math.cos(2 * Math.PI * i / N);
		}
		beforeAfter(fft, interleaved, 0);

		// Ramp
		for (int i = 0; i < N; i++) {
			interleaved[i * 2] = i;
		}
		beforeAfter(fft, interleaved, 0);

		long time = System.currentTimeMillis();
		double iter = 30000;
		for (int i = 0; i < iter; i++)
			fft.apply(interleaved, 0);
		time = System.currentTimeMillis() - time;
		System.out.println("Averaged " + (time / iter) + "ms per iteration");
	}

	protected static void beforeAfter(FFT fft, float[] interleaved, int offset) {
		System.out.println("Before: ");
		printReIm(interleaved, offset);
		//fft.applyWindow(complex, offset);
		fft.apply(interleaved, offset);
		System.out.println("After: ");
		printReIm(interleaved, offset);
	}

	protected static void printReIm(float[] interleaved, int offset) {
		System.out.print("Re: [");
		for (int i = offset; i < interleaved.length; i += 2)
			System.out.print(((int) (interleaved[i] * 1000) / 1000.0) + " ");

		System.out.print("]\nIm: [");
		for (int i = offset + 1; i < interleaved.length; i += 2)
			System.out.print(((int) (interleaved[i] * 1000) / 1000.0) + " ");

		System.out.println("]");
	}
}
