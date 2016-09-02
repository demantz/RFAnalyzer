package com.mantz_it.rfanalyzer;

/**
 * Created by pavlus on 25.06.16.
 */
public class Unsigned24BitIQConverter extends IQConverter {

// TODO: determine how this is supposed to work 
@Override
public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
    return 0;
}

@Override
public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
    // TODO: 25.06.16  
    return 0;
}

@Override
protected void generateLookupTable() {
// TODO: 25.06.16  
}

@Override
protected void generateMixerLookupTable(int mixFrequency) {
// TODO: 25.06.16  
}
}
