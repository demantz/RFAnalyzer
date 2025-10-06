package com.mantz_it.rfanalyzer.source;


/**
 * <h1>RF Analyzer - IQ Converter</h1>
 *
 * Module:      IQConverter.java
 * Description: This class implements methods to convert the raw input data of IQ sources (bytes)
 *              to SamplePackets. It has also methods to do converting and down-mixing at the same
 *              time.
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
public abstract class IQConverter {
	protected long frequency = 0;						// Baseband frequency of the converted samples (is put into the SamplePacket)
	protected int sampleRate = 0;						// Sample rate of the converted samples (is put into the SamplePacket)
	protected float[] lookupTable = null;				// Lookup table to transform IQ bytes into doubles
	protected float[][] cosineRealLookupTable = null;	// Lookup table to transform IQ bytes into frequency shifted doubles
	protected float[][] cosineImagLookupTable = null;	// Lookup table to transform IQ bytes into frequency shifted doubles
	protected int cosineFrequency;						// Frequency of the cosine that is mixed to the signal
	protected int cosineIndex;							// current index within the cosine
	protected static final int MAX_COSINE_LENGTH = 500;	// Max length of the cosine lookup table

	public IQConverter() {
		generateLookupTable();
	}

	public long getFrequency() {
		return frequency;
	}

	public void setFrequency(long frequency) {
		this.frequency = frequency;
	}

	public int getSampleRate() {
		return sampleRate;
	}

	public void setSampleRate(int sampleRate) {
		if(this.sampleRate != sampleRate) {
			this.sampleRate = sampleRate;
			this.cosineFrequency = -1;  // invalidate cosineFrequency. This causes generateMixerLookupTable() to recalculate the lut on next iteration.
		}
	}

	protected int calcOptimalCosineLength() {
		// look for the best fitting array size to hold one or more full cosine cycles:
		double cycleLength = sampleRate / Math.abs((double)cosineFrequency);
		int bestLength = (int) cycleLength;
		double bestLengthError = Math.abs(bestLength-cycleLength);
		for (int i = 1; i*cycleLength < MAX_COSINE_LENGTH ; i++) {
			if(Math.abs(i*cycleLength - (int)(i*cycleLength)) < bestLengthError) {
				bestLength = (int)(i*cycleLength);
				bestLengthError = Math.abs(bestLength - (i*cycleLength));
			}
		}
		return bestLength;
	}

	public abstract int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket);

	public abstract int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency);

	protected abstract void generateLookupTable();

	protected abstract void generateMixerLookupTable(int mixFrequency);


}
