package com.mantz_it.rfanalyzer.dsp.spi;

/**
 * Created by Pavel on 11.11.2016.
 */

public interface Filter extends Transformation<Packet> {

	enum Implementation {
		FIR, IIR;
	}

	enum Type {BANDPASS, DIFFERENTIATOR, HILBERT}


}
