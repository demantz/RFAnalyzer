package com.mantz_it.rfanalyzer.dsp.flow;

/**
 * Created by Pavel on 23.12.2016.
 */

public interface Source<T> extends Runnable {
	boolean addSink(Sink<T> sink);

	boolean removeSink(Sink<T> sink);
}
