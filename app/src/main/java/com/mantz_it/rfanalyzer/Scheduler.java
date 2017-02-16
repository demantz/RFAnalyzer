package com.mantz_it.rfanalyzer;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * <h1>RF Analyzer - Scheduler</h1>
 * <p>
 * Module:      Scheduler.java
 * Description: This Thread is responsible for forwarding the samples from the input hardware
 * to the Demodulator and to the Processing Loop and at the correct speed and format.
 * Sample packets are passed to other blocks by using blocking queues. The samples passed
 * to the Demodulator will be shifted to base band first.
 * If the Demodulator or the Processing Loop are to slow, the scheduler will automatically
 * drop incoming samples to keep the buffer of the hackrf_android library from beeing filled up.
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
public class Scheduler extends Thread {
	private IQSourceInterface source = null;    // Reference to the source of the IQ samples
	private ArrayBlockingQueue<SamplePacket> fftOutputQueue = null;    // Queue that delivers samples to the Processing Loop
	private ArrayBlockingQueue<SamplePacket> fftInputQueue = null;    // Queue that collects used buffers from the Processing Loop
	private ArrayBlockingQueue<SamplePacket> demodOutputQueue = null;    // Queue that delivers samples to the Demodulator block
	private ArrayBlockingQueue<SamplePacket> demodInputQueue = null;    // Queue that collects used buffers from the Demodulator block
	private long channelFrequency = 0;                    // Shift frequency to this value when passing packets to demodulator
	private boolean demodulationActivated = false;        // Indicates if samples should be forwarded to the demodulator queues or not.
	private boolean squelchSatisfied = false;            // indicates whether the current signal is strong enough to cross the squelch threshold
	private boolean stopRequested = true;
	private BufferedOutputStream bufferedOutputStream = null;    // Used for recording
	private boolean stopRecording = false;

	// Define the size of the fft output and input Queues. By setting this value to 2 we basically end up
	// with double buffering. Maybe the two queues are overkill, but it works pretty well like this and
	// it handles the synchronization between the scheduler thread and the processing loop for us.
	// Note that setting the size to 1 will not work well and any number higher than 2 will cause
	// higher delays when switching frequencies.
	private static final int FFT_QUEUE_SIZE = 2;
	private static final int DEMOD_QUEUE_SIZE = 20;
	private static final String LOGTAG = "Scheduler";

	public Scheduler(int fftSize, IQSourceInterface source) {
		super("Scheduler Thread");
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
			demodInputQueue.offer(new SamplePacket(source.getSampledPacketSize()));
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

	public boolean isDemodulationActivated() {
		return demodulationActivated;
	}

	public void setDemodulationActivated(boolean demodulationActivated) {
		this.demodulationActivated = demodulationActivated;
	}

	public long getChannelFrequency() {
		return channelFrequency;
	}

	public void setChannelFrequency(long channelFrequency) {
		this.channelFrequency = channelFrequency;
	}

	/**
	 * Has to be called when the signal strength of the selected channel crosses the squelch threshold
	 *
	 * @param squelchSatisfied true: the signal is now stronger than the threshold; false: signal is now weaker
	 */
	public void setSquelchSatisfied(boolean squelchSatisfied) {
		this.squelchSatisfied = squelchSatisfied;
	}

	/**
	 * Will stop writing samples to the bufferedOutputStream and close it.
	 */
	public void stopRecording() {
		this.stopRecording = true;
	}

	/**
	 * Will start writing the raw samples to the bufferedOutputStream. Stream will be
	 * closed on error, on stopRecording() and on stopSampling()
	 *
	 * @param bufferedOutputStream stream to write the samples out.
	 */
	public void startRecording(BufferedOutputStream bufferedOutputStream) {
		this.stopRecording = false;
		this.bufferedOutputStream = bufferedOutputStream;
		Log.i(LOGTAG, "startRecording: Recording started.");
	}

	/**
	 * @return true if currently recording; false if not
	 */
	public boolean isRecording() {
		return bufferedOutputStream != null;
	}

	@Override
	public void run() {
		Log.i(LOGTAG, "Scheduler started. (Thread: " + this.getName() + ")");
		SamplePacket fftBuffer = null;        // reference to a buffer we got from the fft input queue to fill
		SamplePacket demodBuffer = null;    // reference to a buffer we got from the demod input queue to fill
		while (!stopRequested) {
			// Get a new packet from the source:
			byte[] packet = source.getPacket(1000);
			if (packet == null) {
				Log.e(LOGTAG, "run: No more packets from source. Shutting down...");
				this.stopScheduler();
				break;
			}

			///// Recording ////////////////////////////////////////////////////////////////////////
			if (bufferedOutputStream != null) {
				try {
					bufferedOutputStream.write(packet);
				} catch (IOException e) {
					Log.e(LOGTAG, "run: Error while writing to output stream (recording): " + e.getMessage());
					this.stopRecording();
				}
				if (stopRecording) {
					try {
						bufferedOutputStream.close();
					} catch (IOException e) {
						Log.e(LOGTAG, "run: Error while closing output stream (recording): " + e.getMessage());
					}
					bufferedOutputStream = null;
					Log.i(LOGTAG, "run: Recording stopped.");
				}
			}

			///// Demodulation /////////////////////////////////////////////////////////////////////
			if (demodulationActivated && squelchSatisfied) {
				// Get a buffer from the demodulator inputQueue
				demodBuffer = demodInputQueue.poll();
				if (demodBuffer != null) {
					demodBuffer.setSize(0);    // mark buffer as empty
					// fill the packet into the buffer and shift its spectrum by mixFrequency:
					source.mixPacketIntoSamplePacket(packet, demodBuffer, channelFrequency);
					demodOutputQueue.offer(demodBuffer);    // deliver packet
				} else {
					Log.d(LOGTAG, "run: Flush the demod queue because demodulator is too slow!");
					demodOutputQueue.drainTo(demodInputQueue);
				}
			}

			///// FFT //////////////////////////////////////////////////////////////////////////////
			// If buffer is null we request a new buffer from the fft input queue:
			if (fftBuffer == null) {
				fftBuffer = fftInputQueue.poll();
				if (fftBuffer != null)
					fftBuffer.setSize(0);    // mark buffer as empty
			}

			// If we got a buffer, fill it!
			if (fftBuffer != null) {
				// fill the packet into the buffer:
				source.fillPacketIntoSamplePacket(packet, fftBuffer);

				// check if the buffer is now full and if so: deliver it to the output queue
				if (fftBuffer.full()) {
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
		if (bufferedOutputStream != null) {
			try {
				bufferedOutputStream.close();
			} catch (IOException e) {
				Log.e(LOGTAG, "run: Error while closing output stream (cleanup)(recording): " + e.getMessage());
			}
			bufferedOutputStream = null;
		}
		Log.i(LOGTAG, "Scheduler stopped. (Thread: " + this.getName() + ")");
	}
}
