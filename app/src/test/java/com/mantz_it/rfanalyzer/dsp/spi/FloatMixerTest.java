package com.mantz_it.rfanalyzer.dsp.spi;

import com.mantz_it.rfanalyzer.dsp.Util;
import com.mantz_it.rfanalyzer.dsp.impl.SoftFFT;

import org.junit.Assert;
import org.junit.Test;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Created by pavlus on 13.01.17.
 */
public class FloatMixerTest {

PacketPool A = PacketPool.getArrayPacketPool();
PacketPool B = PacketPool.getDirectPacketPool();


@Test
public void testFloatMixers() {
    FloatMixer AA = new FloatMixer.FloatMixer_AA();
    FloatMixer AB = new FloatMixer.FloatMixer_AB();
    FloatMixer BA = new FloatMixer.FloatMixer_BA();
    FloatMixer BB = new FloatMixer.FloatMixer_BB();
    int fftSize = 512;
    int packetSize = fftSize * 2;
    float[] resultAA = testFloatMixer(AA, A, A, packetSize, fftSize);
    float[] resultAB = testFloatMixer(AB, A, B, packetSize, fftSize);
    float[] resultBA = testFloatMixer(BA, B, A, packetSize, fftSize);
    float[] resultBB = testFloatMixer(BB, B, B, packetSize, fftSize);
    Assert.assertArrayEquals(resultAA, resultAB, 1e-6f);
    Assert.assertArrayEquals(resultAB, resultBA, 1e-6f);
    Assert.assertArrayEquals(resultBB, resultBA, 1e-6f);
    Assert.assertArrayEquals(resultBB, resultAA, 1e-6f);
}

private static float[] testFloatMixer(FloatMixer mixer, PacketPool inPool, PacketPool outPool, int packetSize, int fftSize) {
    FFT fft = new SoftFFT(fftSize);
    int rate = 1 << 15;
    float[] interleavedSamples = new float[packetSize];
    float[] resultSamples;
    float[] origSpectrum, resultSpectrum;
    mixer.setChannelFrequency(rate, 64);
    Util.mixFrequencies(rate, interleavedSamples, 128, 64/*,*20000/*, 300000*/);
    origSpectrum = Util.spectrum(fft, interleavedSamples, 0);

    Packet in = inPool.acquire(interleavedSamples.length);
    Packet out = outPool.acquire(interleavedSamples.length);
    in.getBuffer().put(interleavedSamples).flip();

    int cnt = mixer.apply(in.getBuffer(), out.getBuffer());

    FloatBuffer buff = out.getBuffer();
    buff.flip();
    int offset;
    if (buff.hasArray()) {
        resultSamples = buff.array();
        offset = buff.arrayOffset();
    } else {
        resultSamples = new float[buff.remaining()];
        buff.get(resultSamples, 0, buff.remaining());
        offset = 0;
    }
    resultSpectrum = Util.spectrum(fft, resultSamples, offset);

    String mixerName = mixer.getClass().getSimpleName();
    //System.out.printf("ProcessedItems%s = %d;\n", mixerName, cnt);
    System.out.printf("samplesOrig%s = %s;\n", mixerName, Arrays.toString(interleavedSamples));
    System.out.printf("samplesRslt%s = %s;\n", mixerName, Arrays.toString(resultSamples));
    System.out.printf("spectrumOrig%s = %s;\n", mixerName, Arrays.toString(origSpectrum));
    System.out.printf("spectrumRslt%s = %s;\n", mixerName, Arrays.toString(resultSpectrum));
    System.out.printf("LUT_%s = %s;\n", mixerName, Arrays.toString(mixer.LUT.array()));

    Util.plotInterleavedSamples("samplesOrig" + mixerName);
    Util.plotInterleavedSamples("samplesRslt" + mixerName);

    System.out.println("figure;");
    System.out.println(" hold on;");
    System.out.printf("plot(spectrumOrig%s, 'color', 'green');", mixerName);
    System.out.printf("plot(spectrumRslt%s, 'color', 'red');", mixerName);
    System.out.println("\nhold off;");

    Util.plotInterleavedSamples("LUT_" + mixerName);

    return resultSamples;
}

}
