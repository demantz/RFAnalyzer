package com.mantz_it.rfanalyzer.dsp.spi;

import android.support.annotation.WorkerThread;

/**
 * Created by Pavel on 21.12.2016.
 */
public interface Transformation<T> {
	@WorkerThread
	int apply(T src, T dst);
}
