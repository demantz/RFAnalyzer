package com.mantz_it.rfanalyzer.dsp.spi;

import android.util.SparseArray;

/**
 * Created by Pavel on 16.11.2016.
 */

public class Window {
	private static final float[][] BLACKMAN_TERMS = {
			{0.42f, -0.5f, 0.08f},
			{0.344010f, -0.49755f, 0.158440f},
			{0.217470f, -0.45325f, 0.282560f, -0.04672f},
			{0.084037f, -0.29145f, 0.375696f, -0.20762f, 0.041194f}
	};


	static final float PI_2F = (float) (Math.PI * 2);
	static final double PI_2 = Math.PI * 2;

	public enum Type {
		/**
		 * don't use a window
		 **/
		WIN_NONE(-1) {
			@Override
			public float[] build(int ntaps, int variant, double beta) {
				return new float[0]; // maybe 0 length array is better than null? I hope it is.
			}

			@Override
			public double maxAttenuation(double beta) {
				return 0;
			}
		},
		/**
		 * Hamming window; max attenuation 53 dB
		 **/
		WIN_HAMMING(0) {
			@Override
			public float[] build(int ntaps, int ignore1, double ignore2) {
				float[] taps = new float[ntaps];
				float M = ntaps - 1.0f;

				for (int n = 0; n < ntaps; n++)
					taps[n] = (float) (0.54 - 0.46 * Math.cos(2 * Math.PI * n / M));
				return taps;
			}

			@Override
			public double maxAttenuation(double ignore) {
				return 53;
			}
		},
		/**
		 * Hann window; max attenuation 44 dB
		 **/
		WIN_HANN(1) {
			@Override
			public float[] build(int ntaps, int ignore1, double ignore2) {
				float[] taps = new float[ntaps];
				float M = ntaps - 1.0f;

				for (int n = 0; n < ntaps; n++)
					taps[n] = (float) (0.5 - 0.5 * Math.cos((2 * Math.PI * n) / M));
				return taps;
			}

			@Override
			public double maxAttenuation(double ignore) {
				return 44;
			}
		},
		/**
		 * Blackman window; max attenuation 74 dB
		 **/
		WIN_BLACKMAN(2) {
			@Override
			public float[] build(int ntaps, int variant, double ignore) {
				return coswindow(ntaps, BLACKMAN_TERMS[3]); // fixme: use 'variant'
			}

			@Override
			public double maxAttenuation(double ignore) {
				return 74;
			}


		},
		/**
		 * Basic rectangular window
		 **/
		WIN_RECTANGULAR(3) {
			@Override
			public float[] build(int ntaps, int variant, double beta) {
				float[] taps = new float[ntaps];
				for (int n = 0; n < ntaps; n++)
					taps[n] = 1;
				return taps;
			}

			@Override
			public double maxAttenuation(double ignore) {
				return 21;
			}
		},
		/**
		 * Kaiser window; max attenuation a function of beta, google it
		 **/
		WIN_KAISER(4) {
			@Override
			public float[] build(int ntaps, int variant, double beta) {
				if (beta < 0)
					throw new IllegalArgumentException("window::kaiser: beta must be >= 0");

				float[] taps = new float[ntaps];

				double IBeta = 1.0 / Izero(beta);
				double inm1 = 1.0 / ((double) (ntaps - 1));
				double temp;

				for (int i = 0; i < ntaps; i++) {
					temp = 2 * i * inm1 - 1;
					taps[i] = (float) (Izero(beta * Math.sqrt(1.0 - temp * temp)) * IBeta);
				}
				return taps;
			}

			@Override
			public double maxAttenuation(double beta) {
				return beta / 0.1102 + 8.7;
			}
		},
		/**
		 * Blackman-harris window
		 **/
		WIN_BLACKMAN_HARRIS(5) {
			private SparseArray<float[]> BLACKMAN_HARRIS_ATTENUATION2POLY;

			@Override
			public float[] build(int ntaps, int variant, double ignore) {
				if(BLACKMAN_HARRIS_ATTENUATION2POLY == null)			{
					BLACKMAN_HARRIS_ATTENUATION2POLY = new SparseArray<>(4);
					BLACKMAN_HARRIS_ATTENUATION2POLY.put(61, new float[]{0.42323f, -0.49755f, 0.07922f});
					BLACKMAN_HARRIS_ATTENUATION2POLY.put(67, new float[]{0.44959f, -0.49364f, 0.05677f});
					BLACKMAN_HARRIS_ATTENUATION2POLY.put(74, new float[]{0.40271f, -0.49703f, 0.09392f, -0.00183f});
					BLACKMAN_HARRIS_ATTENUATION2POLY.put(92, new float[]{0.35875f, -0.48829f, 0.14128f, -0.01168f});
				}
				float[] poly = BLACKMAN_HARRIS_ATTENUATION2POLY.get(variant);
				// todo: dynamic list of available attenuations
				if (poly == null) throw new IllegalArgumentException(
						"Can't find poly for desired attenuation. Available attenuations: 61, 67,74, 92 dB");
				return coswindow(ntaps, poly);
			}

			@Override
			public double maxAttenuation(double ignore) {
				return 92;
			}
		},
		/**
		 * Barlett (triangular) window
		 **/
		WIN_BARTLETT(6) {
			@Override
			public float[] build(int ntaps, int variant, double beta) {
				float[] taps = new float[ntaps];
				float M = ntaps - 1.0f;

				for (int n = 0; n < ntaps / 2; n++)
					taps[n] = 2 * n / M;
				for (int n = ntaps / 2; n < ntaps; n++)
					taps[n] = 2 - 2 * n / M;

				return taps;
			}

			@Override
			public double maxAttenuation(double ignore) {
				return 27;
			}
		},
		/**
		 * flat top window; useful in FFTs
		 **/
		WIN_FLATTOP(7) {
			@Override
			public float[] build(int ntaps, int variant, double beta) {
				double scale = 4.63867;
				return coswindow(ntaps,
						(float) (1.0 / scale),
						(float) (1.93 / scale),
						(float) (1.29 / scale),
						(float) (0.388 / scale),
						(float) (0.028 / scale)
				);
			}

			@Override
			public double maxAttenuation(double ignore) {
				return 93;
			}
		};

		protected int id;

		Type(int ord) {
			this.id = ord;
		}

		public boolean equals(Type obj) {
			return obj.id == this.id;
		}

		public abstract float[] build(int ntaps, int variant, double beta);

		public abstract double maxAttenuation(double beta);

		private static double IzeroEPSILON = 1E-21;

		private static double Izero(double x) {
			double sum, u, halfx, temp;
			int n;

			sum = u = n = 1;
			halfx = x / 2.0;
			do {
				temp = halfx / (double) n;
				n += 1;
				temp *= temp;
				u *= temp;
				sum += u;
			} while (u >= IzeroEPSILON * sum);
			return (sum);
		}
	}

	private Window() {
	}

	public static float[] coswindow(int ntaps, float... poly) {
		final float[] window = new float[ntaps];
		final int last = ntaps - 1;
		final double phaseStep = PI_2 / last;
		double phase;
		int index;
		for (index = 0, phase = 0;
		     index < ntaps;
		     ++index, phase += phaseStep) {
			// Fourier series
			double tab = poly[0]; // * cos(0*phase);
			for (int n = 1; n < poly.length; ++n)
				tab += poly[n] * Math.cos(n * phase);
			window[index] = (float) tab;
		}
		return window;
	}
}
