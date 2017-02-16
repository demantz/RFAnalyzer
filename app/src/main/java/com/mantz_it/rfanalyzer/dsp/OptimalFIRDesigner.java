package com.mantz_it.rfanalyzer.dsp;

import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Size;

import com.mantz_it.rfanalyzer.dsp.spi.Filter;

import java.util.Arrays;

/**
 * Ported from gnuradio/gr-filter/python/filter/optifir.py
 * Possible bugs introduced.
 * todo: add support for halfband filters (harris f j "Multirate signal processing for communication systems" 8.4)
 */

public class OptimalFIRDesigner {

	protected OptimalFIRDesigner() {}


	private static final String LOGTAG = "OptiFIRDes";

	@NonNull
	public static double[] lowpass(
			double gain,
			double samplerate,
			double passbandEnd,
			double stopbandStart,
			double passbandRipple,
			double stopbandAttenuation,
			int extraTaps)
			throws ConvergenceException {
		double passbandDev = passbandRipple2Dev(passbandRipple);
		double stopbandDev = stopbandAttenuation2Dev(stopbandAttenuation);
		double[] desiredAmpls = {gain, 0};
		double[] freqs = new double[]{passbandEnd, stopbandStart};
		double[] deviations = new double[]{passbandDev, stopbandDev};
		RemezParams params = remezord(freqs, desiredAmpls, deviations, samplerate);
		//  The remezord typically under-estimates the filter order, so add 2 taps by default
		return Utils.Parks_McCellan_Remez(
				params.order + extraTaps,
				params.bands,
				params.amplitudes,
				params.error_weights,
				Filter.Type.BANDPASS);
	}

	/**
	 * Builds a band pass filter.
	 * <p>
	 * Args:
	 *
	 * @param gain:               Filter gain in the passband (linear)
	 * @param Fs:                 Sampling rate (sps)
	 * @param freq_sb1:           End of stop band (in Hz)
	 * @param freq_pb1:           Start of pass band (in Hz)
	 * @param freq_pb2:           End of pass band (in Hz)
	 * @param freq_sb2:           Start of stop band (in Hz)
	 * @param passband_ripple_db: Pass band ripple in dB (should be small, < 1)
	 * @param stopband_atten_db:  Stop band attenuation in dB (should be large, >= 60)
	 * @param nextra_taps:        Extra taps to use in the filter (default=2)
	 **/
	@NonNull
	public static double[] bandPass(double gain, double Fs, double freq_sb1, double freq_pb1, double freq_pb2, double freq_sb2,
	                                double passband_ripple_db, double stopband_atten_db,
	                                int nextra_taps) throws ConvergenceException {

		double passband_dev = passbandRipple2Dev(passband_ripple_db);

		double stopband_dev = stopbandAttenuation2Dev(stopband_atten_db);

		double[] desired_ampls = {0, gain, 0};
		double[] desired_freqs = {freq_sb1, freq_pb1, freq_pb2, freq_sb2};
		double[] desired_ripple = {stopband_dev, passband_dev, stopband_dev};
		RemezParams params = remezord(desired_freqs, desired_ampls,
				desired_ripple, Fs);

		//The remezord typically underestimates the filter order, so add 2 taps by default
		return Utils.Parks_McCellan_Remez(params.order + nextra_taps, params.bands, params.amplitudes, params.error_weights, Filter.Type.BANDPASS);
	}

