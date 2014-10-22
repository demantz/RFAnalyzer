package com.mantz_it.rfanalyzer;

import android.util.Log;

/**
 * <h1>RF Analyzer - FIR Filter</h1>
 *
 * Module:      FirFilter.java
 * Description: This class implements a FIR filter. Most of the code is
 *              copied from the firdes and firfilter module from GNU Radio.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2014 Dennis Mantz
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
	private double[] taps;
	private double[] delaysReal;
	private double[] delaysImag;
	private int decimation;
	private int decimationCounter = 1;
	private static final String LOGTAG = "FirFilter";

	private FirFilter(double[] taps, int decimation) {
		this.taps = taps;
		this.delaysReal = new double[taps.length];
		this.delaysImag = new double[taps.length];
		this.decimation = decimation;
	}

	public int getNumberOfTaps() {
		return taps.length;
	}

	public int filter(double[] reIn, double[] imIn, double[] reOut, double[] imOut,
					  int offsetIn, int offsetOut, int lengthIn, int lengthOut) {
		int index;

		// insert each input sample into the delay line:
		for (int i = 0; i < lengthIn; i++) {
			delaysReal[tapCounter] = reIn[offsetIn + i];
			delaysImag[tapCounter] = imIn[offsetIn + i];

			// Calculate the filter output for every Mth element (were M = decimation)
			if(decimationCounter == 0) {
				// first check if we have enough space in the output buffers:
				if(lengthOut <= 0)
					return i;	// We return the number of consumed samples from the input buffers

				// Calculate the results:
				reOut[offsetOut] = 0;
				imOut[offsetOut] = 0;
				index = tapCounter;
				for (double tap : taps) {
					reOut[offsetOut] += tap * delaysReal[index];
					imOut[offsetOut] += tap * delaysImag[index];
					index--;
					if (index < 0)
						index = taps.length - 1;
				}

				// update offsetOut and lengthOut:
				offsetOut++;
				lengthOut--;
			}
			// update counters:
			decimationCounter = (decimationCounter + 1) % decimation;
			tapCounter = (tapCounter + 1) % taps.length;
		}
		return lengthIn;
	}

	/**
	 * FROM GNU Radio firdes::low_pass_2:
	 * <p/>
	 * Will calculate the tabs for the specified low pass filter and return a FirFilter instance
	 */
	public static FirFilter createLowPass(int decimation,
										  double gain,
										  double sampling_freq,    // Hz
										  double cutoff_freq,      // Hz BEGINNING of transition band
										  double transition_width, // Hz width of transition band
										  double attenuation_dB)   // attenuation dB
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

		double[] taps = new double[ntaps];
		double[] w = makeWindow(ntaps);

		int M = (ntaps - 1) / 2;
		double fwT0 = 2 * Math.PI * cutoff_freq / sampling_freq;
		for (int n = -M; n <= M; n++) {
			if (n == 0)
				taps[n + M] = fwT0 / Math.PI * w[n + M];
			else {
				// a little algebra gets this into the more familiar sin(x)/x form
				taps[n + M] = Math.sin(n * fwT0) / (n * Math.PI) * w[n + M];
			}
		}

		// find the factor to normalize the gain, fmax.
		// For low-pass, gain @ zero freq = 1.0

		double fmax = taps[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * taps[n + M];

		gain /= fmax;    // normalize

		for (int i = 0; i < ntaps; i++)
			taps[i] *= gain;

		return new FirFilter(taps, decimation);
	}

	private static double[] makeWindow(int ntabs) {
		// Make a blackman window:
		// w(n)=0.42-0.5cos{(2*PI*n)/(N-1)}+0.08cos{(4*PI*n)/(N-1)};
		double[] window = new double[ntabs];
		for (int i = 0; i < window.length; i++)
			window[i] = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (ntabs - 1))
					+ 0.08 * Math.cos(4 * Math.PI * i / (ntabs - 1));
		return window;
	}

}