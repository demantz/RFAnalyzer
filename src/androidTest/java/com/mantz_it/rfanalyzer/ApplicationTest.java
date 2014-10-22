package com.mantz_it.rfanalyzer;

import android.app.Application;
import android.test.ApplicationTestCase;

import java.nio.ByteBuffer;
import java.sql.SQLOutput;
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

	public void testFirFilter() {
		int samples = 128;
		double[] reIn = new double[samples];
		double[] imIn = new double[samples];
		double[] reOut = new double[samples/4];
		double[] imOut = new double[samples/4];
		int sampleRate = 1000;
		int f1 = 50;
		int f2 = 200;

		for (int i = 0; i < reIn.length; i++) {
			reIn[i] = Math.cos(2 * Math.PI * f1 * i/ (float)sampleRate) + Math.cos(2 * Math.PI * f2 * i/ (float)sampleRate);
			imIn[i] = Math.sin(2 * Math.PI * f1 * i/ (float)sampleRate) + Math.sin(2 * Math.PI * f2 * i/ (float)sampleRate);
		}

		FirFilter filter = FirFilter.createLowPass(4, 1, sampleRate, 100, 50, 60);
		System.out.println("Created filter with " + filter.getNumberOfTaps() + " taps!");

		FFT fft1 = new FFT(samples);

		System.out.println("Before FILTER:");
		spectrum(fft1, reIn, imIn);

		filter.filter(reIn, imIn, reOut, imOut, 0, 0, reIn.length, reOut.length);

		FFT fft2 = new FFT(samples/4);

		System.out.println("After FILTER:");
		spectrum(fft2, reOut, imOut);
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

	protected static void spectrum(FFT fft, double[] re, double[] im) {
		//fft.applyWindow(re, im);
		int length = re.length;
		double[] reDouble = new double[length];
		double[] imDouble = new double[length];
		double[] mag = new double[length];
		for (int i = 0; i < length; i++) {
			reDouble[i] = re[i];
			imDouble[i] = im[i];
		}

		fft.fft(reDouble, imDouble);
		// Calculate the logarithmic magnitude:
		for (int i = 0; i < length; i++) {
			// We have to flip both sides of the fft to draw it centered on the screen:
			int targetIndex = (i+length/2) % length;

			// Calc the magnitude = log(  re^2 + im^2  )
			// note that we still have to divide re and im by the fft size
			mag[targetIndex] = Math.log(Math.pow(reDouble[i]/fft.n,2) + Math.pow(imDouble[i]/fft.n,2));
		}

		System.out.print("Spectrum: [");
		for (int i = 0; i < length; i++) {
			System.out.print(" " + (int) mag[i]);
		}
		System.out.println("]");
	}

}