	/**
	 * Builds a band pass filter with complex taps by making an LPF and
	 * spinning it up to the right center frequency
	 * <p>
	 * Args:
	 *
	 * @param gain:               Filter gain in the passband (linear)
	 * @param Fs:                 Sampling rate (sps)
	 * @param freq_sb1:           End of stop band (in Hz) //todo: actually, not used at all, maybe use real BANDPASS as protopype?
	 * @param freq_pb1:           Start of pass band (in Hz)
	 * @param freq_pb2:           End of pass band (in Hz)
	 * @param freq_sb2:           Start of stop band (in Hz)
	 * @param passband_ripple_db: Pass band ripple in dB (should be small, < 1)
	 * @param stopband_atten_db:  Stop band attenuation in dB (should be large, >= 60)
	 * @param nextra_taps:        Extra taps to use in the filter (default=2)
	 **/
	@NonNull
	public static double[] complexBandPass(double gain, double Fs, double freq_sb1, double freq_pb1, double freq_pb2, double freq_sb2,
	                                       double passband_ripple_db, double stopband_atten_db,
	                                       int nextra_taps)
			throws ConvergenceException {
		double center_freq = (freq_pb2 + freq_pb1) / 2;
		double lp_pb = (freq_pb2 - center_freq);
		double lp_sb = (freq_sb2 - center_freq);
		double[] lptaps = lowpass(gain, Fs, lp_pb, lp_sb, passband_ripple_db, stopband_atten_db, nextra_taps);

		double freq = Math.PI * (freq_pb2 + freq_pb1) / Fs;
		double phase = -freq * (lptaps.length / 2.0); // fixme: unsure about sign, supposed to be plus, but other designer uses minus

		double[] taps = new double[lptaps.length * 2];
		double gainNorm = 1;
		for (int i = 0; i < lptaps.length; ++i)
			gainNorm += lptaps[i];
		gainNorm = gain / gainNorm;
		for (int i = 0; i < lptaps.length; ++i) {
			final double tap = lptaps[i] * gainNorm;
			taps[i * 2] = tap * Math.cos(phase);
			taps[i * 2 + 1] = tap * Math.sin(phase);
			phase += freq;
		}
		return taps;

	}

	/**
	 * Builds a band reject filter
	 * spinning it up to the right center frequency
	 * <p>
	 * Args:
	 *
	 * @param gain:               Filter gain in the passband (linear)
	 * @param Fs:                 Sampling rate (sps)
	 * @param freq_pb1:           End of pass band (in Hz)
	 * @param freq_sb1:           Start of stop band (in Hz)
	 * @param freq_sb2:           End of stop band (in Hz)
	 * @param freq_pb2:           Start of pass band (in Hz)
	 * @param passband_ripple_db: Pass band ripple in dB (should be small, < 1)
	 * @param stopband_atten_db:  Stop band attenuation in dB (should be large, >= 60)
	 * @param nextra_taps:        Extra taps to use in the filter (default=2)
	 **/
	@NonNull
	public static double[] bandReject(double gain, double Fs, double freq_pb1, double freq_sb1, int freq_sb2, int freq_pb2,
	                                  double passband_ripple_db, double stopband_atten_db,
	                                  int nextra_taps)
			throws ConvergenceException {

		double passband_dev = passbandRipple2Dev(passband_ripple_db);
		double stopband_dev = stopbandAttenuation2Dev(stopband_atten_db);
		double[] desired_ampls = {gain, 0, gain};
		double[] desired_freqs = {freq_pb1, freq_sb1, freq_sb2, freq_pb2};
		double[] desired_ripple = {passband_dev, stopband_dev, passband_dev};
		RemezParams params = remezord(desired_freqs, desired_ampls, desired_ripple, Fs);
		// Make sure we use an odd number of taps
		params = new RemezParams(params.order + (params.order + nextra_taps) % 2 + nextra_taps, params.bands, params.amplitudes, params.error_weights);
		// The remezord typically under-estimates the filter order, so add 2 taps by default
		double[] taps = Utils.Parks_McCellan_Remez(params.order, params.bands, params.amplitudes, params.error_weights, Filter.Type.BANDPASS);
		return taps;
	}

