package com.mantz_it.rfanalyzer.dsp.spi;

import android.support.annotation.WorkerThread;

/**
 * Created by Pavel on 11.11.2016.
 */

public interface Conversion<S, D> {
	@WorkerThread
	void convert(S src, D dst);
}
