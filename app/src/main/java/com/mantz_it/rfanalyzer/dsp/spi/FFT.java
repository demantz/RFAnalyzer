package com.mantz_it.rfanalyzer.dsp.spi;

/**
 * Created by Pavel on 19.11.2016.
 */

public interface FFT extends Transformation<Packet> {

	int getSize();
	void apply(float[] interleaved, int offset);

	void applyWindow(float[] interleaved, int offset);

}
