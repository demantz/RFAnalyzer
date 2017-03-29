package com.mantz_it.rfanalyzer.device.hackrf;

import android.util.Log;

import com.mantz_it.hackrf_android.Hackrf;
import com.mantz_it.hackrf_android.HackrfUsbException;
import com.mantz_it.rfanalyzer.sdr.controls.MixerSampleRate;
import com.mantz_it.rfanalyzer.sdr.controls.RXSampleRate;

/**
 * Created by Pavel on 21.03.2017.
 */
class HackrfRXSampleRate implements RXSampleRate {
private static final String LOGTAG = "[HackRf]:RxSampleRate";
private int sampleRate;
private Hackrf device;
private HackrfSource hackrfSource;
private MixerSampleRate mixerSampleRate;
private HackrfBasebandFilterControl bbFilter;

public HackrfRXSampleRate(HackrfSource hackrfSource, MixerSampleRate mixerSampleRate, HackrfBasebandFilterControl bbFilter) {
	this.hackrfSource = hackrfSource;
	this.device = hackrfSource.getHackrf();
	this.mixerSampleRate = mixerSampleRate;
	this.bbFilter = bbFilter;
}

@Override
public Integer getMax() {
	return HackrfSource.MAX_SAMPLERATE;
}

@Override
public Integer getMin() {
	return HackrfSource.MIN_SAMPLERATE;
}

@Override
public Integer get() {
	return sampleRate;
}

@Override
public int getNextHigherOptimalSampleRate(int sampleRate) {
	for (int opt : HackrfSource.OPTIMAL_SAMPLE_RATES) {
		if (sampleRate < opt)
			return opt;
	}
	return HackrfSource.OPTIMAL_SAMPLE_RATES[HackrfSource.OPTIMAL_SAMPLE_RATES.length - 1];
}

@Override
public int getNextLowerOptimalSampleRate(int sampleRate) {
	for (int i = 1; i < HackrfSource.OPTIMAL_SAMPLE_RATES.length; i++) {
		if (sampleRate <= HackrfSource.OPTIMAL_SAMPLE_RATES[i])
			return HackrfSource.OPTIMAL_SAMPLE_RATES[i - 1];
	}
	return HackrfSource.OPTIMAL_SAMPLE_RATES[HackrfSource.OPTIMAL_SAMPLE_RATES.length - 1];
}

@Override
public int[] getSupportedSampleRates() {
	return HackrfSource.OPTIMAL_SAMPLE_RATES;
}

@Override
public void set(Integer sampleRate) {
	if (bbFilter.isAutomaticBBFilterCalculation())
		bbFilter.setBandwidth((int) (sampleRate * 0.75));

	// set the hackrf to the new sample rate:

	if (device != null) {
		try {
			device.setSampleRate(sampleRate, 1);
			device.setBasebandFilterBandwidth(bbFilter.getBandwidth());
		}
		catch (HackrfUsbException e) {
			Log.e(LOGTAG, "set: Error while setting sample rate: " + e.getMessage());
			hackrfSource.reportError("Error while setting sample rate");
			return;
		}
	}

	// Flush the queue
	hackrfSource.flushQueue();
	Log.d(LOGTAG, "set: setting sample rate to " + sampleRate);
	this.sampleRate = sampleRate;
	mixerSampleRate.set(sampleRate);
}

}
