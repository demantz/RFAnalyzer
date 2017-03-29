package com.mantz_it.rfanalyzer.device.hiqsdr;

import org.junit.Test;

import java.util.Arrays;

/**
 * Created by Pavel on 28.03.2017.
 */
public class HiqsdrSourceTest {

@Test
public void testHiQSDRPacketCntr() {
	final byte[] buff = new byte[1442];
	HiqsdrSource src = new HiqsdrSource();
	src.previousPacketIdx = 0;
	for (int i = 1; i < 520; ++i) {
		buff[0] = (byte) (i & 0xff);
		final int m = src.updatePacketIndex(buff[0]);
		if (m != 0)
			System.out.println("testHiQSDRPacketCntr: false positive ("
			                   + "i=" + i
			                   + ", missed=" + m
			                   + ", but must be 0).");
		//else System.out.println("testHiQSDRPacketCntr: ok = "+m);
	}
	for (int j = 2; j < 255; ++j) {
		src.previousPacketIdx = 0;
		for (int i = j; i < j * 520; i += j) {
			buff[0] = (byte) (i & 0xff);
			final byte prev = src.previousPacketIdx;
			final int m = src.updatePacketIndex(buff[0]);
			final byte current = src.previousPacketIdx;
			if (m != (j - 1))
				System.out.println("testHiQSDRPacketCntr: false negative ("
				                   + "i=" + i
				                   + ", j=" + j
				                   + ",prev=" + prev
				                   + ", current=" + current
				                   + ", missed=" + m
				                   + ", must be " + (j - 1) + ").");
				/*else System.out.println("testHiQSDRPacketCntr: ok ("
				                        + "i=" + i
				                        + ", j=" + j
				                        + ",prev=" + prev
				                        + ", current=" + current
				                        + ", missed=" + m
				                        + ").");*/
		}
	}


}

@Test
public void initArrays() {
	System.out.println("Testing HiQSDR._init()");
	System.out.println("\tSamplerate codes: " + Arrays.toString(HiqsdrHelper.SAMPLE_RATE_CODES));
	System.out.println("\tSamplerates     : " + Arrays.toString(HiqsdrHelper.SAMPLE_RATES));
	System.out.println("\tPairs:");
	for (int i = 0; i < HiqsdrHelper.SAMPLE_RATE_CODES.length; ++i)
		System.out.println("\t\t" + HiqsdrHelper.SAMPLE_RATE_CODES[i] + ':' + HiqsdrHelper.SAMPLE_RATES[i]);
	System.out.println("---------------------------------------------------------------------");
}
}