package com.mantz_it.rfanalyzer;

import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - Demodulator</h1>
 *
 * Module:      Demodulator.java
 * Description: This class implements demodulation of various analog radio modes (FM, AM, SSB).
 *              It runs as a separate thread. It will read raw complex samples from a queue,
 *              process them (channel selection, filtering, demodulating) and forward the to
 *              an AudioSink thread.
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
public class Demodulator extends Thread {
	private boolean stopRequested = true;
	private static final String LOGTAG = "Demodulator";
	private static final int AUDIO_RATE = 31250;
	private static final int[] QUADRATURE_RATE = {	0,				// off
													2*AUDIO_RATE,	// AM
													2*AUDIO_RATE,	// nFM
													8*AUDIO_RATE};	// wFM
	public static final int INPUT_RATE = 1000000;

	// DECIMATION
	private Decimator decimator;

	// FILTERING
	private static final int USER_FILTER_ATTENUATION = 20;
	private FirFilter userFilter = null;
	private int userFilterCutOff = 0;
	private SamplePacket quadratureSamples;
	private static final int[] MIN_USER_FILTER_WIDTH = {0,		// off
														3000,	// AM
														3000,	// nFM
														50000};	// wFM
	private static final int[] MAX_USER_FILTER_WIDTH = {0,		// off
														15000,	// AM
														15000,	// nFM
														120000};// wFM

	// DEMODULATION
	private SamplePacket demodulatorHistory;
	public static final int DEMODULATION_OFF = 0;
	public static final int DEMODULATION_AM = 1;
	public static final int DEMODULATION_NFM = 2;
	public static final int DEMODULATION_WFM = 3;
	public int demodulationMode;

	// AUDIO OUTPUT
	private AudioSink audioSink = null;

	public Demodulator (ArrayBlockingQueue<SamplePacket> inputQueue, ArrayBlockingQueue<SamplePacket> outputQueue, int packetSize) {
		// Create internal sample buffers:
		// Note that we create the buffers for the case that there is no downsampling necessary
		// All other cases with input decimation > 1 are also possible because they only need
		// smaller buffers.
		this.quadratureSamples = new SamplePacket(packetSize);

		// Create Audio Sink
		this.audioSink = new AudioSink(packetSize, AUDIO_RATE);

		// Create Decimator block
		// Note that the decimator directly reads from the inputQueue and also returns processed packets to the
		// output queue.
		this.decimator = new Decimator(QUADRATURE_RATE[demodulationMode], packetSize, inputQueue, outputQueue);
	}

	public int getDemodulationMode() {
		return demodulationMode;
	}

	public void setDemodulationMode(int demodulationMode) {
		if(demodulationMode > 3 || demodulationMode < 0) {
			Log.e(LOGTAG,"setDemodulationMode: invalid mode: " + demodulationMode);
			return;
		}
		this.decimator.setOutputSampleRate(QUADRATURE_RATE[demodulationMode]);
		this.demodulationMode = demodulationMode;
		this.userFilterCutOff = MAX_USER_FILTER_WIDTH[demodulationMode];
	}

	/**
	 * Will set the cut off frequency of the user filter
	 * @param channelWidth	channel width (single side) in Hz
	 * @return true if channel width is valid, false if out of range
	 */
	public boolean setChannelWidth(int channelWidth) {
		if(channelWidth < MIN_USER_FILTER_WIDTH[demodulationMode] || channelWidth > MAX_USER_FILTER_WIDTH[demodulationMode])
			return false;
		this.userFilterCutOff = channelWidth;
		return true;
	}

	public int getChannelWidth() {
		return userFilterCutOff;
	}

	/**
	 * Starts the thread
	 */
	@Override
	public synchronized void start() {
		stopRequested = false;
		super.start();
	}

	/**
	 * Stops the thread
	 */
	public void stopDemodulator () {
		stopRequested = true;
	}

