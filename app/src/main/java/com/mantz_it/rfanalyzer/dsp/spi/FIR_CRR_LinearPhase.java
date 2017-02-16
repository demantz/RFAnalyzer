package com.mantz_it.rfanalyzer.dsp.spi;

import android.support.annotation.NonNull;

import java.nio.FloatBuffer;

/**
 * Example of realization, actually FIR_CRR is a little bit faster (few %),
 * because of additional index operations here
 * Created by Pavel on 18.12.2016.
 */

public class FIR_CRR_LinearPhase extends FIR_CRR {

public FIR_CRR_LinearPhase(@NonNull float[] taps, int decimation) {
	this(taps, (taps.length >> 1) + 1, decimation);
}

protected FIR_CRR_LinearPhase(@NonNull float[] taps, int realTapsCnt, int decimation) {
	super(taps, new float[taps.length], realTapsCnt, taps.length, decimation);
}

public FIR_CRR_LinearPhase(@NonNull FIR_CRR prototype) {
	this(prototype.getTaps(), prototype.getDecimation());
}

@Override
public int apply(@NonNull Packet src, @NonNull Packet dst) {
	final FloatBuffer dstBuff = dst.getBuffer();
	final FloatBuffer srcBuff = src.getBuffer();
	final int dstRemaining = (dstBuff.capacity() - dstBuff.position()); // want it to be even
	final int srcRemaining = (srcBuff.remaining() >> 1) & ~1;
	final int count = Math.min(dstRemaining, srcRemaining); // complex samples count

	final int lastTap = tapsCount - 1;
	int decimationIter = 0;
	int tapIter = 0;
	// todo: rewrite with more intelligent predictions instead of regular checks? (tried for regular CRR, decreased performance)
	// insert each input sample into the delay line:
	for (int i = 0; i < count; i++) {
		delay[tapIter] = srcBuff.get();
		srcBuff.get(); //im
		// Calculate the apply output for every Mth element (were M = decimation)
		if (decimationIter == 0) {
			// Calculate the results:
			//System.out.printf("tapIter == %d;\n", tapIter);
			int lastDelay = tapIter >= lastTap ? (tapIter - lastTap) : (tapIter + tapsCount);
			//System.out.printf("re = taps[%d] * delay[%d];\n", lastTap, lastDelay);
			float re = taps[lastTap] * delay[lastDelay];
			int index1 = tapIter;
			int index2 = tapIter + 1;
			for (int t = 0; t < lastTap; t++) {
				if (index2 >= tapsLength) {
					index2 = 0;
				}
				if (index1 < 0) {
					index1 = tapsLength - 1;
				}
				//	System.out.printf("re = taps[%d] * (delay[%d] + delay[%d]);\n", t, index1, index2);
				re += taps[t] * (delay[index1--] + delay[index2++]);
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
		if (tapIter >= tapsLength) {
			tapIter = 0;
		}
	}
	dst.sampleRate = src.sampleRate / decimation;
	dst.complex = false;
	return count;            // We return the number of consumed samples from the input buffers
}
}