	/**
	 * Builds a high pass filter.
	 * <p>
	 * Args:
	 *
	 * @param gain:               Filter gain in the passband (linear)
	 * @param Fs:                 Sampling rate (sps)
	 * @param freq1:              End of stop band (in Hz)
	 * @param freq2:              Start of pass band (in Hz)
	 * @param passband_ripple_db: Pass band ripple in dB (should be small, < 1)
	 * @param stopband_atten_db:  Stop band attenuation in dB (should be large, >= 60)
	 * @param nextra_taps:        Extra taps to use in the filter (default=2)
	 **/
	@NonNull
	public static double[] highPass(double gain, double Fs, double freq1, double freq2, double passband_ripple_db, double stopband_atten_db,
	                                int nextra_taps)
			throws ConvergenceException {

		double passband_dev = passbandRipple2Dev(passband_ripple_db);
		double stopband_dev = stopbandAttenuation2Dev(stopband_atten_db);
		double[] desired_ampls = {0, gain};
		double[] desiredFrequencies = {freq1, freq2};
		double[] desiredDeviations = {stopband_dev, passband_dev};
		RemezParams params = remezord(desiredFrequencies, desired_ampls, desiredDeviations, Fs);
		//For a HPF, we need to use an odd number of taps
		//In filter.remez, ntaps = n + 1, so n must be even
		params = new RemezParams(params.order + ((params.order + nextra_taps) & 1) + nextra_taps, params.bands, params.amplitudes, params.error_weights);

		//The remezord typically under -estimates the filter order, so add 2 taps by default
		double[] taps = Utils.Parks_McCellan_Remez(params.order, params.bands, params.amplitudes, params.error_weights, Filter.Type.BANDPASS);
		return taps;
	}


	/**
	 * Convert a stopband attenuation in dB to an absolute value
	 *
	 * @param atten_dB
	 * @return
	 */
	protected static double stopbandAttenuation2Dev(double atten_dB) {
		return Math.pow(10, -atten_dB / 20);
	}

	/**
	 * Convert passband ripple spec expressed in dB to an absolute value
	 *
	 * @param ripple_dB
	 * @return
	 */
	protected static double passbandRipple2Dev(double ripple_dB) {
		double rippleDev = Math.pow(10, ripple_dB / 20);
		return (rippleDev - 1) / (rippleDev + 1);
	}

	protected static class RemezParams {
		/**
		 * Designed FIR filter order (number of taps -1)
		 */
		@IntRange(from=3)
		public final int order;

		/**
		 * frequency at the band edges { b1 e1 b2 e2 b3 e3 ...}
		 */
		@Size(multiple = 2, min=2)
		public final double[] bands;

		/**
		 * desired amplitude at the band edges { a(b1) a(e1) a(b2) a(e2) ...}
		 */
		public final double[] amplitudes;

		/**
		 * weighting applied to each band (usually 1)
		 */
		public final double[] error_weights;

		/**
		 * @param order         See {@link #order}
		 * @param bands         See {@link #bands}
		 * @param amplitudes    See {@link #amplitudes}
		 * @param error_weights See {@link #error_weights}
		 */
		public RemezParams(int order, double[] bands, double[] amplitudes, double[] error_weights) {
			this.order = order;
			this.bands = bands;
			this.amplitudes = amplitudes;
			this.error_weights = error_weights;
		}
	}

