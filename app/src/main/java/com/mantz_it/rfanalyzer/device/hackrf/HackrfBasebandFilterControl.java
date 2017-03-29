package com.mantz_it.rfanalyzer.device.hackrf;

import android.util.Log;

import com.mantz_it.hackrf_android.Hackrf;
import com.mantz_it.hackrf_android.HackrfUsbException;
import com.mantz_it.rfanalyzer.control.Control;

/**
 * Created by Pavel on 21.03.2017.
 */

public class HackrfBasebandFilterControl implements Control {
private static final String LOGTAG = "[HackRF]:BasebandFilter";
private int basebandFilterWidth = 0;
private boolean automaticBBFilterCalculation = true;
private HackrfSource source;
private Hackrf device;

HackrfBasebandFilterControl(HackrfSource source) {
	this.source = source;
	this.device = source.getHackrf();
}

public int getBandwidth() {
	return basebandFilterWidth;
}

public boolean isAutomaticBBFilterCalculation() {
	return automaticBBFilterCalculation;
}

public void setAutomaticBBFilterCalculation(boolean automaticBBFilterCalculation) {
	this.automaticBBFilterCalculation = automaticBBFilterCalculation;
}

public void setBandwidth(int basebandFilterWidth) {
	this.basebandFilterWidth = device.computeBasebandFilterBandwidth(basebandFilterWidth);
	Log.d(LOGTAG, "setBandWidth: Setting BB  filter width to " + this.basebandFilterWidth);
	if (device != null) {
		try {
			device.setBasebandFilterBandwidth(this.basebandFilterWidth);
		}
		catch (HackrfUsbException e) {
			Log.e(LOGTAG, "setBandWidth: Error while setting base band filter width: " + e.getMessage());
			source.reportError("Error while setting base band  filter width");
		}
	}
}
}
