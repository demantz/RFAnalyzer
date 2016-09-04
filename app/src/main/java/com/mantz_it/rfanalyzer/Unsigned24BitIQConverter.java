package com.mantz_it.rfanalyzer;

/**
 * Created by pavlus on 25.06.16.
 */


/**
 * HiQSDR provides 24-bit resolution, it's very good resolution, too good for android devices.
 * Processing requires lots of resources, and we can't use LUT, it would take too much memory even on PC.
 * Possible ways to solve this problem:
 * -> 1. Realtime conversion in hope that we have enough CPU power. (tested for fill, works very good)
 * 2. Using native DSP ()
 * 3. Using RenderScript for DSP
 * 4. Discarding least significant byte and treating signal as 16-bit (can use LUT, takes less than 1 MiB)
 */

public class Unsigned24BitIQConverter extends IQConverter {

    protected static final float CONVERTER_SCALE = 1.0f / (1 << 23);
    protected static final float CONVERTER_SHIFT = (float) (1 << 23) - 1;
    @Override
    public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
        int capacity = samplePacket.capacity();
        int count = 0;
        int startIndex = samplePacket.size();
        float[] re = samplePacket.re();
        float[] im = samplePacket.im();
        // skip first two bytes, step is two 24bit values == 6 bytes
        for (int i = 2; i < re.length; i += 6) {
            // interesting, but direct conversion w/ LUT works on 24bit samples even faster, than LUT on 8bit
            // and direct conversion on 8bit samples is slower, than LUT
            // 6 MiB 24 bits per sample packet filled in ≈40ms
            // and 2 MiB 8 bits per sample -- 50-60 ms, but w/ LUT 70-80ms
            re[startIndex + count] = ((packet[i] & 0xff
                                       | (packet[i + 1] & 0xff) << 8
                                       | (packet[i + 2] & 0xff) << 16
                                      ) - CONVERTER_SHIFT
                                     ) * CONVERTER_SCALE; // I
            im[startIndex + count] = ((packet[i + 3] & 0xff
                                       | (packet[i + 3 + 1] & 0xff) << 8
                                       | (packet[i + 3 + 2] & 0xff) << 16
                                      ) - CONVERTER_SHIFT
                                     ) * CONVERTER_SCALE; // Q
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
    public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
        // TODO: 25.06.16
        return 0;
    }

    @Override
    protected void generateLookupTable() {
        // we don't want to make LUT for 24 bit values, because it will take too much memory
    }

    @Override
    protected void generateMixerLookupTable(int mixFrequency) {
// TODO: 25.06.16  
    }
}
