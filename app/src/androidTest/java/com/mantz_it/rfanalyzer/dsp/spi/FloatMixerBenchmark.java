package com.mantz_it.rfanalyzer.dsp.spi;

import android.app.Application;
import android.os.SystemClock;
import android.test.ApplicationTestCase;

import java.nio.FloatBuffer;
import java.util.GregorianCalendar;

/**
 * Created by pavlus on 14.01.17.
 */
public class FloatMixerBenchmark extends ApplicationTestCase<Application> {
PacketPool A = PacketPool.getArrayPacketPool();
PacketPool B = PacketPool.getDirectPacketPool();

public FloatMixerBenchmark() {
    super(Application.class);
}

public void testBenchmarkFloatMixers() {

    int packetSize = 1 << 14;
    int rounds = 1000;
    Packet I1 = A.acquire(packetSize);
    Packet I2 = A.acquire(packetSize);
    Packet D1 = B.acquire(packetSize);
    Packet D2 = B.acquire(packetSize);
    FloatMixer AA = new FloatMixer.FloatMixer_AA();
    FloatMixer AB = new FloatMixer.FloatMixer_AB();
    FloatMixer BA = new FloatMixer.FloatMixer_BA();
    FloatMixer BB = new FloatMixer.FloatMixer_BB();
    benchmarkFloatMixer(AA, I1.getBuffer(), I2.getBuffer(), rounds);
    benchmarkFloatMixer(AB, I1.getBuffer(), D2.getBuffer(), rounds);
    benchmarkFloatMixer(BA, D1.getBuffer(), I2.getBuffer(), rounds);
    benchmarkFloatMixer(BB, D1.getBuffer(), D2.getBuffer(), rounds);

    I1.release();
    I2.release();
    D1.release();
    D2.release();

}

private static void benchmarkFloatMixer(FloatMixer mixer, FloatBuffer in, FloatBuffer out, int rounds) {
    String mixerName = mixer.getClass().getSimpleName();
    mixer.setChannelFrequency(in.capacity() / 2, in.capacity() / 6);
    System.out.printf("%%%% %s: Benchmarking %s ...\n", GregorianCalendar.getInstance().getTime().toString(), mixerName);
    System.out.printf("%% Warmup...\n");
    out.clear();
    in.limit(in.capacity()).position(0);
    for (int i = 0; i < rounds / 5; ++i) {
        mixer.apply(in, out);
        out.clear();
        in.flip();
    }
    System.out.println("%% Benchmark ... ");
    long start = SystemClock.currentThreadTimeMillis();
    for (int i = 0; i < rounds; ++i) {
        mixer.apply(in, out);
        out.clear();
        in.flip();
    }
    long elapsed = SystemClock.currentThreadTimeMillis() - start;
    System.out.printf("timePerBuffer_%s = %f; %% ms\n", mixerName, elapsed / (double) rounds);
}

}
