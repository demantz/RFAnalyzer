package com.mantz_it.rfanalyzer.dsp.impl;

import com.mantz_it.rfanalyzer.dsp.Utils;
import com.mantz_it.rfanalyzer.dsp.spi.DCRemover;
import com.mantz_it.rfanalyzer.dsp.spi.Packet;

import java.nio.FloatBuffer;

/**
 * Created by pavlus on 14.01.17.
 */
public class DCRemover_AA extends DCRemover {
    @Override
    public int apply(Packet in, Packet out) {
        FloatBuffer inBuff = in.getBuffer();
        float[] inArr = inBuff.array();
        int inOffset = inBuff.arrayOffset() + inBuff.position();

        FloatBuffer outBuff = out.getBuffer();
        float[] outArr = outBuff.array();
        int outOffset = outBuff.arrayOffset();

        float mean = (float) Utils.mean(inArr);
        final int cnt = Math.min(inBuff.remaining(), outBuff.remaining());
        for (int i = 0; i < cnt; ++i) {
            outArr[i + outOffset] = inArr[i + inOffset] - mean;
        }
        inBuff.position(inBuff.position() + cnt);
        outBuff.position(outBuff.position() + cnt);
        return inArr.length;
    }
}
