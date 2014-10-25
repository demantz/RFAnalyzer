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
	private static final int AUDIO_DECIMATION = 8;
	private static final int QUADRATURE_RATE = AUDIO_RATE * AUDIO_DECIMATION; 	// 250000
	private static final int INPUT_RATE = QUADRATURE_RATE*2;					// 500000

	private ArrayBlockingQueue<SamplePacket> inputQueue;
	private ArrayBlockingQueue<SamplePacket> outputQueue;
	private int packetSize;

	//MIXER:
	private int mixFrequency = 0;
	private double[] cosineReal = null;
	private double[] cosineImag = null;
	private int cosineLength = 0;
	private int cosineIndex = 0;
	private static final int MIN_MIX_FREQUENCY = 1000;

	// DOWNSAMPLING:
	private static final int INPUT_FILTER_GAIN = 1;
	private static final int INPUT_FILTER_CUT_OFF = 70000;
	private static final int INPUT_FILTER_TRANSITION = 180000;
	private static final int INPUT_FILTER_ATTENUATION = 20;
	private FirFilter inputFilter = null;
	private SamplePacket downsampledSamples;

	// FILTERING
	private static final int USER_FILTER_GAIN = 1;
	private static final int USER_FILTER_ATTENUATION = 40;
	private static final int MIN_USER_FILTER_WIDTH = 2000;
	private FirFilter userFilter = null;
	private int userFilterCutOff = 0;
	private SamplePacket quadratureSamples;

	// DEMODULATION
	private static final int AUDIO_FILTER_GAIN = 1;
	private static final int AUDIO_FILTER_CUT_OFF = 15000;
	private static final int AUDIO_FILTER_TRANSITION = 15000;
	private static final int AUDIO_FILTER_ATTENUATION = 40;
	private FirFilter audioFilter = null;
	private SamplePacket demodulatedSamples;
	private SamplePacket demodulatorHistory;

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
		this.quadratureSamples = new SamplePacket(packetSize);
		this.demodulatedSamples = new SamplePacket(packetSize);
		this.audioSink = new AudioSink(packetSize, AUDIO_RATE);

		// Create Audio filter:
		this.audioFilter = FirFilter.createLowPass(	AUDIO_DECIMATION,
													AUDIO_FILTER_GAIN,
													QUADRATURE_RATE,
													AUDIO_FILTER_CUT_OFF,
													AUDIO_FILTER_TRANSITION,
													AUDIO_FILTER_ATTENUATION);
		Log.d(LOGTAG,"constructor: created new audio filter with " + audioFilter.getNumberOfTaps()
				+ " taps. Decimation=" + audioFilter.getDecimation() + " Cut-Off="+audioFilter.getCutOffFrequency()
				+ " transition="+audioFilter.getTransitionWidth());
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

		audioSink.start();

		// DEBUG ////////////////////////////////////
		userFilterCutOff = 150000;
		mixFrequency = 100000;
		long startTime = System.currentTimeMillis();
		long timerCounter = 0;
		// DEBUG ////////////////////////////////////

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
				Log.e(LOGTAG, "run: Input sample is null. skip this round...");
				continue;
			}

			// Verify the input sample rate:
			if (inputSamples.getSampleRate() % INPUT_RATE != 0) {
				Log.e(LOGTAG, "run: Input sample rate is not a multiple of " + INPUT_RATE + ". stop.");
				this.stopRequested = true;
				break;
			}

			// downsampling		[sample rate decimated to INPUT_RATE]
			downsampling(inputSamples);
			// The result from downsampling is stored in downsampledSamples

			// return inputSamples back to the Scheduler:
			outputQueue.offer(inputSamples);

			// filtering		[sample rate is INPUT_RATE; output sample rate is QUADRATURE_RATE]
			applyUserFilter(downsampledSamples);
			// The result from filtering is stored in quadratureSamples

			// get buffer from audio sink
			if(audioBuffer == null) {
				audioBuffer = audioSink.getPacketBuffer(1000);
				audioBuffer.setSize(0);	// Mark buffer as empty
			}

			// demodulate		[sample rate decimated from QUADRATURE_RATE to AUDIO_RATE]
			demodulate(quadratureSamples, audioBuffer);

			// play audio if buffer is at least half full [sample rate is AUDIO_RATE]
			if(audioBuffer.size() >= audioBuffer.capacity()/2) {
				audioSink.enqueuePacket(audioBuffer);
				timerCounter += audioBuffer.size();
				audioBuffer = null;
			}
		}

		// DEBUG ////////////////////////////////////
		long timeSpan = (System.currentTimeMillis() - startTime)/1000;
		Log.d(LOGTAG,"##### DONE. Measured Audio Rate: " + timerCounter/(double)timeSpan + " Hz ("
				+timerCounter+" audio samples in "+timeSpan+" sec) ##############################");
		// DEBUG ////////////////////////////////////

	}

	public void downsampling(SamplePacket samples) {
		// Verify that the filter is still correct configured:
		if(inputFilter == null || samples.getSampleRate()/INPUT_RATE != inputFilter.getDecimation()) {
			// We have to (re-)create the input filter:
			this.inputFilter = FirFilter.createLowPass(	samples.getSampleRate()/INPUT_RATE,
														INPUT_FILTER_GAIN,
														samples.getSampleRate(),
														INPUT_FILTER_CUT_OFF,
														INPUT_FILTER_TRANSITION,
														INPUT_FILTER_ATTENUATION);
			Log.d(LOGTAG,"downsampling: created new input filter with " + inputFilter.getNumberOfTaps()
					+ " taps. Decimation=" + inputFilter.getDecimation() + " Cut-Off="+inputFilter.getCutOffFrequency()
					+ " transition="+inputFilter.getTransitionWidth());
		}
		downsampledSamples.setSize(0);	// mark buffer as empty
		if(inputFilter.filter(samples,downsampledSamples, 0, samples.size()) < samples.size()) {
			Log.e(LOGTAG, "downsampling: could not filter all samples from input packet.");
		}
	}

	public void applyUserFilter(SamplePacket samples) {
		// Verify that the filter is still correct configured:
		if(userFilter == null || ((int) userFilter.getCutOffFrequency()) != userFilterCutOff) {
			// We have to (re-)create the user filter:
			this.userFilter = FirFilter.createLowPass(	samples.getSampleRate()/QUADRATURE_RATE, // --> INPUT_RATE to QUADRATURE_RATE
														USER_FILTER_GAIN,
														samples.getSampleRate(),
														userFilterCutOff,
														userFilterCutOff*0.5,
														USER_FILTER_ATTENUATION);
			Log.d(LOGTAG,"applyUserFilter: created new user filter with " + userFilter.getNumberOfTaps()
					+ " taps. Decimation=" + userFilter.getDecimation() + " Cut-Off="+userFilter.getCutOffFrequency()
					+ " transition="+userFilter.getTransitionWidth());
		}
		quadratureSamples.setSize(0);	// mark buffer as empty
		if(userFilter.filter(samples,quadratureSamples, 0, samples.size()) < samples.size()) {
			Log.e(LOGTAG, "applyUserFilter: could not filter all samples from input packet.");
		}
	}

	public void demodulate(SamplePacket samples, SamplePacket output) {
		double[] reIn = samples.re();
		double[] imIn = samples.im();
		double[] reOut = demodulatedSamples.re();
		double[] imOut = demodulatedSamples.im();
		int maxDeviation = 75000;
		double quadratureGain = QUADRATURE_RATE/(2*Math.PI*maxDeviation);

		if(demodulatorHistory == null) {
			demodulatorHistory = new SamplePacket(1);
			demodulatorHistory.re()[0] = reIn[0];
			demodulatorHistory.im()[0] = reOut[0];
		}

		// Quadrature demodulation:
		reOut[0] = reIn[0]*demodulatorHistory.re(0) + imIn[0] * demodulatorHistory.im(0);
		imOut[0] = imIn[0]*demodulatorHistory.re(0) - reIn[0] * demodulatorHistory.im(0);
		reOut[0] = quadratureGain * Math.atan2(imOut[0], reOut[0]);
		for (int i = 1; i < samples.size(); i++) {
			reOut[i] = reIn[i]*reIn[i-1] + imIn[i] * imIn[i-1];
			imOut[i] = imIn[i]*reIn[i-1] - reIn[i] * imIn[i-1];
			reOut[i] = quadratureGain * Math.atan2(imOut[i], reOut[i]);
		}
		demodulatorHistory.re()[0] = reIn[samples.size()-1];
		demodulatorHistory.im()[0] = imIn[samples.size()-1];
		demodulatedSamples.setSize(samples.size());
		demodulatedSamples.setSampleRate(QUADRATURE_RATE);

		// Audio Filter:
		if(audioFilter.filter(demodulatedSamples,output, 0, demodulatedSamples.size()) < demodulatedSamples.size()) {
			Log.e(LOGTAG, "demodulate: could not filter all samples from demodulated packet.");
		}
	}
}
