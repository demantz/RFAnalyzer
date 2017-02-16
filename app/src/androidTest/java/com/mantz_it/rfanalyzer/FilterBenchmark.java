package com.mantz_it.rfanalyzer;

import android.app.Application;
import android.os.SystemClock;
import android.test.ApplicationTestCase;

import com.mantz_it.rfanalyzer.dsp.ConvergenceException;
import com.mantz_it.rfanalyzer.dsp.FilterBuilder;
import com.mantz_it.rfanalyzer.dsp.spi.FIR;
import com.mantz_it.rfanalyzer.dsp.spi.FIR_CCC;
import com.mantz_it.rfanalyzer.dsp.spi.FIR_CRC;
import com.mantz_it.rfanalyzer.dsp.spi.FIR_CRR;
import com.mantz_it.rfanalyzer.dsp.spi.FIR_CRR_LinearPhase;
import com.mantz_it.rfanalyzer.dsp.spi.Packet;
import com.mantz_it.rfanalyzer.dsp.spi.PacketPool;
import com.mantz_it.rfanalyzer.dsp.spi.Window;

/**
 * Created by Pavel on 15.12.2016.
 */

public class FilterBenchmark extends ApplicationTestCase<Application> {
	public FilterBenchmark() {
		super(Application.class);
	}


	public void testSizes() {
		final int[] samplesCnt = {1<<4, 1<<5, 1<<6, 1<<7, 1<<8, 1<<9, 1 << 10, 1 << 11, 1 << 12, 1 << 13, 1 << 14, 1 << 15, 1 << 16, 1<<17};
		int sampleRate = 1000000;
		int rounds = 10000;
		PacketPool direct = PacketPool.getDirectPacketPool();
		PacketPool indirect = PacketPool.getIndirectPacketPool();
		PacketPool array = PacketPool.getArrayPacketPool();
		FIR filter = FilterBuilder.lowPass_CRC(1, 1, sampleRate, sampleRate / 2 - sampleRate / 10, sampleRate / 10, 30, Window.Type.WIN_BLACKMAN, 0);
		describeFIR(filter);
		System.out.println(">>>>>>Testing direct pool<<<<<<<");
		for (int size : samplesCnt) {
			System.out.printf("Packet size: %d\n", size);
			Packet in = direct.acquire(size, true, sampleRate, 0);
			Packet out = direct.acquire(size, true, sampleRate, 0);
			benchmarkFilter(sampleRate, size, rounds, in, out, filter);
			in.release();
			out.release();
		}

		System.out.println(">>>>>>Testing indirect pool<<<<<<<");
		for (int size : samplesCnt) {
			System.out.printf("Packet size: %d\n", size);
			Packet in = indirect.acquire(size, true, sampleRate, 0);
			Packet out = indirect.acquire(size, true, sampleRate, 0);
			benchmarkFilter(sampleRate, size, rounds, in, out, filter);
			in.release();
			out.release();
		}
		System.out.println(">>>>>>Testing array pool<<<<<<<");
		for (int size : samplesCnt) {
			System.out.printf("Packet size: %d\n", size);
			Packet in = array.acquire(size, true, sampleRate, 0);
			Packet out = array.acquire(size, true, sampleRate, 0);
			benchmarkFilter(sampleRate, size, rounds, in, out, filter);
			in.release();
			out.release();
		}
	}

	public void testFilters() {
		final int samplesCnt = Packet.PREFERRED_SIZE / 2;
		final int decimation = 2;
		float[] interleavedSamples = new float[samplesCnt << 1];
		//Util.noise(interleavedSamples);
		PacketPool pool = PacketPool.getArrayPacketPool();
		Packet in = pool.acquire(interleavedSamples.length);
		Packet out = pool.acquire(interleavedSamples.length / decimation);

		double cutoff = samplesCnt / 4;
		double transition = samplesCnt / (64 * decimation);
		FIR_CRR f1;
		try {
			f1 = FilterBuilder.optimalLowPass_CRR(2, 1, samplesCnt, cutoff, transition, 3, 60, 2);
		}
		catch (ConvergenceException e) {
			f1 = FilterBuilder.lowPass_CRR(2, 1, samplesCnt, cutoff, transition, 60, Window.Type.WIN_BLACKMAN, 0);
		}
		System.out.printf("TapsCount =%d;\n", f1.getTapsCount());
		System.out.println("FIR_CRR");
		benchmarkFilter(1e6f, samplesCnt, 1000, in, out, f1);
		FIR_CRR_LinearPhase f2 = new FIR_CRR_LinearPhase(f1);
		System.out.println("FIR_CRR_LinearPhase");
		benchmarkFilter(1e6f, samplesCnt, 1000, in, out, f2);

		in.release();
		out.release();
	}

