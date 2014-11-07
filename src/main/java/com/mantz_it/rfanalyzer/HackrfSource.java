package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.util.Log;

import com.mantz_it.hackrf_android.Hackrf;
import com.mantz_it.hackrf_android.HackrfCallbackInterface;
import com.mantz_it.hackrf_android.HackrfUsbException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - HackRF source</h1>
 *
 * Module:      HackrfSource.java
 * Description: Source class representing a HackRF device.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2014 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
public class HackrfSource implements IQSourceInterface, HackrfCallbackInterface {
	private Hackrf hackrf = null;
	private Callback callback = null;
	private ArrayBlockingQueue<byte[]> queue = null;
	private long frequency = 0;
	private int sampleRate = 0;
	private int basebandFilterWidth = 0;
	private boolean automaticBBFilterCalculation = true;
	private int vgaRxGain = 0;
	private int vgaTxGain = 0;
	private int lnaGain = 0;
	private boolean amplifier = false;
	private boolean antennaPower = false;
	private static final String LOGTAG = "HackRFSource";
	public static final long MIN_FREQUENCY = 1l;
	public static final long MAX_FREQUENCY = 7250000000l;
	public static final int MAX_SAMPLERATE = 20000000;
	public static final int MIN_SAMPLERATE = 4000000;
	public static final int MAX_VGA_RX_GAIN = 62;
	public static final int MAX_VGA_TX_GAIN = 47;
	public static final int MAX_LNA_GAIN = 40;
	public static final int VGA_RX_GAIN_STEP_SIZE = 2;
	public static final int VGA_TX_GAIN_STEP_SIZE = 1;
	public static final int LNA_GAIN_STEP_SIZE = 8;
	public static final int[] OPTIMAL_SAMPLE_RATES = { 4000000, 6000000, 8000000, 10000000, 12500000, 16000000, 20000000};
	public float[] lookupTable = null;					// Lookup table to transform IQ bytes into doubles
	public float[][] cosineRealLookupTable = null;		// Lookup table to transform IQ bytes into frequency shifted doubles
	public float[][] cosineImagLookupTable = null;		// Lookup table to transform IQ bytes into frequency shifted doubles
	public int cosineFrequency;							// Frequency of the cosine that is mixed to the signal
	public int cosineIndex;								// current index within the cosine
	public static final int MAX_COSINE_LENGTH = 50;		// Max length of the cosine lookup table

	/**
	 * Will forward an error message to the callback object
	 *
	 * @param msg	error message
	 */
	private void reportError(String msg) {
		if(callback != null)
			callback.onIQSourceError(this,msg);
		else
			Log.e(LOGTAG,"reportError: Callback is null. (Error: " + msg + ")");
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
		if(callback != null)
			callback.onIQSourceReady(this);
	}

	@Override
	public void onHackrfError(String message) {
		Log.e(LOGTAG, "Error while opening HackRF: " + message);
		reportError(message);
	}

	@Override
	public boolean isOpen() {
		if(hackrf == null)
			return false;
		try {
			hackrf.getBoardID();	// this will only succeed if the hackrf is ready/open
			return true;	// no exception was thrown --> hackrf is open!
		} catch (HackrfUsbException e) {
			return false;	// exception was thrown --> hackrf is not open
		}
	}

	@Override
	public String getName() {
		if(hackrf != null) {
			try {
				return Hackrf.convertBoardIdToString(hackrf.getBoardID());
			} catch (HackrfUsbException e) {
			}
		}
		return "HackRF";
	}

	@Override
	public long getFrequency() {
		return frequency;
	}

	@Override
	public void setFrequency(long frequency) {
		// re-tune the hackrf:
		if(hackrf != null) {
			try {
				hackrf.setFrequency(frequency);
			} catch (HackrfUsbException e) {
				Log.e(LOGTAG, "setFrequency: Error while setting frequency: " + e.getMessage());
				reportError("Error while setting frequency");
				return;
			}
		}

		// Flush the queue:
		this.flushQueue();

		// Store the new frequency
		this.frequency = frequency;
	}

	@Override
	public long getMaxFrequency() {
		return MAX_FREQUENCY;
	}

	@Override
	public long getMinFrequency() {
		return MIN_FREQUENCY;
	}

