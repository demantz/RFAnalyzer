package com.mantz_it.rfanalyzer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - Audio Sink</h1>
 * <p/>
 * Module:      AudioSink.java
 * Description: This class implements the interface to the systems audio API.
 * It will run in a separate thread and buffer incoming sample packets
 * in a blocking queue. Input packets are demodulated (real) signals.
 * This class will decimate the incoming sample rate according to the
 * audio rate.
 *
 * @author Dennis Mantz
 *         <p/>
 *         Copyright (C) 2014 Dennis Mantz
 *         License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *         <p/>
 *         This library is free software; you can redistribute it and/or
 *         modify it under the terms of the GNU General Public
 *         License as published by the Free Software Foundation; either
 *         version 2 of the License, or (at your option) any later version.
 *         <p/>
 *         This library is distributed in the hope that it will be useful,
 *         but WITHOUT ANY WARRANTY; without even the implied warranty of
 *         MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *         General Public License for more details.
 *         <p/>
 *         You should have received a copy of the GNU General Public
 *         License along with this library; if not, write to the Free Software
 *         Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
public class AudioSink extends Thread {
	private AudioTrack audioTrack = null;        // AudioTrack object that is used to pass audio samples to the Android system
	private boolean stopRequested = true;
	private ArrayBlockingQueue<SamplePacket> inputQueue = null;        // Queue that holds incoming samples
	private ArrayBlockingQueue<SamplePacket> outputQueue = null;    // Queue that holds available buffers
	private int packetSize;        // packet size of the incoming sample packets
	private int sampleRate;        // audio sample rate of the AudioSink
	private static final int QUEUE_SIZE = 2;    // This results in a double buffer. see Scheduler...
	private static final String LOGTAG = "AudioSink";
	private FirFilter audioFilter1 = null;        // Filter used to decimate the incoming signal rate
	private FirFilter audioFilter2 = null;        // Cascaded filter for high incoming signal rates
	private SamplePacket tmpAudioSamples;        // tmp buffer for audio filters.

	/**
	 * Constructor. Will create a new AudioSink.
	 *
	 * @param packetSize size of the incoming packets
	 * @param sampleRate sample rate of the audio signal
	 */
	public AudioSink(int packetSize, int sampleRate) {
		super("AudioSink Thread");
		Log.v(LOGTAG, "constructor: packetSize: " + packetSize + ", sampleRate: " + sampleRate);
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
		this.audioFilter1 = FirFilter.createLowPass(2, 1, 1, 0.1f, 0.15f, 30);
		Log.d(LOGTAG, "constructor: created audio filter 1 with " + audioFilter1.getNumberOfTaps() + " Taps.");
		this.audioFilter2 = FirFilter.createLowPass(4, 1, 1, 0.1f, 0.1f, 30);
		Log.d(LOGTAG, "constructor: created audio filter 2 with " + audioFilter2.getNumberOfTaps() + " Taps.");
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
	public void stopSink() {
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
	 * @param timeout max time this method will block
	 * @return free buffer or null if no buffer available
	 */
	public SamplePacket getPacketBuffer(int timeout) {
		try {
			return outputQueue.poll(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Log.e(LOGTAG, "getPacketBuffer: Interrupted. return null...");
			return null;
		}
	}

	/**
	 * Enqueues a packet buffer for being played on the audio track.
	 *
	 * @param packet the packet buffer from getPacketBuffer() filled with samples
	 * @return true if success, false if error
	 */
	public boolean enqueuePacket(SamplePacket packet) {
		if (packet == null) {
			Log.e(LOGTAG, "enqueuePacket: InterleavedPacket is null.");
			return false;
		}
		if (!inputQueue.offer(packet)) {
			Log.e(LOGTAG, "enqueuePacket: Queue is full.");
			return false;
		}
		return true;
	}

	short[] shortPacket;

	@Override
	public void run() {
		SamplePacket packet;
		SamplePacket filteredPacket;
		SamplePacket tempPacket = new SamplePacket(packetSize);
		float[] floatPacket;
		shortPacket = new short[packetSize];

		Log.i(LOGTAG, "AudioSink started. (Thread: " + this.getName() + ")");

		// start audio playback:
		audioTrack.play();

		// Continuously write the data from the queue to the audio track:
		while (!stopRequested) {
			try {
				// Get the next packet from the queue
				packet = inputQueue.poll(1000, TimeUnit.MILLISECONDS);

				if (packet == null) {
					//Log.d(LOGTAG, "run: Queue is empty. skip this round");
					continue;
				}
				//Log.v(LOGTAG, "packet rate: " + packet.getSampleRate()+'('+this.sampleRate+'*'+(packet.getSampleRate()/this.sampleRate)+')');
				// apply audio filter (decimation)
				if (packet.getSampleRate() > this.sampleRate) {
					applyAudioFilter(packet, tempPacket);
					filteredPacket = tempPacket;
				} else
					filteredPacket = packet;

				// Convert doubles to shorts [expect doubles to be in [-1...1]
				floatPacket = filteredPacket.re();
				final int filteredPacketSize = filteredPacket.size();
				//Log.v(LOGTAG, "filteredPacket size: " + filteredPacketSize);
				//Log.v(LOGTAG, "filteredPacket: " + Arrays.toString(floatPacket));
				for (int i = 0; i < filteredPacketSize; i++) {
					shortPacket[i] = (short) (floatPacket[i] * 32767);
				}
				// Write it to the audioTrack:
				if (audioTrack.write(shortPacket, 0, filteredPacketSize) != filteredPacketSize) {
					Log.e(LOGTAG, "run: write() returned with error! stop");
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
		Log.i(LOGTAG, "AudioSink stopped. (Thread: " + this.getName() + ")");
	}

	/**
	 * Will apply the real array contained in input and decimate them to the audio rate.
	 *
	 * @param input  incoming (unfiltered) samples at the incoming rate (quadrature rate)
	 * @param output outgoing (filtered, decimated) samples at audio rate
	 */
	public void applyAudioFilter(SamplePacket input, SamplePacket output) {
		// if we need a decimation of 8: apply first and second filter (decimate to input_rate/8)
		final int inputSize = input.size();
		final int decimation = input.getSampleRate() / sampleRate;
		if ( decimation == 8) {
			// apply first filter (decimate to input_rate/2)
			tmpAudioSamples.setSize(0);    // mark buffer as empty
			if (audioFilter1.filterReal(input, tmpAudioSamples, 0, inputSize) < inputSize) {
				Log.e(LOGTAG, "applyAudioFilter: [audioFilter1] could not filter all samples from input packet.");
			}

			// apply second filter (decimate to input_rate/8)
			output.setSize(0);
			final int tmpAudioSamplesSize = tmpAudioSamples.size();
			if (audioFilter2.filterReal(tmpAudioSamples, output, 0, tmpAudioSamplesSize) < tmpAudioSamplesSize) {
				Log.e(LOGTAG, "applyAudioFilter: [audioFilter2] could not apply all samples from input packet.");
			}
		} else if (decimation == 2) {
			// apply first filter (decimate to input_rate/2 )
			output.setSize(0);
			if (audioFilter1.filterReal(input, output, 0, inputSize) < inputSize) {
				Log.e(LOGTAG, "applyAudioFilter: [audioFilter1] could not apply all samples from input packet.");
			}
		} else
			Log.e(LOGTAG, "applyAudioFilter: incoming sample rate is not supported!");
	}
}
