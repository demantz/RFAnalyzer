package com.mantz_it.rfanalyzer.dsp.spi;

/**
 * Created by Pavel on 11.11.2016.
 */

public abstract class Mixer<T> implements Transformation<T> {
	protected final T LUT; // todo: use ring buffer?
	protected final int maxLUTSize;
	protected int LUTSize;
	protected double cycleLength;
	protected int position;

	/**
	 * Resolution of look-up table, how much samples takes one cycle of harmonic function.
	 *
	 * @param samplesPerCycle desired value
	 */
	protected abstract void setLUTResolution(double samplesPerCycle);

	protected abstract void recalcLUT();

	/**
	 * Creates Buffer of maxLUTSize capacity to store table.
	 */
	protected abstract T initLUT();

	public void setChannelFrequency(long sampleRate, long frequency) {
		//System.out.printf("%s: setChannelFrequency(%d, %d)\n", this.getClass().getSimpleName(), sampleRate, frequency);
		// aliasing hack -- use upper mirror frequency if resolution is too high
		while (Math.abs(sampleRate / (double) frequency) > maxLUTSize)
			frequency += sampleRate;
		setLUTResolution(sampleRate / (double) frequency);
	}

	private Mixer() {
		maxLUTSize = 0;
		LUT = null;
	}

	protected Mixer(int maximumLookupTableSize) {
		this.maxLUTSize = maximumLookupTableSize;
		LUT = initLUT();
	}

	/**
	 * Selects optimal wrap for discrete taps of mixing function LUT constrained by maximum length
	 *
	 * @param maxSize limitation on size
	 * @return optimal length for LUT
	 */
	protected static int optimalLUTLength(double desired, int maxSize) {
		int bestFound; // candidate
		double current; // tested candidate
		double err; // proximity to integer
		for (current = desired, bestFound = (int) desired, err = Math.abs(bestFound - desired);
		     current < maxSize;
		     current += desired) {
			double lastErr;
			if ((lastErr = Math.abs(current - (int) current)) < err) {
				err = lastErr;
				bestFound = (int) current;
			}
		}
		return bestFound;
	}

}
