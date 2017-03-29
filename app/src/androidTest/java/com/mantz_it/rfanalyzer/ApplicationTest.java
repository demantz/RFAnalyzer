package com.mantz_it.rfanalyzer;

import android.app.Application;
import android.test.ApplicationTestCase;

import java.util.Random;

/**
 * <a href="http://d.android.com/tools/testing/testing_android.html">Testing Fundamentals</a>
 */
public class ApplicationTest extends ApplicationTestCase<Application> {
	public ApplicationTest() {
		super(Application.class);
	}

	protected byte[] testPacket;
	protected byte[] test24bitPacket;
	protected Random rnd;

	protected final int SAMPLES_IN_PACKET = (1 << 20);  //  1 Mi sample packet

	@Override
	public void setUp() throws Exception {
		super.setUp();
		rnd = new Random();
		testPacket = new byte[SAMPLES_IN_PACKET * 2];
		test24bitPacket = new byte[SAMPLES_IN_PACKET * 2 * 3 + 2];
		rnd.nextBytes(testPacket);
		rnd.nextBytes(test24bitPacket);
	}


	public long testConverterPerformance(IQConverter converter, byte[] packet, SamplePacket samplePacket) {
		long start = System.currentTimeMillis();
		converter.fillPacketIntoSamplePacket(packet, samplePacket);
		return System.currentTimeMillis() - start;
	}


	public void testConverters() {
		System.out.println("Testing converters...");
		for (int i = 1; i <= 10; ++i) {
			System.out.println("Pass #" + i);
			testUnsigned24BitIQConverter();
			testUnsigned8BitIQConverter();
			testSigned8BitIQConverter();
		}
	}

	public void testSigned8BitIQConverter() {
		IQConverter converter = new Signed8BitIQConverter();
		SamplePacket sp = new SamplePacket(SAMPLES_IN_PACKET);
		long time = testConverterPerformance(converter, testPacket, sp);
		System.out.println("Signed8BitIQConverter fills packet in " + time + " ms");
	}

	public void testUnsigned8BitIQConverter() {
		IQConverter converter = new Unsigned8BitIQConverter();
		SamplePacket sp = new SamplePacket(SAMPLES_IN_PACKET);
		long time = testConverterPerformance(converter, testPacket, sp);
		System.out.println("Unsigned8BitIQConverter fills packet in " + time + " ms");
	}