	@Override
	public int getMaxSampleRate() {
		return MAX_SAMPLERATE;
	}

	@Override
	public int getMinSampleRate() {
		return MIN_SAMPLERATE;
	}

	@Override
	public int getSampleRate() {
		return sampleRate;
	}

	@Override
	public int getNextHigherOptimalSampleRate(int sampleRate) {
		for (int opt : OPTIMAL_SAMPLE_RATES) {
			if (sampleRate < opt)
				return opt;
		}
		return OPTIMAL_SAMPLE_RATES[OPTIMAL_SAMPLE_RATES.length-1];
	}

	@Override
	public int getNextLowerOptimalSampleRate(int sampleRate) {
		for (int i = 1; i < OPTIMAL_SAMPLE_RATES.length; i++) {
			if(sampleRate <= OPTIMAL_SAMPLE_RATES[i])
				return OPTIMAL_SAMPLE_RATES[i-1];
		}
		return OPTIMAL_SAMPLE_RATES[OPTIMAL_SAMPLE_RATES.length-1];
	}

	@Override
	public void setSampleRate(int sampleRate) {
		if(isAutomaticBBFilterCalculation())
			setBasebandFilterWidth((int)(sampleRate * 0.75));

		// set the hackrf to the new sample rate:
		if(hackrf != null) {
			try {
				hackrf.setSampleRate(sampleRate,1);
				hackrf.setBasebandFilterBandwidth(basebandFilterWidth);
			} catch (HackrfUsbException e) {
				Log.e(LOGTAG, "setSampleRate: Error while setting sample rate: " + e.getMessage());
				reportError("Error while setting sample rate");
				return;
			}
		}

		// Flush the queue
		this.flushQueue();
		Log.d(LOGTAG,"setSampleRate: setting sample rate to " + sampleRate);
		this.sampleRate = sampleRate;
	}

	public int getBasebandFilterWidth() {
		return basebandFilterWidth;
	}

