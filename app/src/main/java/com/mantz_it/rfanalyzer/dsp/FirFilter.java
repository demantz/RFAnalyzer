package com.mantz_it.rfanalyzer.dsp;

import android.util.Log;

import com.mantz_it.rfanalyzer.source.SamplePacket;

/**
 * <h1>RF Analyzer - FIR Filter</h1>
 *
 * Module:      FirFilter.java
 * Description: This class implements a FIR filter. Most of the code is
 *              copied from the firdes and firfilter module from GNU Radio.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
public class FirFilter {
	private int tapCounter = 0;
	private float[] taps;
	private float[] delaysReal;
	private float[] delaysImag;
	private int decimation;
	private int decimationCounter = 1;
	private float gain;
	private float sampleRate;
	private float cutOffFrequency;
	private float transitionWidth;
	private float attenuation;
	private static final String LOGTAG = "FirFilter";

	/**
	 * Private Constructor. Creates a new FIR Filter with the given taps and decimation.
	 * Use create*Filter() to calculate taps and create the filter.
	 * @param taps					filter taps (double)
	 * @param decimation			decimation factor
	 * @param gain					filter pass band gain
	 * @param sampleRate			sample rate
	 * @param cutOffFrequency		cut off frequency (end of pass band)
	 * @param transitionWidth		width from end of pass band to start stop band
	 * @param attenuation			attenuation of stop band
	 */
	private FirFilter(float[] taps, int decimation, float gain, float sampleRate, float cutOffFrequency, float transitionWidth, float attenuation) {
		this.taps = taps;
		this.delaysReal = new float[taps.length];
		this.delaysImag = new float[taps.length];
		this.decimation = decimation;
		this.gain = gain;
		this.sampleRate = sampleRate;
		this.cutOffFrequency = cutOffFrequency;
		this.transitionWidth = transitionWidth;
		this.attenuation = attenuation;
	}

	/**
	 * @return length of the taps array
	 */
	public int getNumberOfTaps() {
		return taps.length;
	}

	public int getDecimation() {
		return decimation;
	}

	public float getGain() {
		return gain;
	}

	public float getSampleRate() {
		return sampleRate;
	}

	public float getCutOffFrequency() {
		return cutOffFrequency;
	}

	public float getTransitionWidth() {
		return transitionWidth;
	}

	public float getAttenuation() {
		return attenuation;
	}

	/**
	 * Filters the samples from the input sample packet and appends filter output to the output
	 * sample packet. Stops automatically if output sample packet is full.
	 * @param in		input sample packet
	 * @param out		output sample packet
	 * @param offset	offset to use as start index for the input packet
	 * @param length	max number of samples processed from the input packet
	 * @return number of samples consumed from the input packet
	 */
	public int filter(SamplePacket in, SamplePacket out, int offset, int length) {
		int index;
		int indexOut = out.size();
		int outputCapacity = out.capacity();
		float[] reIn = in.re(), imIn = in.im(), reOut = out.re(), imOut = out.im();

		// insert each input sample into the delay line:
		for (int i = 0; i < length; i++) {
			delaysReal[tapCounter] = reIn[offset + i];
			delaysImag[tapCounter] = imIn[offset + i];

			// Calculate the filter output for every Mth element (were M = decimation)
			if(decimationCounter == 0) {
				// first check if we have enough space in the output buffers:
				if(indexOut == outputCapacity) {
					out.setSize(indexOut);	// update size of output sample packet
					out.setSampleRate(in.getSampleRate()/decimation);	// update the sample rate of the output sample packet
					return i;    // We return the number of consumed samples from the input buffers
				}

				// Calculate the results:
				reOut[indexOut] = 0;
				imOut[indexOut] = 0;
				index = tapCounter;
				for (float tap : taps) {
					reOut[indexOut] += tap * delaysReal[index];
					imOut[indexOut] += tap * delaysImag[index];
					index--;
					if (index < 0)
						index = taps.length - 1;
				}

				// increase indexOut:
				indexOut++;
			}

			// update counters:
			decimationCounter++;
			if(decimationCounter >= decimation)
				decimationCounter = 0;
			tapCounter++;
			if(tapCounter >= taps.length)
				tapCounter = 0;
		}
		out.setSize(indexOut);	// update size of output sample packet
		out.setSampleRate(in.getSampleRate()/decimation);	// update the sample rate of the output sample packet
		return length;			// We return the number of consumed samples from the input buffers
	}

	/**
	 * Filters the real parts of the samples from the input sample packet and appends filter output to the output
	 * sample packet. Stops automatically if output sample packet is full.
	 * @param in		input sample packet
	 * @param out		output sample packet
	 * @param offset	offset to use as start index for the input packet
	 * @param length	max number of samples processed from the input packet
	 * @return number of samples consumed from the input packet
	 */
	public int filterReal(SamplePacket in, SamplePacket out, int offset, int length) {
		int index;
		int indexOut = out.size();
		int outputCapacity = out.capacity();
		float[] reIn = in.re(), reOut = out.re();

		// insert each input sample into the delay line:
		for (int i = 0; i < length; i++) {
			delaysReal[tapCounter] = reIn[offset + i];

			// Calculate the filter output for every Mth element (were M = decimation)
			if(decimationCounter == 0) {
				// first check if we have enough space in the output buffers:
				if(indexOut == outputCapacity) {
					out.setSize(indexOut);	// update size of output sample packet
					out.setSampleRate(in.getSampleRate()/decimation);	// update the sample rate of the output sample packet
					return i;    // We return the number of consumed samples from the input buffers
				}

				// Calculate the results:
				reOut[indexOut] = 0;
				index = tapCounter;
				for (float tap : taps) {
					reOut[indexOut] += tap * delaysReal[index];
					index--;
					if (index < 0)
						index = taps.length - 1;
				}

				// increase indexOut:
				indexOut++;
			}

			// update counters:
			decimationCounter++;
			if(decimationCounter >= decimation)
				decimationCounter = 0;
			tapCounter++;
			if(tapCounter >= taps.length)
				tapCounter = 0;
		}
		out.setSize(indexOut);	// update size of output sample packet
		out.setSampleRate(in.getSampleRate()/decimation);	// update the sample rate of the output sample packet
		return length;			// We return the number of consumed samples from the input buffers
	}

	/**
	 * FROM GNU Radio firdes::low_pass_2:
	 *
	 * Will calculate the tabs for the specified low pass filter and return a FirFilter instance
	 *
	 * @param decimation			decimation factor
	 * @param gain					filter pass band gain
	 * @param sampling_freq			sample rate
	 * @param cutoff_freq			cut off frequency (end of pass band)
	 * @param transition_width		width from end of pass band to start stop band
	 * @param attenuation_dB		attenuation of stop band
	 * @return instance of FirFilter
	 */
	public static FirFilter createLowPass(int decimation,
										  float gain,
										  float sampling_freq,    // Hz
										  float cutoff_freq,      // Hz BEGINNING of transition band
										  float transition_width, // Hz width of transition band
										  float attenuation_dB)   // attenuation dB
	{
		if (sampling_freq <= 0.0) {
			Log.e(LOGTAG,"createLowPass: firdes check failed: sampling_freq > 0");
			return null;
		}

		if (cutoff_freq <= 0.0 || cutoff_freq > sampling_freq / 2) {
			Log.e(LOGTAG, "createLowPass: firdes check failed: 0 < fa <= sampling_freq / 2");
			return null;
		}

		if (transition_width <= 0) {
			Log.e(LOGTAG,"createLowPass: firdes check failed: transition_width > 0");
			return null;
		}

		// Calculate number of tabs
		// Based on formula from Multirate Signal Processing for
		// Communications Systems, fredric j harris
		int ntaps = (int)(attenuation_dB*sampling_freq/(22.0*transition_width));
		if ((ntaps & 1) == 0)	// if even...
			ntaps++;		// ...make odd

		// construct the truncated ideal impulse response
		// [sin(x)/x for the low pass case]

		float[] taps = new float[ntaps];
		float[] w = makeWindow(ntaps);

		int M = (ntaps - 1) / 2;
		float fwT0 = 2 * (float)Math.PI * cutoff_freq / sampling_freq;
		for (int n = -M; n <= M; n++) {
			if (n == 0)
				taps[n + M] = fwT0 / (float)Math.PI * w[n + M];
			else {
				// a little algebra gets this into the more familiar sin(x)/x form
				taps[n + M] = (float)Math.sin(n * fwT0) / (n * (float)Math.PI) * w[n + M];
			}
		}

		// find the factor to normalize the gain, fmax.
		// For low-pass, gain @ zero freq = 1.0

		float fmax = taps[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * taps[n + M];

		float actualGain = gain/fmax;    // normalize

		for (int i = 0; i < ntaps; i++)
			taps[i] *= actualGain;

		return new FirFilter(taps, decimation, gain, sampling_freq, cutoff_freq, transition_width, attenuation_dB);
	}

	/**
	 * Creates a Blackman Window for a FIR Filter
	 *
	 * @param ntabs number of taps of the filter
	 * @return window samples
	 */
	private static float[] makeWindow(int ntabs) {
		// Make a blackman window:
		// w(n)=0.42-0.5cos{(2*PI*n)/(N-1)}+0.08cos{(4*PI*n)/(N-1)};
		float[] window = new float[ntabs];
		for (int i = 0; i < window.length; i++)
			window[i] = 0.42f - 0.5f * (float)Math.cos(2 * Math.PI * i / (ntabs - 1))
					+ 0.08f * (float)Math.cos(4 * Math.PI * i / (ntabs - 1));
		return window;
	}

}