	public void testUnsigned24BitIQConverter() {
		IQConverter converter = new Signed24BitIQConverter();
		SamplePacket sp = new SamplePacket(SAMPLES_IN_PACKET);
		long time = testConverterPerformance(converter, test24bitPacket, sp);
		System.out.println("Signed24BitIQConverter fills packet in " + time + " ms");
	}
	/*
	public void testFirFilterPerformance() {
		int sampleRate = 4000000;
		int packetSize = 16384;
		int loopCycles = 10000;
		SamplePacket in = new SamplePacket(packetSize);
		SamplePacket out = new SamplePacket(packetSize);
		out.setSize(0);
		in.setSize(in.capacity());

		//Debug.startMethodTracing("FirFilter");
		FirFilter filter = FirFilter.createLowPass(4, 1, sampleRate, 100000, 800000, 40);
		System.out.println("Created FIR filter with " + filter.getNumberOfTaps() + " taps!");

		System.out.println("##### START ...");
		long startTime = System.currentTimeMillis();
		for (int i = 0; i < loopCycles; i++) {
			filter.filter(in, out, 0, in.size());
			out.setSize(0);
		}
		System.out.println("##### DONE. Time needed for 1 sec of samples: "
		                   + (System.currentTimeMillis() - startTime)
		                     / (packetSize * loopCycles / (float) sampleRate)
		                   + " ms");
		//Debug.stopMethodTracing();
	}

	public void testComplexFirFilterPerformance() {
		int sampleRate = 4000000;
		int packetSize = 16384;
		int loopCycles = 10000;
		SamplePacket in = new SamplePacket(packetSize);
		SamplePacket out = new SamplePacket(packetSize);
		out.setSize(0);
		in.setSize(in.capacity());

		//Debug.startMethodTracing("FirFilter");
		ComplexFirFilter filter = ComplexFirFilter.createBandPass(4, 1, sampleRate, 0, 100000, 800000, 40);
		System.out.println("Created complex FIR filter with " + filter.getNumberOfTaps() + " taps!");

		System.out.println("##### START ...");
		long startTime = System.currentTimeMillis();
		for (int i = 0; i < loopCycles; i++) {
			filter.filter(in, out, 0, in.size());
			out.setSize(0);
		}
		System.out.println("##### DONE. Time needed for 1 sec of samples: "
		                   + (System.currentTimeMillis() - startTime)
		                     / (packetSize * loopCycles / (float) sampleRate)
		                   + " ms");
		//Debug.stopMethodTracing();
	}
*/
	/*
	public void testHalfBandLowPassFilter() {
		int samples = 128;
		float[] reIn = new float[samples];
		float[] imIn = new float[samples];
		float[] reOut = new float[samples / 2];
		float[] imOut = new float[samples / 2];
		int sampleRate = 1000;
		SamplePacket in = new SamplePacket(reIn, imIn, 0, sampleRate);
		SamplePacket out = new SamplePacket(reOut, imOut, 0, sampleRate / 2);
		out.setSize(0);
		int f1 = 50;
		int f2 = 400;

		for (int i = 0; i < reIn.length; i++) {
			reIn[i] = (float) Math.cos(2 * Math.PI * f1 * i / (float) sampleRate) + (float) Math.cos(2 * Math.PI * f2 * i / (float) sampleRate);
			imIn[i] = (float) Math.sin(2 * Math.PI * f1 * i / (float) sampleRate) + (float) Math.sin(2 * Math.PI * f2 * i / (float) sampleRate);
		}

		HalfBandLowPassFilter halfBandLowPassFilter = new HalfBandLowPassFilter(12);
		assertEquals(halfBandLowPassFilter.filterN12(in, out, 0, in.size()), in.size());

		FFT fft1 = new SoftFFT(samples);
		System.out.println("Before FILTER:");
		spectrum(fft1, reIn, imIn);

		FFT fft2 = new SoftFFT(samples / 2);
		System.out.println("After FILTER:");
		spectrum(fft2, reOut, imOut);
	}

	public void testHalfBandLowPassFilterPerformance() {
		int sampleRate = 1000000;
		int packetSize = 16384;
		int loopCycles = 1000;
		long startTime;
		int firFilterTime;
		int halfBandFilterTime;
		SamplePacket in = new SamplePacket(packetSize);
		SamplePacket out = new SamplePacket(packetSize);
		out.setSize(0);
		in.setSize(in.capacity());

		// Compare with equal FirFilter:
		FirFilter filter = FirFilter.createLowPass(2, 1, sampleRate, 100000, 300000, 30);
		System.out.println("FirFilter for comparing has " + filter.getNumberOfTaps() + " taps!");

		startTime = System.currentTimeMillis();
		for (int i = 0; i < loopCycles; i++) {
			filter.filter(in, out, 0, in.size());
			out.setSize(0);
		}
		firFilterTime = (int) (System.currentTimeMillis() - startTime);
		System.out.println("Time needed by FirFilter for 1 sec of samples: " + firFilterTime / (packetSize * loopCycles / (float) sampleRate));

		// Now the same for the actual half band filter:
		HalfBandLowPassFilter halfBandLowPassFilter = new HalfBandLowPassFilter(12);
		startTime = System.currentTimeMillis();
		for (int i = 0; i < loopCycles; i++) {
			halfBandLowPassFilter.filterN12(in, out, 0, in.size());
			out.setSize(0);
		}
		halfBandFilterTime = (int) (System.currentTimeMillis() - startTime);
		System.out.println("Time needed by HalfBandLowPassFilter for 1 sec of samples: " + halfBandFilterTime / (packetSize * loopCycles / (float) sampleRate));
		System.out.println("Half band filter is " + ((firFilterTime - halfBandFilterTime) / (float) halfBandFilterTime) * 100 + "% faster than the FirFilter!");

		// just for fun: see how the N8 filter performs with filterN8 and with filterN12:
		halfBandLowPassFilter = new HalfBandLowPassFilter(12);
		startTime = System.currentTimeMillis();
		for (int i = 0; i < loopCycles; i++) {
			halfBandLowPassFilter.filterN12(in, out, 0, in.size());
			out.setSize(0);
		}
		halfBandFilterTime = (int) (System.currentTimeMillis() - startTime);
		System.out.println("Time needed by filterN8 HalfBandLowPassFilter for 1 sec of samples: " + halfBandFilterTime / (packetSize * loopCycles / (float) sampleRate));

		halfBandLowPassFilter = new HalfBandLowPassFilter(12);
		startTime = System.currentTimeMillis();
		for (int i = 0; i < loopCycles; i++) {
			halfBandLowPassFilter.filter(in, out, 0, in.size());
			out.setSize(0);
		}
		halfBandFilterTime = (int) (System.currentTimeMillis() - startTime);
		System.out.println("Time needed by filter HalfBandLowPassFilter for 1 sec of samples: " + halfBandFilterTime / (packetSize * loopCycles / (float) sampleRate));
	}
*/
/*
	protected static void spectrum(FFT fft, float[] complex, int offset) {
		//fft.applyWindow(complex);
		int length = complex.length;
		float[] tmp = new float[length - offset];
		float[] mag = new float[(length - offset) / 2];
		for (int i = 0; i < length; i++) {
			tmp[i] = complex[i + offset];
		}

		fft.apply(tmp, 0);
		// Calculate the logarithmic magnitude:
		for (int i = 0; i < length; i++) {
			// We have to flip both sides of the fft to draw it centered on the screen:
			int targetIndex = (i + length / 2) % length;

			// Calc the magnitude = log(  re^2 + im^2  )
			// note that we still have to divide re and im by the fft size
			mag[targetIndex] = (float) Math.log(Math.pow(complex[i*2] / fft.n, 2) + Math.pow(imDouble[i] / fft.n, 2));
		}

		System.out.print("Spectrum: [");
		for (int i = 0; i < length; i++) {
			System.out.print(" " + (int) mag[i]);
		}
		System.out.println("]");
	}
*/
}
