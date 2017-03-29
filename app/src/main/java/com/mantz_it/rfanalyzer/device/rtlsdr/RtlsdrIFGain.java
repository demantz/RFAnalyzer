package com.mantz_it.rfanalyzer.device.rtlsdr;

import android.util.Log;

import com.mantz_it.rfanalyzer.sdr.controls.IntermediateFrequencyGainControl;

/**
 * Created by Pavel on 21.03.2017.
 */

class RtlsdrIFGain implements IntermediateFrequencyGainControl {
private static final String LOGTAG = "[RTL-SDR]:IFGain";
private int value = 0;
private int index = 0;
private RtlsdrSource source;

RtlsdrIFGain(RtlsdrSource source) {this.source = source;}

@Override
public void set(Integer value) {
	if (source.isOpen() && source.getTuner() == RtlsdrTuner.E4000) {
		if (!source.getCommandThread().executeCommand(RtlsdrCommand.SET_IF_GAIN, (short) 0, value.shortValue())) {
			Log.e(LOGTAG, "setIFGain: failed.");
		}
	}
	this.value = value;
}

@Override
public Integer get() {
	return value;
}

private Integer getStep() {
	return 3;
}

@Override
public Integer setByIndex(int index) {
	if (index < 0)
		index = 0;
	if (index > size())
		index = size() - 1;
	int value = valueAt(index);
	this.index = index;
	set(value);
	return value;
}

@Override
public int getIndex() {
	return index;
}

@Override
public Integer valueAt(int index) {
	return (index + 1) * getStep();
}

@Override
public int size() {
	return source.getTuner() == RtlsdrTuner.E4000 ? 54 : 0;
}

int[] getPossibleIFGainValues() {
	if (source.getTuner() == RtlsdrTuner.E4000) {
		int[] ifGainValues = new int[54];
		for (int i = 0; i < ifGainValues.length; i++)
			ifGainValues[i] = i + 3;
		return ifGainValues;
	} else {
		return new int[]{0};
	}
}
}
