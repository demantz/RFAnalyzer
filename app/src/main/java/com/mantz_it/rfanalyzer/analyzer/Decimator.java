package com.mantz_it.rfanalyzer.analyzer;

import android.util.Log;

import com.mantz_it.rfanalyzer.database.GlobalPerformanceData;
import com.mantz_it.rfanalyzer.dsp.FirFilter;
import com.mantz_it.rfanalyzer.source.SamplePacket;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - Decimator</h1>
 *
 * Module:      Decimator.java
 * Description: This class implements a decimation block used to downsample the incoming signal
 *              to the sample rate used by the demodulation routines. It will run in a separate thread.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
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
public class Decimator extends Thread {
	private int outputSampleRate;	// sample rate at the output of the decimator block
	private int packetSize;			// packet size of the incoming packets
	private boolean stopRequested = true;
	private static final String LOGTAG = "Decimator";

	private static final int OUTPUT_QUEUE_SIZE = 2;		// Double Buffer
	private ArrayBlockingQueue<SamplePacket> inputQueue;		// queue that holds the incoming sample packets
	private ArrayBlockingQueue<SamplePacket> inputReturnQueue;	// queue to return used buffers from the input queue
	private ArrayBlockingQueue<SamplePacket> outputQueue;		// queue that will hold the decimated sample packets
	private ArrayBlockingQueue<SamplePacket> outputReturnQueue;	// queue to return used buffers from the output queue

	private FirFilter inputFilter = null;

	/**
	 * Constructor. Will create a new Decimator block.
	 *
	 * @param outputSampleRate		// sample rate to which the incoming samples should be decimated
	 * @param packetSize			// packet size of the incoming sample packets
	 * @param inputQueue			// queue that delivers incoming sample packets
	 * @param inputReturnQueue		// queue to return used input sample packets
	 */
	public Decimator (int outputSampleRate, int packetSize, ArrayBlockingQueue<SamplePacket> inputQueue,
					  ArrayBlockingQueue<SamplePacket> inputReturnQueue) {
		this.outputSampleRate = outputSampleRate;
		this.packetSize = packetSize;
		this.inputQueue = inputQueue;
		this.inputReturnQueue = inputReturnQueue;

		// Create output queues:
		this.outputQueue = new ArrayBlockingQueue<SamplePacket>(OUTPUT_QUEUE_SIZE);
		this.outputReturnQueue = new ArrayBlockingQueue<SamplePacket>(OUTPUT_QUEUE_SIZE);
		for (int i = 0; i < OUTPUT_QUEUE_SIZE; i++)
			outputReturnQueue.offer(new SamplePacket(packetSize));
	}

	public int getOutputSampleRate() {
		return outputSampleRate;
	}

	public void setOutputSampleRate(int outputSampleRate) {
		this.outputSampleRate = outputSampleRate;
	}

	public SamplePacket getDecimatedPacket(int timeout) {
		try {
			return outputQueue.poll(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Log.e(LOGTAG, "getPacket: Interrupted while waiting on queue");
			return null;
		}
	}

	public void returnDecimatedPacket(SamplePacket packet) {
		outputReturnQueue.offer(packet);
	}

	@Override
	public synchronized void start() {
		this.stopRequested = false;
		super.start();
	}

	public void stopDecimator() {
		this.stopRequested = true;
	}

	@Override
	public void run() {
		SamplePacket inputSamples;
		SamplePacket outputSamples;

		this.setName("Thread-Decimator-" + System.currentTimeMillis());
		Log.i(LOGTAG,"Decimator started. (Thread: " + this.getName() + ")");

		while (!stopRequested) {
			// Get a packet from the input queue:
			try {
				inputSamples = inputQueue.poll(1000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "run: Interrupted while waiting on input queue! stop.");
				this.stopRequested = true;
				break;
			}

			// Verify the input sample packet is not null:
			if (inputSamples == null) {
				//Log.d(LOGTAG, "run: Input sample is null. skip this round...");
				continue;
			}

			// Verify the input sample rate (must be a multiple of the output rate):
			if (inputSamples.getSampleRate() % outputSampleRate != 0) {
				Log.d(LOGTAG, "run: Input sample rate is " + inputSamples.getSampleRate() + " which is not a multiple of " + outputSampleRate + ". skip.");
				continue;
			}

			// Get a packet from the output queue:
			try {
				outputSamples = outputReturnQueue.poll(1000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "run: Interrupted while waiting on output return queue! stop.");
				this.stopRequested = true;
				break;
			}

			// Verify the output sample packet is not null:
			if (outputSamples == null) {
				Log.d(LOGTAG, "run: Output sample is null. skip this round...");
				continue;
			}

			// downsampling
			long startTimestamp = System.nanoTime();
			downsampling(inputSamples, outputSamples);

			// Performance Tracking
			float nsPerPacket = inputSamples.size() * 1_000_000_000f / inputSamples.getSampleRate();
			GlobalPerformanceData.INSTANCE.updateLoad("Decimator", (System.nanoTime() - startTimestamp) / nsPerPacket);

			// return inputSamples back to the input queue:
			inputReturnQueue.offer(inputSamples);

			// deliver the outputSamples to the output queue
			outputQueue.offer(outputSamples);
		}

		this.stopRequested = true;
		Log.i(LOGTAG,"Decimator stopped. (Thread: " + this.getName() + ")");
	}

	/**
	 * Will decimate the input samples to the outputSampleRate and store them in output
	 *
	 * @param input		incoming samples at the incoming rate (input rate)
	 * @param output	outgoing (decimated) samples at output rate (quadrature rate)
	 */
	private void downsampling(SamplePacket input, SamplePacket output) {
		// Verify that the input filter is still correct configured (decimation):
		int decimation = input.getSampleRate()/outputSampleRate;
		if(inputFilter == null || inputFilter.getDecimation() != decimation) {
			// We have to (re-)create the filter:
			Log.d(LOGTAG, "downsampling: sample rates changed: input: " + input.getSampleRate() + " output: " + outputSampleRate);
			this.inputFilter = FirFilter.createLowPass(decimation, 2/(float)decimation, input.getSampleRate(), outputSampleRate*0.75f, outputSampleRate*0.25f, 60);
			Log.d(LOGTAG, "downsampling: created new inputFilter with " + inputFilter.getNumberOfTaps()
					+ " taps. Decimation=" + inputFilter.getDecimation() + " Cut-Off=" + inputFilter.getCutOffFrequency()
					+ " transition=" + inputFilter.getTransitionWidth());
		}

		output.setSize(0);	// mark buffer as empty
		if (inputFilter.filter(input, output, 0, input.size()) < input.size()) {
			Log.e(LOGTAG, "downsampling: [inputFilter] could not filter all samples from input packet.");
		}
	}
}
