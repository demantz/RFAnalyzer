package com.mantz_it.rfanalyzer.device.hiqsdr;

import android.util.Log;

import com.mantz_it.rfanalyzer.sdr.controls.MixerSampleRate;
import com.mantz_it.rfanalyzer.sdr.controls.RXSampleRate;

/**
 * Created by Pavel on 28.03.2017.
 */
class HiqsdrRXSampleRate implements RXSampleRate {
private HiqsdrSource hiqsdrSource;
private MixerSampleRate mixerSampleRate;
private final String LOGTAG = "[HiQSDR]: SampleRate";

public HiqsdrRXSampleRate(HiqsdrSource hiqsdrSource, MixerSampleRate mixerSampleRate) {
	this.hiqsdrSource = hiqsdrSource;
	this.mixerSampleRate = mixerSampleRate;
}

@Override
public Integer get() {
	return hiqsdrSource.config.sampleRate;
}

@Override
public void set(Integer sampleRate) {
	Log.i(LOGTAG, "set: "+sampleRate.toString());
	try {
		hiqsdrSource.config.setSampleRate(sampleRate);
		mixerSampleRate.set(sampleRate);
		hiqsdrSource.updateDeviceConfig();
		Log.d(LOGTAG, "set: successfully set to " + sampleRate.toString());
	}
	catch (IllegalArgumentException iae) {
		hiqsdrSource.reportError(iae.getMessage());
	}

}

@Override
public Integer getMax() {
	return HiqsdrHelper.MAX_SAMPLE_RATE;
}

@Override
public Integer getMin() {
	return HiqsdrHelper.MIN_SAMPLE_RATE;
}

@Override
public int getNextHigherOptimalSampleRate(int sampleRate) {
	// sample rates sorted in descending order, start checking from the end
	for (int i = HiqsdrHelper.SAMPLE_RATES.length - 1; i >= 0; --i)
		if (HiqsdrHelper.SAMPLE_RATES[i] > sampleRate) {
			Log.d(LOGTAG, "getNextHigherOptimalSampleRate: " + sampleRate + "->" + HiqsdrHelper.SAMPLE_RATES[i]);
			return HiqsdrHelper.SAMPLE_RATES[i]; // return first met higher
		}
	Log.d(LOGTAG, "getNextHigherOptimalSampleRate: " + sampleRate + "->" + HiqsdrHelper.SAMPLE_RATES[0]);
	return HiqsdrHelper.SAMPLE_RATES[0]; // if not found - return first one
}

@Override
public int getNextLowerOptimalSampleRate(int sampleRate) {
	for (int i = 0; i < HiqsdrHelper.SAMPLE_RATES.length; ++i)
		if (HiqsdrHelper.SAMPLE_RATES[i] < sampleRate) {
			Log.d(LOGTAG, "getNextLowerOptimalSampleRate: " + sampleRate + "->" + HiqsdrHelper.SAMPLE_RATES[i]);
			return HiqsdrHelper.SAMPLE_RATES[i]; // return first met lower
		}
	Log.d(LOGTAG, "getNextLowerOptimalSampleRate: " + sampleRate + "->" + HiqsdrHelper.SAMPLE_RATES[HiqsdrHelper.SAMPLE_RATES.length - 1]);
	return HiqsdrHelper.SAMPLE_RATES[HiqsdrHelper.SAMPLE_RATES.length - 1]; // if not found - return last one

}

@Override
public int[] getSupportedSampleRates() {
	return HiqsdrHelper.SAMPLE_RATES;
}

}
