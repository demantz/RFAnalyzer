package com.mantz_it.rfanalyzer.device.hiqsdr;

import android.util.Log;

import com.mantz_it.rfanalyzer.sdr.controls.MixerFrequency;
import com.mantz_it.rfanalyzer.sdr.controls.RXFrequency;

/**
 * Created by Pavel on 28.03.2017.
 */
class HiqsdrRXFrequency implements RXFrequency {
private static final String LOGTAG = "[HiQSDR]: RXFrequency";
// fixme: this frequencyShift is quite a mess, analyze and test it
private HiqsdrSource hiqsdrSource;
private MixerFrequency mixerFrequency;

public HiqsdrRXFrequency(HiqsdrSource hiqsdrSource, MixerFrequency mixerFrequency) {
	this.hiqsdrSource = hiqsdrSource;
	this.mixerFrequency = mixerFrequency;
}

@Override
public Long get() {
	return hiqsdrSource.config.rxTuneFrequency;
}

@Override
public void set(Long frequency) {
	Log.i(LOGTAG, "set: " + frequency.toString());
	hiqsdrSource.config.setRxFrequency(frequency);
	mixerFrequency.set(frequency);
	hiqsdrSource.updateDeviceConfig();
}

@Override
public Long getMax() {
	return HiqsdrHelper.MAX_FREQUENCY;
}

@Override
public Long getMin() {
	return HiqsdrHelper.MIN_FREQUENCY;
}

@Override
public int getFrequencyShift() {
	return 0;
}

@Override
public void setFrequencyShift(int frequencyShift) {
	throw new UnsupportedOperationException();
}
}
