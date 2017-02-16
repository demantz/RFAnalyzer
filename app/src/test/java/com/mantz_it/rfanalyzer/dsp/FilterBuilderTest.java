package com.mantz_it.rfanalyzer.dsp;

import com.mantz_it.rfanalyzer.dsp.spi.FFT;
import com.mantz_it.rfanalyzer.dsp.spi.FIR_CCC;
import com.mantz_it.rfanalyzer.dsp.spi.FIR_CRC;
import com.mantz_it.rfanalyzer.dsp.spi.Packet;
import com.mantz_it.rfanalyzer.dsp.spi.PacketPool;
import com.mantz_it.rfanalyzer.dsp.impl.SoftFFT;
import com.mantz_it.rfanalyzer.dsp.spi.Window;

import org.junit.Test;

import java.util.Arrays;

/**
 * Created by Pavel on 15.12.2016.
 */
public class FilterBuilderTest {

	@Test
	public void testComplexBandPass() throws Exception {
		// preparations
		int samplesCnt = 128;
		int frequency = 0;
		int rate = 1000;
		int decimation = 1;
		double attenuation = 60;
		double gain = 1.0;
		int lowF = -450;
		int highF = -50;
		int transBand = 50;
		int f1 = 250;
		int f2 = -250;
		testComplexFIR(samplesCnt, frequency, rate, decimation, attenuation, gain, lowF, highF, transBand, f1, f2);
		testComplexFIR(samplesCnt, frequency, rate, decimation, attenuation, gain, -highF, -lowF, transBand, f1, f2);
	}

	@Test
	public void halfbandTest() throws ConvergenceException {
		//Utils.gridDensity = 16;
		double rate = 20;
		double stopBandStart =5;
		double passBandEnd = 5-2;
		double[] taps = OptimalFIRDesigner.lowpass(1, rate, passBandEnd, stopBandStart, 3, 60, 2);
		System.out.printf("TapsHalfband = %s;\n", Arrays.toString(taps));

	}


	@Test
	public void testFirFilter() {
		int samplesCnt = 128;
		int decimation = 1;
		int frequency = 0;
		int rate = 1000;
		double attenuation = 60;
		double gain = 1.0;
		int f1 = 50;
		int f2 = 200;
		int cutOff = 100;
		int transBand = 50;
		Window.Type winType = Window.Type.WIN_BLACKMAN;
		float[] interleavedSamples = new float[samplesCnt * 2];

		Util.mixFrequencies(rate, interleavedSamples, f1, f2);

		FFT fft1 = new SoftFFT(samplesCnt);
		FFT fft2 = new SoftFFT(samplesCnt / decimation);
		Packet in = new Packet(interleavedSamples);
		in.sampleRate = rate;
		//System.out.println("Before FILTER:");
		float[] origSpectrum = Util.spectrum(fft1, interleavedSamples, 0);
		System.out.println("SamplesIn = " + Arrays.toString(interleavedSamples) + ';');
		System.out.println("SpectrumIn = " + Arrays.toString(origSpectrum) + ";");

		// test Window designed filter
		FIR_CRC filter1 = FilterBuilder.lowPass_CRC(decimation, gain, rate, cutOff, transBand, attenuation, winType, 0);
		Util.describeFIR(filter1, in, samplesCnt, decimation, fft2);

		// test Parks-McCellan Remez designed filter
		try {
			FIR_CRC filter2 = FilterBuilder.optimalLowPass_CRC(decimation, gain, rate, cutOff, transBand, 6, attenuation, 2);
			Util.describeFIR(filter2, in, samplesCnt, decimation, fft2);
		} catch (ConvergenceException ce) {
			System.out.println("Remez couldn't converge:\n" + ce.getMessage());
		}
	}


	private void testComplexFIR(int samplesCnt, int frequency, int rate, int decimation, double attenuation, double gain, int lowF, int highF, int transBand, int f1, int f2) {
		System.out.printf("\n%%testComplexFIR(%d, %d, %d, %d, %f, %f, %d, %d, %d, %d, %d);\n"
				, samplesCnt, frequency, rate, decimation, attenuation, gain, lowF, highF, transBand, f1, f2
		);
		float[] interleavedSamples = new float[samplesCnt * 2];
		Window.Type winType = Window.Type.WIN_BLACKMAN;
		Util.mixFrequencies(rate, interleavedSamples, f1, f2);
		Packet in = PacketPool.getIndirectPacketPool().acquire(interleavedSamples.length, true, rate, 0);
		in.getBuffer().put(interleavedSamples);
		FFT fft1 = new SoftFFT(samplesCnt);
		FFT fft2 = new SoftFFT(samplesCnt / decimation);

		System.out.println("SamplesOrig = " + Arrays.toString(interleavedSamples) + ';');
		float[] origSpectrum = Util.spectrum(fft1, interleavedSamples, 0);
		System.out.println("SpectrumOrig = " + Arrays.toString(origSpectrum) + ';');

		// test Window designed filter
		FIR_CCC filter1 = FilterBuilder.bandPass_CCC(
				decimation,
				gain,
				rate,
				lowF, highF, transBand,
				attenuation,
				winType,
				0
		);
		Util.describeFIR(filter1, in, samplesCnt, decimation, fft2);
		// test optimal filter
		try {
			FIR_CCC filter2 = FilterBuilder.optimalBandPass_CCC(
					decimation,
					gain,
					rate,
					lowF, highF, transBand,
					6,
					attenuation,
					2);
			Util.describeFIR(filter2, in, samplesCnt, decimation, fft2);
		} catch (ConvergenceException ce) {
			System.out.println("Remez couldn't converge:\n" + ce.getMessage());
		}
		System.out.println();
	}


	private static void calculateIdealFilterSpectrum(int rate, float attenuation, int lowF, int highF, int transBand, float[] idealSpectrum) {
		float NLowStop = ((float) (lowF - transBand)) / (rate / 2) * (idealSpectrum.length / 2);
		float NLowF = ((float) lowF) / (rate / 2) * (idealSpectrum.length / 2);
		float mul1 = (NLowF - NLowStop) / (idealSpectrum.length / 2) * attenuation;
		float NHighF = ((float) highF) / (rate / 2) * (idealSpectrum.length / 2);
		float NHighStop = ((float) (highF + transBand)) / (rate / 2) * (idealSpectrum.length / 2);
		float mul2 = (NHighF - NHighStop) / (idealSpectrum.length / 2) * attenuation;
		//System.out.printf("NormalizedFreqs = [%f %f %f %f];\n", NLowStop, NLowF, NHighF, NHighStop);
		int shift = idealSpectrum.length / 2;
		int p = 0;
		while (p < NLowStop + shift) idealSpectrum[p++] = -attenuation;
		float tmp = -attenuation;
		while (p < NLowF + shift) idealSpectrum[p++] = tmp += mul1;
		while (p < NHighF + shift) idealSpectrum[p++] = 0;
		tmp = 0;
		while (p < NHighStop + shift) idealSpectrum[p++] = tmp += mul2;
		while (p < idealSpectrum.length) idealSpectrum[p++] = -attenuation;
	}
}
