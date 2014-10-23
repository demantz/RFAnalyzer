package com.mantz_it.rfanalyzer;

import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;

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
	private static final int AUDIO_RATE = 32000;
	private static final int AUDIO_DECIMATION = 8;
	private static final int QUADRATURE_RATE = AUDIO_RATE * AUDIO_DECIMATION; // 256000

	private ArrayBlockingQueue<SamplePacket> queue;
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
	private static final int INPUT_FILTER_ATTENUATION = 60;
	private FirFilter inputFilter = null;
	private SamplePacket downsampledSamples;

	// FILTERING
	private static final int USER_FILTER_GAIN = 1;
	private static final int USER_FILTER_ATTENUATION = 60;
	private static final int MIN_USER_FILTER_WIDTH = 2000;
	private FirFilter userFilter = null;
	private int userFilterWidth = 0;
	private SamplePacket quadratureSamples;

	// DEMODULATION

	// AUDIO OUTPUT
	private AudioSink audioSink = null;



	public Demodulator (ArrayBlockingQueue<SamplePacket> queue, int packetSize) {
		this.queue = queue;
		this.packetSize = packetSize;
		// Create internal sample buffers:
		// Note that we create the buffers for the case that there is no downsampling necessary
		// All other cases with input decimation > 1 are also possible because they only need
		// smaller buffers.
		this.downsampledSamples = new SamplePacket(packetSize);
		this.quadratureSamples = new SamplePacket(packetSize);
		this.audioSink = new AudioSink(packetSize);
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
		SamplePacket inputSamples;
		SamplePacket audioBuffer;

		while (!stopRequested) {
			inputSamples = null;    //todo testdata
			this.mixFrequency = 0;          //todo testdata

			// Verify the input sample rate:
			if (inputSamples.getSampleRate() % QUADRATURE_RATE != 0) {
				Log.e(LOGTAG, "run: Input sample rate is not a multiple of " + QUADRATURE_RATE + ". stop.");
				this.stopRequested = true;
				break;
			}

			// mix down		[sample rate is sample rate of source]
			mixDown(inputSamples);

			// downsampling		[sample rate decimated to QUADRATURE_RATE]
			downsampling(inputSamples);
			// The result from downsampling is stored in downsampledSamples

			// filtering		[sample rate is QUADRATURE_RATE]
			applyUserFilter(downsampledSamples);
			// The result from filtering is stored in quadratureSamples

			// get buffer from audio sink
			//audioBuffer = audioSink.getPacketBuffer(1000);

			// demodulate		[sample rate decimated to AUDIO_RATE]
			demodulate(quadratureSamples);

			// play audio
			//audioSink.enqueuePacket(audioBuffer);
		}
	}


	public void mixDown(SamplePacket samples) {
		// We only mix down if the abs(mixFrequency) is higher than MIN_MIX_FREQUENCY
		if(Math.abs(mixFrequency) < MIN_MIX_FREQUENCY)
			return;

		// calculate the expected length of the cosine:
		cosineLength = samples.getSampleRate() / mixFrequency;

		// Verify that the cosine is correct initialized:
		if(cosineReal == null || cosineReal.length != cosineLength) {
			// We have to (re-)create cosine:
			cosineReal = new double[cosineLength];
			cosineImag = new double[cosineLength];
			for (int i = 0; i < cosineLength; i++) {
				cosineReal[i] = Math.cos(2 * Math.PI * mixFrequency * i / (double) samples.getSampleRate());
				cosineImag[i] = Math.sin(2 * Math.PI * mixFrequency * i / (double) samples.getSampleRate());
			}
		}

		// Mix the signal:
		double[] re = samples.re();
		double[] im = samples.im();
		double tmp;
		for (int i = 0; i < samples.size(); i++) {
			tmp   = re[i]*cosineReal[cosineIndex] - im[i]*cosineImag[cosineIndex];
			im[i] = im[i]*cosineReal[cosineIndex] + re[i]*cosineImag[cosineIndex];
			re[i] = tmp;
			cosineIndex = (cosineIndex + 1) % cosineLength;
		}
	}

	public void downsampling(SamplePacket samples) {
		// Verify that the filter is still correct configured:
		if(inputFilter == null || samples.getSampleRate()/QUADRATURE_RATE != inputFilter.getDecimation()) {
			// We have to (re-)create the input filter:
			this.inputFilter = FirFilter.createLowPass(	samples.getSampleRate()/QUADRATURE_RATE,
														INPUT_FILTER_GAIN,
														samples.getSampleRate(),
														QUADRATURE_RATE - (QUADRATURE_RATE/32),
														(QUADRATURE_RATE/32),
														INPUT_FILTER_ATTENUATION);
		}
		downsampledSamples.setSize(0);	// mark buffer as empty
		if(inputFilter.filter(samples,downsampledSamples, 0, samples.size()) < samples.size()) {
			Log.e(LOGTAG, "downsampling: could not filter all samples from input packet.");
		}
		downsampledSamples.setSampleRate(samples.getSampleRate() / inputFilter.getDecimation());
	}


	public void applyUserFilter(SamplePacket samples) {
		// Verify that the filter is still correct configured:
		if(userFilter == null || 2 * (int)(userFilter.getCutOffFrequency() + userFilter.getTransitionWidth()) != userFilterWidth) {
			// We have to (re-)create the user filter:
			double cutOffFrequency, transitionWidth;
			if(userFilterWidth < MIN_USER_FILTER_WIDTH) {
				cutOffFrequency = samples.getSampleRate() / 2 - samples.getSampleRate() / 64;
				transitionWidth = samples.getSampleRate() / 64;
			} else {
				cutOffFrequency = userFilterWidth / 2 - userFilterWidth / 64;
				transitionWidth = userFilterWidth / 64;
			}

			this.userFilter = FirFilter.createLowPass(	1,
														USER_FILTER_GAIN,
														samples.getSampleRate(),
														cutOffFrequency,
														transitionWidth,
														USER_FILTER_ATTENUATION);
			Log.d(LOGTAG,"applyUserFilter: created new user filter with " + userFilter.getNumberOfTaps()
						+ " taps. Cut-Off="+cutOffFrequency + " transition="+transitionWidth);
		}
		quadratureSamples.setSize(0);	// mark buffer as empty
		if(userFilter.filter(samples,quadratureSamples, 0, samples.size()) < samples.size()) {
			Log.e(LOGTAG, "applyUserFilter: could not filter all samples from input packet.");
		}
		quadratureSamples.setSampleRate(samples.getSampleRate());
	}


	public void demodulate(SamplePacket samples) {

	}
}
