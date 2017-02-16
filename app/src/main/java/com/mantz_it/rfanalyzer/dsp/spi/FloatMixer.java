package com.mantz_it.rfanalyzer.dsp.spi;

import java.nio.FloatBuffer;

/**
 * Created by pavlus on 12.01.17.
 */

//todo: test and benchmark
public abstract class FloatMixer extends Mixer<FloatBuffer> {
private static final double EPSILON = 1E-6; // 1 Hz per MHz
private static final int DEFAULT_LUT_SIZE = 500 * 2;

public FloatMixer() {
    super(DEFAULT_LUT_SIZE);
}

public FloatMixer(int lutElements) {
    super(lutElements << 1); // complex
}


@Override
protected void setLUTResolution(double cycleLength) {
    if (Math.abs(cycleLength - this.cycleLength) < EPSILON)
        return;
    else
        this.cycleLength = cycleLength;
    // complex numbers interleaved -> multiply by 2
    LUTSize = optimalLUTLength(cycleLength, maxLUTSize) ;
    //System.out.printf("setLUTResolution: cycleLength = %f; LUTSize = %d;\n", cycleLength, LUTSize);
    synchronized (LUT) { // todo: add interruption support in case mixing frequency changes faster, than we generate LUT?
        recalcLUT();
    }
}

@Override
protected void recalcLUT() {
    float[] LUTArr = LUT.array(); // as soon as we use heap buffer, we can use this
    final double phaseStep = 2 * Math.PI / cycleLength;
    double phase = 0;
    //System.out.printf("recalcLUT: phaseStep = %f; LUTSize = %d; maxLUTSize = %d\n", phaseStep, LUTSize, maxLUTSize);
    for (int i = 0; i < LUTSize-1; i += 2) {
        LUTArr[i] = (float) Math.cos(phaseStep * i);
        LUTArr[i + 1] = (float) Math.sin(phaseStep * i);
    }
    LUT.limit(LUTSize);
}

@Override
protected FloatBuffer initLUT() {
    return FloatBuffer.allocate(maxLUTSize);
}

protected static class FloatMixer_AA extends FloatMixer {
    public int apply(FloatBuffer src, FloatBuffer dst) {
        final float[] lut = LUT.array();
        final float[] in = src.array();
        final float[] out = dst.array();

        final int srcOff = src.arrayOffset(), dstOff = dst.arrayOffset();

        int i, o;
        i = src.position() + srcOff;
        o = dst.position() + dstOff;

        int total, left;
        left = total = Math.min(src.remaining(), dst.remaining()); // total to process
        do {
            int count = Math.min(left, LUTSize - position); // iterate `left` times or to the end of LUT
            //System.out.println("MixerAA: left = " + left);
            //System.out.println("MixerAA: count = " + count);
            //System.out.println("MixerAA: position = " + position);
            //System.out.println("MixerAA: LUTSize-position = " + (LUTSize - position));
            left -= count;
            while (count > 0) {
                out[o++] = in[i++] * lut[position++];
                --count;
            }
            if (position >= LUTSize)
                position = 0;
        } while (left > 0);
        src.position(i - srcOff);
        dst.position(o - dstOff);
        return total;
    }
}

protected static class FloatMixer_AB extends FloatMixer {
    public int apply(FloatBuffer src, FloatBuffer dst) {
        final float[] lut = LUT.array();
        final float[] in = src.array();

        final int srcOff = src.arrayOffset();

        int i = src.position() + srcOff;

        int total, left;
        left = total = Math.min(src.remaining(), dst.remaining()); // total to process
        do {
            int count = Math.min(left, LUTSize - position); // iterate `left` times or to the end of LUT
            left -= count;
            while (count > 0) {
                dst.put(in[i++] * lut[position++]);
                --count;
            }
            if (position >= LUTSize)
                position = 0;
        } while (left > 0);
        src.position(i - srcOff);
        return total;
    }
}

protected static class FloatMixer_BA extends FloatMixer {
    public int apply(FloatBuffer src, FloatBuffer dst) {
        final float[] lut = LUT.array();
        final float[] out = dst.array();

        final int dstOff = dst.arrayOffset();

        int o;
        o = dst.position() + dstOff;

        int total, left;
        left = total = Math.min(src.remaining(), dst.remaining()); // total to process
        do {
            int count = Math.min(left, LUTSize - position); // iterate `left` times or to the end of LUT
            left -= count;
            while (count > 0) {
                out[o++] = src.get() * lut[position++];
                --count;
            }
            if (position >= LUTSize)
                position = 0;
        } while (left > 0);
        dst.position(o - dstOff);
        return total;
    }
}

protected static class FloatMixer_BB extends FloatMixer {
    public int apply(FloatBuffer src, FloatBuffer dst) {
        final float[] lut = LUT.array();
        int total, left;
        left = total = Math.min(src.remaining(), dst.remaining()); // total to process
        do {
            int count = Math.min(left, LUTSize - position); // iterate `left` times or to the end of LUT
            left -= count;
            while (count > 0) {
                dst.put(src.get() * lut[position++]);
                --count;
            }
            if (position >= LUTSize)
                position = 0;
        } while (left > 0);
        return total;
    }
}

}
