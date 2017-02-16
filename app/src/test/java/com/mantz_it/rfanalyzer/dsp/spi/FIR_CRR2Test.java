package com.mantz_it.rfanalyzer.dsp.spi;

import com.mantz_it.rfanalyzer.dsp.FIRDesigner;
import com.mantz_it.rfanalyzer.dsp.FilterBuilder;
import com.mantz_it.rfanalyzer.dsp.Util;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Created by Pavel on 18.12.2016.
 */
public class FIR_CRR2Test {
	@Test
	public void apply() throws Exception {
		final int samplesCnt = 1024;
		final int decimation = 2;
		float[] taps = FIRDesigner.low_pass(1, samplesCnt, samplesCnt / (4 * decimation), samplesCnt / (4 * decimation), 60, Window.Type.WIN_BLACKMAN, 0);
		FIR_CRR f1 = new FIR_CRR(taps, 2);
		System.out.printf("%%Designed filter %s linear phase response.\n", FilterBuilder.isLinearPhase(f1.getTaps(),1e-6f)?"has":"has not");
		FIR_CRR_LinearPhase f2 = new FIR_CRR_LinearPhase(f1);
		System.out.printf("TapsPrototype = %s;\n", Arrays.toString(f1.getTaps()));
		System.out.printf("TapsCountPrototype = %d;\n", f1.tapsCount);
		System.out.printf("TapsLinearPhase = %s;\n", Arrays.toString(f2.getTaps()));
		System.out.printf("TapsCountLinearPhase = %d;\n", f2.tapsCount);
		float[] interleavedSamples = new float[samplesCnt << 1];
		Util.noise(interleavedSamples);
		Packet in = new Packet(interleavedSamples);
		float[] y1 = Util.applyFilter(f1, in, samplesCnt, decimation);
		float[] y2 = Util.applyFilter(f2, in, samplesCnt, decimation);
		Assert.assertArrayEquals(y1, y2, 1e-6f);
		double err = 0;
		for (int i = 0; i < y1.length; ++i) {
			final float diff = y2[i] - y1[i];
			err += diff * diff;
		}
		System.out.printf("TotalError = %e;", err);
	}


}
