package com.mantz_it.rfanalyzer.dsp;

import com.mantz_it.rfanalyzer.dsp.flow.Sink;

import java.nio.Buffer;
import java.nio.FloatBuffer;

/**
 * Created by pavlus on 15.01.17.
 */

public abstract class BufferRecorder<T extends Buffer> implements Sink<T> {


public static class FloatBufferRecorder extends BufferRecorder<FloatBuffer>{

	@Override
	public void onDataAvailable(final FloatBuffer data) {
	}

	@Override
	public void run() {

	}
}
}