	/**
	 * FIR order estimator (lowpass, highpass, bandpass, mulitiband).
	 * <p>
	 * (n, fo, ao, w) = remezord (f, a, dev)
	 * (n, fo, ao, w) = remezord (f, a, dev, fs)
	 * <p>
	 * (n, fo, ao, w) = remezord (f, a, dev) finds the approximate order,
	 * normalized frequency band edges, frequency band amplitudes, and
	 * weights that meet input specifications f, a, and dev, to use with
	 * the remez command.
	 * <p>
	 * f is a sequence of frequency band edges (between 0 and Fs/2, where
	 * Fs is the sampling frequency), and a is a sequence specifying the
	 * desired amplitude on the bands defined by f. The length of f is
	 * twice the length of a, minus 2. The desired function is
	 * piecewise finalant.
	 * <p>
	 * dev is a sequence the same size as a that specifies the maximum
	 * allowable deviation or ripples between the frequency response
	 * and the desired amplitude of the output filter, for each band.
	 * <p>
	 * Use remez with the resulting order n, frequency sequence fo,
	 * amplitude response sequence ao, and weights w to design the filter b
	 * which approximately meets the specifications given by remezord
	 * input parameters f, a, and dev:
	 * <p>
	 * b = remez(n, fo, ao, w)
	 * <p>
	 * (n, fo, ao, w)=remezord(f, a, dev, Fs) specifies a sampling frequency Fs.
	 * <p>
	 * Fs defaults to 2 Hz, implying a Nyquist frequency of 1 Hz.You can
	 * therefore specify band edges scaled to a particular applications
	 * sampling frequency.
	 * <p>
	 * In some cases remezord underestimates the order n.If the filter
	 * does not meet the specifications,try a higher order such as n +1
	 * or n +2.
	 *
	 * @param cutoffs
	 * @param magnitudes
	 * @param deviations
	 */
	protected static RemezParams remezord(double[] cutoffs, double[] magnitudes, double[] deviations) {
		return remezord(cutoffs, magnitudes, deviations, 2);
	}

	/**
	 * See {@link #remezord(int[], double[], double[])}
	 *
	 * @param freqs
	 * @param magnitudes
	 * @param deviations
	 * @param fsamp
	 * @return
	 */
	protected static RemezParams remezord(
			final double[] freqs,
			final double[] magnitudes,
			final double[] deviations,
			final double fsamp) {
		// get local copies
		double[] cutoffsLocal = new double[freqs.length];
		for (int i = 0; i < cutoffsLocal.length; ++i)  // normalization
			cutoffsLocal[i] = freqs[i] / fsamp;
		double[] magnitudesLocal = Arrays.copyOf(magnitudes, magnitudes.length);
		double[] deviationsLocal = Arrays.copyOf(deviations, deviations.length);

		int freqCnt = cutoffsLocal.length;
		int bandsCnt = magnitudesLocal.length;
		int deviationsCnt = deviationsLocal.length;

		if (bandsCnt != deviationsCnt)
			throw new IllegalArgumentException("Length of mags and deviations must be equal");

		if (freqCnt != 2 * (bandsCnt - 1))
			throw new IllegalArgumentException("Length of f must be 2 * mags.length - 2");

		for (int i = 0; i < bandsCnt; ++i)
			if (magnitudesLocal[i] != 0)// if not stopband, get relative deviation
				deviationsLocal[i] = deviationsLocal[i] / magnitudesLocal[i];

		// separate the passband and stopband edges
		double[] fPass = new double[freqCnt / 2]; // pass
		for (int i = 0; i < fPass.length; i++)
			fPass[i] = cutoffsLocal[i * 2];

		double[] fStop = new double[freqCnt / 2]; // stop
		for (int i = 0; i < fPass.length; i++)
			fStop[i] = cutoffsLocal[1 + i * 2];


		int n = 0; // index of min_delta
		double min_delta = 2;
		for (int i = 0; i < fPass.length; ++i) {
			final double delta = fStop[i] - fPass[i];
			if (delta < min_delta) {
				n = i;
				min_delta = delta;
			}
		}

		double l;
		if (bandsCnt == 2)
			// lowpass or highpass case (use formula)
			l = lporder(fPass[n], fStop[n], deviations[0], deviations[1]);
		else {
			// bandpass or multipass case
			// try different lowpasses and take the worst one that
			// goes through the BP specs
			l = 0;
			for (int i = 1; i < bandsCnt; ++i) { // todo: check correctness
				double l1 = lporder(fPass[i - 1], fStop[i - 1], deviationsLocal[i], deviationsLocal[i - 1]);
				double l2 = lporder(fPass[i], fStop[i], deviationsLocal[i], deviationsLocal[i + 1]);
				l = Utils.max(l, l1, l2);
			}
		}

		n = (int) (Math.ceil(l)) - 1; // need order, not length for remez

		// cook up remez compatible result
		double[] ff = new double[cutoffsLocal.length + 2];
		ff[0] = 0;
		for (int i = 0; i < cutoffsLocal.length; ++i)
			ff[i + 1] = 2 * cutoffsLocal[i];
		ff[ff.length - 1] = 1;

		double[] aa = new double[bandsCnt * 2];
		for (int i = 0; i < bandsCnt; ++i) {
			aa[i * 2] = magnitudesLocal[i];
			aa[i * 2 + 1] = magnitudesLocal[i];
		}

		double max_dev = Utils.max(deviationsLocal);
		double[] wts = new double[deviationsLocal.length];
		for (int i = 0; i < wts.length; ++i)
			wts[i] = max_dev / deviationsLocal[i];
		return new RemezParams(n, ff, aa, wts);
	}

