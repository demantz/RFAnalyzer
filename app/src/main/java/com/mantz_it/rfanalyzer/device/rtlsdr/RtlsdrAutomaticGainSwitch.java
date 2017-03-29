package com.mantz_it.rfanalyzer.device.rtlsdr;

import android.util.Log;

import com.mantz_it.rfanalyzer.sdr.controls.AutomaticGainSwitchControl;

/**
 * Created by Pavel on 21.03.2017.
 */

class RtlsdrAutomaticGainSwitch implements AutomaticGainSwitchControl {
private static final String LOGTAG = "[RTL-SDR]:AGC Switch";
private RtlsdrSource source;
private boolean value = false;

RtlsdrAutomaticGainSwitch(RtlsdrSource source) {this.source = source;}

@Override
public boolean set(boolean value) {
	if (source.isOpen()) {
		if (!source.getCommandThread().executeCommand(RtlsdrCommand.SET_AGC_MODE, value)) {
			Log.e(LOGTAG, String.format("set(%s): failed.", Boolean.toString(value)));
			return false;
		}
	}
	this.value = value;
	return true;
}

@Override
public boolean get() {
	return value;
}

@Override
public boolean on() {
	if (source.isOpen()) {
		if (!source.getCommandThread().executeCommand(RtlsdrCommand.SET_AGC_MODE, true)) {
			Log.e(LOGTAG, "on(): failed.");
			return false;
		}
	}
	this.value = true;
	return true;
}

@Override
public boolean off() {
	if (source.isOpen()) {
		if (!source.getCommandThread().executeCommand(RtlsdrCommand.SET_AGC_MODE, false)) {
			Log.e(LOGTAG, "off(): failed.");
			return false;
		}
	}
	this.value = false;
	return true;
}
}
