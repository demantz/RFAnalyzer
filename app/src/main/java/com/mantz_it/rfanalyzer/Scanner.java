package com.mantz_it.rfanalyzer;

import android.util.Log;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * <h1>RF Analyzer - Scanner</h1>
 *
 * Module:      Scanner.java
 * Description: This class implements the scanning feature used to search the frequency band
 *              for strong signals.
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
public class Scanner {
	private AnalyzerSurface analyzerSurface;
	private RFControlInterface rfControlInterface;
	private boolean stopAfterFirstFound = false;
	private String logFilename;
	private List<Channel> channelList;
	private List<Long> frequencyHoppingList;
	private int frequencyHoppingListIndex;
	private Channel candidateChannel = null;
	private float squelch;
	private boolean scanRunning;
	private long millisOfLastFrequencyChange = 0;
	private int timeToSwitchFrequeny = 500;			// in ms
	private int logInterval = 5000;					// time between logging the same channel (in ms)
	private static final int FREQUENCY_SWITCHING_TIME = 250;
	private static final String LOGTAG = "Scanner";

	/**
	 * constructor.
	 * @param analyzerSurface			AnalyzerSurface instance
	 * @param rfControlInterface	ChannelControlInterface instance to manipulate the demodulation process
	 * @param channelList				List of Channels which should be scanned
	 * @param squelch					The threshold to detect signals
	 * @param stopAfterFirstFound		Stop scanning after first channel that exceeds the squelch threshold
	 * @param logFilename				path to the log file
	 */
	public Scanner(AnalyzerSurface analyzerSurface, RFControlInterface rfControlInterface,
				   List<Channel> channelList, float squelch, boolean stopAfterFirstFound, String logFilename) {
		this.analyzerSurface = analyzerSurface;
		this.rfControlInterface = rfControlInterface;
		this.channelList = channelList;
		this.squelch = squelch;
		this.stopAfterFirstFound = stopAfterFirstFound;
		this.logFilename = logFilename;
		this.scanRunning = true;

		// Create the frequency hopping list:
		int sampleRate = rfControlInterface.requestCurrentSampleRate();
		frequencyHoppingList = new ArrayList<>();
		Channel firstChannel = channelList.get(0);
		Channel lastChannel = channelList.get(channelList.size() - 1);
		// add the first frequency:
		frequencyHoppingList.add(firstChannel.getFrequency() - firstChannel.getBandwidth()/2 + sampleRate/2);
		frequencyHoppingListIndex = 0;
		for(Channel channel: channelList) {
			if(channel.getFrequency() + channel.getBandwidth()/2 > frequencyHoppingList.get(frequencyHoppingListIndex) + sampleRate/2) {
				frequencyHoppingList.add(channel.getFrequency() - channel.getBandwidth() / 2 + sampleRate/2);
				frequencyHoppingListIndex++;
			}
		}
		// Correct the last entry:
		if(frequencyHoppingList.size() > 1)
			frequencyHoppingList.set(frequencyHoppingListIndex, lastChannel.getFrequency() + lastChannel.getBandwidth() - sampleRate/4);
		frequencyHoppingListIndex = 0;

		// DEBUG:
		Log.d(LOGTAG, "constructor: Frequency hopping list:");
		for(Long freq: frequencyHoppingList) {
			Log.d(LOGTAG, "constructor: " + freq);
		}
	}

	public boolean isScanRunning() {
		return scanRunning;
	}

	public void stopScanning() {
		this.scanRunning = false;
	}

