package com.mantz_it.rfanalyzer.dsp.spi;

import android.support.annotation.IntRange;
import android.support.annotation.NonNull;

/**
 * Created by Pavel on 15.12.2016.
 */

public abstract class FIR implements Filter {
	//public static final Implementation implementation = Implementation.FIR;
	protected final float[] taps;
	protected final float[] delay;
	protected final int tapsCount;
	protected final int tapsLength;
	@IntRange(from = 0)
	protected final int decimation;


	protected FIR(@NonNull float[] taps,@NonNull float[] delays, int tapsCount, int decimation) {
		this.taps = taps;
		this.delay = delays;
		this.tapsCount = tapsCount;
		this.tapsLength = taps.length;
		this.decimation = decimation;
	}


	protected FIR(@NonNull float[] taps,@NonNull float[] delays, int tapsCount, int tapsLength, int decimation) {
		this.taps = taps;
		this.delay = delays;
		this.tapsCount = tapsCount;
		this.tapsLength = tapsLength;
		this.decimation = decimation;
	}

	public int getTapsCount() {return tapsCount;}

	public float[] getTaps() {return taps;}

	public int getDecimation() {return decimation;}

}
