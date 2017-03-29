package com.mantz_it.rfanalyzer.device.rtlsdr;

import com.mantz_it.rfanalyzer.sdr.controls.ManualGainSwitchControl;

/**
 * Created by Pavel on 21.03.2017.
 */

class RtlsdrManualGainSwitch implements ManualGainSwitchControl {
private RtlsdrSource source;
private boolean value = false;

RtlsdrManualGainSwitch(RtlsdrSource source) {this.source = source;}

@Override
public boolean set(boolean value) {
	if (source.getCommandThread().executeCommand(RtlsdrCommand.SET_GAIN_MODE, value)) {
		this.value = value;
		return true;
	}
	return false;
}

@Override
public boolean get() {
	return value;
}

@Override
public boolean on() {
	if (source.getCommandThread().executeCommand(RtlsdrCommand.SET_GAIN_MODE, true)) {
		this.value = true;
		return true;
	}
	return false;
}

@Override
public boolean off() {
	if (source.getCommandThread().executeCommand(RtlsdrCommand.SET_GAIN_MODE, false)) {
		this.value = false;
		return true;
	}
	return false;
}
}