	/**
	 * FIR lowpass filter length estimator.
	 * Note, this works for high pass filters too (freq1 > freq2), but doesnt work well if the transition
	 * is near f == 0 or f == fs / 2
	 * <p>
	 * From Herrmann et al (1973), Practical design rules for optimum finite impulse response filters.
	 * Bell System Technical J., 52, 769 - 99
	 *
	 * @param freq1   passband end normalized to the sampling frequency.
	 * @param freq2   stopband start normalized to the sampling frequency.
	 * @param delta_p passband deviation(ripple)
	 * @param delta_s stopband deviation(ripple)
	 * @return
	 */
	public static double lporder(
			final double freq1,
			final double freq2,
			final double delta_p,
			final double delta_s) {
		double df = Math.abs(freq2 - freq1);

		double ddp = Math.log10(delta_p);
		double dds = Math.log10(delta_s);

		double a1 = 5.309e-3;
		double a2 = 7.114e-2;
		double a3 = -4.761e-1;
		double a4 = -2.66e-3;
		double a5 = -5.941e-1;
		double a6 = -4.278e-1;

		double b1 = 11.01217;
		double b2 = 0.5124401;

		double t1 = a1 * ddp * ddp;
		double t2 = a2 * ddp;
		double t3 = a4 * ddp * ddp;
		double t4 = a5 * ddp;

		double dinf = ((t1 + t2 + a3) * dds) + (t3 + t4 + a6);
		double ff = b1 + b2 * (ddp - dds);
		double n = dinf / df - ff * df + 1;
		//System.out.println("lporder = " + n);
		return n;
	}

	/**
	 * FIR bandpass filter length estimator.  freq1 and freq2 are
	 * normalized to the sampling frequency.  delta_p is the passband
	 * deviation (ripple), delta_s is the stopband deviation (ripple).
	 * <p>
	 * From Mintzer and Liu (1979)
	 *
	 * @param freq1   passband end normalized to the sampling frequency.
	 * @param freq2   stopband start normalized to the sampling frequency.
	 * @param delta_p passband deviation(ripple)
	 * @param delta_s stopband deviation(ripple)
	 * @return
	 **/
	public static double bporder(
			final double freq1,
			final double freq2,
			final double delta_p,
			final double delta_s) {
		double df = Math.abs(freq2 - freq1);
		double ddp = Math.log10(delta_p);
		double dds = Math.log10(delta_s);

		double a1 = 0.01201;
		double a2 = 0.09664;
		double a3 = -0.51325;
		double a4 = 0.00203;
		double a5 = -0.57054;
		double a6 = -0.44314;

		double t1 = a1 * ddp * ddp;
		double t2 = a2 * ddp;
		double t3 = a4 * ddp * ddp;
		double t4 = a5 * ddp;

		double cinf = dds * (t1 + t2 + a3) + t3 + t4 + a6;
		double ginf = -14.6 * Math.log10(delta_p / delta_s) - 16.9;
		double n = cinf / df + ginf * df + 1;
		return n;
	}

}

