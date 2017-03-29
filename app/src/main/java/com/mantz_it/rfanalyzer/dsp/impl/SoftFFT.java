package com.mantz_it.rfanalyzer.dsp.impl;

import com.mantz_it.rfanalyzer.dsp.spi.FFT;
import com.mantz_it.rfanalyzer.dsp.spi.Packet;
import com.mantz_it.rfanalyzer.dsp.spi.Window;

import java.nio.FloatBuffer;

/**
 * Created by Pavel on 19.11.2016.
 */

public class SoftFFT implements FFT {
protected int size, powerOf2;

@Override
public int getSize() {return size;}

public int getPowerOf2() {return powerOf2;}

// Lookup tables.  Only need to recompute when size of FFT changes.
protected float[] cos;
protected float[] sin;

protected float[] window;

public SoftFFT(int size) {
	this(size, Window.Type.WIN_FLATTOP);
}

public SoftFFT(int size, Window.Type windowType) {
	this.size = size;
	this.powerOf2 = (int) (Math.log(size) / Math.log(2));

	// Make sure size is a power of 2
	if (size != (1 << powerOf2))
		throw new RuntimeException("FFT length must be power of 2");

	// precompute tables
	cos = new float[size / 2];
	sin = new float[size / 2];

//     for(int i=0; i<size/4; i++) {
//       cos[i] = Math.cos(-2*Math.PI*i/size);
//       sin[size/4-i] = cos[i];
//       cos[size/2-i] = -cos[i];
//       sin[size/4+i] = cos[i];
//       cos[size/2+i] = -cos[i];
//       sin[size*3/4-i] = -cos[i];
//       cos[size-i]   = cos[i];
//       sin[size*3/4+i] = -cos[i];
//     }

	for (int i = 0; i < size / 2; i++) {
		cos[i] = (float) Math.cos(-2 * Math.PI * i / size);
		sin[i] = (float) Math.sin(-2 * Math.PI * i / size);
	}

	makeWindow(windowType);
}

protected void makeWindow(Window.Type type) {
	window = type.build(size, 0, 0);
}

public float[] getWindow() {
	return window;
}

public void applyWindow(float[] interleaved, int offset) {
	for (int wi = 0, si = offset; wi < window.length; wi++, si += 2) {
		interleaved[si] *= window[wi];
		interleaved[si] *= window[wi];
	}
}


/***************************************************************
 * fft.c
 * Douglas L. Jones
 * University of Illinois at Urbana-Champaign
 * January 19, 1992
 * http://cnx.rice.edu/content/m12016/latest/
 * <p>
 * fft: in-place radix-2 DIT DFT of a complex input
 * <p>
 * input:
 * size: length of FFT: must be a power of two
 * powerOf2: size = 2**powerOf2
 * input/output
 * re: double array of length size with real part of data
 * im: double array of length size with imag part of data
 * <p>
 * Permission to copy and use this program is granted
 * as long as this header is included.
 ****************************************************************/
public void apply(float[] interleaved, int offset) {
	int i, j, k, recurentMiddle, middle, phase;
	float cosine, sine, e, tmp1, tmp2;


	// Bit-reverse
	j = 0;
	middle = size / 2;
	for (i = 1; i < size - 1; i++) {
		recurentMiddle = middle;
		while (j >= recurentMiddle) {
			j = j - recurentMiddle;
			recurentMiddle = recurentMiddle / 2;
		}
		j = j + recurentMiddle;

		int reI = (i << 1) + offset;
		int imI = (i << 1) + 1 + offset;
		int reJ = (j << 1) + offset;
		int imJ = (j << 1) + 1 + offset;
		if (i < j) {
			tmp1 = interleaved[reI];
			interleaved[reI] = interleaved[reJ];
			interleaved[reJ] = tmp1;

			tmp1 = interleaved[imI];
			interleaved[imI] = interleaved[imJ];
			interleaved[imJ] = tmp1;
		}
	}

	// FFT
	recurentMiddle = 0;
	middle = 1;

	for (i = 0; i < powerOf2; i++) {
		recurentMiddle = middle;
		middle = middle + middle; // recurrent unwinding
		phase = 0;

		for (j = 0; j < recurentMiddle; j++) {
			cosine = cos[phase];
			sine = sin[phase];
			phase += 1 << (powerOf2 - i - 1);

			for (k = j; k < size; k = k + middle) {
				int reKRec = (k + recurentMiddle << 1) + offset;
				int imKRec = (k + recurentMiddle << 1) + 1 + offset;
				int reK = (k << 1) + offset;
				int imK = (k << 1) + 1 + offset;
				tmp1 = cosine * interleaved[reKRec] - sine * interleaved[imKRec];
				tmp2 = sine * interleaved[reKRec] + cosine * interleaved[imKRec];
				interleaved[reKRec] = interleaved[reK] - tmp1;
				interleaved[imKRec] = interleaved[imK] - tmp2;
				interleaved[reK] += tmp1;
				interleaved[imK] += tmp2;
			}
		}
	}
}


@Override
public int apply(Packet src, Packet dst) {
	FloatBuffer dstBuff = dst.getBuffer();
	int count = src.getBuffer().remaining();
	dstBuff.put(src.getBuffer());
	dstBuff.limit(dstBuff.position());
	dstBuff.position(dstBuff.position() - count);
	apply(dstBuff.array(), dstBuff.arrayOffset() + dstBuff.position());
	dst.sampleRate = src.sampleRate;
	dst.frequency = src.frequency;
	return count;
}
}