	@Override
	public void run() {
		SamplePacket inputSamples = null;
		SamplePacket audioBuffer = null;

		Log.i(LOGTAG,"Demodulator started. (Thread: " + this.getName() + ")");

		// Start the audio sink thread:
		audioSink.start();

		while (!stopRequested) {

			// Get downsampled packet from the decimator:
			inputSamples = decimator.getDecimatedPacket(1000);

			// Verify the input sample packet is not null:
			if (inputSamples == null) {
				Log.d(LOGTAG, "run: Decimated sample is null. skip this round...");
				continue;
			}

			// filtering		[sample rate is QUADRATURE_RATE]
			applyUserFilter(inputSamples, quadratureSamples);		// The result from filtering is stored in quadratureSamples

			// return input samples to the decimator block:
			decimator.returnDecimatedPacket(inputSamples);

			// get buffer from audio sink
			audioBuffer = audioSink.getPacketBuffer(1000);

			// demodulate		[sample rate is QUADRATURE_RATE]
			demodulate(quadratureSamples, audioBuffer);			// The result from demodulating is stored in audioBuffer

			// play audio		[sample rate is QUADRATURE_RATE]
			audioSink.enqueuePacket(audioBuffer);
		}

		// Stop the audio sink thread:
		audioSink.stopSink();

		this.stopRequested = true;
		Log.i(LOGTAG,"Demodulator stopped. (Thread: " + this.getName() + ")");
	}

	public void applyUserFilter(SamplePacket input, SamplePacket output) {
		// Verify that the filter is still correct configured:
		if(userFilter == null || ((int) userFilter.getCutOffFrequency()) != userFilterCutOff) {
			// We have to (re-)create the user filter:
			this.userFilter = FirFilter.createLowPass(	1,
														1,
														input.getSampleRate(),
														userFilterCutOff,
														input.getSampleRate()*0.10,
														USER_FILTER_ATTENUATION);
			Log.d(LOGTAG,"applyUserFilter: created new user filter with " + userFilter.getNumberOfTaps()
					+ " taps. Decimation=" + userFilter.getDecimation() + " Cut-Off="+userFilter.getCutOffFrequency()
					+ " transition="+userFilter.getTransitionWidth());
		}
		output.setSize(0);	// mark buffer as empty
		if(userFilter.filter(input, output, 0, input.size()) < input.size()) {
			Log.e(LOGTAG, "applyUserFilter: could not filter all samples from input packet.");
		}
	}

	public void demodulate(SamplePacket input, SamplePacket output) {
		double[] reIn = input.re();
		double[] imIn = input.im();
		double[] reOut = output.re();
		double[] imOut = output.im();
		int maxDeviation = 75000;
		double quadratureGain = QUADRATURE_RATE[demodulationMode]/(2*Math.PI*maxDeviation);

		if(demodulatorHistory == null) {
			demodulatorHistory = new SamplePacket(1);
			demodulatorHistory.re()[0] = reIn[0];
			demodulatorHistory.im()[0] = reOut[0];
		}

		// Quadrature demodulation:
		reOut[0] = reIn[0]*demodulatorHistory.re(0) + imIn[0] * demodulatorHistory.im(0);
		imOut[0] = imIn[0]*demodulatorHistory.re(0) - reIn[0] * demodulatorHistory.im(0);
		reOut[0] = quadratureGain * Math.atan2(imOut[0], reOut[0]);
		for (int i = 1; i < input.size(); i++) {
			reOut[i] = reIn[i]*reIn[i-1] + imIn[i] * imIn[i-1];
			imOut[i] = imIn[i]*reIn[i-1] - reIn[i] * imIn[i-1];
			reOut[i] = quadratureGain * Math.atan2(imOut[i], reOut[i]);
		}
		demodulatorHistory.re()[0] = reIn[input.size()-1];
		demodulatorHistory.im()[0] = imIn[input.size()-1];
		output.setSize(input.size());
		output.setSampleRate(QUADRATURE_RATE[demodulationMode]);
	}
}
