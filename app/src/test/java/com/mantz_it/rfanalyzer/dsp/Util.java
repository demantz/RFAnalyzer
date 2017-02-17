package com.mantz_it.rfanalyzer.dsp;

import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import com.mantz_it.rfanalyzer.dsp.spi.FFT;
import com.mantz_it.rfanalyzer.dsp.spi.FIR;
import com.mantz_it.rfanalyzer.dsp.spi.Filter;
import com.mantz_it.rfanalyzer.dsp.spi.Packet;
import com.mantz_it.rfanalyzer.dsp.spi.PacketPool;

import org.junit.Assert;

import java.util.Arrays;

/**
 * Created by Pavel on 16.12.2016.
 */

public final class Util {
	private Util() {}

	public static void testFFT(FFT fft, float[] in, float[] golden, float delta) {
		fft.apply(in, 0);
		Assert.assertArrayEquals(golden, in, delta);
	}


	public static void beforeAfter(FFT fft, float[] interleaved, int offset) {
		System.out.println("Before: ");
		printReIm(interleaved, offset);
		//fft.applyWindow(complex, offset);
		fft.apply(interleaved, offset);
		System.out.println("After: ");
		printReIm(interleaved, offset);
	}


	public static void printReIm(float[] interleaved, int offset) {
		System.out.print("Re: [");
		for (int i = offset; i < interleaved.length; i += 2)
			System.out.print(((int) (interleaved[i] * 1000) / 1000.0) + " ");

		System.out.print("]\nIm: [");
		for (int i = offset + 1; i < interleaved.length; i += 2)
			System.out.print(((int) (interleaved[i] * 1000) / 1000.0) + " ");

		System.out.println("]");
	}

	@NonNull
	public static float[] applyFilter(Filter filter, Packet in, int samplesCnt, @IntRange(from = 1) int decimation) {
		Packet out = PacketPool.getArrayPacketPool().acquire(samplesCnt * 2 / decimation);
		in.getBuffer().limit(samplesCnt * 2).position(0);
		out.getBuffer().clear();
		filter.apply(in, out);
		return out.getBuffer().array();
	}

	public static void describeFIR(FIR filter, Packet in, int samplesCnt, int decimation, FFT fft) {
		int responseResolution = 360;
		String name = filter.getClass().getSimpleName();
		System.out.printf("TapsCount%s = %d;\n", name, filter.getTapsCount());
		System.out.printf("Taps%s = %s;\n", name, Arrays.toString(filter.getTaps()));
		float[] samples = Util.applyFilter(filter, in, samplesCnt, decimation);
		System.out.printf("Samples%s = %s;\n", name, Arrays.toString(samples));
		float[] spectrum = Util.spectrum(fft, samples, 0);
		System.out.printf("Spectrum%s = %s;\n", name, Arrays.toString(spectrum));
		double[] amp = new double[responseResolution], phase = new double[responseResolution];
		Utils.filterFreqResponse_R(filter.getTaps(), amp, phase);
		System.out.printf("ResponseAmp%s = %s;\n", name, Arrays.toString(amp));
		System.out.printf("ResponsePhase%s = %s;\n", name, Arrays.toString(phase));
	}


	public static float[] spectrum(FFT fft, float[] interleaved) {
		return spectrum(fft, interleaved, 0);
	}

	public static float[] spectrum(FFT fft, float[] interleaved, int offset) {
		//fft.applyWindow(complex);
		int length = interleaved.length;
		int count = length - offset;
		int samplesCount = count / 2;

		float[] tmp = new float[count];
		float[] mag = new float[count / 2];
		System.arraycopy(interleaved, offset, tmp, offset, length - offset);

		fft.apply(tmp, 0);
		final int mul = 1 / (fft.getSize() * fft.getSize());
		// Calculate the logarithmic magnitude:
		for (int i = 0; i < samplesCount; i++) {
			// We have to flip both sides of the fft to draw it centered on the screen:
			int targetIndex = ((i + samplesCount / 2) % samplesCount);

			// Calc the magnitude = log(  re^2 + im^2  )
			// note that we still have to divide re and im by the fft size
			final double re = interleaved[i * 2] * mul;
			final double im = interleaved[i * 2 + 1] * mul;
			mag[targetIndex] = (float) Math.log(re * re + im * im);
		}
		return mag;
	}

	public static void noise(float[] interleavedSamples) {
		for (int i = 0; i < interleavedSamples.length; ++i)
			interleavedSamples[i] = (float) (Math.random() * 2 - 1);
	}

	public static void mixFrequencies(float rate, float[] interleavedSamples, int... f) {
		for (int i = 0; i < interleavedSamples.length; ) {
			float re = 0;
			for (int freq : f)
				re += (float) Math.cos(2 * Math.PI * freq * i / rate);
			interleavedSamples[i++] = re / f.length;

			float im = 0;
			for (int freq : f)
				im += (float) Math.sin(2 * Math.PI * freq * i / rate);
			interleavedSamples[i++] = im / f.length;
		}
	}

	public static float[] reverseDiff(float[] in) {
		float[] out = new float[in.length];
		for (int i = 0, j = in.length - 1; i < in.length; ++i, --j)
			out[i] = in[i] - in[j];
		return out;
	}

	public static void plotInterleavedSamples(String name) {
		System.out.println("figure; hold on;");
		System.out.printf("plot(%s(1:2:end), 'color', 'green');", name);
		System.out.printf("plot(%s(2:2:end), 'color', 'red');", name);
		System.out.printf("plot(%s(1:2:end)+%s(2:2:end), 'color', 'blue');", name, name);
		System.out.println("\nhold off;");
	}
}
