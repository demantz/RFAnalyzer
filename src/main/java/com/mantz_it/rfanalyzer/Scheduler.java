package com.mantz_it.rfanalyzer;

import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * <h1>RF Analyzer - Scheduler</h1>
 *
 * Module:      Scheduler.java
 * Description: This Thread is responsible for forwarding the samples from the input hardware
 *              to the Processing Loop at the correct speed and format. Therefore it has to
 *              drop incoming samples that won't be used in order to keep the buffer of the
 *              hackrf_android library from beeing filled up.
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
public class Scheduler extends Thread {
	private IQSourceInterface source = null;	// Reference to the source of the IQ samples
	private ArrayBlockingQueue<SamplePacket> outputQueue = null;	// Queue that delivers samples to the Processing Loop
	private ArrayBlockingQueue<SamplePacket> inputQueue = null;		// Queue that collects used buffers from the Processing Loop
	private boolean stopRequested = true;

	// Define the size of the output and input Queues. By setting this value to 2 we basically end up
	// with double buffering. Maybe the two queues are overkill, but it works pretty well like this and
	// it handles the synchronization between the scheduler thread and the processing loop for us.
	// Note that setting the size to 1 will not work well and any number higher than 2 will cause
	// higher delays when switching frequencies.
	private static final int queueSize = 2;
	private static final String LOGTAG = "Scheduler";

	public Scheduler(int fftSize, IQSourceInterface source) {
		this.source = source;

		// Create the input- and output queues and allocate the buffer packets.
		this.outputQueue = new ArrayBlockingQueue<SamplePacket>(queueSize);
		this.inputQueue = new ArrayBlockingQueue<SamplePacket>(queueSize);
		for (int i = 0; i < queueSize; i++)
			inputQueue.offer(new SamplePacket(fftSize));
	}

	public void stopScheduler() {
		this.stopRequested = true;
		this.source.stopSampling();
	}

	public void start() {
		this.stopRequested = false;
		this.source.startSampling();
		super.start();
	}

	/**
	 * @return true if scheduler is running; false if not.
	 */
	public boolean isRunning() {
		return !stopRequested;
	}

	public ArrayBlockingQueue<SamplePacket> getOutputQueue() {
		return outputQueue;
	}

	public ArrayBlockingQueue<SamplePacket> getInputQueue() {
		return inputQueue;
	}

	@Override
	public void run() {
		Log.i(LOGTAG,"Scheduler started. (Thread: " + this.getName() + ")");
		SamplePacket buffer = null;		// reference to a buffer we got from the input queue to fill

		while(!stopRequested) {
			// Get a new packet from the source:
			byte[] packet = source.getPacket(1000);
			if(packet == null) {
				Log.e(LOGTAG, "run: No more packets from source. Shutting down...");
				this.stopScheduler();
				break;
			}

			// If buffer is null we request a new buffer from the input queue:
			if(buffer == null)
				buffer = inputQueue.poll();

			// If we got a buffer, fill it!
			if(buffer != null)
			{
				// fill the packet into the buffer at bufferIndex:
				buffer.setSize(0);	// mark buffer as empty
				source.fillPacketIntoSamplePacket(packet,buffer);
				buffer.setFrequency(source.getFrequency());
				buffer.setSampleRate(source.getSampleRate());

				// check if the buffer is now full and if so: deliver it to the output queue
				if(buffer.capacity() == buffer.size()) {
					outputQueue.offer(buffer);
					buffer = null;
				}
				// otherwise we would just go for another round...
			}
			// If buffer was null we currently have no buffer available, which means we
			// simply throw the samples away (this will happen most of the time).

			// In both cases: Return the packet back to the source buffer pool:
			source.returnPacket(packet);
		}
		this.stopRequested = true;
		Log.i(LOGTAG,"Scheduler stopped. (Thread: " + this.getName() + ")");
	}
}
