package com.mantz_it.rfanalyzer.dsp;

import com.mantz_it.rfanalyzer.BuildConfig;
import com.mantz_it.rfanalyzer.dsp.spi.FIR_CCC;
import com.mantz_it.rfanalyzer.dsp.spi.FIR_CRC;
import com.mantz_it.rfanalyzer.dsp.spi.FIR_CRR;
import com.mantz_it.rfanalyzer.dsp.spi.Window;

import java.util.Locale;

/**
 * Created by Pavel on 11.12.2016.
 */

public class FilterBuilder {
	protected static final String LOGTAG = "FilterBuilder";

	public static FIR_CCC bandPass_CCC(int decimation,
	                                   double gain,
	                                   double sampling_freq,    // Hz
	                                   double low_cutoff_freq,      // Hz BEGINNING of transition band
	                                   double high_cutoff_freq,      // Hz END of transition band
	                                   double transition_width, // Hz width of transition band
	                                   double attenuation_dB,
	                                   Window.Type win_type, double beta)   // attenuation dB
	{
		float[] taps;
		taps = FIRDesigner.complexBandPass(
				gain,
				sampling_freq,
				low_cutoff_freq,
				high_cutoff_freq,
				transition_width,
				attenuation_dB,
				win_type,
				beta
		);
		return new FIR_CCC(taps, decimation);
	}

	public static FIR_CCC optimalBandPass_CCC(int decimation,
	                                          double gain,
	                                          double sampling_freq,    // Hz
	                                          double low_cutoff_freq,      // Hz BEGINNING of transition band
	                                          double high_cutoff_freq,      // Hz END of transition band
	                                          double transition_width, // Hz width of transition band
	                                          double passbandRipple_dB,
	                                          double stopbandAttenuation_dB,    // attenuation dB
	                                          int extraTaps
	)
			throws ConvergenceException {
		float[] taps;
		double[] d_taps = OptimalFIRDesigner.complexBandPass(
				gain,
				sampling_freq,
				low_cutoff_freq - transition_width,
				low_cutoff_freq,
				high_cutoff_freq,
				high_cutoff_freq + transition_width,
				passbandRipple_dB,
				stopbandAttenuation_dB,
				extraTaps);
		taps = new float[d_taps.length];
		if (BuildConfig.DEBUG) {
			// log quantization error of precision reduction
			double accum = 0;
			System.out.print("errorsQuantization =[ ");
			for (int i = 0; i < taps.length; ++i) {
				taps[i] = (float) d_taps[i];
				final double error = d_taps[i] - taps[i];
				accum += Math.abs(error);
				System.out.print(error + ", ");
			}
			System.out.println("];");
			System.out.printf(Locale.US, "QuantizationError = %e;\n", accum);// usually it's around 10e-7 ≈ -140 dB, seems ok.
			System.out.printf(Locale.US, "QuantizationNoiseLevel = %f; %%dB\n", 20 * Math.log10(accum));// usually it's around 10e-7 ≈ -140 dB, seems ok.
		} else {
			for (int i = 0; i < taps.length; ++i) {
				taps[i] = (float) d_taps[i];
			}
		}
		return new FIR_CCC(taps, decimation);
	}

	public static FIR_CRC lowPass_CRC(int decimation,
	                                  double gain,
	                                  double samplingFreq,
	                                  double cutOffFreq,
	                                  double transitionBand,
	                                  double attenuation_dB,
	                                  Window.Type winType,
	                                  double beta) {
		float[] taps;
		taps = FIRDesigner.low_pass(
				gain,
				samplingFreq,
				cutOffFreq,
				transitionBand,
				attenuation_dB,
				winType,
				beta
		);
		return new FIR_CRC(taps, decimation);
	}

	public static FIR_CRC optimalLowPass_CRC(int decimation,
	                                         double gain,
	                                         double samplingFreq,
	                                         double cutOffFreq,
	                                         double transitionBand,
	                                         double passbandRipple_dB,
	                                         double attenuation_dB,
	                                         int extraTaps)
			throws ConvergenceException {
		double[] d_taps = OptimalFIRDesigner.lowpass(
				gain,
				samplingFreq,
				cutOffFreq,
				cutOffFreq + transitionBand,
				passbandRipple_dB,
				attenuation_dB,
				extraTaps
		);
		float[] taps = new float[d_taps.length];
		for (int i = 0; i < d_taps.length; ++i) taps[i] = (float) d_taps[i];
		return new FIR_CRC(taps, decimation);
	}

	public static FIR_CRR lowPass_CRR(int decimation,
	                                  double gain,
	                                  double samplingFreq,
	                                  double cutOffFreq,
	                                  double transitionBand,
	                                  double attenuation_dB,
	                                  Window.Type winType,
	                                  double beta) {
		float[] taps;
		taps = FIRDesigner.low_pass(
				gain,
				samplingFreq,
				cutOffFreq,
				transitionBand,
				attenuation_dB,
				winType,
				beta
		);
		return new FIR_CRR(taps, decimation);
	}

	public static FIR_CRR optimalLowPass_CRR(int decimation,
	                                         double gain,
	                                         double samplingFreq,
	                                         double cutOffFreq,
	                                         double transitionBand,
	                                         double passbandRipple_dB,
	                                         double attenuation_dB,
	                                         int extraTaps) throws ConvergenceException {
		double[] d_taps = OptimalFIRDesigner.lowpass(
				gain,
				samplingFreq,
				cutOffFreq,
				cutOffFreq + transitionBand,
				passbandRipple_dB,
				attenuation_dB,
				extraTaps
		);
		float[] taps = new float[d_taps.length];
		for (int i = 0; i < d_taps.length; ++i) taps[i] = (float) d_taps[i];
		return new FIR_CRR(taps, decimation);
	}

	public static boolean isLinearPhase(final float[] taps, final float maxDeviation) {
		for (int i = 0, j = taps.length - 1; i < taps.length; ++i, --j)
			if (Math.abs(taps[i] - taps[j]) > maxDeviation) return false;
		return true;
	}
}
