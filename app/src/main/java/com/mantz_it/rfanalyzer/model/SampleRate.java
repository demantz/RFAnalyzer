package com.mantz_it.rfanalyzer.model;

import com.mantz_it.rfanalyzer.control.Controllable;

/**Interface for controlling sampling rate. This interface should not be used as parameter
 * for {@link Controllable#getControl(Class)} method, because it's only abstraction over sampling rate control,
 * and entity can have multiple controllable sampling rates (TX/RX, for example)
 * Created by Pavel on 20.03.2017.
 */

public interface SampleRate extends ConstrainedProperty<Integer> {

/**
	 * @param sampleRate	initial sample rate for the lookup
	 * @return next optimal sample rate that is higher than sampleRate
	 */
int getNextHigherOptimalSampleRate(int sampleRate);

/**
	 * @param sampleRate	initial sample rate for the lookup
	 * @return next optimal sample rate that is lower than sampleRate
	 */
int getNextLowerOptimalSampleRate(int sampleRate);

/**
	 * @return Array of all supported (optimal) sample rates
	 */
int[] getSupportedSampleRates();
}