	public boolean isAutomaticBBFilterCalculation() {
		return automaticBBFilterCalculation;
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

	public void setBasebandFilterWidth(int basebandFilterWidth) {
		this.basebandFilterWidth = hackrf.computeBasebandFilterBandwidth(basebandFilterWidth);
		Log.d(LOGTAG,"setBasebandFilterWidth: Setting BB filter width to " + this.basebandFilterWidth);
		if(hackrf != null) {
			try {
				hackrf.setBasebandFilterBandwidth(this.basebandFilterWidth);
			} catch (HackrfUsbException e) {
				Log.e(LOGTAG, "setBasebandFilterWidth: Error while setting base band filter width: " + e.getMessage());
				reportError("Error while setting base band filter width");
			}
		}
	}

	public void setAutomaticBBFilterCalculation(boolean automaticBBFilterCalculation) {
		this.automaticBBFilterCalculation = automaticBBFilterCalculation;
	}

	public void setVgaRxGain(int vgaRxGain) {
		if(vgaRxGain > MAX_VGA_RX_GAIN) {
			Log.e(LOGTAG, "setVgaRxGain: Value (" + vgaRxGain + ") too high. Maximum is: " + MAX_VGA_RX_GAIN);
			return;
		}

		if(hackrf != null) {
			try {
				hackrf.setRxVGAGain(vgaRxGain);
			} catch (HackrfUsbException e) {
				Log.e(LOGTAG, "setVgaRxGain: Error while setting vga gain: " + e.getMessage());
				reportError("Error while setting vga gain");
				return;
			}
		}
		this.vgaRxGain = vgaRxGain;
	}

	public void setVgaTxGain(int vgaTxGain) {
		if(vgaTxGain > MAX_VGA_TX_GAIN) {
			Log.e(LOGTAG, "setVgaTxGain: Value (" + vgaTxGain + ") too high. Maximum is: " + MAX_VGA_TX_GAIN);
			return;
		}

		if(hackrf != null) {
			try {
				hackrf.setTxVGAGain(vgaTxGain);
			} catch (HackrfUsbException e) {
				Log.e(LOGTAG, "setVgaTxGain: Error while setting vga gain: " + e.getMessage());
				reportError("Error while setting vga gain");
				return;
			}
		}
		this.vgaTxGain = vgaTxGain;
	}

	public void setLnaGain(int lnaGain) {
		if(lnaGain > MAX_LNA_GAIN) {
			Log.e(LOGTAG, "setLnaGain: Value (" + lnaGain + ") too high. Maximum is: " + MAX_LNA_GAIN);
			return;
		}

		if(hackrf != null) {
			try {
				hackrf.setRxLNAGain(lnaGain);
			} catch (HackrfUsbException e) {
				Log.e(LOGTAG, "setLnaGain: Error while setting lna gain: " + e.getMessage());
				reportError("Error while setting lna gain");
				return;
			}
		}
		this.lnaGain = lnaGain;
	}

	public void setAmplifier(boolean amplifier) {
		if(hackrf != null) {
			try {
				hackrf.setAmp(amplifier);
			} catch (HackrfUsbException e) {
				Log.e(LOGTAG, "setAmplifier: Error while setting amplifier: " + e.getMessage());
				reportError("Error while setting amplifier state");
				return;
			}
		}
		this.amplifier = amplifier;
	}

	public void setAntennaPower(boolean antennaPower) {
		if(hackrf != null) {
			try {
				hackrf.setAntennaPower(antennaPower);
			} catch (HackrfUsbException e) {
				Log.e(LOGTAG, "setAntennaPower: Error while setting antenna power: " + e.getMessage());
				reportError("Error while setting antenna power state");
				return;
			}
		}
		this.antennaPower = antennaPower;
	}

	@Override
	public int getPacketSize() {
		if(hackrf != null)
			return hackrf.getPacketSize();
		else {
			Log.e(LOGTAG, "getPacketSize: Hackrf instance is null");
			return 0;
		}
	}

	@Override
	public byte[] getPacket(int timeout) {
		if(queue != null)
			try {
				return queue.poll(timeout, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "getPacket: Interrupted while waiting on queue");
				return null;
			}
		else {
			Log.e(LOGTAG, "getPacket: Queue is null");
			return null;
		}
	}

	@Override
	public void returnPacket(byte[] buffer) {
		if(hackrf != null)
			hackrf.returnBufferToBufferPool(buffer);
		else {
			Log.e(LOGTAG, "returnPacket: Hackrf instance is null");
		}
	}

	@Override
	public void startSampling() {
		if(hackrf != null) {
			try {
				hackrf.setSampleRate(sampleRate, 1);
				hackrf.setFrequency(frequency);
				hackrf.setBasebandFilterBandwidth(basebandFilterWidth);
				hackrf.setRxVGAGain(vgaRxGain);
				hackrf.setRxLNAGain(lnaGain);
				hackrf.setAmp(amplifier);
				hackrf.setAntennaPower(antennaPower);
				this.queue = hackrf.startRX();
				Log.i(LOGTAG, "startSampling: Started HackRF with: sampleRate="+sampleRate+" frequency="+frequency
							+ " basebandFilterWidth="+basebandFilterWidth+" rxVgaGain="+vgaRxGain+" lnaGain="+lnaGain
							+ " amplifier="+amplifier+" antennaPower="+antennaPower);
			} catch (HackrfUsbException e) {
				Log.e(LOGTAG, "startSampling: Error while set up hackrf: " + e.getMessage());
			}
		} else {
			Log.e(LOGTAG, "startSampling: Hackrf instance is null");
		}
	}

	@Override
	public void stopSampling() {
		if(hackrf != null) {
			try {
				hackrf.stop();
			} catch (HackrfUsbException e) {
				Log.e(LOGTAG, "stopSampling: Error while tear down hackrf: " + e.getMessage());
			}
		} else {
			Log.e(LOGTAG, "stopSampling: Hackrf instance is null");
		}
	}

	@Override
	public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
		/**
		 * The HackRF delivers samples in the following format:
		 * The bytes are interleaved, 8-bit, signed IQ samples (in-phase
		 *  component first, followed by the quadrature component):
		 *
		 *  [--------- first sample ----------]   [-------- second sample --------]
		 *         I                  Q                  I                Q ...
		 *  receivedBytes[0]   receivedBytes[1]   receivedBytes[2]       ...
		 */

		// If lookupTable is null, we create it:
		if(lookupTable == null) {
			lookupTable = new float[256];
			for (int i = 0; i < 256; i++)
				lookupTable[i] = (i-128) / 128.0f;
		}

		int capacity = samplePacket.capacity();
		int count = 0;
		int startIndex = samplePacket.size();
		float[] re = samplePacket.re();
		float[] im = samplePacket.im();
		for (int i = 0; i < packet.length; i+=2) {
			re[startIndex+count] = lookupTable[packet[i]+128];
			im[startIndex+count] = lookupTable[packet[i+1]+128];
			count++;
			if(startIndex+count >= capacity)
				break;
		}
		samplePacket.setSize(samplePacket.size()+count);	// update the size of the sample packet
		samplePacket.setSampleRate(sampleRate);				// update the sample rate
		samplePacket.setFrequency(frequency);				// update the frequency
		return count;
	}

