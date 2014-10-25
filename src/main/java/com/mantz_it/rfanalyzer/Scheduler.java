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
	private ArrayBlockingQueue<SamplePacket> fftOutputQueue = null;	// Queue that delivers samples to the Processing Loop
	private ArrayBlockingQueue<SamplePacket> fftInputQueue = null;	// Queue that collects used buffers from the Processing Loop
	private ArrayBlockingQueue<SamplePacket> demodOutputQueue = null;	// Queue that delivers samples to the Demodulator block
	private ArrayBlockingQueue<SamplePacket> demodInputQueue = null;	// Queue that collects used buffers from the Demodulator block
	private int mixFrequency = 0;						// Shift frequency by this value when passing packets to demodulator
	private boolean stopRequested = true;

	// Define the size of the fft output and input Queues. By setting this value to 2 we basically end up
	// with double buffering. Maybe the two queues are overkill, but it works pretty well like this and
	// it handles the synchronization between the scheduler thread and the processing loop for us.
	// Note that setting the size to 1 will not work well and any number higher than 2 will cause
	// higher delays when switching frequencies.
	private static final int FFT_QUEUE_SIZE = 2;
	private static final int DEMOD_QUEUE_SIZE = 10;
	private static final String LOGTAG = "Scheduler";

	public Scheduler(int fftSize, IQSourceInterface source) {
		this.source = source;

		// Create the fft input- and output queues and allocate the buffer packets.
		this.fftOutputQueue = new ArrayBlockingQueue<SamplePacket>(FFT_QUEUE_SIZE);
		this.fftInputQueue = new ArrayBlockingQueue<SamplePacket>(FFT_QUEUE_SIZE);
		for (int i = 0; i < FFT_QUEUE_SIZE; i++)
			fftInputQueue.offer(new SamplePacket(fftSize));

		// Create the demod input- and output queues and allocate the buffer packets.
		this.demodOutputQueue = new ArrayBlockingQueue<SamplePacket>(DEMOD_QUEUE_SIZE);
		this.demodInputQueue = new ArrayBlockingQueue<SamplePacket>(DEMOD_QUEUE_SIZE);
		for (int i = 0; i < DEMOD_QUEUE_SIZE; i++)
			demodInputQueue.offer(new SamplePacket(source.getPacketSize()));
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

	public ArrayBlockingQueue<SamplePacket> getFftOutputQueue() {
		return fftOutputQueue;
	}

	public ArrayBlockingQueue<SamplePacket> getFftInputQueue() {
		return fftInputQueue;
	}

	public ArrayBlockingQueue<SamplePacket> getDemodOutputQueue() {
		return demodOutputQueue;
	}

	public ArrayBlockingQueue<SamplePacket> getDemodInputQueue() {
		return demodInputQueue;
	}

	public int getMixFrequency() {
		return mixFrequency;
	}

	public void setMixFrequency(int mixFrequency) {
		this.mixFrequency = mixFrequency;
	}

	@Override
	public void run() {
		Log.i(LOGTAG,"Scheduler started. (Thread: " + this.getName() + ")");
		SamplePacket fftBuffer = null;		// reference to a buffer we got from the fft input queue to fill
		SamplePacket demodBuffer = null;	// reference to a buffer we got from the demod input queue to fill
		SamplePacket tmpFlushBuffer = null;	// Just a tmp buffer to flush a queue if necessary

		while(!stopRequested) {
			// Get a new packet from the source:
			byte[] packet = source.getPacket(1000);
			if(packet == null) {
				Log.e(LOGTAG, "run: No more packets from source. Shutting down...");
				this.stopScheduler();
				break;
			}

			///// Demodulation /////////////////////////////////////////////////////////////////////
			// Get a buffer from the demodulator inputQueue
			demodBuffer = demodInputQueue.poll();
			if(demodBuffer != null) {
				demodBuffer.setSize(0);	// mark buffer as empty
				// fill the packet into the buffer and shift its spectrum by mixFrequency:
				source.mixPacketIntoSamplePacket(packet, demodBuffer, mixFrequency);
				demodOutputQueue.offer(demodBuffer);	// deliver packet
			} else {
				Log.d(LOGTAG,"run: Flush the demod queue because demodulator is too slow!");
				while ((tmpFlushBuffer = demodOutputQueue.poll()) != null)
					demodInputQueue.offer(tmpFlushBuffer);
			}

			///// FFT //////////////////////////////////////////////////////////////////////////////
			// If buffer is null we request a new buffer from the fft input queue:
			if(fftBuffer == null) {
				fftBuffer = fftInputQueue.poll();
				if(fftBuffer != null)
					fftBuffer.setSize(0);	// mark buffer as empty
			}

			// If we got a buffer, fill it!
			if(fftBuffer != null)
			{
				// fill the packet into the buffer:
				source.fillPacketIntoSamplePacket(packet,fftBuffer);

				// check if the buffer is now full and if so: deliver it to the output queue
				if(fftBuffer.capacity() == fftBuffer.size()) {
					fftOutputQueue.offer(fftBuffer);
					fftBuffer = null;
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