	public void testComplexFirFilterPerformance() {
		int lowBound = 100;
		int upBound = 3000;
		int transition = 100;
		double attenuation = 60;
		Window.Type winType = Window.Type.WIN_BLACKMAN;
		double passbandRipple_db = 3;
		int extraTaps = 2;
		int sampleRate = 80000;
		int packetSize = 16384;
		int loopCycles = 200;
		float[] inBuff = new float[packetSize];
		float[] outBuff = new float[packetSize];
		Packet in = new Packet(inBuff);
		in.sampleRate = sampleRate;
		Packet out = new Packet(outBuff);
		in.getBuffer().position(0).limit(packetSize);
		out.getBuffer().clear();
		FIR_CCC fir_ccc_win = FilterBuilder.bandPass_CCC(
				4,
				1,
				sampleRate,
				lowBound, upBound, transition,
				attenuation,
				winType,
				0);
		System.out.println("Created FIR_CCC with " + fir_ccc_win.getTapsCount() + " taps, Window: " + winType.toString());
		benchmarkFilter(sampleRate, packetSize, loopCycles, in, out, fir_ccc_win);

		try {
			FIR_CCC fir_ccc_opti = FilterBuilder.optimalBandPass_CCC(
					4,
					1,
					sampleRate,
					lowBound, upBound, transition,
					passbandRipple_db,
					attenuation,
					20);
			System.out.println("Created optimal FIR_CCC with " + fir_ccc_opti.getTapsCount() + " taps, passband deviation: " + passbandRipple_db + " dB, extra taps: " + extraTaps);
			benchmarkFilter(sampleRate, packetSize, loopCycles, in, out, fir_ccc_opti);
		}
		catch (ConvergenceException ce) {
			System.out.println("Remez couldn't converge: " + ce.getMessage());
		}
		FIR_CRC fir_crc_win = FilterBuilder.lowPass_CRC(
				4,
				1,
				sampleRate,
				upBound, transition,
				attenuation,
				winType,
				0);
		System.out.println("Created FIR_CRC with " + fir_crc_win.getTapsCount() + " taps, Window: " + winType.toString());
		benchmarkFilter(sampleRate, packetSize, loopCycles, in, out, fir_crc_win);
		try {
			FIR_CRC fir_crc_opti = FilterBuilder.optimalLowPass_CRC(
					4,
					1,
					sampleRate,
					upBound, transition,
					passbandRipple_db,
					attenuation,
					20);
			System.out.println("Created optimal FIR_CRC with " + fir_crc_opti.getTapsCount() + " taps, passband deviation: " + passbandRipple_db + " dB, extra taps: " + extraTaps);
			benchmarkFilter(sampleRate, packetSize, loopCycles, in, out, fir_crc_opti);
		}
		catch (ConvergenceException ce) {
			System.out.println("Remez couldn't converge: " + ce.getMessage());
		}
	}

	private void describeFIR(FIR filter) {
		System.out.printf("Filter: %s\n\ttaps count: %d\n\tdecimation: %d\n ",
				filter.getClass().getSimpleName(), filter.getTapsCount(), filter.getDecimation());
	}

	private void benchmarkFilter(float sampleRate, int packetSize, int loopCycles, Packet in, Packet out, FIR winFilter) {
		//Debug.startMethodTracing("FirFilter");
		//System.out.println("##### WARMUP ...");
		for (int i = 0; i < loopCycles / 4; i++) {
			winFilter.apply(in, out);
			in.getBuffer().position(0).limit(packetSize);
			out.getBuffer().clear();
		}
		//System.out.println("##### START ...");
		long startTime = SystemClock.currentThreadTimeMillis();
		for (int i = 0; i < loopCycles; i++) {
			winFilter.apply(in, out);
			in.getBuffer().position(0).limit(packetSize);
			out.getBuffer().clear();
		}
		System.out.println("DONE. Time needed for 1 sec of samples: "
		                   + (SystemClock.currentThreadTimeMillis() - startTime)
		                     / (packetSize * loopCycles / sampleRate)
		                   + " ms");
		//Debug.stopMethodTracing();
	}

}
