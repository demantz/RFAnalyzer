package com.mantz_it.rfanalyzer.dsp;

import android.support.annotation.NonNull;

import com.mantz_it.rfanalyzer.dsp.spi.Filter;
import com.mantz_it.rfanalyzer.dsp.spi.Packet;
import com.mantz_it.rfanalyzer.dsp.spi.PacketPool;
import com.mantz_it.rfanalyzer.dsp.spi.Window;

import java.util.Map;
import java.util.TreeMap;

/**
 * Created by Pavel on 07.12.2016.
 */

public class Decimation {

	public final Factorization decimFactorization;
	public final int decimation;
	public final int input;
	public final int output;

	protected Decimation(int inputRate, Map<Integer, Integer> factorization) {
		this.input = inputRate;
		this.decimFactorization = new Factorization(factorization);
		this.decimation = Utils.prod(decimFactorization.factors);
		this.output = input / decimation;
	}

	protected Decimation(int inputRate, Factorization factorization) {
		this.input = inputRate;
		this.decimFactorization = factorization;
		this.decimation = Utils.prod(decimFactorization.factors);
		this.output = input / decimation;
	}

	// todo: parametrize more
	@NonNull
	public FilterChain decimationChain(int attenuation_dB, int passbandRipple_dB, Window.Type winType, int beta) {
		FilterChain.FilterChainBuilder builder = new FilterChain.FilterChainBuilder();
		PacketPool pool = PacketPool.getArrayPacketPool();
		builder.setBuffers(pool.acquire(Packet.PREFERRED_SIZE, true, 0, 0), pool.acquire(Packet.PREFERRED_SIZE, true, 0, 0));
		double rate = input;
		for (Map.Entry<Integer, Integer> entry : decimFactorization.factorization.entrySet()) {
			final int decimation = entry.getKey();
			double transBand = rate * 0.02 / decimation;
			double passBand = rate * 0.48 / decimation;
			Filter f;
			// todo: gain correction
			try {
				f = FilterBuilder.optimalLowPass_CRC(decimation, 1, rate, passBand, transBand, passbandRipple_dB, attenuation_dB, 2);
			}
			catch (ConvergenceException ce) {
				//System.out.println(ce.getMessage());
				f = FilterBuilder.lowPass_CRC(decimation, 1, rate, passBand, transBand, attenuation_dB, winType, beta);
			}
			builder.addFilter(f);
			rate /= decimation; // rate after decimation
		}
		return builder.build();
	}

	@NonNull
	public static Decimation getOptimalDecimation(
			int minOutRate, int optimalOutRate, int maxOutRate,
			int inputRate
	) {
		TreeMap<Integer, Integer> factors = new TreeMap<>();
		int outputRate = 0;

		// argument checks
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
			factors.put(1, 1);
			return new Decimation(inputRate, factors);
		}

		// decimation factors
		int minimum = (int) (Math.ceil(inputRate / (float) maxOutRate)); // (float) for fp division, instead of integer
		int maximum = (int) (Math.floor(inputRate / (float) minOutRate));
		double ideal = (double) inputRate / optimalOutRate;

		// Log.d(LOGTAG, "Calculated weights for different decimations:"); // debug
		double selectedWeight = Double.MAX_VALUE;
		Factorization selected = null;
		for (int i = minimum; i <= maximum; ++i) {
			Factorization f = Factorization.optimalFactorization(i);
			double weight = weight(f, i, ideal);
			if (weight <= selectedWeight) {
				selectedWeight = weight;
				selected = f;
			}
		}
		return new Decimation(inputRate, selected);
	}

	protected static double weight(@NonNull Factorization f, int outRate, double idealRate) {
		//  todo: choose better formula, maybe involve GCD?
		// we want minimum stages(minimum operations),
		// minimum different stages (minimum memory)
		// and minimum decimation per stage
		// less decimation -> more bandwidth of filter -> less taps -> less operations)
		return //Math.sqrt(Factorization.factorsSqrSum(f.factorization))
		       /**/ f.factors.size()
		            * f.factorization.size()
		            * Math.abs(outRate - idealRate)
		            / (double) outRate;
	}
}
