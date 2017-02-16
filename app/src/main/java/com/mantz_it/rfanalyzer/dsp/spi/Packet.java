package com.mantz_it.rfanalyzer.dsp.spi;

import android.support.annotation.NonNull;
import android.support.annotation.Size;

import com.mantz_it.rfanalyzer.SamplePacket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.FloatBuffer;

/**
 * Created by Pavel on 19.11.2016.
 */

public class Packet {
/**
 * Estimate of 61 packets per second at 1 Complex Mega samples per second.
 * That's like 60 FPS, but packets, assuming, that most systems Sleep() granularity is 16 seconds,
 * it seems legit for scheduled playback at that popular rate.
 */
public static int PREFERRED_SIZE = 32768;
public boolean complex;
public int sampleRate;
public long frequency;
protected int bandwidth = -1;

public int getBandwidth() {
	if (bandwidth > 0) {
		return bandwidth;
	} else {
		return complex ? sampleRate : sampleRate / 2;
	}
}

public void setBandwidth(int bandwidth) {
	this.bandwidth = bandwidth; // don't check for anything, coz can't do anything
}

FloatBuffer buff;
protected PacketPool pool = null;

@Deprecated
public Packet(SamplePacket sp) {
	final int samplesCount = sp.capacity();
	buff = FloatBuffer.allocate(samplesCount << 1);
	float[] re = sp.re();
	float[] im = sp.im();
	for (int i = 0; i < samplesCount; ++i) {
		buff.put(re[i]);
		buff.put(im[i]);
	}
	sampleRate = sp.getSampleRate();
	frequency = sp.getFrequency();
}

@Deprecated
public Packet(int size) {
	this.buff = FloatBuffer.wrap(new float[size]);
}

public Packet(FloatBuffer buff) {
	this.buff = buff;
}

@Deprecated
public Packet(@Size(multiple = 2) float[] buff) {
	this.buff = FloatBuffer.wrap(buff);
}

@Deprecated
public float re(int index) {
	return buff.get(index << 1);
}

@Deprecated
public float im(int index) {
	return buff.get((index << 1) + 1);
}

public FloatBuffer getBuffer() {
	return buff;
}

public double duration() {
	if (complex) {
		return (buff.capacity() >> 1) / sampleRate;
	} else {
		return buff.capacity() / sampleRate;
	}
}

@Deprecated
public SamplePacket toSamplePacket() {
	buff.reset();
	SamplePacket ret = new SamplePacket(buff.remaining() >> 1);
	float[] re = ret.re();
	float[] im = ret.im();
	for (int i = 0; buff.remaining() > 1; ++i) {
		re[i] = buff.get();
		im[i] = buff.get();
	}
	ret.setSampleRate(sampleRate);
	ret.setFrequency(frequency);
	return ret;
}

public void release() {
	if (pool != null) {
		pool.release(this);
	}
}

public void clear() {
	buff.clear();
	bandwidth = -1;
	complex = true;
	sampleRate = 1;
	frequency = 0;
}

public PacketPool getPool() {
	return pool;
}


private static final int MAGIC = 0xAD517101;

// todo: maybe implement Externalizable? Just not familiar enough with it yet, so don't trust ABI.
public void serialize(@NonNull final DataOutput output) throws IOException {
	final int remaining = buff.remaining();
	float[] arr = new float[remaining];
	buff.get(arr, 0, remaining);  // hotpoint: need zero-copy version for indirect buffers

	output.writeInt(MAGIC);                     // 0-3: magic
	output.writeInt(complex ? 1 : 0);           // 4-7: complex
	output.writeInt(sampleRate);                // 8-11: sample rate
	output.writeLong(frequency);                // 12-19: frequency
	output.writeInt(bandwidth);                 // 20-23: bandwidth
	output.writeInt(remaining);                 // 24-27: size
	for (int i = 0; i < remaining; ++i) {
		output.writeFloat(arr[i]);              // 28-(28+4*size): samples
	}
}


public static Packet deserialize(@NonNull final DataInput input) throws IOException {
	final int magic = input.readInt();          // 0:3: magic
	if (magic != MAGIC) {
		throw new IOException("Magic number check failed. Is it really a Packet stream?");
	}
	boolean complex = input.readInt() != 0;     // 4-7: complex
	int sampleRate = input.readInt();           // 8-11: sample rate
	long frequency = input.readLong();          // 12-19: frequency
	int bandwidth = input.readInt();            // 20-23: bandwidth
	final int size = input.readInt();           // 24-27: size
	float[] arr = new float[size];  // except: NegativeArraySizeException here
	for (int i = 0; i < size; ++i) {
		arr[i] = input.readFloat();             // 28-(28+4*size) samples
	}

	Packet p = PacketPool.getArrayPacketPool().acquire(size);
	p.buff.clear();
	p.buff.put(arr); // hotpoint: not zero-copy, bad!
	p.complex = complex;
	p.sampleRate = sampleRate;
	p.frequency = frequency;
	p.bandwidth = bandwidth;

	return p;
}
}
