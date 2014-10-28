package com.mantz_it.rfanalyzer;

import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - Decimator</h1>
 *
 * Module:      Decimator.java
 * Description: This class implements a decimation block used to downsample the incoming signal
 *              to the sample rate used by the demodulation routines.
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
public class Decimator extends Thread {
	private int outputSampleRate;
	private int packetSize;
	private boolean stopRequested = true;
	private static final String LOGTAG = "Decimator";

	private static final int OUTPUT_QUEUE_SIZE = 2;		// Double Buffer
	private ArrayBlockingQueue<SamplePacket> inputQueue;
	private ArrayBlockingQueue<SamplePacket> inputReturnQueue;
	private ArrayBlockingQueue<SamplePacket> outputQueue;
	private ArrayBlockingQueue<SamplePacket> outputReturnQueue;

	// DOWNSAMPLING:
	private static final int INPUT_RATE = 1000000;	// For now, this decimator only works with a fixed input rate of 1Msps
	private HalfBandLowPassFilter inputFilter1 = null;
	private HalfBandLowPassFilter inputFilter2 = null;
	private HalfBandLowPassFilter inputFilter3 = null;
	private FirFilter inputFilter4 = null;
	private SamplePacket tmpDownsampledSamples;

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

		// Create half band filters for downsampling:
		this.inputFilter1 = new HalfBandLowPassFilter(8);
		this.inputFilter2 = new HalfBandLowPassFilter(8);
		this.inputFilter3 = new HalfBandLowPassFilter(8);

		// Create local buffers:
		this.tmpDownsampledSamples = new SamplePacket(packetSize);
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
				Log.d(LOGTAG, "run: Input sample is null. skip this round...");
				continue;
			}

			// Verify the input sample rate: 	(For now, this decimator only works with a fixed input rate of 1Msps)
			if (inputSamples.getSampleRate() != INPUT_RATE) {
				Log.d(LOGTAG, "run: Input sample rate is " + inputSamples.getSampleRate() + " but should be" + INPUT_RATE + ". skip.");
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
			downsampling(inputSamples, outputSamples);

			// return inputSamples back to the input queue:
			inputReturnQueue.offer(inputSamples);

			// deliver the outputSamples to the output queue
			outputQueue.offer(outputSamples);
		}
	}

	private void downsampling(SamplePacket input, SamplePacket output) {
		// Verify that the input filter 4 is still correct configured (gain):
		if(inputFilter4 == null || inputFilter4.getGain() != 2*(outputSampleRate/(double)input.getSampleRate()) ) {
			// We have to (re-)create the filter:
			this.inputFilter4 = FirFilter.createLowPass(2, 2*(outputSampleRate/(double)input.getSampleRate()), 1, 0.15, 0.2, 20);
			Log.d(LOGTAG, "downsampling: created new inputFilter4 with " + inputFilter4.getNumberOfTaps()
					+ " taps. Decimation=" + inputFilter4.getDecimation() + " Cut-Off=" + inputFilter4.getCutOffFrequency()
					+ " transition=" + inputFilter4.getTransitionWidth());
		}

		// apply first filter (decimate to INPUT_RATE/2)
		tmpDownsampledSamples.setSize(0);	// mark buffer as empty
		if (inputFilter1.filterN8(input, tmpDownsampledSamples, 0, input.size()) < input.size()) {
			Log.e(LOGTAG, "downsampling: [inputFilter1] could not filter all samples from input packet.");
		}

		// if we need a decimation of 16: apply second and third filter (decimate to INPUT_RATE/8)
		if(input.getSampleRate()/outputSampleRate == 16) {
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
}
