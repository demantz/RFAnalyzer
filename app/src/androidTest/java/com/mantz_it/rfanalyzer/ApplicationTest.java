package com.mantz_it.rfanalyzer;

import android.app.Application;
import android.test.ApplicationTestCase;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

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

	protected final int SAMPLES_IN_PACKET = 2 * (1 << 20);  // 2 MiB packet

	@Override
	public void setUp() throws Exception {
		super.setUp();
		rnd = new Random();/*
		testPacket = new byte[SAMPLES_IN_PACKET];
		test24bitPacket = new byte[SAMPLES_IN_PACKET * 3];
		rnd.nextBytes(testPacket);
		rnd.nextBytes(test24bitPacket);*/
	}

	public void testGetOptimalDecimation() {
		System.out.println("Testing getOptimalDecimationOutputRate()");

		int minRate = 4000;
		int maxRate = 196000;
		int idealRate = 44100;
		HiQSDRSource src = new HiQSDRSource("localhost", 0, 0, 0);
		int[] inputRates = src.getSupportedSampleRates();
		System.out.println("MinRate=" + minRate + " IdealRate=" + idealRate + " MaxRate=" + maxRate);
		LinkedList<Integer> factors = new LinkedList<>();
		for (int rate : inputRates) {
			System.out.println("InputRate=" + rate);
			int decimation = Decimator.getOptimalDecimationOutputRate(minRate, idealRate, maxRate, rate, factors);
			System.out.println("Calculated optimal output rate: " + decimation);
			System.out.println("Selected factors: " + Arrays.toString(factors.toArray()));
			factors.clear();
		}
	}

	public void testHiQSDRPacketCntr() {
		final byte[] buff = new byte[1442];
		HiQSDRSource src = new HiQSDRSource();
		src.previousPacketIdx = 0;
		for (int i = 1; i < 520; ++i) {
			buff[0] = (byte) (i & 0xff);
			final int m = src.updatePacketIndex(buff);
			if (m != 0)
				System.out.println("testHiQSDRPacketCntr: false positive ("
				                   + "i=" + i
				                   + ", missed=" + m
				                   + ", but must be 0).");
			//else System.out.println("testHiQSDRPacketCntr: ok = "+m);
		}
		for (int j = 2; j < 255; ++j) {
			src.previousPacketIdx = 0;
			for (int i = j; i < j * 520; i += j) {
				buff[0] = (byte) (i & 0xff);
				final byte prev = src.previousPacketIdx;
				final int m = src.updatePacketIndex(buff);
				final byte current = src.previousPacketIdx;
				if (m != (j - 1))
					System.out.println("testHiQSDRPacketCntr: false negative ("
					                   + "i=" + i
					                   + ", j=" + j
					                   + ",prev=" + prev
					                   + ", current=" + current
					                   + ", missed=" + m
					                   + ", must be " + (j - 1) + ").");
				/*else System.out.println("testHiQSDRPacketCntr: ok ("
				                        + "i=" + i
				                        + ", j=" + j
				                        + ",prev=" + prev
				                        + ", current=" + current
				                        + ", missed=" + m
				                        + ").");*/
			}
		}


	}

	public void testInitArrays() {
		System.out.println("Testing HiQSDR.initArrays()");
		HiQSDRSource.initArrays();
		System.out.println("\tSamplerate codes: " + Arrays.toString(HiQSDRSource.SAMPLE_RATE_CODES));
		System.out.println("\tSamplerates     : " + Arrays.toString(HiQSDRSource.SAMPLE_RATES));
		System.out.println("\tPairs:");
		for (int i = 0; i < HiQSDRSource.SAMPLE_RATE_CODES.length; ++i)
			System.out.println("\t\t" + HiQSDRSource.SAMPLE_RATE_CODES[i] + ':' + HiQSDRSource.SAMPLE_RATES[i]);
		System.out.println("---------------------------------------------------------------------");
	}

	public long testConverterPerformance(IQConverter converter, byte[] packet, SamplePacket samplePacket) {
		long start = System.currentTimeMillis();
		converter.fillPacketIntoSamplePacket(packet, samplePacket);
		return System.currentTimeMillis() - start;
	}

	public void testMinSumFactorsFuzzy() {
		final int COUNT = 100000;
		final int MAX = 1000000;
		double averageTime = 0;
		long timeAccum = 0;
		long minTime = 99999999;
		long maxTime = 0;
		//System.out.println("Running fuzzy test on minSumFactors function over " + COUNT + " random numbers [0:" + MAX + ')');
		for (int i = 1; i <= COUNT; ++i) {
			int next = i;//Math.abs(rnd.nextInt());
			next %= MAX;
			System.out.print("Test#" + i + ", n=" + next);
			long time = runMinSumFactorTestForNumber(next, 0);
			timeAccum += time;
			if (time > maxTime)
				maxTime = time;
			if (time < minTime)
				minTime = time;
			if (i % 1000 == 0) {
				averageTime = timeAccum / 1000;
				timeAccum = 0;
				System.out.println("Average time: " + averageTime + "; min time: " + minTime + "; max time: " + maxTime);
			}
		}

	}


	protected long runMinSumFactorTestForNumber(int n, int desiredMinSum) {
		TreeMap<Integer, Integer> out = new TreeMap<>();
		long elapsed;
		final long then = System.currentTimeMillis();
		int factorsCnt = Decimator.minSumFactors(n, out);
		System.out.print(" took " + (elapsed = System.currentTimeMillis() - then) + " ms ");
		if (desiredMinSum > 0) {
			System.out.print("Factors count: " + factorsCnt);
			int factorsSum = Decimator.factorsSum(out);
			System.out.print("; Factors sum: " + factorsSum);
			if (factorsSum != desiredMinSum)
				System.out.print(" But should be " + desiredMinSum);
		}
		System.out.print(" Result: ");
		int prod = 1;
		for (Map.Entry<Integer, Integer> factor : out.entrySet()) {
			for (int j = 0; j < factor.getValue(); ++j) {
				int k = factor.getKey();
				System.out.print(" " + k);
				prod *= k;
			}
		}
		System.out.println();
		if (prod != n) {
			System.out.println("Resulting product: " + prod + " BUT MUST BE " + n + '.');
			this.terminateApplication();
		}
		return elapsed;
	}

	public void testMinSumFactors() {
		int[] numbers = {2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 16, 18, 20, 32, 41, 48, 64};
		int[] minSums = {2, 3, 4, 5, 5, 7, 6, 6, 7, 7, 8, 8, 8, 9, 10, 41, 11, 12};

		for (int i = 0; i < numbers.length; ++i) {
			System.out.print("Test#" + i + ", n=" + numbers[i]);
			runMinSumFactorTestForNumber(numbers[i], minSums[i]);
		}
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
		IQConverter converter = new Unsigned24BitIQConverter();
		SamplePacket sp = new SamplePacket(SAMPLES_IN_PACKET);
		long time = testConverterPerformance(converter, test24bitPacket, sp);
		System.out.println("Unsigned24BitIQConverter fills packet in " + time + " ms");
	}

	public void testFirFilter() {
		int samples = 128;
		float[] reIn = new float[samples];
		float[] imIn = new float[samples];
		float[] reOut = new float[samples / 4];
		float[] imOut = new float[samples / 4];
		int sampleRate = 1000;
		SamplePacket in = new SamplePacket(reIn, imIn, 0, sampleRate);
		SamplePacket out = new SamplePacket(reOut, imOut, 0, sampleRate / 4);
		out.setSize(0);
		int f1 = 50;
		int f2 = 200;

		for (int i = 0; i < reIn.length; i++) {
			reIn[i] = (float) Math.cos(2 * Math.PI * f1 * i / (float) sampleRate) + (float) Math.cos(2 * Math.PI * f2 * i / (float) sampleRate);
			imIn[i] = (float) Math.sin(2 * Math.PI * f1 * i / (float) sampleRate) + (float) Math.sin(2 * Math.PI * f2 * i / (float) sampleRate);
		}

		FirFilter filter = FirFilter.createLowPass(4, 1, sampleRate, 100, 50, 60);
		System.out.println("Created filter with " + filter.getNumberOfTaps() + " taps!");

		FFT fft1 = new FFT(samples);

		System.out.println("Before FILTER:");
		spectrum(fft1, reIn, imIn);

		filter.filter(in, out, 0, in.size());

		FFT fft2 = new FFT(samples / 4);

		System.out.println("After FILTER:");
		spectrum(fft2, reOut, imOut);
	}

	public void testComplexFirFilter() {
		int samples = 32;
		float[] reIn = new float[samples];
		float[] imIn = new float[samples];
		float[] reOut = new float[samples];
		float[] imOut = new float[samples];
		int sampleRate = 1000;
		SamplePacket in = new SamplePacket(reIn, imIn, 0, sampleRate);
		SamplePacket out = new SamplePacket(reOut, imOut, 0, sampleRate);
		out.setSize(0);
		int f1 = 250;
		int f2 = -250;

		for (int i = 0; i < reIn.length; i++) {
			reIn[i] = (float) Math.cos(2 * Math.PI * f1 * i / (float) sampleRate) + (float) Math.cos(2 * Math.PI * f2 * i / (float) sampleRate);
			imIn[i] = (float) Math.sin(2 * Math.PI * f1 * i / (float) sampleRate) + (float) Math.sin(2 * Math.PI * f2 * i / (float) sampleRate);
		}

		ComplexFirFilter filter = ComplexFirFilter.createBandPass(1, 1, sampleRate, -450, -50, 50, 60);
		System.out.println("Created filter with " + filter.getNumberOfTaps() + " taps!");

		FFT fft1 = new FFT(samples);

		System.out.println("Before FILTER:");
		spectrum(fft1, reIn, imIn);

		filter.filter(in, out, 0, in.size());

		FFT fft2 = new FFT(samples);

		System.out.println("After FILTER:");
		spectrum(fft2, reOut, imOut);
	}

	public void testComplexFirFilter2() {
		int samples = 32;
		float[] reIn = new float[samples];
		float[] imIn = new float[samples];
		float[] reOut = new float[samples];
		float[] imOut = new float[samples];
		int sampleRate = 1000;
		SamplePacket in = new SamplePacket(reIn, imIn, 0, sampleRate);
		SamplePacket out = new SamplePacket(reOut, imOut, 0, sampleRate);
		out.setSize(0);

		reIn[0] = 1;
		imIn[0] = 1;
		for (int i = 1; i < reIn.length; i++) {
			reIn[i] = 0;
			imIn[i] = 0;
		}

		ComplexFirFilter filter = ComplexFirFilter.createBandPass(1, 1, sampleRate, 50, 450, 50, 60);
		System.out.println("Created filter with " + filter.getNumberOfTaps() + " taps!");

		FFT fft1 = new FFT(samples);

		System.out.println("Before FILTER:");
		spectrum(fft1, reIn, imIn);

		filter.filter(in, out, 0, in.size());

		FFT fft2 = new FFT(samples);

		System.out.println("After FILTER:");
		spectrum(fft2, reOut, imOut);
	}

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

		FFT fft1 = new FFT(samples);
		System.out.println("Before FILTER:");
		spectrum(fft1, reIn, imIn);

		FFT fft2 = new FFT(samples / 2);
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

		// just for fun: see how the N8 filter performs with filterN8 and with filter:
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

	public void testFFT() throws Exception {
		// Test the FFT to make sure it's working
		int N = 8;

		FFT fft = new FFT(N);

		float[] window = fft.getWindow();
		float[] re = new float[N];
		float[] im = new float[N];

		// Impulse
		re[0] = 1;
		im[0] = 0;
		for (int i = 1; i < N; i++)
			re[i] = im[i] = 0;
		beforeAfter(fft, re, im);

		// Nyquist
		for (int i = 0; i < N; i++) {
			re[i] = (float) Math.pow(-1, i);
			im[i] = 0;
		}
		beforeAfter(fft, re, im);

		// Single sin
		for (int i = 0; i < N; i++) {
			re[i] = (float) Math.cos(2 * Math.PI * i / N);
			im[i] = 0;
		}
		beforeAfter(fft, re, im);

		// Ramp
		for (int i = 0; i < N; i++) {
			re[i] = i;
			im[i] = 0;
		}
		beforeAfter(fft, re, im);

		long time = System.currentTimeMillis();
		double iter = 30000;
		for (int i = 0; i < iter; i++)
			fft.fft(re, im);
		time = System.currentTimeMillis() - time;
		System.out.println("Averaged " + (time / iter) + "ms per iteration");
	}

	protected static void beforeAfter(FFT fft, float[] re, float[] im) {
		System.out.println("Before: ");
		printReIm(re, im);
		//fft.applyWindow(re, im);
		fft.fft(re, im);
		System.out.println("After: ");
		printReIm(re, im);
	}

	protected static void printReIm(float[] re, float[] im) {
		System.out.print("Re: [");
		for (int i = 0; i < re.length; i++)
			System.out.print(((int) (re[i] * 1000) / 1000.0) + " ");

		System.out.print("]\nIm: [");
		for (int i = 0; i < im.length; i++)
			System.out.print(((int) (im[i] * 1000) / 1000.0) + " ");

		System.out.println("]");
	}

	protected static void spectrum(FFT fft, float[] re, float[] im) {
		//fft.applyWindow(re, im);
		int length = re.length;
		float[] reDouble = new float[length];
		float[] imDouble = new float[length];
		float[] mag = new float[length];
		for (int i = 0; i < length; i++) {
			reDouble[i] = re[i];
			imDouble[i] = im[i];
		}

		fft.fft(reDouble, imDouble);
		// Calculate the logarithmic magnitude:
		for (int i = 0; i < length; i++) {
			// We have to flip both sides of the fft to draw it centered on the screen:
			int targetIndex = (i + length / 2) % length;

			// Calc the magnitude = log(  re^2 + im^2  )
			// note that we still have to divide re and im by the fft size
			mag[targetIndex] = (float) Math.log(Math.pow(reDouble[i] / fft.n, 2) + Math.pow(imDouble[i] / fft.n, 2));
		}

		System.out.print("Spectrum: [");
		for (int i = 0; i < length; i++) {
			System.out.print(" " + (int) mag[i]);
		}
		System.out.println("]");
	}

}