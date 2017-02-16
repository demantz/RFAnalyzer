package com.mantz_it.rfanalyzer;

/**
 * Created by pavlus on 25.06.16.
 */


import android.util.Log;

/**
 * HiQSDR provides 24-bit resolution, it's very good resolution, too good for android devices.
 * Processing requires lots of resources, and we can't use LUT, it would take too much memory even on PC.
 * Possible ways to solve this problem:
 * -> 1. Realtime conversion in hope that we have enough CPU power. (tested for fill, works very good)
 * 2. Using native DSP ()
 * 3. Using RenderScript for DSP
 * 4. Discarding least significant byte and treating signal as 16-bit (can use LUT, takes less than 1 MiB)
 */
@Deprecated
public class Signed24BitIQConverter extends IQConverter {
	protected static final float CONVERTER_SCALE = (1 << 23);
	//protected static final float CONVERTER_SHIFT = 0;//(float) (1 << 23) - 1;
	protected static final int SAMPLES_SHIFT = 2;
	protected static final int SAMPLE_SIZE = 3;
	protected float cosLUT[];
	protected float sinLUT[];

	@Override
	public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, int offset) {
		int capacity = samplePacket.capacity();
		int count = 0;
		int startIndex = samplePacket.size();
		float[] re = samplePacket.re();
		float[] im = samplePacket.im();
		Log.w("S24Converter","offset: "+offset);
		for (int i = offset; i < packet.length; i += SAMPLE_SIZE << 1) {
			re[startIndex + count] = ((packet[i] & 0xff
			                           | (packet[i + 1] & 0xff) << 8
			                           | packet[i + 2] << 16 // keeps sign of most significant byte
			                          )
			                         ) / CONVERTER_SCALE; // I
			im[startIndex + count] = ((packet[i + 3] & 0xff
			                           | (packet[i + 3 + 1] & 0xff) << 8
			                           | packet[i + 3 + 2] << 16
			                          )
			                         ) / CONVERTER_SCALE; // Q
			count++;
			if (startIndex + count >= capacity)
				break;
		}
		samplePacket.setSize(samplePacket.size() + count);    // update the size of the sample packet
		samplePacket.setSampleRate(sampleRate);                // update the sample rate
		samplePacket.setFrequency(frequency);                // update the frequency
		return count;
	}

	@Override
	public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, int offset, long channelFrequency) {
		int mixFrequency = (int) (frequency - channelFrequency);

		generateMixerLookupTable(mixFrequency);    // will only generate table if really necessary

		// Mix the samples from packet and store the results in the samplePacket
		int capacity = samplePacket.capacity();
		int count = 0;
		int startIndex = samplePacket.size();
		float[] re = samplePacket.re();
		float[] im = samplePacket.im();
		int packetLength = packet.length;
		for (int i = offset; i < packetLength; i += SAMPLE_SIZE << 1) {
			float reSample = ((packet[i] & 0xff
			                   | (packet[i + 1] & 0xff) << 8
			                   | packet[i + 2] << 16
			                  )
			                 ) / CONVERTER_SCALE;
			float imSample = ((packet[i + 3] & 0xff
			                   | (packet[i + 3 + 1] & 0xff) << 8
			                   | packet[i + 3 + 2] << 16
			                  )
			                 ) / CONVERTER_SCALE;

			// was bug here?
			re[startIndex + count] = cosLUT[cosineIndex] * reSample
			                         - sinLUT[cosineIndex] * imSample;
			im[startIndex + count] = cosLUT[cosineIndex]* imSample
			                         + sinLUT[cosineIndex] * reSample;
			cosineIndex = (cosineIndex + 1) % cosLUT.length;
			count++;
			if (startIndex + count >= capacity)
				break;
		}
		samplePacket.setSize(samplePacket.size() + count);    // update the size of the sample packet
		samplePacket.setSampleRate(sampleRate);                // update the sample rate
		samplePacket.setFrequency(channelFrequency);        // update the frequency
		return count;
	}

	@Override
	protected void generateLookupTable() {
		// we don't want to make LUT for 24 bit values, because it will take too much memory
	}

	@Override
	protected void generateMixerLookupTable(int mixFrequency) {
		// If mix frequency is too low, just add the sample rate (sampled spectrum is periodic):
		if (mixFrequency == 0 || (sampleRate / Math.abs(mixFrequency) > MAX_COSINE_LENGTH))
			mixFrequency += sampleRate;
		// Only generate lookupTable if null or invalid:
		if (cosLUT == null || mixFrequency != cosineFrequency) {
			cosineFrequency = mixFrequency;
			int bestLength = calcOptimalCosineLength();
			cosLUT = new float[bestLength];
			sinLUT = new float[bestLength];
			float cosineAtT;
			float sineAtT;
			for (int t = 0; t < bestLength; t++) {
				cosineAtT = (float) Math.cos(2 * Math.PI * cosineFrequency * t / sampleRate);
				sineAtT = (float) Math.sin(2 * Math.PI * cosineFrequency * t / sampleRate);
				cosLUT[t] = cosineAtT;
				sinLUT[t] = sineAtT;
			}
			cosineIndex = 0;
		}
	}

	@Override
	public int getSampleSize() {
		return 3;
	}
}
