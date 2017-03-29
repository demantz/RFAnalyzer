package com.mantz_it.rfanalyzer.device.rtlsdr;

import android.util.Log;

import com.mantz_it.rfanalyzer.sdr.controls.MixerSampleRate;
import com.mantz_it.rfanalyzer.sdr.controls.RXSampleRate;

/**
 * Created by Pavel on 21.03.2017.
 */
class RtlsdrRXSampleRate implements RXSampleRate {
private static final String LOGTAG = "[RTL-SDR]:SampleRate";

private int sampleRate = 0;

private RtlsdrSource rtlsdrSource;
private MixerSampleRate mixerSampleRate;

RtlsdrRXSampleRate(RtlsdrSource rtlsdrSource, MixerSampleRate mixerSampleRate) {
	this.rtlsdrSource = rtlsdrSource;
	this.mixerSampleRate = mixerSampleRate;
}

@Override
public Integer get() {
	return sampleRate;
}

@Override
public void set(Integer sampleRate) {
	if (rtlsdrSource.isOpen()) {
		if (sampleRate < getMin() || sampleRate > getMax()) {
			Log.e(LOGTAG, "set: Sample rate out of valid range: " + sampleRate);
			return;
		}

		if (!rtlsdrSource.getCommandThread().executeCommand(RtlsdrCommand.SET_SAMPLERATE, sampleRate)) {
			Log.e(LOGTAG, "setSampleRate: failed.");
		}
	}

	// Flush the queue:
	rtlsdrSource.flushQueue();

	this.sampleRate = sampleRate;
	mixerSampleRate.set(sampleRate);
}

@Override
public Integer getMax() {
	return RtlsdrSource.OPTIMAL_SAMPLE_RATES[RtlsdrSource.OPTIMAL_SAMPLE_RATES.length - 1];
}

@Override
public Integer getMin() {
	return RtlsdrSource.OPTIMAL_SAMPLE_RATES[0];
}

@Override
public int getNextHigherOptimalSampleRate(int sampleRate) {
	for (int opt : RtlsdrSource.OPTIMAL_SAMPLE_RATES) {
		if (sampleRate < opt)
			return opt;
	}
	return RtlsdrSource.OPTIMAL_SAMPLE_RATES[RtlsdrSource.OPTIMAL_SAMPLE_RATES.length - 1];
}

@Override
public int getNextLowerOptimalSampleRate(int sampleRate) {
	for (int i = 1; i < RtlsdrSource.OPTIMAL_SAMPLE_RATES.length; i++) {
		if (sampleRate <= RtlsdrSource.OPTIMAL_SAMPLE_RATES[i])
			return RtlsdrSource.OPTIMAL_SAMPLE_RATES[i - 1];
	}
	return RtlsdrSource.OPTIMAL_SAMPLE_RATES[RtlsdrSource.OPTIMAL_SAMPLE_RATES.length - 1];
}

@Override
public int[] getSupportedSampleRates() {
	return RtlsdrSource.OPTIMAL_SAMPLE_RATES;
}
}
