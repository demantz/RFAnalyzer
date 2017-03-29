package com.mantz_it.rfanalyzer.device.rtlsdr;

import android.util.Log;

import com.mantz_it.rfanalyzer.sdr.controls.FrequencyCorrectionControl;

/**
 * Created by Pavel on 28.03.2017.
 */

class RtlsdrFrequencyCorrection implements FrequencyCorrectionControl {
private static final String LOGTAG = "[RTL-SDR]:FreqCorr";
private int value = 0;
private final RtlsdrSource source;

RtlsdrFrequencyCorrection(RtlsdrSource source) {this.source = source;}

@Override
public void set(Integer value) {
	if (source.isOpen()) {
		if (!source.getCommandThread().executeCommand(RtlsdrCommand.SET_FREQ_CORRECTION, value)) {
			Log.e(LOGTAG, "setFrequencyCorrection: failed.");
			return;
		}
	}
	this.value = value;
}

@Override
public Integer get() {
	return value;
}
}