	public void processFftSamples(float[] mag) {
		if(!scanRunning)
			return;

		// Ignore all samples if we did a frequency change just before:
		long currentTimeMillis = System.currentTimeMillis();
		long timeSinceLastFrequencyChange = currentTimeMillis - millisOfLastFrequencyChange;
		if(timeSinceLastFrequencyChange < FREQUENCY_SWITCHING_TIME) {
			Log.d(LOGTAG, "processFftSamples: skip round because of recent frequency change...");
			return;
		}

		List<Channel> logList = new ArrayList<Channel>();
		float hzPerSample = (float) rfControlInterface.requestCurrentSampleRate() / mag.length;
		long startFrequency = rfControlInterface.requestCurrentSourceFrequency() - rfControlInterface.requestCurrentSampleRate() / 2;
		long endFrequency = rfControlInterface.requestCurrentSourceFrequency() + rfControlInterface.requestCurrentSampleRate() / 2;

		// calculate the noise floor level
		float noiseFloorLevel =  Float.MAX_VALUE;
		for(float sample: mag) {
			if(sample < noiseFloorLevel)
				noiseFloorLevel = sample;
		}

		// Check all channels in the fft data for signals:
		for(Channel channel: channelList) {
			// check if channel is inside mag array:
			if(channel.getStartFrequency() > startFrequency && channel.getEndFrequency() < endFrequency) {
				// This channel is within the spectrum. Let's check it's level:
				int sum = 0;
				int startIndex = (int) ((channel.getStartFrequency() - startFrequency) / hzPerSample);
				int sampleCount = (int) (channel.getBandwidth() / hzPerSample);
				for(int i = 0; i < sampleCount; i++)
					sum += mag[startIndex + i];
				channel.setLevel((float) sum / sampleCount);
				if(channel.getLevel() > squelch) {
					Log.d(LOGTAG, "processFftSamples: Channel " + channel.getFrequency() + ": level=" + channel.getLevel()
							+ " is higher than squelch=" + squelch);

					// If logging is enabled, we have to log this channel:
					if(logFilename != null && currentTimeMillis - channel.getTimestamp() >= logInterval)
						logList.add(channel);

					// If this is the strongest channel we set it as the candidate channel
					if(candidateChannel == null || candidateChannel.getLevel() < channel.getLevel()) {
						Log.d(LOGTAG, "processFftSamples: Set channel " + channel.getFrequency() + " as new candidate");
						candidateChannel = channel;
					}
				}
			}
		}

		// Log all channels in the log list:
		if(logFilename != null) {
			try {
				SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
				String date = simpleDateFormat.format(new Date());
				FileWriter fileWriter = new FileWriter(logFilename, true);
				for (Channel channel : logList) {
					fileWriter.append(date + " center_freq " + rfControlInterface.requestCurrentSourceFrequency()
							+ " freq " + channel.getFrequency() + " power_db " + channel.getLevel() +
							" noise_floor_db " + noiseFloorLevel + "\n");
					channel.setTimestamp(currentTimeMillis);    // Set the channel timestamp to the last logging time (now)
				}
				fileWriter.close();
			} catch (IOException e) {
				Log.e(LOGTAG, "processFftSamples: Could not write to file (" + logFilename + "): " + e.getMessage());
			}
		}

		// Check if we have to change frequency:
		boolean endOfRun = false;	// In case we have to hop between frequencies: Are we at the end of one round?
		long nextFrequency = -1;	// Next frequency in case we have to hop
		if(frequencyHoppingList.size() > 1) {
			// Check if it is time for another frequency change
			if(timeSinceLastFrequencyChange > timeToSwitchFrequeny) {
				// Check if we have to do a rewind
				frequencyHoppingListIndex++;
				if (frequencyHoppingListIndex >= frequencyHoppingList.size()) {
					frequencyHoppingListIndex = 0;
					endOfRun = true;
				}
				nextFrequency = frequencyHoppingList.get(frequencyHoppingListIndex);
			}
		} else {
			// No need for frequency changes just make sure the source is actually tuned to the correct frequency:
			if(rfControlInterface.requestCurrentSourceFrequency() != frequencyHoppingList.get(0))
			{
				nextFrequency = frequencyHoppingList.get(0);

			}
			endOfRun = true;
		}

		// Select and reset candidate channel (also maybe stop scanning) if this is the end of the round
		if (endOfRun && candidateChannel != null) {
			Log.i(LOGTAG, "processFftSamples: Select channel: " + candidateChannel.getFrequency()
					+ " (Level: " + candidateChannel.getLevel() + ")");
			if (stopAfterFirstFound) {
				scanRunning = false;
				// Make sure we tune the source back to this channel
				nextFrequency = candidateChannel.getFrequency() + rfControlInterface.requestCurrentSampleRate() / 4;
				analyzerSurface.setVirtualFrequency(nextFrequency);
				analyzerSurface.setVirtualSampleRate(rfControlInterface.requestCurrentSampleRate());
			}
			analyzerSurface.setChannelFrequency(candidateChannel.getFrequency());
			analyzerSurface.setChannelWidth(candidateChannel.getBandwidth());
			analyzerSurface.setSquelch(squelch);
			if(rfControlInterface != null && candidateChannel.getMode() != Demodulator.DEMODULATION_OFF) {
				rfControlInterface.updateDemodulationMode(candidateChannel.getMode());
				rfControlInterface.updateChannelWidth(candidateChannel.getBandwidth());
				rfControlInterface.updateChannelFrequency(candidateChannel.getFrequency());
			}
			candidateChannel = null;
		}

		// Do frequency change
		if(nextFrequency > 0) {
			millisOfLastFrequencyChange = currentTimeMillis;
			Log.d(LOGTAG, "processFftSamples: Change frequency from " + rfControlInterface.requestCurrentSourceFrequency() + " to " + nextFrequency);
			rfControlInterface.updateSourceFrequency(nextFrequency);
		}
	}

}
