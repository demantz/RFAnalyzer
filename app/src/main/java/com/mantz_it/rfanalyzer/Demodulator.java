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
	private static final int AUDIO_RATE = 31250;	// Even though this is not a proper audio rate, the Android system can
													// handle it properly and it is a integer fraction of the input rate (1MHz).
	// The quadrature rate is the sample rate that is used for the demodulation:
	private static final int[] QUADRATURE_RATE = {	1,				// off; this value is not 0 to avoid divide by zero errors!
													2*AUDIO_RATE,	// AM
													2*AUDIO_RATE,	// nFM
													8*AUDIO_RATE};	// wFM
	public static final int INPUT_RATE = 1000000;	// Expected rate of the incoming samples

	// DECIMATION
	private Decimator decimator;	// will do INPUT_RATE --> QUADRATURE_RATE

	// FILTERING (This is the channel filter controlled by the user)
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
	private SamplePacket demodulatorHistory;	// used for FM demodulation
	private float lastMax = 0;	// used for gain control in AM demodulation
	public static final int DEMODULATION_OFF 	= 0;
	public static final int DEMODULATION_AM 	= 1;
	public static final int DEMODULATION_NFM 	= 2;
	public static final int DEMODULATION_WFM 	= 3;
	public int demodulationMode;

	// AUDIO OUTPUT
	private AudioSink audioSink = null;		// Will do QUADRATURE_RATE --> AUDIO_RATE and audio output

	/**
	 * Constructor. Creates a new demodulator block reading its samples from the given input queue and
	 * returning the buffers to the given output queue. Expects input samples to be at baseband (mixing
	 * is done by the scheduler)
	 *
	 * @param inputQueue	Queue that delivers received baseband signals
	 * @param outputQueue	Queue to return used buffers from the inputQueue
	 * @param packetSize	Size of the packets in the input queue
	 */
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

	/**
	 * @return	Demodulation Mode (DEMODULATION_OFF, *_AM, *_NFM, *_WFM)
	 */
	public int getDemodulationMode() {
		return demodulationMode;
	}

	/**
	 * Sets a new demodulation mode. This can be done while the demodulator is running!
	 * Will automatically adjust internal sample rate conversions and the user filter
	 * if necessary
	 *
	 * @param demodulationMode	Demodulation Mode (DEMODULATION_OFF, *_AM, *_NFM, *_WFM)
	 */
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

	/**
	 * @return Current width (cut-off frequency - one sided) of the user filter
	 */
	public int getChannelWidth() {
		return userFilterCutOff;
	}

	/**
	 * Starts the thread. This thread will start 2 more threads for decimation and audio output.
	 * These threads are managed by the Demodulator and terminated, when the Demodulator thread
	 * terminates.
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

		// Start decimator thread:
		decimator.start();

		while (!stopRequested) {

			// Get downsampled packet from the decimator:
			inputSamples = decimator.getDecimatedPacket(1000);

			// Verify the input sample packet is not null:
			if (inputSamples == null) {
				//Log.d(LOGTAG, "run: Decimated sample is null. skip this round...");
				continue;
			}

			// filtering		[sample rate is QUADRATURE_RATE]
			applyUserFilter(inputSamples, quadratureSamples);		// The result from filtering is stored in quadratureSamples

			// return input samples to the decimator block:
			decimator.returnDecimatedPacket(inputSamples);

			// get buffer from audio sink
			audioBuffer = audioSink.getPacketBuffer(1000);

			// demodulate		[sample rate is QUADRATURE_RATE]
			switch (demodulationMode) {
				case DEMODULATION_OFF:
					break;

				case DEMODULATION_AM:
					demodulateAM(quadratureSamples, audioBuffer);
					break;

				case DEMODULATION_NFM:
					demodulateFM(quadratureSamples, audioBuffer, 5000);
					break;

				case DEMODULATION_WFM:
					demodulateFM(quadratureSamples, audioBuffer, 75000);
					break;

				default:
					Log.e(LOGTAG, "run: invalid demodulationMode: " + demodulationMode);
			}

			// play audio		[sample rate is QUADRATURE_RATE]
			audioSink.enqueuePacket(audioBuffer);
		}

		// Stop the audio sink thread:
		audioSink.stopSink();

		// Stop the decimator thread:
		decimator.stopDecimator();

		this.stopRequested = true;
		Log.i(LOGTAG,"Demodulator stopped. (Thread: " + this.getName() + ")");
	}

	/**
	 * Will filter the samples in input according to the user filter settings.
	 * Filtered samples are stored in output. Note: All samples in output
	 * will always be overwritten!
	 *
	 * @param input		incoming (unfiltered) samples
	 * @param output	outgoing (filtered) samples
	 */
	private void applyUserFilter(SamplePacket input, SamplePacket output) {
		// Verify that the filter is still correct configured:
		if(userFilter == null || ((int) userFilter.getCutOffFrequency()) != userFilterCutOff) {
			// We have to (re-)create the user filter:
			this.userFilter = FirFilter.createLowPass(	1,
														1,
														input.getSampleRate(),
														userFilterCutOff,
														input.getSampleRate()*0.10f,
														USER_FILTER_ATTENUATION);
			if(userFilter == null)
				return;	// This may happen if input samples changed rate or demodulation was turned off. Just skip the filtering.
			Log.d(LOGTAG,"applyUserFilter: created new user filter with " + userFilter.getNumberOfTaps()
					+ " taps. Decimation=" + userFilter.getDecimation() + " Cut-Off="+userFilter.getCutOffFrequency()
					+ " transition="+userFilter.getTransitionWidth());
		}
		output.setSize(0);	// mark buffer as empty
		if(userFilter.filter(input, output, 0, input.size()) < input.size()) {
			Log.e(LOGTAG, "applyUserFilter: could not filter all samples from input packet.");
		}
	}

	/**
	 * Will FM demodulate the samples in input. Use ~75000 deviation for wide band FM
	 * and ~3000 deviation for narrow band FM.
	 * Demodulated samples are stored in the real array of output. Note: All samples in output
	 * will always be overwritten!
	 *
	 * @param input		incoming (modulated) samples
	 * @param output	outgoing (demodulated) samples
	 */
	private void demodulateFM(SamplePacket input, SamplePacket output, int maxDeviation) {
		float[] reIn = input.re();
		float[] imIn = input.im();
		float[] reOut = output.re();
		float[] imOut = output.im();
		int inputSize = input.size();
		float quadratureGain =  QUADRATURE_RATE[demodulationMode]/(2*(float)Math.PI*maxDeviation);

		if(demodulatorHistory == null) {
			demodulatorHistory = new SamplePacket(1);
			demodulatorHistory.re()[0] = reIn[0];
			demodulatorHistory.im()[0] = reOut[0];
		}

		// Quadrature demodulation:
		reOut[0] = reIn[0]*demodulatorHistory.re(0) + imIn[0] * demodulatorHistory.im(0);
		imOut[0] = imIn[0]*demodulatorHistory.re(0) - reIn[0] * demodulatorHistory.im(0);
		reOut[0] = quadratureGain * (float) Math.atan2(imOut[0], reOut[0]);
		for (int i = 1; i < inputSize; i++) {
			reOut[i] = reIn[i]*reIn[i-1] + imIn[i] * imIn[i-1];
			imOut[i] = imIn[i]*reIn[i-1] - reIn[i] * imIn[i-1];
			reOut[i] = quadratureGain * (float) Math.atan2(imOut[i], reOut[i]);
		}
		demodulatorHistory.re()[0] = reIn[inputSize-1];
		demodulatorHistory.im()[0] = imIn[inputSize-1];
		output.setSize(inputSize);
		output.setSampleRate(QUADRATURE_RATE[demodulationMode]);
	}

	/**
	 * Will AM demodulate the samples in input.
	 * Demodulated samples are stored in the real array of output. Note: All samples in output
	 * will always be overwritten!
	 *
	 * @param input		incoming (modulated) samples
	 * @param output	outgoing (demodulated) samples
	 */
	private void demodulateAM(SamplePacket input, SamplePacket output) {
		float[] reIn = input.re();
		float[] imIn = input.im();
		float[] reOut = output.re();
		float gain = 1/lastMax;
		lastMax = 0;

		// Complex to magnitude
		for (int i = 0; i < input.size(); i++) {
			reOut[i] = (reIn[i] * reIn[i] + imIn[i] * imIn[i]);
			if(reOut[i] > lastMax)
				lastMax = reOut[i];
			reOut[i] = reOut[i] * gain - 0.5f;
		}

		output.setSize(input.size());
		output.setSampleRate(QUADRATURE_RATE[demodulationMode]);
	}
}
