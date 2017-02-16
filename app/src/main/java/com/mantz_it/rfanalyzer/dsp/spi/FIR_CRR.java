package com.mantz_it.rfanalyzer.dsp.spi;

import android.support.annotation.NonNull;

import java.nio.FloatBuffer;

/**
 * Created by Pavel on 15.12.2016.
 */

public class FIR_CRR extends FIR {

public FIR_CRR(@NonNull float[] taps, int decimation) {
	super(taps, new float[taps.length], taps.length, decimation);
}

protected FIR_CRR(@NonNull float[] taps, @NonNull float[] delays, int tapsCount, int tapsLength, int decimation) {
	super(taps, delays, tapsCount, tapsLength, decimation);
}


@Override
public int apply(@NonNull Packet src, @NonNull Packet dst) {
	final FloatBuffer dstBuff = dst.getBuffer();
	final FloatBuffer srcBuff = src.getBuffer();
	final int dstRemaining = (dstBuff.capacity() - dstBuff.position()); // want it to be even
	final int srcRemaining = (srcBuff.remaining() >> 1) & ~1;
	final int count = Math.min(dstRemaining, srcRemaining); // complex samples count

	int tapIter = 0;
	int decimationIter = 0;

	// todo: rewrite with more intelligent predictions instead of regular checks
	// todo: how about linear phase FIR filters with reduced multiplication count by 2(use taps symmetry)?
	// insert each input sample into the delay line:
	for (int i = 0; i < count; i++) {
		delay[tapIter] = srcBuff.get();
		srcBuff.get(); //im
		// Calculate the apply output for every Mth element (were M = decimation)
		if (decimationIter == 0) {
			// Calculate the results:
			float re = 0;
			int index = tapIter;
			for (int t = 0; t < tapsCount; t++) {
				re += taps[t] * delay[index];
				index--;
				if (index < 0) {
					index = taps.length - 1;
				}
			}
			dstBuff.put(re);
			dstBuff.put(0); // im
		}

		// update counters:
		decimationIter++;
		if (decimationIter >= decimation) {
			decimationIter = 0;
		}
		tapIter++;
		if (tapIter >= tapsCount) {
			tapIter = 0;
		}
	}
	dst.sampleRate = src.sampleRate / decimation;
	dst.complex = false;
	return count;            // We return the number of consumed samples from the input buffers
}
}
