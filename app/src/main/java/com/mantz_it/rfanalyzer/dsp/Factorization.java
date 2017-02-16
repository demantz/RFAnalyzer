package com.mantz_it.rfanalyzer.dsp;

import android.support.annotation.IntRange;
import android.support.annotation.NonNull;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Created by Pavel on 16.12.2016.
 */

public class Factorization {
	private static final String LOGTAG = "Factorization";
	public final List<Integer> factors;
	public final Map<Integer, Integer> factorization;
	public final int n;

	protected Factorization(@NonNull Map<Integer, Integer> factorization) {
		if (factorization.isEmpty()) throw new IllegalArgumentException("Empty factorization map");
		this.factorization = Collections.unmodifiableMap(factorization);
		List<Integer> tmp = unrollFactors(factorization);
		n = Utils.prod(tmp);
		this.factors = Collections.unmodifiableList(tmp);
		//System.out.println("new Factorization: \nFactors" + Arrays.toString(tmp.toArray()));
	}

	/**
	 * Unrolls factors from a1^b1 * a2^b2...
	 * to a1*a1*..b1 times)..*a2*a2*..(b2 times)...
	 *
	 * @return array, containing every factor value times
	 * @map key -- factor, value -- power
	 */
	static List<Integer> unrollFactors(@NonNull Map<Integer, Integer> map) {
		LinkedList<Integer> ret = new LinkedList<>();
		for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
			//System.out.print("unrollFactors: entry: (" + entry.getKey() + ":" + entry.getValue() + ")");
			for (int k = entry.getValue(); k > 0; k--) {
				//System.out.print("unrollFactors: add: (" + entry.getKey() + ")");
				ret.addFirst(entry.getKey());
			}
		}
		return ret;
	}


	/**
	 * Tries to factorize {@code n} with minimal sum of factors and count of multiplications from 3 to 5
	 * Doesn't really check that sum of factors is minimal, just goes recursively on factors closest to
	 * square root and then aggregates them if count of multiplications is greater than 5.
	 *
	 * @param n number to be factorized
	 * @return factors count
	 */
	static Factorization optimalFactorization(@IntRange(from = 1) int n) {
		if (n <= 0)
			throw new IllegalArgumentException("Cannot factorize numbers <= 0!"); // sorry for this

		TreeMap<Integer, Integer> outFactors = new TreeMap<>();

		if (n == 1) {
			outFactors.put(1, 1);
			return new Factorization(outFactors);
		}

		leastSumFactorization(n, outFactors);
		if (1 == 1) {
			// now we have some non-prime factors of n, if there is less than 4 factors -- we can increase
			// their count by factorizing higher factors, or if there are more than 5 factors -- reduce their
			// count by multiplying greatest and least>2

			Integer cnt = sum(outFactors.values());
			// if n is prime -- nothing can be done
			if (cnt == 1) {
				return new Factorization(outFactors);
			}

			// try factorize greatest factor
			// will not be literally 'greatest' if greatest is prime

			if (increaseFactorsCount(outFactors, cnt)
			    && cnt != sum(outFactors.values()))
				return new Factorization(outFactors);
			// cnt didn't change
			decreaseFactorsCount(outFactors, cnt);
		}
		return new Factorization(outFactors);
	}

	private static void decreaseFactorsCount(TreeMap<Integer, Integer> outFactors, Integer cnt) {
		while (cnt > 5) {
			Map.Entry<Integer, Integer> min = outFactors.pollFirstEntry();
			Map.Entry<Integer, Integer> max = outFactors.pollLastEntry();
			// only one factor (like 2^6)
			if (max == null)
				max = min;
			int newFactor = min.getKey() * max.getKey();

			// guaranted to be unique: max_val * x, where x>=2.
			outFactors.put(newFactor, 1);
			if (min != max) {
				// if less than 1 -- no need to return it back, it's 0
				if (min.getValue() > 1)
					outFactors.put(min.getKey(), min.getValue() - 1);
				if (max.getValue() > 1)
					outFactors.put(max.getKey(), max.getValue() - 1);
			} else {
				// can't be less than 6, so no need to check
				outFactors.put(min.getKey(), min.getValue() - 2);
			}
			cnt = sum(outFactors.values());
		}
	}

	private static boolean increaseFactorsCount(TreeMap<Integer, Integer> outFactors, Integer cnt) {
		boolean increased = false;
		Map.Entry<Integer, Integer> greatest = outFactors.lastEntry();
		while (cnt < 4) {
			int f = greatest.getKey();
			int power = greatest.getValue();
			int a = getNextFactor(f);
			int b = f / a;
			// if 'greatest' is prime
			if (b == 1 || a == 1) {
				Map.Entry<Integer, Integer> next = outFactors.lowerEntry(f);
				//outFactors.put(f, power);
				if (next != null) {
					greatest = next;
					continue;
				} else {
					return true;
				}
			}
			// if greatest factor power > 1 (i.e this is exact square root) -- return it back, but factorize one time
			if (power > 1) {
				outFactors.put(f, power - 1);
			} else {
				outFactors.remove(f);
			}
			Integer tmp;
			// put factorized part 1
			if (null != (tmp = outFactors.get(a)))
				tmp++;
			else tmp = 1;
			outFactors.put(a, tmp);

			// put factorized part 2
			if (null != (tmp = outFactors.get(b)))
				tmp++;
			else tmp = 1;
			outFactors.put(b, tmp);

			// recount
			cnt = sum(outFactors.values());
			// next greatest factor for next iteration
			greatest = outFactors.lastEntry();
			increased = true;
		}
		return increased;
	}

	private static void leastSumFactorization(int n, TreeMap<Integer, Integer> outFactors) {
		while (n > 1) {
			int factor = getNextFactor(n);
			// can fire few times(1-2, usually)
			while (n % factor == 0 && n != 1) {
				Integer tmp;
				if (null != (tmp = outFactors.get(factor)))
					tmp++;
				else tmp = 1;
				//System.out.println("leastSumFactorization: " + "put(" + factor + ", " + tmp + ")");
				outFactors.put(factor, tmp);
				n /= factor;
				//if (n == 1) break;
			}
		}
	}

	/**
	 * Calculates sum of factors with respect to their power
	 */
	static int factorsSum(Map<Integer, Integer> factors) {
		int cnt = 0;
		for (Map.Entry<Integer, Integer> factor : factors.entrySet()) {
			cnt += factor.getKey() * factor.getValue();
		}
		return cnt;
	}


	/**
	 * Same as @factorsSum, but squares each factor before multiplying by power.
	 * For statistical operations.
	 */
	static int factorsSqrSum(Map<Integer, Integer> factors) {
		int cnt = 0;
		for (Map.Entry<Integer, Integer> factor : factors.entrySet()) {
			cnt += factor.getKey() * factor.getKey() * factor.getValue();
		}
		return cnt;
	}

	/**
	 * Just returns sum of values in specified vector, but has task-specific name.
	 * Represents number of naïve multiplications needed to get the number from factorization.
	 *
	 * @param powers collection of integers
	 * @return sum of items in collection
	 */
	static int sum(Iterable<Integer> powers) {
		int cnt = 0;
		for (int pow : powers) {
			cnt += pow;
		}
		return cnt;
	}

	/**
	 * @param n positive number
	 * @return smallest in range: sqrt(n) <= factor <= n
	 */
	static int getNextFactor(int n) {
		int factor = (int) Math.ceil(Math.sqrt(n));
		for (; factor <= n; ++factor)
			if (n % factor == 0)
				return factor;
		return n;
	}

}