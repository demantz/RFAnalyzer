package com.mantz_it.rfanalyzer.dsp;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

/**
 * Created by Pavel on 10.01.2017.
 */

public class PlaygroundTest {

	@Test
	public void testProd() {
		int[] data = new int[100000];
		ArrayList<Integer> data2 = new ArrayList<> (Collections.nCopies(100000, 0));
		long dummy = 0;
		long start;

		System.out.printf("#array\n");
		for (int i = 0; i < 10000; ++i) {
			final int prod = Utils.prod(data);
			dummy += prod; // do not jit optimize! >:(
		}
		start = System.currentTimeMillis();
		for (int i = 0; i < 10000; ++i) {
			final int prod = Utils.prod(data);
			dummy += prod; // do not jit optimize! >:(
		}
		System.out.printf("Time: %d, dummy: %d\n", System.currentTimeMillis() - start, dummy);
		//----------------------
		System.out.printf("#array hand iter\n");
		for (int i = 0; i < 10000; ++i) {
			final int prod = Utils.prod2(data2);
			dummy += prod; // do not jit optimize! >:(
		}
		start = System.currentTimeMillis();
		for (int i = 0; i < 10000; ++i) {
			final int prod = Utils.prod2(data2);
			dummy += prod; // do not jit optimize! >:(
		}
		System.out.printf("Time: %d, dummy: %d\n", System.currentTimeMillis() - start, dummy);
		//----------------------
		System.out.printf("#Iterable unboxed\n");
		dummy = 0;
		for (int i = 0; i < 10000; ++i) {
			final int prod = Utils.prod(data2);
			dummy += prod; // do not jit optimize! >:(
		}
		start = System.currentTimeMillis();
		for (int i = 0; i < 10000; ++i) {
			final int prod = Utils.prod(data2);
			dummy += prod; // do not jit optimize! >:(
		}
		System.out.printf("Time: %d, dummy: %d\n", System.currentTimeMillis() - start, dummy);
		//----------------------
		System.out.printf("#Iterable boxed\n");
		dummy = 0;
		for (int i = 0; i < 10000; ++i) {
			final int prod = Utils.prod2(data2);
			dummy += prod; // do not jit optimize! >:(
		}
		start = System.currentTimeMillis();
		for (int i = 0; i < 10000; ++i) {
			final int prod = Utils.prod2(data2);
			dummy += prod; // do not jit optimize! >:(
		}
		System.out.printf("Time: %d, dummy: %d\n", System.currentTimeMillis() - start, dummy);
	}

	public double mean_naive(float[] data) {
		double mean = 0;
		for (int i = 0; i < data.length; ++i) {
			mean += data[i];
		}
		mean /= data.length;
		return mean;
	}

	public double mean_continuous(float[] data) {
		double meanPrev = data[0];
		double mean = meanPrev;
		for (int i = 1; i < data.length - 1; ++i) {
			mean = (meanPrev * i + data[i + 1]) / (i + 1);
			//
			meanPrev = mean;
		}
		return mean;
	}

	public float[] DC_IIR(float[] data, float[] out) {
		out[0] = data[0];
		final float amp = 0.5f;
		for (int i = 1; i < data.length; ++i)
			out[i] = amp * (data[i] - data[i - 1] + out[i - 1]);
		return out;
	}

	public float[] DC_naive1(float[] data, float[] out) {
		float mean = (float) mean_naive(data);
		for (int i = 0; i < data.length; ++i)
			out[i] = data[i] - mean;
		return out;
	}

	public float[] DC_naive2(float[] data, float[] out) {
		float mean = (float) mean_continuous(data);
		for (int i = 0; i < data.length; ++i)
			out[i] = data[i] - mean;
		return out;
	}

	@Test
	public void testDC_Removals() {
		float[] data = randomFloats(16384);
		float[] dc1 = DC_IIR(data, new float[data.length]);
		double mean11 = mean_naive(dc1);
		double mean12 = mean_continuous(dc1);
		float[] dc2 = DC_naive1(data, new float[data.length]);
		double mean21 = mean_naive(dc2);
		double mean22 = mean_continuous(dc2);
		float[] dc3 = DC_naive2(data, new float[data.length]);
		double mean31 = mean_naive(dc3);
		double mean32 = mean_continuous(dc3);
		System.out.println(">>>>>>>>>>>>>DC offset removal test<<<<<<<<<<<<<");
		System.out.printf("IIR:\n\t     Mean naive: %f\n\tMean continuous: %f\n", mean11, mean12);
		System.out.printf("Naive1:\n\t     Mean naive: %f\n\tMean continuous: %f\n", mean21, mean22);
		System.out.printf("Naive2:\n\t     Mean naive: %f\n\tMean continuous: %f\n", mean31, mean32);
	}

	@Test
	public void benchmarkDC_removals() {
		int rounds = 100000;
		float[] data = randomFloats(16384);
		float[] out = new float[data.length];
		long start, total;
		System.out.printf("IIR [Rounds: %d]: ", rounds);
		start = System.currentTimeMillis();
		for (int i = 0; i < rounds; ++i) {
			DC_IIR(data, out);
		}
		total = System.currentTimeMillis() - start;
		System.out.printf("\n\tTotal  : %d ms\n\tAverage: %f ms\n", total, (double) total / rounds);

		System.out.printf("Naive1 [Rounds: %d]: ", rounds);
		start = System.currentTimeMillis();
		for (int i = 0; i < rounds; ++i) {
			DC_naive1(data, out);
		}
		total = System.currentTimeMillis() - start;
		System.out.printf("\n\tTotal  : %d ms\n\tAverage: %f ms\n", total, (double) total / rounds);

		System.out.printf("Naive2 [Rounds: %d]: ", rounds);
		start = System.currentTimeMillis();
		for (int i = 0; i < rounds; ++i) {
			DC_naive2(data, out);
		}
		total = System.currentTimeMillis() - start;
		System.out.printf("\n\tTotal  : %d ms\n\tAverage: %f ms\n", total, (double) total / rounds);

	}

	@Test
	public void testMeans() {
		float[] data = randomFloats(16384);

		System.out.printf("Mean Naive     : %f\n", mean_naive(data));
		System.out.printf("Mean Continuous: %f\n", mean_continuous(data));
	}

	public static float[] randomFloats(int size) {
		Random rnd = new Random();
		float[] data = new float[size];
		for (int i = 0; i < data.length; ++i)
			data[i] = rnd.nextFloat();
		return data;
	}

}
