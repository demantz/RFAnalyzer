package com.mantz_it.rfanalyzer.dsp.spi;

import com.mantz_it.rfanalyzer.BuildConfig;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Pavel on 07.01.2017.
 */


public abstract class PacketPool {

	protected static DirectPacketPool DPool;
	protected static IndirectPacketPool IPool;
	protected static ArrayPacketPool APool;

	protected static final boolean trackStats = BuildConfig.DEBUG;
	protected AtomicInteger allocatedPackets = trackStats ? new AtomicInteger(0) : null;
	protected AtomicInteger lostPackets = trackStats ? new AtomicInteger(0) : null;
	protected AtomicInteger packetsInUse = trackStats ? new AtomicInteger(0) : null;
	protected AtomicInteger ignoredPackets = trackStats ? new AtomicInteger(0) : null;

/**
 * Don't use for Java-based processing -- 4 times slower than Indirect/Array pools.
 * Use for native/OpenGL ES computations.
 * @return Pool which allocates packets with direct ByteBuffers.
 */
	public static PacketPool getDirectPacketPool() {
		if (DPool == null)
			DPool = new DirectPacketPool();
		return DPool;
	}

	public static PacketPool getIndirectPacketPool() {
		if (IPool == null)
			IPool = new IndirectPacketPool();
		return IPool;
	}


	public static PacketPool getArrayPacketPool() {
		if (APool == null)
			APool = new ArrayPacketPool();
		return APool;
	}

	protected Map<Integer, BlockingQueue<Packet>> pools;

	private PacketPool() {
		pools = new HashMap<>();
	}

	protected BlockingQueue<Packet> getOrInitPool(int size) {
		BlockingQueue<Packet> pool = pools.get(size);
		if (pool == null) {
			pool = new LinkedBlockingQueue<>(64);
			pools.put(size, pool);
		}
		return pool;
	}

	public abstract Packet acquire(int size);

	public Packet acquire(int size, boolean complex) {
		Packet p = acquire(size);
		p.complex = complex;
		return p;
	}

	public Packet acquire(int size, boolean complex, int sampleRate, long frequency) {
		Packet p = acquire(size);
		p.complex = complex;
		p.sampleRate = sampleRate;
		p.frequency = frequency;
		return p;
	}

	public boolean release(Packet p) {
		if (p == null) {
			if (trackStats) ignoredPackets.incrementAndGet();
			return true;
		}
		p.clear();
		BlockingQueue<Packet> pool = pools.get(p.getBuffer().capacity());
		if (pool == null) {
			if (trackStats) ignoredPackets.incrementAndGet();
			return false;
		}
		if (trackStats) {
			if (pool.offer(p)) {
				packetsInUse.decrementAndGet();
				return true;
			} else {
				lostPackets.incrementAndGet();
				return false;
			}
		}
		return pool.offer(p);
	}

	public void printStats() {
		if (trackStats)
			System.out.printf("%s stats:"
			                  + "\n\tallocated packets: %d"
			                  + "\n\t   packets in use: %d"
			                  + "\n\t     lost packets: %d"
			                  + "\n\t  ignored packets: %d",
					allocatedPackets, packetsInUse, lostPackets, ignoredPackets);
		else System.out.println("Pool statistics disabled.");
	}

	protected static class DirectPacketPool extends PacketPool {
		DirectPacketPool() {
			super();
		}

		public Packet acquire(int size) {
			Packet p = null;
			BlockingQueue<Packet> pool = getOrInitPool(size);
			p = pool.poll();

			if (p == null) {
				p = new Packet(ByteBuffer.allocateDirect(size << 2).asFloatBuffer());
				p.pool = this;
				if (trackStats) allocatedPackets.incrementAndGet();
			}
			if (trackStats) packetsInUse.incrementAndGet();
			return p;
		}
	}


	protected static class IndirectPacketPool extends PacketPool {
		IndirectPacketPool() {
			super();
		}

		public Packet acquire(int size) {
			Packet p = null;
			BlockingQueue<Packet> pool = getOrInitPool(size);
			p = pool.poll();

			if (p == null) {
				p = new Packet(FloatBuffer.allocate(size));
				p.pool = this;
				if (trackStats) allocatedPackets.incrementAndGet();
			}
			if (trackStats) packetsInUse.incrementAndGet();
			return p;
		}
	}

	protected static class ArrayPacketPool extends PacketPool {
		ArrayPacketPool() {
			super();
		}

		public Packet acquire(int size) {
			Packet p = null;
			BlockingQueue<Packet> pool = getOrInitPool(size);
			p = pool.poll();

			if (p == null) {
				p = new Packet(FloatBuffer.wrap(new float[size]));
				p.pool = this;
				if (trackStats) allocatedPackets.incrementAndGet();
			}
			if (trackStats) packetsInUse.incrementAndGet();
			return p;
		}
	}
}
