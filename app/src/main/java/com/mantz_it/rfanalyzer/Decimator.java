package com.mantz_it.rfanalyzer;

import android.util.Log;

import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - Decimator</h1>
 * <p/>
 * Module:      Decimator.java
 * Description: This class implements a decimation block used to downsample the incoming signal
 * to the sample rate used by the demodulation routines. It will run in a separate thread.
 *
 * @author Dennis Mantz
 *         <p/>
 *         Copyright (C) 2014 Dennis Mantz
 *         License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *         <p/>
 *         This library is free software; you can redistribute it and/or
 *         modify it under the terms of the GNU General Public
 *         License as published by the Free Software Foundation; either
 *         version 2 of the License, or (at your option) any later version.
 *         <p/>
 *         This library is distributed in the hope that it will be useful,
 *         but WITHOUT ANY WARRANTY; without even the implied warranty of
 *         MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *         General Public License for more details.
 *         <p/>
 *         You should have received a copy of the GNU General Public
 *         License along with this library; if not, write to the Free Software
 *         Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
public class Decimator extends Thread {
	private int outputSampleRate;    // sample rate at the output of the decimator block
	private int packetSize;            // packet size of the incoming packets
	private boolean stopRequested = true;
	private static final String LOGTAG = "Decimator";

	private static final int OUTPUT_QUEUE_SIZE = 2;        // Double Buffer
	private ArrayBlockingQueue<SamplePacket> inputQueue;        // queue that holds the incoming sample packets
	private ArrayBlockingQueue<SamplePacket> inputReturnQueue;    // queue to return used buffers from the input queue
	private ArrayBlockingQueue<SamplePacket> outputQueue;        // queue that will hold the decimated sample packets
	private ArrayBlockingQueue<SamplePacket> outputReturnQueue;    // queue to return used buffers from the output queue

	// DOWNSAMPLING:
	public static final int INPUT_RATE = 1000000; // For now, this decimator only works with a fixed input rate of 1Msps
	private HalfBandLowPassFilter inputFilter1 = null;
	private HalfBandLowPassFilter inputFilter2 = null;
	private HalfBandLowPassFilter inputFilter3 = null;
	private FirFilter inputFilter4 = null;
	private SamplePacket tmpDownsampledSamples;

	/**
	 * Constructor. Will create a new Decimator block.
	 *
	 * @param outputSampleRate // sample rate to which the incoming samples should be decimated
	 * @param packetSize       // packet size of the incoming sample packets
	 * @param inputQueue       // queue that delivers incoming sample packets
	 * @param inputReturnQueue // queue to return used input sample packets
	 */
	public Decimator(int outputSampleRate, int packetSize, ArrayBlockingQueue<SamplePacket> inputQueue,
	                 ArrayBlockingQueue<SamplePacket> inputReturnQueue) {
		super("Decimator Thread");
		this.outputSampleRate = outputSampleRate;
		this.packetSize = packetSize;
		this.inputQueue = inputQueue;
		this.inputReturnQueue = inputReturnQueue;

		// Create output queues:
		this.outputQueue = new ArrayBlockingQueue<SamplePacket>(OUTPUT_QUEUE_SIZE);
		this.outputReturnQueue = new ArrayBlockingQueue<SamplePacket>(OUTPUT_QUEUE_SIZE);
		for (int i = 0; i < OUTPUT_QUEUE_SIZE; i++)
			outputReturnQueue.offer(new SamplePacket(packetSize));

		// Create half band filters for downsampling:
		this.inputFilter1 = new HalfBandLowPassFilter(8);
		this.inputFilter2 = new HalfBandLowPassFilter(8);
		this.inputFilter3 = new HalfBandLowPassFilter(8);

		// Create local buffers:
		this.tmpDownsampledSamples = new SamplePacket(packetSize);
	}

	public int getOutputSampleRate() {
		return outputSampleRate;
	}

	public void setOutputSampleRate(int outputSampleRate) {
		this.outputSampleRate = outputSampleRate;
	}

