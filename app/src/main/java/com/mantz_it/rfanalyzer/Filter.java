package com.mantz_it.rfanalyzer;

/**
 * Created by Pavel on 14.10.2016.
 */

@Deprecated
public abstract class Filter {
	public enum FilterFamily {IIR, FIR, CIC}

	protected FilterFamily family;
	protected int tapIter = 0;
	protected int tapsCount;
	protected float[] tapsReal;
	protected float[] tapsImag;
	protected float[] delaysReal;
	protected float[] delaysImag;
	protected int decimation;
	protected int decimationIter = 1;
	protected float gain;
	protected float sampleRate;
	protected float lowCutOffFrequency;
	protected float highCutOffFrequency;
	protected float transitionWidth;
	protected float attenuation;

	public FilterFamily getFamily() {return family;}

	public int getNumberOfTaps() {
		return tapsCount;
	}

	public int getDecimation() {
		return decimation;
	}

	public float getGain() {
		return gain;
	}

	public float getSampleRate() {
		return sampleRate;
	}

	public float getLowCutOffFrequency() {
		return lowCutOffFrequency;
	}

	public float getHighCutOffFrequency() {
		return highCutOffFrequency;
	}

	public float getTransitionWidth() {
		return transitionWidth;
	}

	public float getAttenuation() {
		return attenuation;
	}

	public abstract int filter(SamplePacket in, SamplePacket out, int offset, int length);

	public abstract int filterReal(SamplePacket in, SamplePacket out, int offset, int length);

	//public abstract int filterReal(SamplePacket in, SamplePacket out, int offset, int length);

}
