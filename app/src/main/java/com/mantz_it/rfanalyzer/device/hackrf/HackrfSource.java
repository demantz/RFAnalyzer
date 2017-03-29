package com.mantz_it.rfanalyzer.device.hackrf;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.mantz_it.hackrf_android.Hackrf;
import com.mantz_it.hackrf_android.HackrfCallbackInterface;
import com.mantz_it.hackrf_android.HackrfUsbException;
import com.mantz_it.rfanalyzer.IQConverter;
import com.mantz_it.rfanalyzer.IQSource;
import com.mantz_it.rfanalyzer.R;
import com.mantz_it.rfanalyzer.SamplePacket;
import com.mantz_it.rfanalyzer.Signed8BitIQConverter;
import com.mantz_it.rfanalyzer.control.Control;
import com.mantz_it.rfanalyzer.sdr.controls.MixerFrequency;
import com.mantz_it.rfanalyzer.sdr.controls.MixerSampleRate;
import com.mantz_it.rfanalyzer.sdr.controls.RXSampleRate;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - HackRF source</h1>
 * <p>
 * Module:      HackrfSource.java
 * Description: Source class representing a HackRF device.
 *
 * @author Dennis Mantz
 *         <p>
 *         Copyright (C) 2014 Dennis Mantz
 *         License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *         <p>
 *         This library is free software; you can redistribute it and/or
 *         modify it under the terms of the GNU General Public
 *         License as published by the Free Software Foundation; either
 *         version 2 of the License, or (at your option) any later version.
 *         <p>
 *         This library is distributed in the hope that it will be useful,
 *         but WITHOUT ANY WARRANTY; without even the implied warranty of
 *         MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *         General Public License for more details.
 *         <p>
 *         You should have received a copy of the GNU General Public
 *         License along with this library; if not, write to the Free Software
 *         Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
