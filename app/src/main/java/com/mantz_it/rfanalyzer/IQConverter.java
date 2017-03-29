package com.mantz_it.rfanalyzer;


import android.util.Log;

import com.mantz_it.rfanalyzer.control.Control;
import com.mantz_it.rfanalyzer.control.Controllable;
import com.mantz_it.rfanalyzer.sdr.controls.MixerFrequency;
import com.mantz_it.rfanalyzer.sdr.controls.MixerSampleRate;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <h1>RF Analyzer - IQ Conversion</h1>
 * <p>
 * Module:      IQConverter.java
 * Description: This class implements methods to convert the raw input data of IQ sources (bytes)
 * to SamplePackets. It has also methods to do converting and down-mixing at the same
 * time.
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
@Deprecated
public abstract class IQConverter implements Controllable {
protected float[] lookupTable = null;                // Lookup table to transform IQ bytes into doubles
protected float[][] cosineRealLookupTable = null;    // Lookup table to transform IQ bytes into frequency shifted doubles
protected float[][] cosineImagLookupTable = null;    // Lookup table to transform IQ bytes into frequency shifted doubles
protected int cosineFrequency;                        // Frequency of the cosine that is mixed to the signal
protected int cosineIndex;                            // current index within the cosine
protected static final int MAX_COSINE_LENGTH = 500;    // Max length of the cosine lookup table

protected final Map<Class<? extends Control>, Control> controls;

public IQConverter() {
	controls = new HashMap<>();
	controls.put(
			MixerFrequency.class,
			//MethodInterceptor.wrapWithLog(
			new IQMixerFrequency());
	//, "MixerFrequency", MixerFrequency.class));
	controls.put(
			MixerSampleRate.class,
			//MethodInterceptor.wrapWithLog(
			new IQMixerSampleRate());
	//, "MixerSampleRate", MixerSampleRate.class));
	generateLookupTable();
}


protected int calcOptimalCosineLength() {
	// look for the best fitting array size to hold one or more full cosine cycles:
	double cycleLength = getControl(MixerSampleRate.class).get() / Math.abs((double) cosineFrequency);
	int bestLength = (int) cycleLength;
	double bestLengthError = Math.abs(bestLength - cycleLength);
	for (int i = 1; i * cycleLength < MAX_COSINE_LENGTH; i++) {
		if (Math.abs(i * cycleLength - (int) (i * cycleLength)) < bestLengthError) {
			bestLength = (int) (i * cycleLength);
			bestLengthError = Math.abs(bestLength - (i * cycleLength));
		}
	}
	return bestLength;
}

@Deprecated
public abstract int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, int offset);

@Deprecated
public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
	return fillPacketIntoSamplePacket(packet, samplePacket, -1);
}

@Deprecated
public abstract int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, int offset, long channelFrequency);

@Deprecated
public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
	return mixPacketIntoSamplePacket(packet, samplePacket, 0, channelFrequency);
}

protected abstract void generateLookupTable();

protected abstract void generateMixerLookupTable(int mixFrequency);

public abstract int getSampleSize();

@Override
@SuppressWarnings("unchecked")
public <T extends Control> T getControl(Class<T> clazz) {
	return (T) controls.get(clazz);
}

@Override
public Collection<Control> getControls() {
	return Collections.unmodifiableCollection(controls.values());
}

private static class IQMixerFrequency implements MixerFrequency {
	private volatile long frequency = 0;                        // Baseband frequency of the converted samples (is put into the SamplePacket)

	@Override
	public Long get() {
		return frequency;
	}

	@Override
	public void set(Long frequency) {
		this.frequency = frequency;
	}

}

private class IQMixerSampleRate implements MixerSampleRate {
	private volatile int sampleRate = 1;                        // Sample rate of the converted samples (is put into the SamplePacket)

	@Override
	public void set(Integer sampleRate) {
		if (this.sampleRate != sampleRate) {
			this.sampleRate = sampleRate;
			generateMixerLookupTable(cosineFrequency);
		}
		Log.d("!!!!!!", String.format("Mixer samplerate set(%d), now %d", sampleRate, this.sampleRate));
	}

	@Override
	public Integer get() {
		return sampleRate;
	}
}
}
