package com.mantz_it.rfanalyzer.dsp.spi;


import android.support.annotation.NonNull;

import java.nio.FloatBuffer;

/**
 * Complex inputs, Real taps (but stored complex), Complex output
 * Created by Pavel on 15.12.2016.
 */

public class FIR_CRC extends FIR {

	public FIR_CRC(@NonNull float[] taps, int decimation) {
		super(taps, new float[taps.length * 2], taps.length, decimation);
	}

	@Override
	public int apply(@NonNull Packet src, @NonNull Packet dst) {
		final FloatBuffer dstBuff = dst.getBuffer();
		final FloatBuffer srcBuff = src.getBuffer();
		final int dstRemaining = (dstBuff.capacity() - dstBuff.position()) & ~1; // want it to be even
		final int srcRemaining = srcBuff.remaining();
		final int count = Math.min(dstRemaining, srcRemaining) >> 1; // complex samples count

		int tapIter = 0;
		int decimationIter = 0;
		// todo: rewrite with more intelligent predictions instead of regular checks
		// insert each input sample into the delay line:
		for (int i = 0; i < count; i++) {
			delay[tapIter] = srcBuff.get();
			delay[tapIter + 1] = srcBuff.get();
			// Calculate the filter output for every Mth element (were M = decimation)
			if (decimationIter == 0) {
				// Calculate the results:
				float re = 0;
				float im = 0;
				int index = tapIter * 2;
				for (int t = 0; t < tapsCount; t++) {
					final float tap = taps[t];
					re += tap * delay[index];
					im += tap * delay[index + 1];
					index -= 2;
					if (index < 0)
						index = taps.length * 2 - 2;
				}
				dstBuff.put(re);
				dstBuff.put(im);
			}

			// update counters:
			decimationIter++;
			if (decimationIter >= decimation)
				decimationIter = 0;
			tapIter++;
			if (tapIter >= tapsCount)
				tapIter = 0;

		}
		dst.sampleRate = src.sampleRate / decimation;
		dst.complex=true;
		return count;            // We return the number of consumed samples from the input buffers
	}
}
