package com.mantz_it.rfanalyzer.device.hackrf;

import android.util.Log;

import com.mantz_it.hackrf_android.Hackrf;
import com.mantz_it.hackrf_android.HackrfUsbException;
import com.mantz_it.rfanalyzer.sdr.controls.MixerFrequency;

/**
 * Created by Pavel on 21.03.2017.
 */
class HackrfRXFrequency implements com.mantz_it.rfanalyzer.sdr.controls.RXFrequency {
private static final String LOGTAG = "[HackRF]:RXFrequency";

private long frequency = 0;
private int frequencyShift = 0;    // virtually shift the frequency according to an external up/down-converter

private HackrfSource hackrfSource;
private Hackrf device;
private MixerFrequency mixerFrequency;

public HackrfRXFrequency(HackrfSource hackrfSource, MixerFrequency mixerFrequency) {
	this.hackrfSource = hackrfSource;
	this.device = hackrfSource.getHackrf();
	this.mixerFrequency = mixerFrequency;
}

@Override
public Long get() {
	return this.frequency + this.frequencyShift;
}

@Override
public void set(Long frequency) {
	long actualFrequency = frequency - this.frequencyShift;
	// re-tune the hackrf:
	if (device != null) {
		try {
			device.setFrequency(actualFrequency);
		}
		catch (HackrfUsbException e) {
			Log.e(LOGTAG, "setFrequency: Error while setting frequency: " + e.getMessage());
			hackrfSource.reportError("Error while setting frequency");
			return;
		}
	}

	// Flush the queue:
	hackrfSource.flushQueue();

	// Store the new frequency
	frequency = actualFrequency;
	mixerFrequency.set(frequency); // xxx: probably a bug here (+frequencyShift)
}

@Override
public Long getMax() {
	return HackrfSource.MAX_FREQUENCY + frequencyShift;
}

@Override
public Long getMin() {
	return HackrfSource.MIN_FREQUENCY + frequencyShift;
}

public int getFrequencyShift() {
	return frequencyShift;
}

public void setFrequencyShift(int frequencyShift) {
	this.frequencyShift = frequencyShift;
	this.mixerFrequency.set(frequency + frequencyShift);
}
}