	public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
		int mixFrequency = (int)(frequency - channelFrequency);
		// If mix frequency is too low, just add the sample rate (sampled spectrum is periodic):
		if(mixFrequency == 0 || (sampleRate / Math.abs(mixFrequency) > MAX_COSINE_LENGTH))
			mixFrequency += sampleRate;

		// If lookupTable is null or is invalid, we create it:
		if(cosineRealLookupTable == null || cosineFrequency != mixFrequency) {
			cosineFrequency = mixFrequency;
			// look for the best fitting array size to hold one or more full cosine cycles:
			double cycleLength = sampleRate / Math.abs((double)mixFrequency);
			int bestLength = (int) cycleLength;
			double bestLengthError = Math.abs(bestLength-cycleLength);
			for (int i = 1; i*cycleLength < MAX_COSINE_LENGTH ; i++) {
				if(Math.abs(i*cycleLength - (int)(i*cycleLength)) < bestLengthError) {
					bestLength = (int)(i*cycleLength);
					bestLengthError = Math.abs(bestLength - (i*cycleLength));
				}
			}
//			Log.d(LOGTAG, "mixPacketIntoSamplePacket: creating cosine lookup array for mix-frequency=" +
//					mixFrequency + ". Length="+bestLength + " Error="+bestLengthError);
			cosineRealLookupTable = new float[bestLength][256];
			cosineImagLookupTable = new float[bestLength][256];
			float cosineAtT;
			float sineAtT;
			for (int t = 0; t < bestLength; t++) {
				cosineAtT = (float) Math.cos(2 * Math.PI * mixFrequency * t / (float) sampleRate);
				sineAtT = (float) Math.sin(2 * Math.PI * mixFrequency * t / (float) sampleRate);
				for (int i = 0; i < 256; i++) {
					cosineRealLookupTable[t][i] = (i-128)/128.0f * cosineAtT;
					cosineImagLookupTable[t][i] = (i-128)/128.0f * sineAtT;
				}
			}
			cosineIndex=0;
		}

		// Mix the samples from packet and store the results in the samplePacket
		int capacity = samplePacket.capacity();
		int count = 0;
		int startIndex = samplePacket.size();
		float[] re = samplePacket.re();
		float[] im = samplePacket.im();
		for (int i = 0; i < packet.length; i+=2) {
			re[startIndex+count] = cosineRealLookupTable[cosineIndex][packet[i]+128] - cosineImagLookupTable[cosineIndex][packet[i+1]+128];
			im[startIndex+count] = cosineRealLookupTable[cosineIndex][packet[i+1]+128] + cosineImagLookupTable[cosineIndex][packet[i]+128];
			cosineIndex = (cosineIndex + 1) % cosineRealLookupTable.length;
			count++;
			if(startIndex+count >= capacity)
				break;
		}
		samplePacket.setSize(samplePacket.size()+count);	// update the size of the sample packet
		samplePacket.setSampleRate(sampleRate);				// update the sample rate
		samplePacket.setFrequency(frequency);				// update the frequency
		return count;
	}

	/**
	 * Will empty the queue
	 */
	public void flushQueue() {
		byte[] buffer;

		if(hackrf == null || queue == null)
			return; // nothing to flush...

		for (int i = 0; i < queue.size(); i++) {
			buffer = queue.poll();
			if(buffer == null)
				return; // we are done; the queue is empty.
			hackrf.returnBufferToBufferPool(buffer);
		}
	}
}
