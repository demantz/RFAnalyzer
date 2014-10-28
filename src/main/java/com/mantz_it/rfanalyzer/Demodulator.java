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

	private ArrayBlockingQueue<SamplePacket> inputQueue;
	private ArrayBlockingQueue<SamplePacket> outputQueue;
	private int packetSize;

	// DOWNSAMPLING:
	private HalfBandLowPassFilter inputFilter1 = null;
	private HalfBandLowPassFilter inputFilter2 = null;
	private HalfBandLowPassFilter inputFilter3 = null;
	private FirFilter inputFilter4 = null;
	private SamplePacket downsampledSamples;
	private SamplePacket tmpDownsampledSamples;

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
	private SamplePacket demodulatedSamples;
	private SamplePacket demodulatorHistory;
	public static final int DEMODULATION_OFF = 0;
	public static final int DEMODULATION_AM = 1;
	public static final int DEMODULATION_NFM = 2;
	public static final int DEMODULATION_WFM = 3;
	public int demodulationMode;

	// AUDIO OUTPUT
	private AudioSink audioSink = null;

	public Demodulator (ArrayBlockingQueue<SamplePacket> inputQueue, ArrayBlockingQueue<SamplePacket> outputQueue, int packetSize) {
		this.inputQueue = inputQueue;
		this.outputQueue = outputQueue;
		this.packetSize = packetSize;

		// Create internal sample buffers:
		// Note that we create the buffers for the case that there is no downsampling necessary
		// All other cases with input decimation > 1 are also possible because they only need
		// smaller buffers.
		this.downsampledSamples = new SamplePacket(packetSize);
		this.tmpDownsampledSamples = new SamplePacket(packetSize);
		this.quadratureSamples = new SamplePacket(packetSize);
		this.demodulatedSamples = new SamplePacket(packetSize);

		// Create Audio Sink
		this.audioSink = new AudioSink(packetSize, AUDIO_RATE);

		// Create half band filters for downsampling:
		this.inputFilter1 = new HalfBandLowPassFilter(8);
		this.inputFilter2 = new HalfBandLowPassFilter(8);
		this.inputFilter3 = new HalfBandLowPassFilter(8);
	}

	public int getDemodulationMode() {
		return demodulationMode;
	}

	public void setDemodulationMode(int demodulationMode) {
		if(demodulationMode > 3 || demodulationMode < 0) {
			Log.e(LOGTAG,"setDemodulationMode: invalid mode: " + demodulationMode);
			return;
		}
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

			try {
				inputSamples = inputQueue.poll(1000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Log.e(LOGTAG,"run: Interrupted while waiting on input queue! stop.");
				this.stopRequested = true;
				break;
			}

			// Verify the input sample packet is not null:
			if (inputSamples == null) {
				Log.d(LOGTAG, "run: Input sample is null. skip this round...");
				continue;
			}

			// Verify the input sample rate:
			if (inputSamples.getSampleRate() != INPUT_RATE) {
				Log.d(LOGTAG, "run: Input sample rate is " + inputSamples.getSampleRate() + " but should be" + INPUT_RATE + ". skip.");
				continue;
			}

			// downsampling		[sample rate decimated from INPUT_RATE to QUADRATURE_RATE]
			downsampling(inputSamples, downsampledSamples);				// The result from downsampling is stored in downsampledSamples

			// return inputSamples back to the Scheduler:
			outputQueue.offer(inputSamples);

			// filtering		[sample rate is QUADRATURE_RATE]
			applyUserFilter(downsampledSamples, quadratureSamples);		// The result from filtering is stored in quadratureSamples

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

	public void downsampling(SamplePacket input, SamplePacket output) {
		// Verify that the input filter 4 is still correct configured (gain):
		if(inputFilter4 == null || inputFilter4.getGain() != 2*(QUADRATURE_RATE[demodulationMode]/(double)INPUT_RATE) ) {
			// We have to (re-)create the filter:
			this.inputFilter4 = FirFilter.createLowPass(2, 2*(QUADRATURE_RATE[demodulationMode]/(double)INPUT_RATE), 1, 0.15, 0.2, 20);
			Log.d(LOGTAG,"downsampling: created new inputFilter4 with " + inputFilter4.getNumberOfTaps()
					+ " taps. Decimation=" + inputFilter4.getDecimation() + " Cut-Off="+inputFilter4.getCutOffFrequency()
					+ " transition="+inputFilter4.getTransitionWidth());
		}

		// apply first filter (decimate to INPUT_RATE/2)
		tmpDownsampledSamples.setSize(0);	// mark buffer as empty
		if (inputFilter1.filterN8(input, tmpDownsampledSamples, 0, input.size()) < input.size()) {
			Log.e(LOGTAG, "downsampling: [inputFilter1] could not filter all samples from input packet.");
		}

		// if we need a decimation of 16: apply second and third filter (decimate to INPUT_RATE/8)
		if(INPUT_RATE/QUADRATURE_RATE[demodulationMode] == 16) {
			output.setSize(0);	// mark buffer as empty
			if (inputFilter2.filterN8(tmpDownsampledSamples, output, 0, tmpDownsampledSamples.size()) < tmpDownsampledSamples.size()) {
				Log.e(LOGTAG, "downsampling: [inputFilter2] could not filter all samples from input packet.");
			}

			tmpDownsampledSamples.setSize(0);	// mark tmp buffer as again
			if (inputFilter3.filterN8(output, tmpDownsampledSamples, 0, output.size()) < output.size()) {
				Log.e(LOGTAG, "downsampling: [inputFilter3] could not filter all samples from input packet.");
			}
		}

		// apply fourth filter (decimate either to INPUT_RATE/4 or INPUT_RATE/16)
		output.setSize(0);	// mark buffer as empty
		if (inputFilter4.filter(tmpDownsampledSamples, output, 0, tmpDownsampledSamples.size()) < tmpDownsampledSamples.size()) {
			Log.e(LOGTAG, "downsampling: [inputFilter4] could not filter all samples from input packet.");
		}
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
