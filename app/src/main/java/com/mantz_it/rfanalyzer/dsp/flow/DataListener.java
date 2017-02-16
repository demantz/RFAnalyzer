package com.mantz_it.rfanalyzer.dsp.flow;

/**
 * Created by Pavel on 06.01.2017.
 */

public interface DataListener<T> {
	void onDataAvailable(T data);
}
