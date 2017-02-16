package com.mantz_it.rfanalyzer.dsp;

import com.mantz_it.rfanalyzer.dsp.spi.Window;

/**
 * This was copy-pasted and quickly converted to Java from gnuradio/gr-filter/lib/firdes.cc
 * Possibly bugs introduced.
 */

public class FIRDesigner {
	protected FIRDesigner() {}

	//
	//	=== Low Pass ===
	//

	/**
	 * Use "window method" to design a low-pass FIR filter.  The
	 * normalized width of the transition band and the required stop band
	 * attenuation is what sets the number of taps required.  Narrow --> more
	 * taps More attenuation --> more taps. The window type determines
	 * maximum attentuation and passband ripple.
	 *
	 * @param gain             overall gain of filter (typically 1.0)
	 * @param sampling_freq    sampling freq (Hz)
	 * @param cutoff_freq      beginning of transition band (Hz)
	 * @param transition_width width of transition band (Hz)
	 * @param attenuation_dB   required stopband attenuation
	 * @param window           one of Window.Type
	 * @param beta             parameter for Kaiser window
	 */

	public static float[] low_pass(double gain,
	                               double sampling_freq,    // Hz
	                               double cutoff_freq,      // Hz BEGINNING of transition band
	                               double transition_width, // Hz width of transition band
	                               double attenuation_dB,   // attenuation dB
	                               Window.Type window,
	                               double beta)             // used only with Kaiser
	{
		sanity_check_1f(sampling_freq, cutoff_freq, transition_width);

		int ntaps = Utils.tapsCount(sampling_freq,
				transition_width,
				attenuation_dB);

		// construct the truncated ideal impulse response
		// [sin(x)/x for the low pass case]

		float[] taps = new float[ntaps];
		float[] w = Utils.window(window, ntaps, beta);

		int M = (ntaps - 1) / 2;
		double fwT0 = 2 * Math.PI * cutoff_freq / sampling_freq;
		for (int n = -M; n <= M; n++) {
			if (n == 0)
				taps[n + M] = (float) (fwT0 / Math.PI * w[n + M]);
			else {
				// a little algebra gets this into the more familiar sin(x)/x form
				taps[n + M] = (float) (Math.sin(n * fwT0) / (n * Math.PI) * w[n + M]);
			}
		}

		// find the factor to normalize the gain, fmax.
		// For low-pass, gain @param  zero freq = 1.0

		double fmax = taps[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * taps[n + M];

		gain /= fmax;    // normalize

		for (int i = 0; i < ntaps; i++)
			taps[i] *= gain;

		return taps;
	}

	/**
	 * Use "window method" to design a low-pass FIR filter.  The
	 * normalized width of the transition band is what sets the number of
	 * taps required.  Narrow --> more taps.  Window type determines maximum
	 * attenuation and passband ripple.
	 *
	 * @param gain             overall gain of filter (typically 1.0)
	 * @param sampling_freq    sampling freq (Hz)
	 * @param cutoff_freq      center of transition band (Hz)
	 * @param transition_width width of transition band (Hz)
	 * @param window           one of Window.Type
	 * @param beta             parameter for Kaiser window
	 */
	public static float[]
	low_pass(double gain,
	         double sampling_freq,
	         double cutoff_freq,    // Hz center of transition band
	         double transition_width,    // Hz width of transition band
	         Window.Type window,
	         double beta)        // used only with Kaiser
	{
		sanity_check_1f(sampling_freq, cutoff_freq, transition_width);

		int ntaps = Utils.tapsCount(sampling_freq,
				transition_width,
				window, beta);

		// construct the truncated ideal impulse response
		// [Math.sin(x)/x for the low pass case]

		float[] taps = new float[ntaps];
		float[] w = Utils.window(window, ntaps, beta);

		int M = (ntaps - 1) / 2;
		double fwT0 = 2 * Math.PI * cutoff_freq / sampling_freq;

		for (int n = -M; n <= M; n++) {
			if (n == 0)
				taps[n + M] = (float) (fwT0 / Math.PI * w[n + M]);
			else {
				// a little algebra gets this into the more familiar sin(x)/x form
				taps[n + M] = (float) (Math.sin(n * fwT0) / (n * Math.PI) * w[n + M]);
			}
		}

		// find the factor to normalize the gain, fmax.
		// For low-pass, gain @param  zero freq = 1.0

		double fmax = taps[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * taps[n + M];

		gain /= fmax;    // normalize

		for (int i = 0; i < ntaps; i++)
			taps[i] *= gain;

		return taps;
	}


	//
	//	=== High Pass ===
	//

	/**
	 * Use "window method" to design a high-pass FIR filter. The
	 * normalized width of the transition band and the required stop band
	 * attenuation is what sets the number of taps required.  Narrow --> more
	 * taps More attenuation --> more taps. The window determines maximum
	 * attenuation and passband ripple.
	 *
	 * @param gain             overall gain of filter (typically 1.0)
	 * @param sampling_freq    sampling freq (Hz)
	 * @param cutoff_freq      center of transition band (Hz)
	 * @param transition_width width of transition band (Hz).
	 * @param attenuation_dB   out of band attenuation
	 * @param window           one of Window.Type
	 * @param beta             parameter for Kaiser window
	 */
	public static float[]
	high_pass(double gain,
	          double sampling_freq,
	          double cutoff_freq,       // Hz center of transition band
	          double transition_width,  // Hz width of transition band
	          double attenuation_dB,    // attenuation dB
	          Window.Type window,
	          double beta)              // used only with Kaiser
	{
		sanity_check_1f(sampling_freq, cutoff_freq, transition_width);

		int ntaps = Utils.tapsCount(sampling_freq,
				transition_width,
				attenuation_dB);

		// construct the truncated ideal impulse response times the window function

		float[] taps = new float[ntaps];
		float[] w = Utils.window(window, ntaps, beta);

		int M = (ntaps - 1) / 2;
		double fwT0 = 2 * Math.PI * cutoff_freq / sampling_freq;

		for (int n = -M; n <= M; n++) {
			if (n == 0)
				taps[n + M] = (float) ((1 - (fwT0 / Math.PI)) * w[n + M]);
			else {
				// a little algebra gets this into the more familiar sin(x)/x form
				taps[n + M] = (float) (-Math.sin(n * fwT0) / (n * Math.PI) * w[n + M]);
			}
		}

		// find the factor to normalize the gain, fmax.
		// For high-pass, gain @param  fs/2 freq = 1.0

		double fmax = taps[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * taps[n + M] * Math.cos(n * Math.PI);

		gain /= fmax; // normalize

		for (int i = 0; i < ntaps; i++)
			taps[i] *= gain;

		return taps;
	}

	/**
	 * Use "window method" to design a high-pass FIR filter.  The
	 * normalized width of the transition band is what sets the number of
	 * taps required.  Narrow --> more taps. The window determines maximum
	 * attenuation and passband ripple.
	 *
	 * @param gain             overall gain of filter (typically 1.0)
	 * @param sampling_freq    sampling freq (Hz)
	 * @param cutoff_freq      center of transition band (Hz)
	 * @param transition_width width of transition band (Hz)
	 * @param window           one of Window.Type
	 * @param beta             parameter for Kaiser window
	 */
	public static float[]
	high_pass(double gain,
	          double sampling_freq,
	          double cutoff_freq,       // Hz center of transition band
	          double transition_width,  // Hz width of transition band
	          Window.Type window,
	          double beta)              // used only with Kaiser
	{
		sanity_check_1f(sampling_freq, cutoff_freq, transition_width);

		int ntaps = Utils.tapsCount(sampling_freq,
				transition_width,
				window, beta);

		// construct the truncated ideal impulse response times the window function

		float[] taps = new float[ntaps];
		float[] w = Utils.window(window, ntaps, beta);

		int M = (ntaps - 1) / 2;
		double fwT0 = 2 * Math.PI * cutoff_freq / sampling_freq;

		for (int n = -M; n <= M; n++) {
			if (n == 0)
				taps[n + M] = (float) ((1 - (fwT0 / Math.PI)) * w[n + M]);
			else {
				// a little algebra gets this into the more familiar sin(x)/x form
				taps[n + M] = (float) (-Math.sin(n * fwT0) / (n * Math.PI) * w[n + M]);
			}
		}

		// find the factor to normalize the gain, fmax.
		// For high-pass, gain @param  fs/2 freq = 1.0

		double fmax = taps[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * taps[n + M] * Math.cos(n * Math.PI);

		gain /= fmax; // normalize

		for (int i = 0; i < ntaps; i++)
			taps[i] *= gain;

		return taps;
	}

	//
	//      === Band Pass ===
	//

	/**
	 * Use "window method" to design a band-pass FIR filter.  The
	 * normalized width of the transition band and the required stop band
	 * attenuation is what sets the number of taps required.  Narrow --> more
	 * taps.  More attenuation --> more taps.  Window type determines maximum
	 * attenuation and passband ripple.
	 *
	 * @param gain             overall gain of filter (typically 1.0)
	 * @param sampling_freq    sampling freq (Hz)
	 * @param low_cutoff_freq  center of transition band (Hz)
	 * @param high_cutoff_freq center of transition band (Hz)
	 * @param transition_width width of transition band (Hz).
	 * @param attenuation_dB   out of band attenuation
	 * @param window           one of Window.Type
	 * @param beta             parameter for Kaiser window
	 */
	public static float[]
	band_pass(double gain,
	          double sampling_freq,
	          double low_cutoff_freq,     // Hz center of transition band
	          double high_cutoff_freq, // Hz center of transition band
	          double transition_width, // Hz width of transition band
	          double attenuation_dB,   // attenuation dB
	          Window.Type window,
	          double beta)         // used only with Kaiser
	{
		sanity_check_2f(sampling_freq,
				low_cutoff_freq,
				high_cutoff_freq, transition_width);

		int ntaps = Utils.tapsCount(sampling_freq,
				transition_width,
				attenuation_dB);

		float[] taps = new float[ntaps];
		float[] w = Utils.window(window, ntaps, beta);

		int M = (ntaps - 1) / 2;
		double fwT0 = 2 * Math.PI * low_cutoff_freq / sampling_freq;
		double fwT1 = 2 * Math.PI * high_cutoff_freq / sampling_freq;

		for (int n = -M; n <= M; n++) {
			if (n == 0)
				taps[n + M] = (float) ((fwT1 - fwT0) / Math.PI * w[n + M]);
			else {
				taps[n + M] = (float) ((Math.sin(n * fwT1) - Math.sin(n * fwT0)) / (n * Math.PI) * w[n + M]);
			}
		}

		// find the factor to normalize the gain, fmax.
		// For band-pass, gain @param  center freq = 1.0

		double fmax = taps[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * taps[n + M] * Math.cos(n * (fwT0 + fwT1) * 0.5);

		gain /= fmax;    // normalize

		for (int i = 0; i < ntaps; i++)
			taps[i] *= gain;

		return taps;
	}

	/**
	 * Use "window method" to design a band-pass FIR filter. The
	 * normalized width of the transition band is what sets the number of
	 * taps required.  Narrow --> more taps. The window determines maximum
	 * attenuation and passband ripple.
	 *
	 * @param gain             overall gain of filter (typically 1.0)
	 * @param sampling_freq    sampling freq (Hz)
	 * @param low_cutoff_freq  center of transition band (Hz)
	 * @param high_cutoff_freq center of transition band (Hz)
	 * @param transition_width width of transition band (Hz).
	 * @param window           one of Window.Type
	 * @param beta             parameter for Kaiser window
	 */
	public static float[]
	band_pass(double gain,
	          double sampling_freq,
	          double low_cutoff_freq,    // Hz center of transition band
	          double high_cutoff_freq,    // Hz center of transition band
	          double transition_width,    // Hz width of transition band
	          Window.Type window,
	          double beta)        // used only with Kaiser
	{
		sanity_check_2f(sampling_freq,
				low_cutoff_freq,
				high_cutoff_freq,
				transition_width);

		int ntaps = Utils.tapsCount(sampling_freq,
				transition_width,
				window, beta);

		// construct the truncated ideal impulse response times the window function

		float[] taps = new float[ntaps];
		float[] w = Utils.window(window, ntaps, beta);

		int M = (ntaps - 1) / 2;
		double fwT0 = 2 * Math.PI * low_cutoff_freq / sampling_freq;
		double fwT1 = 2 * Math.PI * high_cutoff_freq / sampling_freq;

		for (int n = -M; n <= M; n++) {
			if (n == 0)
				taps[n + M] = (float) ((fwT1 - fwT0) / Math.PI * w[n + M]);
			else {
				taps[n + M] = (float) ((Math.sin(n * fwT1) - Math.sin(n * fwT0)) / (n * Math.PI) * w[n + M]);
			}
		}

		// find the factor to normalize the gain, fmax.
		// For band-pass, gain @param  center freq = 1.0

		double fmax = taps[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * taps[n + M] * Math.cos(n * (fwT0 + fwT1) * 0.5);

		gain /= fmax;    // normalize

		for (int i = 0; i < ntaps; i++)
			taps[i] *= gain;

		return taps;
	}

	//
	//	=== Complex Band Pass ===
	//

	/**
	 * !
	 * Use "window method" to design a complex band-pass FIR filter.
	 * The normalized width of the transition band and the required stop band
	 * attenuation is what sets the number of taps required.  Narrow --> more
	 * taps More attenuation --> more taps. Window type determines maximum
	 * attenuation and passband ripple.
	 * <p>
	 *
	 * @param gain             overall gain of filter (typically 1.0)
	 * @param sampling_freq    sampling freq (Hz)
	 * @param low_cutoff_freq  center of transition band (Hz)
	 * @param high_cutoff_freq center of transition band (Hz)
	 * @param transition_width width of transition band (Hz)
	 * @param attenuation_dB   out of band attenuation
	 * @param window           one of Window.Type
	 * @param beta             parameter for Kaiser window
	 * @return complex taps re, im, re, im...
	 */
	public static float[]
	complexBandPass(double gain,
	                double sampling_freq,
	                double low_cutoff_freq,     // Hz center of transition band
	                double high_cutoff_freq, // Hz center of transition band
	                double transition_width, // Hz width of transition band
	                double attenuation_dB,   // attenuation dB
	                Window.Type window,
	                double beta)         // used only with Kaiser
	{
		sanity_check_2f_c(sampling_freq,
				low_cutoff_freq,
				high_cutoff_freq,
				transition_width);

		int ntaps = Utils.tapsCount(sampling_freq,
				transition_width,
				attenuation_dB);

		float[] taps = new float[ntaps * 2];
		float[] lptaps = new float[ntaps];
		float[] w = Utils.window(window, ntaps, beta);

		lptaps = low_pass(gain, sampling_freq,
				(high_cutoff_freq - low_cutoff_freq) / 2,
				transition_width, attenuation_dB,
				window, beta);

		double freq = Math.PI * (high_cutoff_freq + low_cutoff_freq) / sampling_freq;
		double phase = 0;
		if ((lptaps.length & 1) != 0) {
			phase = -freq * (lptaps.length >> 1);
		} else
			phase = -freq / 2.0 * ((1 + 2 * lptaps.length) >> 1);

		for (int i = 0, o = 0; i < lptaps.length; i++, phase += freq) {
			taps[o++] = (float) (lptaps[i] * Math.cos(phase)); // re
			taps[o++] = (float) (lptaps[i] * Math.sin(phase)); // im
		}

		return taps;
	}

	/**
	 * !
	 * Use the "window method" to design a complex band-pass FIR
	 * filter.  The normalized width of the transition band is what sets the
	 * number of taps required.  Narrow --> more taps. The window type
	 * determines maximum attenuation and passband ripple.
	 * <p>
	 *
	 * @param gain             overall gain of filter (typically 1.0)
	 * @param sampling_freq    sampling freq (Hz)
	 * @param low_cutoff_freq  center of transition band (Hz)
	 * @param high_cutoff_freq center of transition band (Hz)
	 * @param transition_width width of transition band (Hz)
	 * @param window           one of Window.Type
	 * @param beta             parameter for Kaiser window
	 * @return complex taps re, im, re, im...
	 */
	public static float[]
	complexBandPass(double gain,
	                double sampling_freq,
	                double low_cutoff_freq,    // Hz center of transition band
	                double high_cutoff_freq,    // Hz center of transition band
	                double transition_width,    // Hz width of transition band
	                Window.Type window,
	                double beta)        // used only with Kaiser
	{
		sanity_check_2f_c(sampling_freq,
				low_cutoff_freq,
				high_cutoff_freq,
				transition_width);

		int ntaps = Utils.tapsCount(sampling_freq,
				transition_width,
				window, beta);

		// construct the truncated ideal impulse response times the window function

		float[] taps = new float[ntaps * 2];
		float[] lptaps = new float[ntaps];
		float[] w = Utils.window(window, ntaps, beta);

		lptaps = low_pass(gain, sampling_freq,
				(high_cutoff_freq - low_cutoff_freq) / 2,
				transition_width, window, beta);

		double freq = Math.PI * (high_cutoff_freq + low_cutoff_freq) / sampling_freq;
		double phase;
		if ((lptaps.length & 1) != 0) {
			phase = -freq * (lptaps.length >> 1);
		} else
			phase = -freq / 2.0 * ((1 + 2 * lptaps.length) >> 1);

		for (int i = 0, o = 0; i < lptaps.length; i++, phase += freq) {
			taps[o++] = (float) (lptaps[i] * Math.cos(phase)); // re
			taps[o++] = (float) (lptaps[i] * Math.sin(phase)); // im
		}

		return taps;
	}

	//
	//	=== Band Reject ===
	//

	/**
	 * Use "window method" to design a band-reject FIR filter.  The
	 * normalized width of the transition band and the required stop band
	 * attenuation is what sets the number of taps required.  Narrow --> more
	 * taps More attenuation --> more taps.  Window type determines maximum
	 * attenuation and passband ripple.
	 *
	 * @param gain             overall gain of filter (typically 1.0)
	 * @param sampling_freq    sampling freq (Hz)
	 * @param low_cutoff_freq  center of transition band (Hz)
	 * @param high_cutoff_freq center of transition band (Hz)
	 * @param transition_width width of transition band (Hz).
	 * @param attenuation_dB   out of band attenuation
	 * @param window           one of Window.Type
	 * @param beta             parameter for Kaiser window
	 */
	public static float[]
	band_reject(double gain,
	            double sampling_freq,
	            double low_cutoff_freq,  // Hz center of transition band
	            double high_cutoff_freq, // Hz center of transition band
	            double transition_width, // Hz width of transition band
	            double attenuation_dB,   // attenuation dB
	            Window.Type window,
	            double beta)          // used only with Kaiser
	{
		sanity_check_2f(sampling_freq,
				low_cutoff_freq,
				high_cutoff_freq,
				transition_width);

		int ntaps = Utils.tapsCount(sampling_freq,
				transition_width,
				attenuation_dB);

		// construct the truncated ideal impulse response times the window function

		float[] taps = new float[ntaps];
		float[] w = Utils.window(window, ntaps, beta);

		int M = (ntaps - 1) / 2;
		double fwT0 = 2 * Math.PI * low_cutoff_freq / sampling_freq;
		double fwT1 = 2 * Math.PI * high_cutoff_freq / sampling_freq;

		for (int n = -M; n <= M; n++) {
			if (n == 0)
				taps[n + M] = (float) (1.0 + ((fwT0 - fwT1) / Math.PI * w[n + M]));
			else {
				taps[n + M] = (float) ((Math.sin(n * fwT0) - Math.sin(n * fwT1)) / (n * Math.PI) * w[n + M]);
			}
		}

		// find the factor to normalize the gain, fmax.
		// For band-reject, gain @param  zero freq = 1.0

		double fmax = taps[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * taps[n + M];

		gain /= fmax;    // normalize

		for (int i = 0; i < ntaps; i++)
			taps[i] *= gain;

		return taps;
	}

	/**
	 * Use "window method" to design a band-reject FIR filter.  The
	 * normalized width of the transition band is what sets the number of
	 * taps required.  Narrow --> more taps. Window type determines maximum
	 * attenuation and passband ripple.
	 *
	 * @param gain             overall gain of filter (typically 1.0)
	 * @param sampling_freq    sampling freq (Hz)
	 * @param low_cutoff_freq  center of transition band (Hz)
	 * @param high_cutoff_freq center of transition band (Hz)
	 * @param transition_width width of transition band (Hz)
	 * @param window           one of Window.Type
	 * @param beta             parameter for Kaiser window
	 */

	public static float[]
	band_reject(double gain,
	            double sampling_freq,
	            double low_cutoff_freq,     // Hz center of transition band
	            double high_cutoff_freq, // Hz center of transition band
	            double transition_width, // Hz width of transition band
	            Window.Type window,
	            double beta)         // used only with Kaiser
	{
		sanity_check_2f(sampling_freq,
				low_cutoff_freq,
				high_cutoff_freq,
				transition_width);

		int ntaps = Utils.tapsCount(sampling_freq,
				transition_width,
				window, beta);

		// construct the truncated ideal impulse response times the window function

		float[] taps = new float[ntaps];
		float[] w = Utils.window(window, ntaps, beta);

		int M = (ntaps - 1) / 2;
		double fwT0 = 2 * Math.PI * low_cutoff_freq / sampling_freq;
		double fwT1 = 2 * Math.PI * high_cutoff_freq / sampling_freq;

		for (int n = -M; n <= M; n++) {
			if (n == 0)
				taps[n + M] = (float) (1.0 + ((fwT0 - fwT1) / Math.PI * w[n + M]));
			else {
				taps[n + M] = (float) ((Math.sin(n * fwT0) - Math.sin(n * fwT1)) / (n * Math.PI) * w[n + M]);
			}
		}

		// find the factor to normalize the gain, fmax.
		// For band-reject, gain @param  zero freq = 1.0

		double fmax = taps[0 + M];
		for (int n = 1; n <= M; n++)
			fmax += 2 * taps[n + M];

		gain /= fmax;    // normalize

		for (int i = 0; i < ntaps; i++)
			taps[i] *= gain;

		return taps;
	}

	//
	// Hilbert Transform
	//

	/**
	 * design a Hilbert Transform Filter
	 *
	 * @param ntaps      number of taps, must be odd
	 * @param windowtype one kind of Window.Type
	 * @param beta       parameter for Kaiser window
	 */
	public static float[]
	hilbert(int ntaps,
	        Window.Type windowtype,
	        double beta) {
		if ((ntaps & 1) == 0)
			throw new IllegalArgumentException("Hilbert:  Must have odd number of taps");

		float[] taps = new float[ntaps];
		float[] w = Utils.window(windowtype, ntaps, beta);
		int h = (ntaps - 1) / 2;
		float gain = 0;
		for (int i = 1; i <= h; i++) {
			if ((i & 1) != 0) {
				float x = 1 / (float) i;
				taps[h + i] = x * w[h + i];
				taps[h - i] = -x * w[h - i];
				gain = taps[h + i] - gain;
			} else
				taps[h + i] = taps[h - i] = 0;
		}

		gain = 2 * Math.abs(gain);
		for (int i = 0; i < ntaps; i++)
			taps[i] /= gain;
		return taps;
	}

	//
	// Gaussian
	//

	/**
	 * design a Gaussian filter
	 *
	 * @param gain  overall gain of filter (typically 1.0)
	 * @param spb   symbol rate, must be a factor of sample rate
	 * @param bt    bandwidth to bitrate ratio
	 * @param ntaps number of taps
	 */
	public static float[]
	gaussian(double gain,
	         double spb,
	         double bt,
	         int ntaps) {
		float[] taps = new float[ntaps];
		double scale = 0;
		double dt = 1.0 / spb;
		double s = 1.0 / (Math.sqrt(Math.log(2.0)) / (2 * Math.PI * bt));
		double t0 = -0.5 * ntaps;
		double ts;
		for (int i = 0; i < ntaps; i++) {
			t0++;
			ts = s * dt * t0;
			taps[i] = (float) Math.exp(-0.5 * ts * ts);
			scale += taps[i];
		}
		for (int i = 0; i < ntaps; i++)
			taps[i] = (float) (taps[i] / scale * gain);

		return taps;
	}


	//
	// Root Raised Cosine
	//

	/**
	 * design a Root Cosine FIR Filter (do we need a window?)
	 *
	 * @param gain          overall gain of filter (typically 1.0)
	 * @param sampling_freq sampling freq (Hz)
	 * @param symbol_rate   symbol rate, must be a factor of sample rate
	 * @param alpha         excess bandwidth factor
	 * @param ntaps         number of taps
	 */
	public static float[]
	root_raised_cosine(double gain,
	                   double sampling_freq,
	                   double symbol_rate,
	                   double alpha,
	                   int ntaps) {
		ntaps |= 1;    // ensure that ntaps is odd

		double spb = sampling_freq / symbol_rate; // samples per bit/symbol
		float[] taps = new float[ntaps];
		double scale = 0;
		for (int i = 0; i < ntaps; i++) {
			double x1, x2, x3, num, den;
			double xindx = i - ntaps / 2;
			x1 = Math.PI * xindx / spb;
			x2 = 4 * alpha * xindx / spb;
			x3 = x2 * x2 - 1;

			if (Math.abs(x3) >= 0.000001) {  // Avoid Rounding errors...
				if (i != ntaps / 2)
					num = Math.cos((1 + alpha) * x1) + Math.sin((1 - alpha) * x1) / (4 * alpha * xindx / spb);
				else
					num = Math.cos((1 + alpha) * x1) + (1 - alpha) * Math.PI / (4 * alpha);
				den = x3 * Math.PI;
			} else {
				if (alpha == 1) {
					taps[i] = -1;
					continue;
				}
				x3 = (1 - alpha) * x1;
				x2 = (1 + alpha) * x1;
				num = (Math.sin(x2) * (1 + alpha) * Math.PI
				       - Math.cos(x3) * ((1 - alpha) * Math.PI * spb) / (4 * alpha * xindx)
				       + Math.sin(x3) * spb * spb / (4 * alpha * xindx * xindx)
				);
				den = -32 * Math.PI * alpha * alpha * xindx / spb;
			}
			taps[i] = (float) (4 * alpha * num / den);
			scale += taps[i];
		}

		for (int i = 0; i < ntaps; i++)
			taps[i] = (float) (taps[i] * gain / scale);

		return taps;
	}

	//
	//	=== Utilities ===
	//

	protected static void
	sanity_check_1f(double sampling_freq,
	                double fa,            // cutoff freq
	                double transition_width) {
		if (sampling_freq <= 0.0)
			throw new IllegalArgumentException("firdes check failed: sampling_freq > 0");

		if (fa <= 0.0 || fa > sampling_freq / 2)
			throw new IllegalArgumentException("firdes check failed: 0 < fa <= sampling_freq / 2");

		if (transition_width <= 0)
			throw new IllegalArgumentException("firdes check failed: transition_width > 0");
	}

	protected static void
	sanity_check_2f(double sampling_freq,
	                double fa,            // first cutoff freq
	                double fb,            // second cutoff freq
	                double transition_width) {
		if (sampling_freq <= 0.0)
			throw new IllegalArgumentException("firdes check failed: sampling_freq > 0");

		if (fa <= 0.0 || fa > sampling_freq / 2)
			throw new IllegalArgumentException("firdes check failed: 0 < fa <= sampling_freq / 2");

		if (fb <= 0.0 || fb > sampling_freq / 2)
			throw new IllegalArgumentException("firdes check failed: 0 < fb <= sampling_freq / 2");

		if (fa > fb)
			throw new IllegalArgumentException("firdes check failed: fa <= fb");

		if (transition_width <= 0)
			throw new IllegalArgumentException("firdes check failed: transition_width > 0");
	}

	protected static void
	sanity_check_2f_c(double sampling_freq,
	                  double fa,        // first cutoff freq
	                  double fb,        // second cutoff freq
	                  double transition_width) {
		if (sampling_freq <= 0.0)
			throw new IllegalArgumentException("firdes check failed: sampling_freq > 0");

		if (fa < -sampling_freq / 2 || fa > sampling_freq / 2)
			throw new IllegalArgumentException("firdes check failed: 0 < fa <= sampling_freq / 2");

		if (fb < -sampling_freq / 2 || fb > sampling_freq / 2)
			throw new IllegalArgumentException("firdes check failed: 0 < fb <= sampling_freq / 2");

		if (fa > fb)
			throw new IllegalArgumentException("firdes check failed: fa <= fb");

		if (transition_width <= 0)
			throw new IllegalArgumentException("firdes check failed: transition_width > 0");
	}


}
