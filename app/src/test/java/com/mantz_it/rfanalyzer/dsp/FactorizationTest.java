package com.mantz_it.rfanalyzer.dsp;

import com.mantz_it.rfanalyzer.HiQSDRSource;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Created by Pavel on 11.12.2016.
 */
public class FactorizationTest {

	@Test
	public void getOptimalDecimationOutputRate() throws Exception {
		System.out.println("Testing getOptimalDecimation()");
		testODOR(4000, 196000, 44100);
		testODOR(4000, 196000, 48000);
	}

	private void testODOR(int minRate, int maxRate, int idealRate) {
		HiQSDRSource src = new HiQSDRSource("localhost", 0, 0, 0);
		int[] inputRates = src.getSupportedSampleRates();
		System.out.println("MinRate=" + minRate + " IdealRate=" + idealRate + " MaxRate=" + maxRate);
		LinkedList<Integer> factors = new LinkedList<>();
		for (int rate : inputRates) {
			Decimation f = Decimation.getOptimalDecimation(minRate, idealRate, maxRate, rate);
			System.out.printf("Input -> Output = %d -> %d, decimation: %d\n", rate, f.output, f.decimation);
			System.out.println("Factors: " + Arrays.toString(f.decimFactorization.factors.toArray()));
			System.out.println();
			factors.clear();
		}
	}

	@Test
	public void leastSumFactorization() throws Exception {
		int[] numbers = {2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 16, 18, 20, 32, 41, 48, 64};
		int[] minSums = {2, 3, 4, 5, 5, 7, 6, 6, 7, 7, 8, 8, 8, 9, 10, 41, 11, 12};

		for (int i = 0; i < numbers.length; ++i) {
			System.out.print("Test#" + i + ", n=" + numbers[i]);
			leastSumFactors(numbers[i], minSums[i]);
		}
	}


	protected long leastSumFactors(int n, int desiredSum) {
		long elapsed;
		final long then = System.currentTimeMillis();
		Factorization f = Factorization.optimalFactorization(n);
		System.out.print(" took " + (elapsed = System.currentTimeMillis() - then) + " ms ");
		if (desiredSum > 0) {
			System.out.print("Factors count: " + f.factors.size());
			int decimation = Utils.prod(f.factors);
			System.out.print("; Factors decimation: " + decimation);
			int sum = Factorization.sum(f.factors);
			System.out.print("; Factors sum: " + sum);
			System.out.print("; Factors: " + Arrays.toString(f.factors.toArray()));
			assertEquals(sum, desiredSum);
		}
		System.out.print(" Result: ");
		int prod = 1;
		for (Map.Entry<Integer, Integer> factor : f.factorization.entrySet()) {
			for (int j = 0; j < factor.getValue(); ++j) {
				int k = factor.getKey();
				System.out.print(" " + k);
				prod *= k;
			}
		}
		System.out.println();
		assertEquals(prod, n);
		return elapsed;
	}

	@Test
	public void testMinSumFactorsFuzzy() throws Exception {
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
			long time = leastSumFactors(next, 0);
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

}