public class HackrfSource implements IQSource, HackrfCallbackInterface {
Hackrf getHackrf() {
	return hackrf;
}

IQConverter getIqConverter() {
	return iqConverter;
}

private Hackrf hackrf = null;
private String name = null;
private Callback callback = null;
private ArrayBlockingQueue<byte[]> queue = null;
private int vgaRxGain = 0;
private int vgaTxGain = 0;
private int lnaGain = 0;
private boolean amplifier = false;
private boolean antennaPower = false;
private IQConverter iqConverter;
private static final String LOGTAG = "HackRFSource";
public static final long MIN_FREQUENCY = 1L;
public static final long MAX_FREQUENCY = 7250000000L;
public static final int MAX_SAMPLERATE = 20000000;
public static final int MIN_SAMPLERATE = 4000000;
public static final int MAX_VGA_RX_GAIN = 62;
public static final int MAX_VGA_TX_GAIN = 47;
public static final int MAX_LNA_GAIN = 40;
public static final int VGA_RX_GAIN_STEP_SIZE = 2;
public static final int VGA_TX_GAIN_STEP_SIZE = 1;
public static final int LNA_GAIN_STEP_SIZE = 8;
public static final int[] OPTIMAL_SAMPLE_RATES = {4000000, 6000000, 8000000, 10000000, 12500000, 16000000, 20000000};

private HackrfBasebandFilterControl basebandFilterControl = new HackrfBasebandFilterControl(this);

private HackrfRXFrequency rxFrequency;

private RXSampleRate rxSampleRate = null;

private MixerFrequency mixerFrequency;
private MixerSampleRate mixerSampleRate;
private final Map<Class<? extends Control>, Control> controls = new HashMap<>();

{
	controls.put(HackrfRXFrequency.class, rxFrequency);
	controls.put(RXSampleRate.class, rxSampleRate);
	controls.put(HackrfBasebandFilterControl.class, basebandFilterControl);
}

public HackrfSource() {
	iqConverter = new Signed8BitIQConverter();
	mixerFrequency = iqConverter.getControl(MixerFrequency.class);
	mixerSampleRate = iqConverter.getControl(MixerSampleRate.class);
	rxSampleRate = new HackrfRXSampleRate(this, mixerSampleRate, basebandFilterControl);
	rxFrequency = new HackrfRXFrequency(this, mixerFrequency);
}

public HackrfSource(Context context, SharedPreferences preferences) {
	this();
	rxFrequency.set(preferences.getLong(context.getString(R.string.pref_frequency), 97000000));
	rxSampleRate.set(preferences.getInt(context.getString(R.string.pref_sampleRate), HackrfSource.MAX_SAMPLERATE));
	setVgaRxGain(preferences.getInt(context.getString(R.string.pref_hackrf_vgaRxGain), HackrfSource.MAX_VGA_RX_GAIN / 2));
	setLnaGain(preferences.getInt(context.getString(R.string.pref_hackrf_lnaGain), HackrfSource.MAX_LNA_GAIN / 2));
	setAmplifier(preferences.getBoolean(context.getString(R.string.pref_hackrf_amplifier), false));
	setAntennaPower(preferences.getBoolean(context.getString(R.string.pref_hackrf_antennaPower), false));
	rxFrequency.setFrequencyShift(Integer.parseInt(
			preferences.getString(context.getString(R.string.pref_hackrf_frequencyShift), "0")));
}

/**
 * Will forward an error message to the callback object
 *
 * @param msg error message
 */
void reportError(String msg) {
	if (callback != null)
		callback.onIQSourceError(this, msg);
	else
		Log.e(LOGTAG, "reportError: Callback is null. (Error: " + msg + ")");
}

@Override
public boolean open(Context context, Callback callback) {
	int queueSize = 1000000;
	this.callback = callback;
	// Initialize the HackRF (i.e. open the USB device, which requires the user to give permissions)
	return Hackrf.initHackrf(context, this, queueSize);
}

@Override
public boolean close() {
	return true;
}

@Override
public void onHackrfReady(Hackrf hackrf) {
	this.hackrf = hackrf;
	if (callback != null)
		callback.onIQSourceReady(this);
}

@Override
public void onHackrfError(String message) {
	Log.e(LOGTAG, "Error while opening HackRF: " + message);
	reportError(message);
}

@Override
public boolean isOpen() {
	if (hackrf == null)
		return false;
	try {
		hackrf.getBoardID();    // this will only succeed if the hackrf is ready/open
		return true;    // no exception was thrown --> hackrf is open!
	}
	catch (HackrfUsbException e) {
		return false;    // exception was thrown --> hackrf is not open
	}
}

@Override
public String getName() {
	if (name == null && hackrf != null) {
		try {
			name = Hackrf.convertBoardIdToString(hackrf.getBoardID());
		}
		catch (HackrfUsbException e) {
		}
	}
	if (name != null)
		return name;
	else
		return "HackRF";
}

@Override
public HackrfSource updatePreferences(Context context, SharedPreferences preferences) {
	boolean amp = preferences.getBoolean(context.getString(R.string.pref_hackrf_amplifier), false);
	boolean antennaPower = preferences.getBoolean(context.getString(R.string.pref_hackrf_antennaPower), false);
	int frequencyShift = Integer.parseInt(preferences.getString(context.getString(R.string.pref_hackrf_frequencyShift), "0"));
	if (isAmplifierOn() != amp)
		setAmplifier(amp);
	if (isAntennaPowerOn() != antennaPower)
		setAntennaPower(antennaPower);
	if (rxFrequency.getFrequencyShift() != frequencyShift)
		rxFrequency.setFrequencyShift(frequencyShift);
	return this;
}

public int getVgaRxGain() {
	return vgaRxGain;
}

public int getVgaTxGain() {
	return vgaTxGain;
}

public int getLnaGain() {
	return lnaGain;
}

public boolean isAmplifierOn() {
	return amplifier;
}

public boolean isAntennaPowerOn() {
	return antennaPower;
}

public void setVgaRxGain(int vgaRxGain) {
	if (vgaRxGain > MAX_VGA_RX_GAIN) {
		Log.e(LOGTAG, "setVgaRxGain: Value (" + vgaRxGain + ") too high. Maximum is: " + MAX_VGA_RX_GAIN);
		return;
	}

	if (hackrf != null) {
		try {
			hackrf.setRxVGAGain(vgaRxGain);
		}
		catch (HackrfUsbException e) {
			Log.e(LOGTAG, "setVgaRxGain: Error while setting vga gain: " + e.getMessage());
			reportError("Error while setting vga gain");
			return;
		}
	}
	this.vgaRxGain = vgaRxGain;
}

public void setVgaTxGain(int vgaTxGain) {
	if (vgaTxGain > MAX_VGA_TX_GAIN) {
		Log.e(LOGTAG, "setVgaTxGain: Value (" + vgaTxGain + ") too high. Maximum is: " + MAX_VGA_TX_GAIN);
		return;
	}

	if (hackrf != null) {
		try {
			hackrf.setTxVGAGain(vgaTxGain);
		}
		catch (HackrfUsbException e) {
			Log.e(LOGTAG, "setVgaTxGain: Error while setting vga gain: " + e.getMessage());
			reportError("Error while setting vga gain");
			return;
		}
	}
	this.vgaTxGain = vgaTxGain;
}

public void setLnaGain(int lnaGain) {
	if (lnaGain > MAX_LNA_GAIN) {
		Log.e(LOGTAG, "setLnaGain: Value (" + lnaGain + ") too high. Maximum is: " + MAX_LNA_GAIN);
		return;
	}

	if (hackrf != null) {
		try {
			hackrf.setRxLNAGain(lnaGain);
		}
		catch (HackrfUsbException e) {
			Log.e(LOGTAG, "setLnaGain: Error while setting lna gain: " + e.getMessage());
			reportError("Error while setting lna gain");
			return;
		}
	}
	this.lnaGain = lnaGain;
}

public void setAmplifier(boolean amplifier) {
	if (hackrf != null) {
		try {
			hackrf.setAmp(amplifier);
		}
		catch (HackrfUsbException e) {
			Log.e(LOGTAG, "setAmplifier: Error while setting amplifier: " + e.getMessage());
			reportError("Error while setting amplifier state");
			return;
		}
	}
	this.amplifier = amplifier;
}

public void setAntennaPower(boolean antennaPower) {
	if (hackrf != null) {
		try {
			hackrf.setAntennaPower(antennaPower);
		}
		catch (HackrfUsbException e) {
			Log.e(LOGTAG, "setAntennaPower: Error while setting antenna power: " + e.getMessage());
			reportError("Error while setting antenna power state");
			return;
		}
	}
	this.antennaPower = antennaPower;
}

@Override
public int getSampledPacketSize() {
	if (hackrf != null)
		return hackrf.getPacketSize();
	else {
		Log.e(LOGTAG, "getSampledPacketSize: Hackrf instance is null");
		return 0;
	}
}

@Override
public byte[] getPacket(int timeout) {
	if (queue != null && hackrf != null) {
		try {
			byte[] packet = queue.poll(timeout, TimeUnit.MILLISECONDS);
			if (packet == null && (hackrf.getTransceiverMode() != Hackrf.HACKRF_TRANSCEIVER_MODE_RECEIVE)) {
				Log.e(LOGTAG, "getPacket: HackRF is not in receiving mode!");
				reportError("HackRF stopped receiving");
			}
			return packet;
		}
		catch (InterruptedException e) {
			Log.e(LOGTAG, "getPacket: Interrupted while waiting on queue");
			return null;
		}
	} else {
		Log.e(LOGTAG, "getPacket: Queue is null");
		return null;
	}
}

@Override
public void returnPacket(byte[] buffer) {
	if (hackrf != null)
		hackrf.returnBufferToBufferPool(buffer);
	else {
		Log.e(LOGTAG, "returnPacket: Hackrf instance is null");
	}
}

@Override
public void startSampling() {
	if (hackrf != null) {
		try {
			hackrf.setSampleRate(rxSampleRate.get(), 1);
			hackrf.setFrequency(rxFrequency.get());
			hackrf.setBasebandFilterBandwidth(basebandFilterControl.getBandwidth());
			hackrf.setRxVGAGain(vgaRxGain);
			hackrf.setRxLNAGain(lnaGain);
			hackrf.setAmp(amplifier);
			hackrf.setAntennaPower(antennaPower);
			this.queue = hackrf.startRX();
			Log.i(LOGTAG, String.format(
					"startSampling: Started HackRF with: "
					+ "sampleRate=%d "
					+ "frequency=%s "
					+ "basebandFilterWidth=%s "
					+ "rxVgaGain=%d "
					+ "lnaGain=%d "
					+ "amplifier=%s "
					+ "antennaPower=%s",
					rxSampleRate.get(), rxFrequency.get(), basebandFilterControl.getBandwidth(),
					vgaRxGain, lnaGain, amplifier, antennaPower));
		}
		catch (HackrfUsbException e) {
			Log.e(LOGTAG, "startSampling: Error while set up hackrf: " + e.getMessage());
		}
	} else {
		Log.e(LOGTAG, "startSampling: Hackrf instance is null");
	}
}

@Override
public void stopSampling() {
	if (hackrf != null) {
		try {
			hackrf.stop();
		}
		catch (HackrfUsbException e) {
			Log.e(LOGTAG, "stopSampling: Error while tear down hackrf: " + e.getMessage());
		}
	} else {
		Log.e(LOGTAG, "stopSampling: Hackrf instance is null");
	}
}

@Override
public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
	return this.iqConverter.fillPacketIntoSamplePacket(packet, samplePacket);
}

public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
	return this.iqConverter.mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency);
}

/**
 * Will empty the queue
 */
public void flushQueue() {
	byte[] buffer;

	if (hackrf == null || queue == null)
		return; // nothing to flush...

	for (int i = 0; i < queue.size(); i++) {
		buffer = queue.poll();
		if (buffer == null)
			return; // we are done; the queue is empty.
		hackrf.returnBufferToBufferPool(buffer);
	}
}

@Override
public <T extends Control> T getControl(Class<T> clazz) {
	return (T) controls.get(clazz);
}

@Override
public Collection<Control> getControls() {
	return Collections.unmodifiableCollection(controls.values());
}

}
