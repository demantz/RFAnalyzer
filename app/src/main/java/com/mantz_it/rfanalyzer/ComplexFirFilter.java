package com.mantz_it.rfanalyzer;

import android.util.Log;

import static com.mantz_it.rfanalyzer.Filter.FilterFamily.FIR;

/**
 * <h1>RF Analyzer - complex FIR Filter</h1>
 *
 * Module:      ComplexFirFilter.java
 * Description: This class implements a FIR filter with complex taps. Most of the code is
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
@Deprecated
public class ComplexFirFilter extends Filter {

	private static final String LOGTAG = "ComplexFirFilter";

	/**
	 * Private Constructor. Creates a new complex FIR Filter with the given taps and decimation.
	 * Use create*Filter() to calculate taps and create the filter.
	 * @param tapsReal				filter taps real part
	 * @param tapsImag				filter taps imaginary part
	 * @param decimation			decimation factor
	 * @param gain					filter pass band gain
	 * @param sampleRate			sample rate
	 * @param lowCutOffFrequency	lower cut off frequency (start of pass band)
	 * @param highCutOffFrequency	upper cut off frequency (end of pass band)
	 * @param transitionWidth		width from end of pass band to start stop band
	 * @param attenuation			attenuation of stop band
	 */
	private ComplexFirFilter(float[] tapsReal, float[] tapsImag, int decimation, float gain, float sampleRate, float lowCutOffFrequency,
					  float highCutOffFrequency, float transitionWidth, float attenuation) {
		if(tapsReal.length != tapsImag.length)
			throw new IllegalArgumentException("real and imag filter taps have to be of the same length!");
		this.family = FIR;
		this.tapsCount = tapsReal.length;
		this.tapsReal = tapsReal;
		this.tapsImag = tapsImag;
		this.delaysReal = new float[tapsCount];
		this.delaysImag = new float[tapsCount];
		this.decimation = decimation;
		this.gain = gain;
		this.sampleRate = sampleRate;
		this.lowCutOffFrequency = lowCutOffFrequency;
		this.highCutOffFrequency = highCutOffFrequency;
		this.transitionWidth = transitionWidth;
		this.attenuation = attenuation;
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
	@Deprecated
	public int filter(SamplePacket in, SamplePacket out, int offset, int length) {
		int index;
		int indexOut = out.size();
		int outputCapacity = out.capacity();
		float[] reIn = in.re(), imIn = in.im(), reOut = out.re(), imOut = out.im();


		// insert each input sample into the delay line:
		for (int i = 0; i < length; i++) {
			delaysReal[tapIter] = reIn[offset + i];
			delaysImag[tapIter] = imIn[offset + i];

			// Calculate the filter output for every Mth element (were M = decimation)
			if(decimationIter == 0) {
				// first check if we have enough space in the output buffers:
				if(indexOut == outputCapacity) {
					out.setSize(indexOut);	// update size of output sample packet
					out.setSampleRate(in.getSampleRate()/decimation);	// update the sample rate of the output sample packet
					return i;    // We return the number of consumed samples from the input buffers
				}

				// Calculate the results:
				reOut[indexOut] = 0;
				imOut[indexOut] = 0;
				index = tapIter;
				for (int j = 0; j < tapsCount; j++) {
					final float tapRe = tapsReal[j];
					final float tapIm = tapsImag[j];
					final float delayRe = delaysReal[index];
					final float delayIm = delaysImag[index];
					reOut[indexOut] += tapRe*delayRe - tapIm*delayIm;
					imOut[indexOut] += tapIm*delayRe + tapIm*delayIm;
					index--;
					if (index < 0)
						index = tapsCount - 1;
				}

				// increase indexOut:
				indexOut++;
			}

			// update counters:
			decimationIter++;
			if(decimationIter >= decimation)
				decimationIter = 0;
			tapIter++;
			if(tapIter >= tapsCount)
				tapIter = 0;
		}
		out.setSize(indexOut);	// update size of output sample packet
		out.setSampleRate(in.getSampleRate()/decimation);	// update the sample rate of the output sample packet
		return length;			// We return the number of consumed samples from the input buffers
	}

	@Override
	public int filterReal(SamplePacket in, SamplePacket out, int offset, int length) {
		throw new UnsupportedOperationException("ComplexFirFilter does not support filterReal method.");
	}

	/**
	 * FROM GNU Radio firdes::band_pass_2:
	 *
	 * Will calculate the tabs for the specified low pass filter and return a FirFilter instance
	 *
	 * @param decimation			decimation factor
	 * @param gain					filter pass band gain
	 * @param sampling_freq			sample rate
	 * @param low_cutoff_freq		cut off frequency (beginning of pass band)
	 * @param high_cutoff_freq		cut off frequency (end of pass band)
	 * @param transition_width		width from end of pass band to start stop band
	 * @param attenuation_dB		attenuation of stop band
	 * @return instance of FirFilter
	 */
	@Deprecated
	public static ComplexFirFilter createBandPass(int decimation,
										  float gain,
										  float sampling_freq,    // Hz
										  float low_cutoff_freq,      // Hz BEGINNING of transition band
										  float high_cutoff_freq,      // Hz END of transition band
										  float transition_width, // Hz width of transition band
										  float attenuation_dB)   // attenuation dB
	{
		if (sampling_freq <= 0.0) {
			Log.e(LOGTAG, "createBandPass: firdes check failed: sampling_freq > 0");
			return null;
		}

		if (low_cutoff_freq < sampling_freq * -0.5 || high_cutoff_freq > sampling_freq * 0.5) {
			Log.e(LOGTAG, "createBandPass: firdes check failed: -sampling_freq / 2 < fa <= sampling_freq / 2");
			return null;
		}

		if (low_cutoff_freq >= high_cutoff_freq) {
			Log.e(LOGTAG,"createBandPass: firdes check failed: low_cutoff_freq >= high_cutoff_freq");
			return null;
		}

		if (transition_width <= 0) {
			Log.e(LOGTAG,"createBandPass: firdes check failed: transition_width > 0");
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
		// Note: we calculate the real taps for a low pass and shift them
		float low_pass_cut_off = (high_cutoff_freq - low_cutoff_freq)/2f;
		float[] tapsLowPass = new float[ntaps];
		float[] w = makeWindow(ntaps);

		int M = (ntaps - 1) / 2;
		float fwT0 = 2 * (float)Math.PI * low_pass_cut_off / sampling_freq;
		for (int n = -M; n <= M; n++) {
			if (n == 0)
				tapsLowPass[n + M] = fwT0 / (float)Math.PI * w[n + M];
			else {
				// a little algebra gets this into the more familiar sin(x)/x form
				tapsLowPass[n + M] = (float)Math.sin(n * fwT0) / (n * (float)Math.PI) * w[n + M];
			}
		}

		// find the factor to normalize the gain, fmax.
		// For low-pass, gain @ zero freq = 1.0
		float fmax = tapsLowPass[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * tapsLowPass[n + M];
		float actualGain = gain/fmax;    // normalize
		for (int i = 0; i < ntaps; i++)
			tapsLowPass[i] *= actualGain;

		// calc the band pass taps:
		float[] tapsReal = new float[ntaps];
		float[] tapsImag = new float[ntaps];
		float freq = (float)Math.PI * (high_cutoff_freq + low_cutoff_freq)/sampling_freq;
		float phase = - freq * ( ntaps/2 );

		for(int i = 0; i < ntaps; i++) {
			tapsReal[i] = tapsLowPass[i] * (float)Math.cos(phase);
			tapsImag[i] = tapsLowPass[i] * (float)Math.sin(phase);
			phase += freq;
			//Log.d(LOGTAG, "createBandPass: Filter Taps [i="+i+"]: " + tapsReal[i] + "   " + tapsImag[i]);
		}

		return new ComplexFirFilter(tapsReal, tapsImag, decimation, gain, sampling_freq, low_cutoff_freq, high_cutoff_freq, transition_width, attenuation_dB);
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
