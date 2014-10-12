package com.mantz_it.rfanalyzer;

import android.app.Application;
import android.test.ApplicationTestCase;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * <a href="http://d.android.com/tools/testing/testing_android.html">Testing Fundamentals</a>
 */
public class ApplicationTest extends ApplicationTestCase<Application> {
    public ApplicationTest() {
        super(Application.class);
    }

	@Override
	public void setUp() throws Exception {
		super.setUp();

	}

	public void testFFT() throws Exception {
		// Test the FFT to make sure it's working
		int N = 8;

		FFT fft = new FFT(N);

		double[] window = fft.getWindow();
		double[] re = new double[N];
		double[] im = new double[N];

		// Impulse
		re[0] = 1; im[0] = 0;
		for(int i=1; i<N; i++)
			re[i] = im[i] = 0;
		beforeAfter(fft, re, im);

		// Nyquist
		for(int i=0; i<N; i++) {
			re[i] = Math.pow(-1, i);
			im[i] = 0;
		}
		beforeAfter(fft, re, im);

		// Single sin
		for(int i=0; i<N; i++) {
			re[i] = Math.cos(2*Math.PI*i / N);
			im[i] = 0;
		}
		beforeAfter(fft, re, im);

		// Ramp
		for(int i=0; i<N; i++) {
			re[i] = i;
			im[i] = 0;
		}
		beforeAfter(fft, re, im);

		long time = System.currentTimeMillis();
		double iter = 30000;
		for(int i=0; i<iter; i++)
			fft.fft(re,im);
		time = System.currentTimeMillis() - time;
		System.out.println("Averaged " + (time/iter) + "ms per iteration");
	}

	protected static void beforeAfter(FFT fft, double[] re, double[] im) {
		System.out.println("Before: ");
		printReIm(re, im);
		//fft.applyWindow(re, im);
		fft.fft(re, im);
		System.out.println("After: ");
		printReIm(re, im);
	}

	protected static void printReIm(double[] re, double[] im) {
		System.out.print("Re: [");
		for(int i=0; i<re.length; i++)
			System.out.print(((int)(re[i]*1000)/1000.0) + " ");

		System.out.print("]\nIm: [");
		for(int i=0; i<im.length; i++)
			System.out.print(((int)(im[i]*1000)/1000.0) + " ");

		System.out.println("]");
	}

}