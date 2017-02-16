package com.mantz_it.rfanalyzer.dsp.impl;

import com.mantz_it.rfanalyzer.dsp.spi.Demodulation;
import com.mantz_it.rfanalyzer.dsp.spi.Packet;

import java.nio.FloatBuffer;

/**
 * Created by pavlus on 14.01.17.
 */
public class FM extends Demodulation {

    protected float modulationDepth;
    private float quadratureGain;
    protected float prevI, prevQ;

    @Override
    public int apply(Packet in, Packet out) {
        FloatBuffer src = in.getBuffer();
        FloatBuffer dst = out.getBuffer();
        final int inputSize = Math.min(src.remaining(), dst.remaining());

        // Quadrature demodulation:
        float nextI = src.get();
        float nextQ = src.get();
        float I, Q;
        I = nextI * prevI + nextQ * prevQ;
        Q = nextQ * prevI - nextI * prevQ;
        dst.put((float) (quadratureGain * Math.atan2(Q, I)));
        for (int i = 1; i < inputSize; i++) {
            prevI = nextI;
            prevQ = nextQ;
            nextI = src.get();
            nextQ = src.get();
            I = nextI * prevI + nextQ * prevQ;
            Q = nextQ * prevI - nextI * prevQ;
            dst.put((float) (quadratureGain * Math.atan2(Q, I)));
        }
        prevI = nextI;
        prevQ = nextQ;
        out.sampleRate = in.sampleRate;
        out.complex = false;
        out.frequency = 0;
        return inputSize;
    }

    public float getModulationDepth() {
        return modulationDepth;
    }

    public void setModulationDepth(float modulationDepth) {
        this.modulationDepth = modulationDepth;
        quadratureGain = (float) (gain * modulationDepth / (2 * Math.PI));
    }

    @Override
    public void setGain(float gain) {
        this.gain = gain;
        this.quadratureGain *= this.gain;
    }

}
