package com.mantz_it.rfanalyzer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - Audio Sink</h1>
 *
 * Module:      AudioSink.java
 * Description: This class implements the interface to the systems audio API.
 *              It will run in a separate thread and buffer incoming sample packets
 *              in a blocking queue. Input packets are demodulated (real) signals.
 *              This class will decimate the incoming sample rate according to the
 *              audio rate.
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
public class AudioSink extends Thread {
	private AudioTrack audioTrack = null;
	private boolean stopRequested = true;
	private ArrayBlockingQueue<SamplePacket> inputQueue = null;
	private ArrayBlockingQueue<SamplePacket> outputQueue = null;
	private int packetSize;
	private int sampleRate;
	private static final int QUEUE_SIZE = 2;	// This results in a double buffer. see Scheduler...
	private static final String LOGTAG = "AudioSink";
	private FirFilter audioFilter1 = null;
	private FirFilter audioFilter2 = null;
	private SamplePacket tmpAudioSamples;

	public AudioSink (int packetSize, int sampleRate) {
		this.packetSize = packetSize;
		this.sampleRate = sampleRate;

		// Create the queues and fill them with
		this.inputQueue = new ArrayBlockingQueue<SamplePacket>(QUEUE_SIZE);
		this.outputQueue = new ArrayBlockingQueue<SamplePacket>(QUEUE_SIZE);
		for (int i = 0; i < QUEUE_SIZE; i++)
			this.outputQueue.offer(new SamplePacket(packetSize));

		// Create an instance of the AudioTrack class:
		int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
		this.audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
									AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);

		// Create the audio filters:
		this.audioFilter1 = FirFilter.createLowPass(2, 1, 1, 0.1, 0.15, 30);
		Log.d(LOGTAG,"constructor: created audio filter 1 with " + audioFilter1.getNumberOfTaps() + " Taps.");
		this.audioFilter2 = FirFilter.createLowPass(4, 1, 1, 0.1, 0.1, 30);
		Log.d(LOGTAG,"constructor: created audio filter 2 with " + audioFilter2.getNumberOfTaps() + " Taps.");
		this.tmpAudioSamples = new SamplePacket(packetSize);
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
	public void stopSink () {
		stopRequested = true;
	}

	/**
	 * @return size of the packets that are offered by getPacketBuffer()
	 */
	public int getPacketSize() {
		return packetSize;
	}

	/**
	 * The AudioSink allocates the buffers for audio playback. Use this method to request
	 * a free buffer. This method will block if no buffer is available.
	 *
	 * @param timeout	max time this method will block
	 * @return free buffer or null if no buffer available
	 */
	public SamplePacket getPacketBuffer(int timeout) {
		try {
			return outputQueue.poll(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Log.e(LOGTAG,"getPacketBuffer: Interrupted. return null...");
			return null;
		}
	}

	/**
	 * Enqueues a packet buffer for being played on the audio track.
	 *
	 * @param packet	the packet buffer from getPacketBuffer() filled with samples
	 * @return true if success, false if error
	 */
	public boolean enqueuePacket(SamplePacket packet) {
		if(packet == null) {
			Log.e(LOGTAG, "enqueuePacket: Packet is null.");
			return false;
		}
		if(!inputQueue.offer(packet)) {
			Log.e(LOGTAG, "enqueuePacket: Queue is full.");
			return false;
		}
		return true;
	}

	@Override
	public void run() {
		SamplePacket packet;
		SamplePacket filteredPacket = new SamplePacket(packetSize);
		double[] doublePacket;
		short[] shortPacket = new short[packetSize];

		Log.i(LOGTAG,"AudioSink started. (Thread: " + this.getName() + ")");

		// start audio playback:
		audioTrack.play();

		// Continuously write the data from the queue to the audio track:
		while (!stopRequested) {
			try {
				// Get the next packet from the queue
				packet = inputQueue.poll(1000, TimeUnit.MILLISECONDS);

				if(packet == null) {
					Log.d(LOGTAG, "run: Queue is empty. skip this round");
					continue;
				}

				// apply audio filter (decimation)
				applyAudioFilter(packet, filteredPacket);

				// Convert doubles to shorts [expect doubles to be in [-1...1]
				doublePacket = filteredPacket.re();
				for (int i = 0; i < filteredPacket.size(); i++) {
					shortPacket[i] = (short) (doublePacket[i] * 32767);
				}

				// Write it to the audioTrack:
				if(audioTrack.write(shortPacket, 0, filteredPacket.size()) != filteredPacket.size()) {
					Log.e(LOGTAG,"run: write() returned with error! stop");
					stopRequested = true;
				}

				// Return the buffer to the output queue
				outputQueue.offer(packet);
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "run: Interrupted while polling from queue. stop");
				stopRequested = true;
			}
		}

		// stop audio playback:
		audioTrack.stop();
		this.stopRequested = true;
		Log.i(LOGTAG,"AudioSink stopped. (Thread: " + this.getName() + ")");
	}

	public void applyAudioFilter(SamplePacket input, SamplePacket output) {
		// if we need a decimation of 8: apply first and second filter (decimate to input_rate/8)
		if(input.getSampleRate()/sampleRate == 8) {
			// apply first filter (decimate to input_rate/2)
			tmpAudioSamples.setSize(0);	// mark buffer as empty
			if (audioFilter1.filterReal(input, tmpAudioSamples, 0, input.size()) < input.size()) {
				Log.e(LOGTAG, "applyAudioFilter: [audioFilter1] could not filter all samples from input packet.");
			}

			// apply second filter (decimate to input_rate/8)
			output.setSize(0);
			if (audioFilter2.filterReal(tmpAudioSamples, output, 0, tmpAudioSamples.size()) < tmpAudioSamples.size()) {
				Log.e(LOGTAG, "applyAudioFilter: [audioFilter2] could not filter all samples from input packet.");
			}
		} else if (input.getSampleRate()/sampleRate == 2){
			// apply first filter (decimate to input_rate/2 )
			output.setSize(0);
			if (audioFilter1.filterReal(input, output, 0, input.size()) < input.size()) {
				Log.e(LOGTAG, "applyAudioFilter: [audioFilter1] could not filter all samples from input packet.");
			}
		} else
			Log.e(LOGTAG,"applyAudioFilter: incoming sample rate is not supported!");
	}
}