	public SamplePacket getDecimatedPacket(int timeout) {
		try {
			return outputQueue.poll(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Log.e(LOGTAG, "getPacket: Interrupted while waiting on queue");
			return null;
		}
	}

	public void returnDecimatedPacket(SamplePacket packet) {
		outputReturnQueue.offer(packet);
	}

	@Override
	public synchronized void start() {
		this.stopRequested = false;
		super.start();
	}

	public void stopDecimator() {
		this.stopRequested = true;
	}

	@Override
	public void run() {
		SamplePacket inputSamples;
		SamplePacket outputSamples;

		Log.i(LOGTAG, "Decimator started. (Thread: " + this.getName() + ")");

		while (!stopRequested) {
			// Get a packet from the input queue:
			try {
				inputSamples = inputQueue.poll(1000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "run: Interrupted while waiting on input queue! stop.");
				this.stopRequested = true;
				break;
			}

			// Verify the input sample packet is not null:
			if (inputSamples == null) {
				//Log.d(LOGTAG, "run: Input sample is null. skip this round...");
				continue;
			}

			// Verify the input sample rate: 	(For now, this decimator only works with a fixed input rate of 1Msps)
			if (inputSamples.getSampleRate() != INPUT_RATE) {
				Log.d(LOGTAG, "run: Input sample rate is " + inputSamples.getSampleRate() + " but should be" + INPUT_RATE + ". skip.");
				continue;
			}

			// Get a packet from the output queue:
			try {
				outputSamples = outputReturnQueue.poll(1000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "run: Interrupted while waiting on output return queue! stop.");
				this.stopRequested = true;
				break;
			}

			// Verify the output sample packet is not null:
			if (outputSamples == null) {
				Log.d(LOGTAG, "run: Output sample is null. skip this round...");
				continue;
			}

			// downsampling
			downsampling(inputSamples, outputSamples);

			// return inputSamples back to the input queue:
			inputReturnQueue.offer(inputSamples);

			// deliver the outputSamples to the output queue
			outputQueue.offer(outputSamples);
		}

		this.stopRequested = true;
		Log.i(LOGTAG, "Decimator stopped. (Thread: " + this.getName() + ")");
	}

	/**
	 * Will decimate the input samples to the outputSampleRate and store them in output
	 *
	 * @param input  incoming samples at the incoming rate (input rate)
	 * @param output outgoing (decimated) samples at output rate (quadrature rate)
	 */
	private void downsampling(SamplePacket input, SamplePacket output) {
		// Verify that the input filter 4 is still correct configured (gain):
		if (inputFilter4 == null || inputFilter4.getGain() != 2 * (outputSampleRate / (double) input.getSampleRate())) {
			// We have to (re-)create the filter:
			this.inputFilter4 = FirFilter.createLowPass(2, 2 * (outputSampleRate / (float) input.getSampleRate()), 1, 0.15f, 0.2f, 20);
			Log.d(LOGTAG, "downsampling: created new inputFilter4 with " + inputFilter4.getNumberOfTaps()
			              + " taps. Decimation=" + inputFilter4.getDecimation() + " Cut-Off=" + inputFilter4.getCutOffFrequency()
			              + " transition=" + inputFilter4.getTransitionWidth());
		}

		// apply first filter (decimate to INPUT_RATE/2)
		tmpDownsampledSamples.setSize(0);    // mark buffer as empty
		if (inputFilter1.filterN8(input, tmpDownsampledSamples, 0, input.size()) < input.size()) {
			Log.e(LOGTAG, "downsampling: [inputFilter1] could not filter all samples from input packet.");
		}

		// if we need a decimation of 16: apply second and third filter (decimate to INPUT_RATE/8)
		if (input.getSampleRate() / outputSampleRate == 16) {
			output.setSize(0);    // mark buffer as empty
			if (inputFilter2.filterN8(tmpDownsampledSamples, output, 0, tmpDownsampledSamples.size()) < tmpDownsampledSamples.size()) {
				Log.e(LOGTAG, "downsampling: [inputFilter2] could not filter all samples from input packet.");
			}

			tmpDownsampledSamples.setSize(0);    // mark tmp buffer as again
			if (inputFilter3.filterN8(output, tmpDownsampledSamples, 0, output.size()) < output.size()) {
				Log.e(LOGTAG, "downsampling: [inputFilter3] could not filter all samples from input packet.");
			}
		}

		// apply fourth filter (decimate either to INPUT_RATE/4 or INPUT_RATE/16)
		output.setSize(0);    // mark buffer as empty
		if (inputFilter4.filter(tmpDownsampledSamples, output, 0, tmpDownsampledSamples.size()) < tmpDownsampledSamples.size()) {
			Log.e(LOGTAG, "downsampling: [inputFilter4] could not filter all samples from input packet.");
		}
	}

	/**
	 * Selects optimal decimation frequency and factorization.
	 *
	 * @param minOutRate     Minimal supported output rate by system's resampler.
	 * @param optimalOutRate Desired output rate (one that doesn't need to use system's resampling function).
	 * @param maxOutRate     Maximal supported output rate by system's resampler.
	 * @param inputRate      Rate of signal to be decimated.
	 * @param factors        Output parameter, filled with factors for multistage decimation.
	 * @return Selected output rate.
	 */
	public static int getOptimalDecimationOutputRate(
			int minOutRate, int optimalOutRate, int maxOutRate,
			int inputRate,
			LinkedList<Integer> factors
	) {
		// argument checks
		if (factors == null)
			throw new NullPointerException("List parameter must be non-null");
		factors.clear();  // clear

		if (minOutRate <= 0 || maxOutRate <= 0 || inputRate <= 0)
			throw new IllegalArgumentException("Rates must be provided by positive numbers.");

		// this will trigger min>max too
		if (minOutRate > optimalOutRate || optimalOutRate > maxOutRate)
			throw new IllegalArgumentException("Output rate boundaries must meet rules: min<=optimal<=max.");

		if (inputRate < minOutRate)
			throw new IllegalArgumentException("Input rate is lower, than minimal output rate, upsampling needed, but not implemented.");
		//Log.d(LOGTAG, "Getting optimal Decimation for input rate: " + inputRate);

		// if rate in bounds, then no extra work is an optimal choice
		if (inputRate < maxOutRate) {
			factors.add(1);
			return inputRate;
		}

		// decimation factors
		int minimum = (int) (Math.ceil(inputRate / (float) maxOutRate)); // (float) for fp division, instead of integer
		int maximum = (int) (Math.floor(inputRate / (float) minOutRate));
		float ideal = (float) inputRate / optimalOutRate;

		int[] weights = new int[maximum - minimum + 1]; // decimation factor -- weight pairs

		int selected = minimum;
		TreeMap<Integer, Integer> selectedFactorization = null;
		TreeMap<Integer, Integer> factorization = new TreeMap<>();
		Log.d(LOGTAG, "Calculated weights for different decimations:"); // debug
		for (int i = minimum; i <= maximum; ++i) {
			int cnt = minSumFactors(i, factorization); // cnt is count of stages
			// we want minimum stages(minimum operations),
			// minimum different stages (minimum memory)
			// and minimum stage decimation (maximum bandwidth of FIR filter -> less taps -> much less operations)
			// todo: choose better formula
			int weight =
					(int) (
							Math.sqrt(factorsSqrSum(factorization))
							* cnt
							* factorization.size()
							* Math.abs(i - ideal)
					);

			weights[i - minimum] = weight;
			if (weight <= weights[selected - minimum]) {
				selected = i;
				// copy constructor and clear uses less memory, than new constructor for each change and reference copy
				selectedFactorization = new TreeMap<>(factorization);
			}
			// debug
			//Log.d(LOGTAG, "Decimation: " + i + " Factors: " + Arrays.toString(unrollFactors(factorization))
			//              + " Weight: " + weight);
			//\debug
			factorization.clear();
		}
		//Log.d(LOGTAG, "Selected decimation: " + selected); // debug
		// results:
		// consider output type change to TreeMap instead of LinkedList
		int[] factor_values = unrollFactors(selectedFactorization);
		for (int factor_value : factor_values) factors.add(factor_value);
		return inputRate / selected;
	}

	/**
	 * Unrolls factors from a1^b1 * a2^b2...
	 * to a1*a1*..b1 times)..*a2*a2*..(b2 times)...
	 * @map key -- factor, value -- power
	 * @return array, containing every factor value times
	 */
	static int[] unrollFactors(TreeMap<Integer, Integer> map) {
		if (map == null) throw new NullPointerException();
		int[] result = new int[factorsCount(map.values())];
		int iter = 0;
		for (Integer key : map.keySet()) {
			for (int i = map.get(key); i > 0;
			     ++iter, --i)
				result[iter] = key;
		}
		return result;
	}

	/**
	 * Tries to factorize {@code n} with minimal sum of factors and count of multiplications from 3 to 5
	 * Doesn't really check that sum of factors is minimal, just goes recursively on factors closest to
	 * square root and then aggregates them if count of multiplications is greater than 5.
	 *
	 * @param n          number to be factorized
	 * @param outFactors returns factor:power pairs, so we can process unique factors aggregated as factor^power
	 *                   (n=П&nbsp;[i=0..UniqueFactorsCnt] factor[i]^power[i])
	 * @return factors count
	 */
	static int minSumFactors(int n, TreeMap<Integer, Integer> outFactors) {
		if (n <= 0)
			throw new IllegalArgumentException("Cannot factorize numbers <= 0!");
		outFactors.clear();
		if (n == 1) {
			outFactors.put(1, 1);
			return 1;
		}
		// I bet this all can be rewritten more elegant way.
		while (n > 1) {
			int factor = getNextFactor(n);
			// can fire 1 or 2 times
			while (n % factor == 0) {
				Integer tmp;
				if (null != (tmp = outFactors.get(factor)))
					tmp++;
				else tmp = 1;
				outFactors.put(factor, tmp);
				n /= factor;
				if (n == 1) break;
			}
		}
		// now we have some non-prime factors of n, if there is less than 4 factors -- we can increase
		// their count by factorizing higher factors, or if there are more than 5 factors -- reduce their
		// count by multiplying greatest and least>2
		int cnt = factorsCount(outFactors.values());
		// if n is prime
		if (cnt == 1) {
			return 1;
		}
		boolean increasedFactorCount = false;
		// try factorize greatest factor
		// will not be literally 'greatest' if greatest is prime
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
					return factorsCount(outFactors.values());
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
			cnt = factorsCount(outFactors.values());
			// next greatest factor for next iteration
			greatest = outFactors.lastEntry();
			// don't try reduce if just increased
			increasedFactorCount = true;
		}
		if (increasedFactorCount) {
			return cnt;
		}

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
			cnt = factorsCount(outFactors.values());
		}
		return cnt;
	}

	/**
	 * Calculates sum of factors with respect to their power
	 */
	static int factorsSum(TreeMap<Integer, Integer> factors) {
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
	static int factorsSqrSum(TreeMap<Integer, Integer> factors) {
		int cnt = 0;
		for (Map.Entry<Integer, Integer> factor : factors.entrySet()) {
			cnt += factor.getKey() * factor.getKey() * factor.getValue();
		}
		return cnt;
	}

	/** Just returns sum of values in specified vector, but has task-specific name.
	 *  Represents number of naïve multiplications needed to get the number from factorization.
	 * @param powers collection of integers
	 * @return sum of items in collection
	 */
	static int factorsCount(Iterable<Integer> powers) {
		int cnt = 0;
		for (int pow : powers) {
			cnt += pow;
		}
		return cnt;
	}

	/**
	 *
	 * @param n positive number
	 * @return smallest in range: sqrt(n) <= factor <= n
	 */
	private static int getNextFactor(int n) {
		int factor = (int) Math.ceil(Math.sqrt(n));
		for (; factor <= n; ++factor)
			if (n % factor == 0)
				return factor;
		return n; // actually unreachable
	}
}
