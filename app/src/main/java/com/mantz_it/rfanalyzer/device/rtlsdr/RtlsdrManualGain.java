package com.mantz_it.rfanalyzer.device.rtlsdr;

import android.util.Log;

import com.mantz_it.rfanalyzer.sdr.controls.ManualGainControl;

/**
 * Created by Pavel on 21.03.2017.
 */
// TODO: tuner changed event listener, gain changed event listener
class RtlsdrManualGain implements ManualGainControl {
private static final String LOGTAG = "[RTL-SDR]:ManualGain";
private int value = 0;
private int index = -1;

private RtlsdrSource source;

RtlsdrManualGain(RtlsdrSource source) {this.source = source;}

@Override
public void set(Integer value) {
	if (source.isOpen()) {
		if (!source.getCommandThread().executeCommand(RtlsdrCommand.SET_GAIN, value)) {
			Log.e(LOGTAG, "set: failed.");
		}
	}
	this.value = value;
	// todo: lookup index, util methods for set and lookup
	this.index = -1;
}

@Override
public Integer get() {
	return value;
}

@Override
public Integer setByIndex(int index) {
	int[] values = source.getTuner().getGainValues();
	if (index < 0)
		index = 0;
	if (index >= values.length)
		index = values.length - 1;
	set(values[index]);
	if (value == values[index])
		this.index = index;
	return value;
}

@Override
public int getIndex() {
	return index;
}

@Override
public Integer valueAt(int index) throws IndexOutOfBoundsException {
	return source.getTuner().getGainValues()[index];
}

@Override
public int size() {
	return source.getTuner().getGainValues().length;
}


public int[] getPossibleIFGainValues() {
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
