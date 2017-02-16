package com.mantz_it.rfanalyzer.dsp.impl;

import com.mantz_it.rfanalyzer.dsp.spi.Demodulation;
import com.mantz_it.rfanalyzer.dsp.spi.Packet;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.FloatBuffer;

/**
 * Created by pavlus on 14.01.17.
 */
public class AM extends Demodulation {
    @Override
    public void setGain(float gain) {
        this.gain = gain;
    }

    @Override
    public int apply(Packet in, Packet out) {
        FloatBuffer inBuff = in.getBuffer();//.asReadOnlyBuffer();
        FloatBuffer outBuff = out.getBuffer();
        int cnt = 0;
        try {
            for (; ; ) {
                final float re = inBuff.get();
                final float im = inBuff.get();
                final float quad = (re * re + im * im) * gain;
                outBuff.put(quad);
                cnt++;
            }
        } catch (BufferOverflowException | BufferUnderflowException ignored) {
        }
        out.complex = false;
        out.sampleRate = in.sampleRate;
        out.frequency = in.frequency;
        return cnt;
    }
}
